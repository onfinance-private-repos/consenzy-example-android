package com.example.consenzy.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import `in`.onfinance.consent.ui.ConsentManagerScreen
import kotlinx.coroutines.launch

/**
 * Journey 3: preferences centre, headless. Withdrawing must be as easy as giving (DPDP s.6(4)).
 */
object Preferences {
    /** `GET /v1/me/consents?active_only=true`, newest first. */
    suspend fun activeConsents(session: ConsentSession): List<Consent> = session.listConsents(activeOnly = true)

    /** Purpose code → display name from the current notice, so the list shows names rather than codes. */
    suspend fun purposeNames(session: ConsentSession): Map<String, String> =
        session.currentNotice().purposes.associate { it.code to it.name }

    /** `POST /v1/me/consents/{id}/withdraw` with an optional reason. Never retried automatically by the SDK. */
    suspend fun withdraw(session: ConsentSession, consentId: String, reason: String?): Consent =
        session.withdrawConsent(consentId, reason = reason?.trim()?.takeIf { it.isNotEmpty() })
}

@Composable
fun PreferencesScreen(signedIn: SignedInSession) {
    var variant by rememberSaveable { mutableStateOf(UiVariant.HEADLESS) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) { ChoiceRow(UiVariant.entries, variant, { it.label }, { variant = it }) }
        when (variant) {
            UiVariant.HEADLESS -> HeadlessPreferences(signedIn.consent)
            // The SDK's drop-in privacy settings screen: active consents, each with Withdraw + confirmation dialog.
            UiVariant.DROP_IN -> Box(Modifier.weight(1f)) { ConsentManagerScreen(viewModel = rememberConsentViewModel(signedIn.consent)) }
        }
    }
}

@Composable
private fun HeadlessPreferences(session: ConsentSession) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var consents by remember { mutableStateOf<List<Consent>?>(null) }
    var names by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<Consent?>(null) }
    var lastWithdrawn by remember { mutableStateOf<Consent?>(null) }

    LaunchedEffect(reload) {
        error = null
        try {
            if (names.isEmpty()) names = Preferences.purposeNames(session)
            consents = Preferences.activeConsents(session)
        } catch (e: Exception) {
            error = e.describe()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorMessage(error)
        lastWithdrawn?.let { w ->
            SectionCard("Withdrawn") {
                Field("${w.purposeCode} · ${w.status.wire}", w.id)
                Field("withdrawn_at", w.withdrawnAt)
                Field("reason", w.withdrawalReason)
            }
        }
        val list = consents
        when {
            list == null && error == null -> CircularProgressIndicator()
            list.isNullOrEmpty() -> Hint("No active consents. Give some in journey 1.")
            else -> list.orEmpty().forEach { c ->
                SectionCard(names[c.purposeCode] ?: c.purposeCode) {
                    Field("Consent id", c.id)
                    Field("Given at · channel", "${c.givenAt} · ${c.channel}")
                    Field("Expires", c.expiresAt)
                    OutlinedButton(onClick = { confirming = c }, modifier = Modifier.fillMaxWidth()) { Text("Withdraw") }
                }
            }
        }
        TextButton(onClick = { reload++ }) { Text("Refresh") }
    }

    confirming?.let { c ->
        WithdrawDialog(
            purposeName = names[c.purposeCode] ?: c.purposeCode,
            onDismiss = { confirming = null },
            onConfirm = { reason ->
                confirming = null
                scope.launch {
                    try {
                        lastWithdrawn = Preferences.withdraw(session, c.id, reason)
                        reload++
                    } catch (e: Exception) {
                        error = e.describe()
                    }
                }
            },
        )
    }
}

/** Confirm before withdrawing, with an optional reason that is stored on the consent artefact. */
@Composable
private fun WithdrawDialog(purposeName: String, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Withdraw consent?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Demo Bank will stop using your data for \"$purposeName\".")
                OutlinedTextField(value = reason, onValueChange = { reason = it.take(1000) }, label = { Text("Reason (optional)") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }) { Text("Withdraw") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
