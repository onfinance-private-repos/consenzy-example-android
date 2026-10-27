// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.onfinance.consent.core.BreachNotice
import `in`.onfinance.consent.core.Consent
import `in`.onfinance.consent.core.ConsentApiException
import `in`.onfinance.consent.core.ConsentNetworkException
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.core.DataRequest
import `in`.onfinance.consent.core.DataRequestDetails
import `in`.onfinance.consent.core.InvalidSessionTokenException
import `in`.onfinance.consent.core.Language
import `in`.onfinance.consent.core.Notice
import `in`.onfinance.consent.core.Principal
import `in`.onfinance.consent.core.RequestType
import `in`.onfinance.consent.core.SessionCapability
import `in`.onfinance.consent.core.isUuid
import `in`.onfinance.consent.core.sortedNewestFirst
import `in`.onfinance.consent.core.unacknowledgedCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Outcome of the consent sheet, delivered once via [ConsentUiState.decision]. */
public sealed class ConsentDecision {
    /** Consent recorded for exactly the purposes the principal switched on. */
    public data class Accepted(val noticeId: String, val consents: List<Consent>) : ConsentDecision()

    /** The principal declined every purpose. Nothing is recorded: no consent was given. */
    public data object Rejected : ConsentDecision()
}

/** What went wrong, so the UI can show a localised message (see [ConsentStrings.errorMessage]). */
public sealed class ConsentError {
    public data object Network : ConsentError()

    /** "Accept selected" was pressed with every purpose switched off. */
    public data object NothingSelected : ConsentError()
    public data class Api(val status: Int, val detail: String) : ConsentError()

    /** HTTP 403, or caught before sending: this session lacks the capability for the action. */
    public data class NotPermitted(val detail: String) : ConsentError()

    /**
     * HTTP 422 on a grant for a child or a person with disability: the session was not minted
     * with `acting_as=guardian`, so a parent or guardian must give this consent (s.9).
     * [verificationPending] is true when it is a guardian session but the guardian has not
     * been verified yet.
     */
    public data class GuardianRequired(val verificationPending: Boolean = false) : ConsentError()
    public data class Session(val message: String) : ConsentError()
    public data class Invalid(val message: String) : ConsentError()
    public data class Unexpected(val message: String) : ConsentError()
}

public data class ConsentUiState(
    /** `GET /v1/me`: who is signed in and what the session may do; null until loaded. */
    val principal: Principal? = null,
    val principalLoading: Boolean = false,
    /** Language explicitly chosen in the picker; null means the principal's preferred language. */
    val language: Language? = null,
    val notice: Notice? = null,
    val noticeLoading: Boolean = false,
    /** Purposes switched on. Empty by default: DPDP s.6 needs a clear affirmative action. */
    val selectedPurposeIds: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val decision: ConsentDecision? = null,
    val consents: List<Consent> = emptyList(),
    val consentsLoading: Boolean = false,
    val withdrawingId: String? = null,
    val dataRequests: List<DataRequest> = emptyList(),
    val dataRequestsLoading: Boolean = false,
    val filingRequest: Boolean = false,
    val lastFiledRequest: DataRequest? = null,
    /** `GET /v1/me/breach-notices`, newest first. Every session can read these. */
    val breachNotices: List<BreachNotice> = emptyList(),
    val breachNoticesLoading: Boolean = false,
    /** Breach id of the notice being acknowledged, if any. */
    val acknowledgingBreachId: String? = null,
    val error: ConsentError? = null,
) {
    /** Breach notices not yet acknowledged, for a badge (see [BreachNoticesBadge]). */
    val unacknowledgedBreachNotices: Int get() = breachNotices.unacknowledgedCount()

    // Until /v1/me has loaded, actions stay disabled. If it cannot be loaded the UI falls back
    // to allowing them: the server enforces capabilities either way and answers 403.
    private fun allowed(check: (Principal) -> Boolean): Boolean = principal?.let(check) ?: !principalLoading

    /** The session holds `consents:write` (accept / withdraw). */
    val canGiveConsent: Boolean get() = allowed { it.canGiveConsent }

    /** The session holds `requests:write` (file a rights request). */
    val canFileRequests: Boolean get() = allowed { it.canFileRequests }

    /** The session holds `requests:read` (list past rights requests). */
    val canReadRequests: Boolean get() = allowed { it.canReadRequests }

    /** The signed-in user is a parent / guardian acting for the principal. */
    val actingAsGuardian: Boolean get() = principal?.isGuardianSession == true
}

/**
 * Screen state for the Compose components, backed by a [ConsentSession]. Create it with
 * [ConsentViewModel.factory] so it survives configuration changes:
 *
 * ```
 * val vm: ConsentViewModel = viewModel(factory = ConsentViewModel.factory(session))
 * ```
 */
public class ConsentViewModel(private val session: ConsentSession) : ViewModel() {
    private val _state = MutableStateFlow(ConsentUiState())
    public val state: StateFlow<ConsentUiState> = _state.asStateFlow()

    // Idempotency keys are kept until the submission succeeds, so tapping again after a
    // timeout replays the original request instead of creating a duplicate record.
    private var pendingGrant: Pair<String, String>? = null
    private var pendingRequest: Pair<String, String>? = null
    private var principalJob: Deferred<Principal?>? = null

    public companion object {
        public fun factory(session: ConsentSession): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConsentViewModel(session) }
        }
    }

    // ------------------------------------------------------------------ principal / capabilities

    /**
     * Load `GET /v1/me` (once; [refresh] forces a reload, e.g. after the app swaps sessions).
     * The other loaders call this automatically, so the UI knows the session's capabilities.
     */
    public fun loadPrincipal(refresh: Boolean = false) {
        if (refresh) principalJob = null
        principalAsync()
    }

    private fun principalAsync(): Deferred<Principal?> {
        principalJob?.let { return it }
        _state.update { it.copy(principalLoading = true) }
        lateinit var job: Deferred<Principal?>
        job = viewModelScope.async(start = CoroutineStart.LAZY) {
            val me = try {
                session.me()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // capabilities unknown: the UI falls back to the server's enforcement
            }
            // A failed load is retried by the next loader call.
            if (me == null && principalJob === job) principalJob = null
            _state.update { it.copy(principal = me ?: it.principal, principalLoading = false) }
            me
        }
        principalJob = job
        job.start()
        return job
    }

    // ------------------------------------------------------------------ notice / consent sheet

    /** Load the latest notice in [language] (null: the principal's preferred language). */
    public fun loadNotice(language: Language? = _state.value.language) {
        principalAsync()
        _state.update { it.copy(language = language, noticeLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(noticeLoading = false, error = e) } }) {
                val notice = session.currentNotice(language)
                val valid = notice.purposes.map { it.id }.toSet()
                _state.update {
                    it.copy(notice = notice, noticeLoading = false, selectedPurposeIds = it.selectedPurposeIds intersect valid)
                }
            }
        }
    }

    public fun selectLanguage(language: Language) {
        if (language != _state.value.language || _state.value.notice == null) loadNotice(language)
    }

    public fun setPurposeSelected(purposeId: String, selected: Boolean) {
        _state.update {
            it.copy(selectedPurposeIds = if (selected) it.selectedPurposeIds + purposeId else it.selectedPurposeIds - purposeId)
        }
    }

    /** Record consent for the switched-on purposes. Requires at least one. */
    public fun acceptSelected() {
        val current = _state.value
        val notice = current.notice ?: return
        if (current.submitting) return
        val ids = notice.purposes.map { it.id }.filter { it in current.selectedPurposeIds }
        if (ids.isEmpty()) {
            _state.update { it.copy(error = ConsentError.NothingSelected) }
            return
        }
        if (!current.canGiveConsent) {
            _state.update { it.copy(error = ConsentError.NotPermitted(SessionCapability.CONSENTS_WRITE)) }
            return
        }
        val fingerprint = notice.id + ":" + ids.joinToString(",")
        val key = pendingGrant?.takeIf { it.first == fingerprint }?.second ?: ConsentSession.newIdempotencyKey()
        pendingGrant = fingerprint to key
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(submitting = false, error = e) } }) {
                val consents = session.giveConsent(notice.id, ids, idempotencyKey = key)
                pendingGrant = null
                _state.update { it.copy(submitting = false, decision = ConsentDecision.Accepted(notice.id, consents)) }
            }
        }
    }

    /** Decline everything. No consent is recorded; the selection is cleared. */
    public fun rejectAll() {
        pendingGrant = null
        _state.update { it.copy(selectedPurposeIds = emptySet(), decision = ConsentDecision.Rejected, error = null) }
    }

    /** Call after handling [ConsentUiState.decision]. */
    public fun consumeDecision() {
        _state.update { it.copy(decision = null) }
    }

    public fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ consent manager

    public fun refreshConsents(activeOnly: Boolean = true) {
        principalAsync()
        _state.update { it.copy(consentsLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(consentsLoading = false, error = e) } }) {
                val consents = session.listConsents(activeOnly = activeOnly, limit = 200)
                _state.update { it.copy(consents = consents, consentsLoading = false) }
            }
        }
    }

    public fun withdraw(consentId: String, reason: String? = null) {
        if (_state.value.withdrawingId != null) return
        if (!_state.value.canGiveConsent) {
            _state.update { it.copy(error = ConsentError.NotPermitted(SessionCapability.CONSENTS_WRITE)) }
            return
        }
        _state.update { it.copy(withdrawingId = consentId, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(withdrawingId = null, error = e) } }) {
                val updated = session.withdrawConsent(consentId, reason)
                _state.update { s ->
                    s.copy(
                        withdrawingId = null,
                        consents = s.consents.mapNotNull { c -> if (c.id == consentId) updated.takeIf { u -> u.isActive } else c },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ rights requests

    /** Lists past rights requests. Does nothing when the session lacks `requests:read`. */
    public fun refreshDataRequests() {
        val principal = principalAsync()
        _state.update { it.copy(dataRequestsLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(dataRequestsLoading = false, error = e) } }) {
                if (principal.await()?.canReadRequests == false) {
                    _state.update { it.copy(dataRequests = emptyList(), dataRequestsLoading = false) }
                    return@runCatchingConsent
                }
                val requests = session.listDataRequests(limit = 200)
                _state.update { it.copy(dataRequests = requests, dataRequestsLoading = false) }
            }
        }
    }

    public fun fileDataRequest(type: RequestType, details: DataRequestDetails) {
        if (_state.value.filingRequest) return
        if (type !in details.compatibleTypes) {
            _state.update { it.copy(error = ConsentError.Invalid("details do not match request type")) }
            return
        }
        if (!_state.value.canFileRequests) {
            _state.update { it.copy(error = ConsentError.NotPermitted(SessionCapability.REQUESTS_WRITE)) }
            return
        }
        val fingerprint = "$type:$details"
        val key = pendingRequest?.takeIf { it.first == fingerprint }?.second ?: ConsentSession.newIdempotencyKey()
        pendingRequest = fingerprint to key
        _state.update { it.copy(filingRequest = true, error = null, lastFiledRequest = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(filingRequest = false, error = e) } }) {
                val filed = session.fileDataRequest(type, details, idempotencyKey = key)
                pendingRequest = null
                _state.update { s ->
                    s.copy(filingRequest = false, lastFiledRequest = filed, dataRequests = listOf(filed) + s.dataRequests.filter { it.id != filed.id })
                }
            }
        }
    }

    // ------------------------------------------------------------------ breach notices

    /** Loads personal data breach notices (`GET /v1/me/breach-notices`), newest first. */
    public fun refreshBreachNotices() {
        _state.update { it.copy(breachNoticesLoading = true, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(breachNoticesLoading = false, error = e) } }) {
                val notices = session.listBreachNotices(limit = 200)
                _state.update { it.copy(breachNotices = notices, breachNoticesLoading = false) }
            }
        }
    }

    /**
     * "I have read this": `POST /v1/me/breach-notices/{breachId}/acknowledge`. [breachId] must
     * be a UUID. Does nothing while another acknowledgement is in flight or when the notice is
     * already acknowledged; the server keeps the first acknowledgement time in any case.
     */
    public fun acknowledgeBreachNotice(breachId: String) {
        val current = _state.value
        if (current.acknowledgingBreachId != null) return
        if (!isUuid(breachId)) {
            _state.update { it.copy(error = ConsentError.Invalid("breachId must be a UUID")) }
            return
        }
        if (current.breachNotices.any { it.breachId == breachId && it.isAcknowledged }) return
        _state.update { it.copy(acknowledgingBreachId = breachId, error = null) }
        viewModelScope.launch {
            runCatchingConsent(onError = { e -> _state.update { it.copy(acknowledgingBreachId = null, error = e) } }) {
                val updated = session.acknowledgeBreachNotice(breachId)
                _state.update { s ->
                    s.copy(
                        acknowledgingBreachId = null,
                        breachNotices = s.breachNotices
                            .map { if (it.breachId == updated.breachId) updated else it }
                            .sortedNewestFirst(),
                    )
                }
            }
        }
    }

    /** Report a validation problem found by a form before anything is sent. */
    public fun reportInvalidInput(message: String) {
        _state.update { it.copy(error = ConsentError.Invalid(message)) }
    }

    private suspend fun runCatchingConsent(onError: (ConsentError) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConsentApiException) {
            onError(
                when {
                    e.isForbidden -> ConsentError.NotPermitted(e.detail)
                    e.isGuardianRequired -> ConsentError.GuardianRequired()
                    e.isGuardianVerificationRequired -> ConsentError.GuardianRequired(verificationPending = true)
                    else -> ConsentError.Api(e.status, e.detail)
                },
            )
        } catch (e: ConsentNetworkException) {
            onError(ConsentError.Network)
        } catch (e: java.io.IOException) {
            // Typically thrown by the app's session provider while fetching a token from its backend.
            onError(ConsentError.Network)
        } catch (e: InvalidSessionTokenException) {
            onError(ConsentError.Session(e.message ?: "invalid session"))
        } catch (e: IllegalArgumentException) {
            onError(ConsentError.Invalid(e.message ?: "invalid input"))
        } catch (e: Exception) {
            onError(ConsentError.Unexpected(e.message ?: e.javaClass.simpleName))
        }
    }
}
