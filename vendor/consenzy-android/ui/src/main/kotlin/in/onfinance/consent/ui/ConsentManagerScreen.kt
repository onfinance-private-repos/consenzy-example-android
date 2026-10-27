// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.onfinance.consent.core.Consent

/**
 * Lists the principal's active consents, each with a Withdraw button behind a confirmation
 * dialog (DPDP s.6(4): withdrawing must be as easy as giving consent). Purpose names come
 * from the current notice; the purpose code is shown when a purpose is no longer on it.
 */
@Composable
public fun ConsentManagerScreen(
    viewModel: ConsentViewModel,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
    theme: ConsentTheme = ConsentTheme(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.refreshConsents(activeOnly = true)
        if (viewModel.state.value.notice == null) viewModel.loadNotice()
    }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val names = state.notice?.purposes?.associate { it.id to it.name }.orEmpty()
    fun nameOf(c: Consent) = names[c.purposeId] ?: c.purposeCode

    ApplyConsentTheme(theme) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(strings.managerTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }
            state.principal?.takeIf { it.isGuardianSession }?.let { p -> item { GuardianContext(strings.actingAsGuardian(p)) } }
            if (state.principal != null && !state.canGiveConsent) {
                item { InfoText(strings.consentNotPermitted, Modifier.testTag(ConsentTestTags.CONSENT_NOT_PERMITTED)) }
            }
            state.error?.let { error -> item { ErrorText(strings.errorMessage(error)) } }
            val active = state.consents.filter { it.isActive }
            when {
                state.consentsLoading && active.isEmpty() -> item { LoadingRow(strings) }
                active.isEmpty() -> item { Text(strings.noActiveConsents, style = MaterialTheme.typography.bodyMedium) }
                else -> items(active, key = { it.id }) { consent ->
                    ConsentCard(
                        name = nameOf(consent),
                        consent = consent,
                        withdrawing = state.withdrawingId == consent.id,
                        enabled = state.withdrawingId == null && state.canGiveConsent,
                        strings = strings,
                        onWithdraw = { confirming = consent.id },
                    )
                }
            }
            item {
                Text(
                    strings.poweredBy,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        val target = confirming?.let { id -> state.consents.firstOrNull { it.id == id } }
        if (target != null) {
            WithdrawDialog(
                purposeName = nameOf(target),
                strings = strings,
                onConfirm = { reason ->
                    confirming = null
                    viewModel.withdraw(target.id, reason)
                },
                onDismiss = { confirming = null },
            )
        }
    }
}

@Composable
private fun ConsentCard(
    name: String,
    consent: Consent,
    withdrawing: Boolean,
    enabled: Boolean,
    strings: ConsentStrings,
    onWithdraw: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(strings.givenOn(isoDate(consent.givenAt)), style = MaterialTheme.typography.bodySmall)
            }
            if (withdrawing) {
                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = strings.loading })
            } else {
                val description = strings.withdrawDescription(name)
                OutlinedButton(
                    onClick = onWithdraw,
                    enabled = enabled,
                    modifier = Modifier
                        .testTag(ConsentTestTags.withdraw(consent.id))
                        .semantics { contentDescription = description },
                ) { Text(strings.withdraw) }
            }
        }
    }
}

@Composable
private fun WithdrawDialog(purposeName: String, strings: ConsentStrings, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.withdrawConfirmTitle) },
        text = {
            Column {
                Text(strings.withdrawConfirmBody(purposeName))
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(1000) },
                    label = { Text(strings.withdrawReasonLabel) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim().ifEmpty { null }) },
                modifier = Modifier.testTag(ConsentTestTags.WITHDRAW_CONFIRM),
            ) { Text(strings.withdraw) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}
