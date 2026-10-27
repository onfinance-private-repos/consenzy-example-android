package com.example.consenzy.ui.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.consenzy.session.SignedInSession
import com.example.consenzy.ui.common.ChoiceRow
import com.example.consenzy.ui.common.ErrorMessage
import com.example.consenzy.ui.common.Field
import com.example.consenzy.ui.common.Hint
import com.example.consenzy.ui.common.SectionCard
import com.example.consenzy.ui.common.UiVariant
import com.example.consenzy.ui.common.describe
import com.example.consenzy.ui.common.rememberConsentViewModel
import `in`.onfinance.consent.core.Consent
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.core.Language
import `in`.onfinance.consent.core.Notice
import `in`.onfinance.consent.ui.ConsentDecision
import `in`.onfinance.consent.ui.ConsentSheet
import kotlinx.coroutines.launch

/**
 * Journey 1: consent collection, headless. These are the only SDK calls the headless screen below makes.
 */
object ConsentCollection {
    /** English, Hindi, and Tamil (no Tamil translation is published in the demo, so the server falls back to English). */
    val LANGUAGES = listOf(Language.EN, Language.HI, Language.TA)

    /** `GET /v1/me/notice?language=…`: the latest published notice; the server falls back to English. */
    suspend fun loadNotice(session: ConsentSession, language: Language): Notice = session.currentNotice(language)

    /** True when the server answered in another language than the one asked for (no translation published). */
    fun isFallback(requested: Language, notice: Notice): Boolean = notice.languageCode != requested.wire

    /**
     * `POST /v1/me/consents`: records consent for exactly the purposes the customer switched on (channel
     * `mobile_app`, with an automatic Idempotency-Key, so a retry never duplicates the record).
     */
    suspend fun recordConsent(session: ConsentSession, notice: Notice, selectedPurposeIds: Set<String>): List<Consent> {
        val chosen = notice.purposes.map { it.id }.filter { it in selectedPurposeIds }
        return session.giveConsent(notice.id, chosen)
    }
}

@Composable
fun ConsentCollectionScreen(signedIn: SignedInSession) {
    var variant by rememberSaveable { mutableStateOf(UiVariant.HEADLESS) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) { ChoiceRow(UiVariant.entries, variant, { it.label }, { variant = it }) }
        when (variant) {
            UiVariant.HEADLESS -> HeadlessConsent(signedIn.consent)
            UiVariant.DROP_IN -> DropInConsentSheet(signedIn)
        }
    }
}

/** Your own UI: show the itemised notice, let the customer switch purposes on, record consent. */
@Composable
private fun HeadlessConsent(session: ConsentSession) {
    val scope = rememberCoroutineScope()
    var language by rememberSaveable { mutableStateOf(Language.EN) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) } // every purpose starts OFF (DPDP s.6(1))
    var submitting by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf<List<Consent>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(language) {
        loading = true
        error = null
        try {
            val loaded = ConsentCollection.loadNotice(session, language)
            notice = loaded
            selected = selected intersect loaded.purposes.map { it.id }.toSet()
        } catch (e: Exception) {
            error = e.describe()
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChoiceRow(ConsentCollection.LANGUAGES, language, { "${it.nativeName} (${it.wire})" }, { language = it }, enabled = !loading)
        if (loading) CircularProgressIndicator()
        ErrorMessage(error)

        val current = notice
        if (current != null) {
            if (ConsentCollection.isFallback(language, current)) {
                Hint(
                    "No ${language.englishName} translation is published, so the server returned the " +
                        "${Language.fromCode(current.languageCode)?.englishName ?: current.languageCode} notice.",
                )
            }
            Text(current.title, style = MaterialTheme.typography.headlineSmall)
            Hint("Notice version ${current.version} · language ${current.languageCode}")
            Text(current.body, style = MaterialTheme.typography.bodyMedium)

            current.purposes.forEach { purpose ->
                SectionCard(purpose.name) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(purpose.description, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = purpose.id in selected,
                            onCheckedChange = { on -> selected = if (on) selected + purpose.id else selected - purpose.id },
                        )
                    }
                    Field("Data collected", purpose.dataCategories.joinToString(", "))
                    Field("Kept for", purpose.retentionDays?.let { "$it days" })
                    Hint("Purpose code: ${purpose.code}")
                }
            }

            Button(
                onClick = {
                    submitting = true
                    error = null
                    scope.launch {
                        try {
                            granted = ConsentCollection.recordConsent(session, current, selected)
                        } catch (e: Exception) {
                            error = e.describe()
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = selected.isNotEmpty() && !submitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (selected.isEmpty()) "Switch on at least one purpose" else "Give consent for ${selected.size} purpose(s)") }
        }

        if (granted.isNotEmpty()) {
            SectionCard("Consent recorded") {
                granted.forEach { c -> Field("${c.purposeCode} · ${c.status.wire} · ${c.channel}", c.id) }
                Hint("Each purpose gets its own consent artefact. Manage them in journey 3.")
            }
        }
    }
}

/** The SDK's drop-in `ConsentSheet`: notice, language picker, purpose switches, "Accept selected" / "Reject all". */
@Composable
private fun DropInConsentSheet(signedIn: SignedInSession) {
    val vm = rememberConsentViewModel(signedIn.consent)
    var show by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint(
            "ConsentSheet is a modal bottom sheet from consenzy-android-ui. Every switch starts off and " +
                "\"Accept selected\" and \"Reject all\" have equal weight. With dismissible = false the customer must choose.",
        )
        Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Open ConsentSheet") }
        outcome?.let { SectionCard("Decision") { Text(it) } }
    }

    if (show) {
        ConsentSheet(
            viewModel = vm,
            onDecision = { decision ->
                outcome = when (decision) {
                    is ConsentDecision.Accepted -> "Accepted: " + decision.consents.joinToString { it.purposeCode }
                    ConsentDecision.Rejected -> "Rejected all: nothing was recorded."
                }
                show = false
            },
            onDismissRequest = { show = false },
            dismissible = false,
        )
    }
}
