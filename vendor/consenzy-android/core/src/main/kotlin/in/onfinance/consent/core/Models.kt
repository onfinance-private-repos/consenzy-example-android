// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Response models for the principal API. Identifiers are UUID strings and timestamps are
// ISO-8601 strings with an offset exactly as the server sends them (parse with
// java.time.OffsetDateTime.parse on API 26+ or with core library desugaring).

/** `GET /v1/me`: the signed-in Data Principal. */
@Serializable
public data class Principal(
    @SerialName("principal_id") val principalId: String,
    @SerialName("external_ref") val externalRef: String,
    @SerialName("preferred_language") val preferredLanguageCode: String,
    /** True for a child or a person with disability: consent is given by a lawful guardian (s.9). */
    @SerialName("acts_through_guardian") val actsThroughGuardian: Boolean,
    @SerialName("guardian_verified") val guardianVerified: Boolean,
    /**
     * Who the minting backend authenticated: the principal, or their parent / lawful guardian.
     * Values a newer server adds decode as [ActingAs.UNKNOWN].
     */
    @SerialName("acting_as") val actingAs: ActingAs = ActingAs.DATA_PRINCIPAL,
    /**
     * What this session may do (see [SessionCapability]); unknown future values are kept as
     * strings. Null only from a server that predates session capabilities, which allowed every
     * action; the server always enforces capabilities whatever the app shows.
     */
    val capabilities: List<String>? = null,
) {
    val preferredLanguage: Language? get() = Language.fromCode(preferredLanguageCode)

    /** True when the session holds [capability] (or the server predates capabilities). */
    public fun hasCapability(capability: String): Boolean = capabilities?.contains(capability) ?: true

    /** The session may give and withdraw consent (`POST /v1/me/consents`, `/withdraw`). */
    val canGiveConsent: Boolean get() = hasCapability(SessionCapability.CONSENTS_WRITE)

    /** Same capability as [canGiveConsent]: withdrawing is as easy as giving (s.6(4)). */
    val canWithdrawConsent: Boolean get() = canGiveConsent

    /** The session may file rights requests (`POST /v1/me/data-requests`). */
    val canFileRequests: Boolean get() = hasCapability(SessionCapability.REQUESTS_WRITE)

    /** The session may list past rights requests (`GET /v1/me/data-requests`). */
    val canReadRequests: Boolean get() = hasCapability(SessionCapability.REQUESTS_READ)

    /** The session was minted with `acting_as=guardian`: a parent / lawful guardian is acting. */
    val isGuardianSession: Boolean get() = actingAs == ActingAs.GUARDIAN

    /**
     * Consent for this principal can only be given by a guardian (s.9), and this session is
     * not a guardian session, so a grant will fail with HTTP 422.
     */
    val needsGuardianSession: Boolean get() = actsThroughGuardian && !isGuardianSession
}

/** Capabilities a principal session can hold (`GET /v1/me` → `capabilities`). */
public object SessionCapability {
    public const val CONSENTS_WRITE: String = "consents:write"
    public const val REQUESTS_WRITE: String = "requests:write"
    public const val REQUESTS_READ: String = "requests:read"
}

/** A purpose listed on a notice, for which consent can be given. */
@Serializable
public data class NoticePurpose(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    @SerialName("data_categories") val dataCategories: List<String> = emptyList(),
    @SerialName("retention_days") val retentionDays: Int? = null,
)

/** `GET /v1/me/notice`: the latest notice in a language, with its consent-based purposes. */
@Serializable
public data class Notice(
    val id: String,
    val version: Int,
    @SerialName("language") val languageCode: String,
    val title: String,
    val body: String,
    val purposes: List<NoticePurpose> = emptyList(),
) {
    val language: Language? get() = Language.fromCode(languageCode)
}

/** One consent artefact (one purpose) belonging to the signed-in principal. */
@Serializable
public data class Consent(
    val id: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("purpose_id") val purposeId: String,
    @SerialName("purpose_code") val purposeCode: String,
    @SerialName("notice_id") val noticeId: String,
    val status: ConsentStatus,
    val channel: String,
    @SerialName("consent_manager_ref") val consentManagerRef: String? = null,
    @SerialName("given_at") val givenAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("withdrawn_at") val withdrawnAt: String? = null,
    @SerialName("withdrawal_reason") val withdrawalReason: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    val isActive: Boolean get() = status == ConsentStatus.ACTIVE
}

/** A Data Principal rights request (access, correction, erasure, grievance, nomination). */
@Serializable
public data class DataRequest(
    val id: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("request_type") val requestType: RequestType,
    val status: RequestStatus,
    /**
     * Where the request was received: `app` or `web` for requests filed through a principal
     * session (this SDK files as `app`), or `email`, `whatsapp`, `sms`, `phone`, `branch` or
     * `other` for requests your backend recorded on the principal's behalf. Every channel
     * shows up in [ConsentSession.listDataRequests]. Null only from a server that predates
     * request channels; unknown future values are kept as strings.
     */
    val channel: String? = null,
    /** Your reference for an off-app request (e.g. a WhatsApp message or ticket id), if any. */
    @SerialName("channel_reference") val channelReference: String? = null,
    val details: JsonObject = JsonObject(emptyMap()),
    val response: JsonObject? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    /** Statutory deadline computed by the server. */
    @SerialName("due_at") val dueAt: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

// ------------------------------------------------------------------ request bodies (internal)

@Serializable
internal data class GrantBody(
    @SerialName("notice_id") val noticeId: String,
    @SerialName("purpose_ids") val purposeIds: List<String>,
    val channel: String = "mobile_app",
)

@Serializable
internal data class WithdrawBody(val reason: String? = null)

@Serializable
internal data class DataRequestBody(
    @SerialName("request_type") val requestType: RequestType,
    val details: JsonObject,
)
