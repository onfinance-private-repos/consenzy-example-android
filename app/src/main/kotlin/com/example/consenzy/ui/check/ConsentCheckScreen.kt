package com.example.consenzy.ui.check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.consenzy.session.ConsentCheckResult
import com.example.consenzy.session.SignedInSession
import com.example.consenzy.ui.common.ErrorMessage
import com.example.consenzy.ui.common.Field
import com.example.consenzy.ui.common.Hint
import com.example.consenzy.ui.common.SectionCard
import com.example.consenzy.ui.common.describe
import kotlinx.coroutines.launch

/**
 * Journey 2: check consent before using data for a purpose.
 *
 * The check runs on **your backend** (`POST /consent-check` on the session server, which calls Consenzy's
 * `POST /v1/consents/check` with its org key). The app never decides on its own that processing is allowed.
 */
object ConsentCheck {
    const val PURPOSE = "marketing"

    sealed class Outcome {
        abstract val check: ConsentCheckResult

        /** Allowed: the offer goes out; keep `consent_id` and `ledger_hash` with the processing record as evidence. */
        data class Sent(override val check: ConsentCheckResult) : Outcome()

        /** No active consent for the purpose: do not process. */
        data class Blocked(override val check: ConsentCheckResult) : Outcome()
    }

    /** "Send me offers": ask the backend for `marketing`, and only proceed when the ledger says `allowed`. */
    suspend fun sendOffers(signedIn: SignedInSession): Outcome {
        val check = signedIn.backend.consentCheck(signedIn.externalRef, PURPOSE)
        if (!check.allowed) return Outcome.Blocked(check)
        // ...your backend would now send the offer, storing check.consentId and check.ledgerHash with it.
        return Outcome.Sent(check)
    }
}

@Composable
fun ConsentCheckScreen(signedIn: SignedInSession) {
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<ConsentCheck.Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint(
            "Before your backend processes personal data for a purpose, it asks the consent ledger. Grant " +
                "\"Marketing messages\" in journey 1 and the offer goes out; withdraw it in journey 3 and it is blocked again.",
        )
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        outcome = ConsentCheck.sendOffers(signedIn)
                    } catch (e: Exception) {
                        error = e.describe()
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send me offers") }
        ErrorMessage(error)

        when (val o = outcome) {
            is ConsentCheck.Outcome.Sent -> SectionCard("Offer sent") {
                Text("The ledger allows \"${ConsentCheck.PURPOSE}\" (basis: ${o.check.basis}). Evidence to store:")
                Field("consent_id", o.check.consentId)
                Field("ledger_event_id", o.check.ledgerEventId?.toString())
                Field("ledger_hash", o.check.ledgerHash)
                Field("expires_at", o.check.expiresAt)
            }
            is ConsentCheck.Outcome.Blocked -> SectionCard("Not sent") {
                Text(
                    "There is no active consent for \"${ConsentCheck.PURPOSE}\", so nothing was processed.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            null -> Unit
        }
    }
}
