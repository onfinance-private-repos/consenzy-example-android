package com.example.consenzy.ui.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.consenzy.session.ConsenzySessions
import com.example.consenzy.session.SessionBackend
import com.example.consenzy.session.SignedInSession
import com.example.consenzy.ui.common.ErrorMessage
import com.example.consenzy.ui.common.Field
import com.example.consenzy.ui.common.Hint
import com.example.consenzy.ui.common.SectionCard
import com.example.consenzy.ui.common.describe
import com.example.consenzy.ui.common.rememberConsentViewModel
import `in`.onfinance.consent.core.ActingAs
import `in`.onfinance.consent.core.Consent
import `in`.onfinance.consent.core.ConsentApiException
import `in`.onfinance.consent.core.DataRequest
import `in`.onfinance.consent.core.DataRequestDetails
import `in`.onfinance.consent.core.Notice
import `in`.onfinance.consent.core.Principal
import `in`.onfinance.consent.ui.ConsentDecision
import `in`.onfinance.consent.ui.ConsentSheet
import kotlinx.coroutines.launch

/**
 * Journey 5: consent for a child (DPDP Act s.9), plus nomination (s.14).
 *
 * Consent for a child or a person with disability can only be given by a parent or lawful guardian. Your backend
 * authenticates the guardian and mints the session with `acting_as = guardian`. A session minted for the child itself
 * is refused with HTTP 422, which the SDK exposes as `ConsentApiException.isGuardianRequired`.
 */
object GuardianConsent {
    /** The synthetic minor in the demo data (and the session server's default allow-list). */
    const val CHILD_REF = "CUST-DEMO-CHILD-7"

    /** What happened when the child's own session tried to give consent. */
    data class ChildAttempt(val me: Principal, val refusal: ConsentApiException?)

    /**
     * Step 1: a session minted as the child (`acting_as = data_principal`). `me().needsGuardianSession` is true, and
     * `giveConsent` fails with HTTP 422 "…must be minted with acting_as=guardian (s.9)".
     */
    suspend fun tryAsChild(backend: SessionBackend): ChildAttempt {
        val child = ConsenzySessions.open(backend, CHILD_REF, ActingAs.DATA_PRINCIPAL)
        try {
            val me = child.consent.me()
            val notice = child.consent.currentNotice()
            return try {
                child.consent.giveConsent(notice.id, listOf(notice.purposes.first().id))
                ChildAttempt(me, refusal = null) // not expected: the server should have refused
            } catch (e: ConsentApiException) {
                if (!e.isGuardianRequired) throw e
                ChildAttempt(me, refusal = e)
            }
        } finally {
            child.logout()
        }
    }

    /** Step 2: after your backend has authenticated the parent, it mints `acting_as = guardian` for the child. */
    suspend fun openGuardianSession(backend: SessionBackend): SignedInSession =
        ConsenzySessions.open(backend, CHILD_REF, ActingAs.GUARDIAN)

    /** Step 3: the guardian records consent for the purposes they switched on. */
    suspend fun consentAsGuardian(guardian: SignedInSession, notice: Notice, purposeIds: Set<String>): List<Consent> {
        check(guardian.consent.me().isGuardianSession) { "not a guardian session" }
        return guardian.consent.giveConsent(notice.id, notice.purposes.map { it.id }.filter { it in purposeIds })
    }

    /** s.14: nominate someone to exercise the principal's rights on death or incapacity (email or E.164 phone). */
    suspend fun nominate(
        session: SignedInSession,
        nomineeName: String,
        relationship: String,
        nomineePhone: String?,
        nomineeEmail: String?,
    ): DataRequest = session.consent.fileDataRequest(
        DataRequestDetails.Nomination(
            nomineeName = nomineeName,
            relationship = relationship,
            nomineeEmail = nomineeEmail?.trim()?.takeIf { it.isNotEmpty() },
            nomineePhone = nomineePhone?.trim()?.takeIf { it.isNotEmpty() },
        ),
    )
}

@Composable
fun GuardianScreen(backend: SessionBackend) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var childAttempt by remember { mutableStateOf<GuardianConsent.ChildAttempt?>(null) }
    var guardian by remember { mutableStateOf<SignedInSession?>(null) }
    var guardianMe by remember { mutableStateOf<Principal?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var granted by remember { mutableStateOf<List<Consent>>(emptyList()) }
    var nomination by remember { mutableStateOf<DataRequest?>(null) }

    fun launchStep(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.describe()
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint("Customer ${GuardianConsent.CHILD_REF} is a minor: consent can only come from a verified parent or guardian (s.9).")
        ErrorMessage(error)

        SectionCard("Step 1: the child's own session is refused") {
            Button(
                onClick = { launchStep { childAttempt = GuardianConsent.tryAsChild(backend) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Try to give consent as the child") }
            childAttempt?.let { a ->
                Field("acts_through_guardian", a.me.actsThroughGuardian.toString())
                Field("acting_as", a.me.actingAs.wire)
                Field("needsGuardianSession", a.me.needsGuardianSession.toString())
                val refusal = a.refusal
                if (refusal != null) {
                    Text("Refused, as required:", color = MaterialTheme.colorScheme.error)
                    Field("HTTP status", refusal.status.toString())
                    Field("isGuardianRequired", refusal.isGuardianRequired.toString())
                    Field("detail", refusal.detail)
                } else {
                    Text("Unexpected: the server accepted the child's consent.", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        SectionCard("Step 2: the guardian gives consent") {
            Hint("Your backend authenticates the parent first, then mints the session with acting_as = guardian.")
            val g = guardian
            if (g == null) {
                Button(
                    onClick = {
                        launchStep {
                            val opened = GuardianConsent.openGuardianSession(backend)
                            guardianMe = opened.consent.me()
                            notice = opened.consent.currentNotice()
                            guardian = opened
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in as the guardian") }
            } else {
                guardianMe?.let { me ->
                    Field("acting_as", me.actingAs.wire)
                    Field("isGuardianSession", me.isGuardianSession.toString())
                    Field("guardian_verified", me.guardianVerified.toString())
                }
                notice?.let { n ->
                    Text(n.title, style = MaterialTheme.typography.titleSmall)
                    n.purposes.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, Modifier.weight(1f))
                            Switch(checked = p.id in selected, onCheckedChange = { on -> selected = if (on) selected + p.id else selected - p.id })
                        }
                    }
                    Button(
                        onClick = { launchStep { granted = GuardianConsent.consentAsGuardian(g, n, selected) } },
                        enabled = !busy && selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Give consent as guardian") }
                }
                granted.forEach { c -> Field("${c.purposeCode} · ${c.status.wire}", c.id) }
                GuardianConsentSheet(g)
                OutlinedButton(
                    onClick = {
                        launchStep {
                            g.logout()
                            guardian = null
                            guardianMe = null
                            granted = emptyList()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign the guardian out") }
            }
        }

        val g = guardian
        if (g != null) NominationCard(busy = busy, filed = nomination) { name, relationship, phone, email ->
            launchStep { nomination = GuardianConsent.nominate(g, name, relationship, phone, email) }
        }
    }
}

/** The drop-in sheet also works in a guardian session, and shows "You are acting as guardian…" context. */
@Composable
private fun GuardianConsentSheet(guardian: SignedInSession) {
    val vm = rememberConsentViewModel(guardian.consent)
    var show by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Or open the drop-in ConsentSheet as guardian") }
    outcome?.let { Hint(it) }
    if (show) {
        ConsentSheet(
            viewModel = vm,
            onDecision = { decision ->
                outcome = when (decision) {
                    is ConsentDecision.Accepted -> "Guardian accepted: " + decision.consents.joinToString { it.purposeCode }
                    ConsentDecision.Rejected -> "Guardian rejected all: nothing recorded."
                }
                show = false
            },
            onDismissRequest = { show = false },
        )
    }
}

@Composable
private fun NominationCard(busy: Boolean, filed: DataRequest?, onSubmit: (String, String, String?, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("Asha Rao") }
    var relationship by rememberSaveable { mutableStateOf("mother") }
    var phone by rememberSaveable { mutableStateOf("+919812345678") }
    var email by rememberSaveable { mutableStateOf("") }
    SectionCard("Nominate someone (s.14)") {
        Hint("DataRequestDetails.Nomination needs a name, a relationship, and an email or an E.164 phone number. Synthetic values.")
        OutlinedTextField(name, { name = it }, label = { Text("Nominee name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(relationship, { relationship = it }, label = { Text("Relationship") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (E.164)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSubmit(name, relationship, phone, email) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("File nomination")
        }
        filed?.let { Field("Nomination ${it.status.wire} · due ${it.dueAt}", it.id) }
    }
}
