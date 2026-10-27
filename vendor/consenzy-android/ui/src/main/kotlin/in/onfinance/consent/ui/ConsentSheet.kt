// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.onfinance.consent.core.Language
import `in`.onfinance.consent.core.NoticePurpose

/** Stable test tags so apps can drive the components from their own UI tests. */
public object ConsentTestTags {
    public const val SHEET: String = "consent_sheet"
    public const val ACCEPT: String = "consent_accept_selected"
    public const val REJECT: String = "consent_reject_all"
    public const val LANGUAGE: String = "consent_language"
    public const val ERROR: String = "consent_error"
    public const val GUARDIAN_CONTEXT: String = "consent_guardian_context"
    public const val CONSENT_NOT_PERMITTED: String = "consent_not_permitted"
    public const val REQUESTS_NOT_PERMITTED: String = "consent_requests_not_permitted"
    public const val REQUEST_HISTORY: String = "consent_request_history"
    public fun purpose(id: String): String = "consent_purpose_$id"
    public fun withdraw(consentId: String): String = "consent_withdraw_$consentId"
    public const val WITHDRAW_CONFIRM: String = "consent_withdraw_confirm"
    public const val REQUEST_SUBMIT: String = "consent_request_submit"
    public fun requestType(type: String): String = "consent_request_type_$type"
    public const val BREACH_NOTICES: String = "consent_breach_notices"
    public const val BREACH_BADGE: String = "consent_breach_badge"
    public fun breachNotice(breachId: String): String = "consent_breach_notice_$breachId"
    public fun breachAcknowledge(breachId: String): String = "consent_breach_acknowledge_$breachId"
}

/**
 * Modal bottom sheet showing the current notice with one switch per purpose. Every switch
 * starts OFF (no pre-ticked boxes); "Accept selected" and "Reject all" have equal weight.
 * [onDecision] is called once with the outcome; the app should then dismiss the sheet.
 *
 * @param dismissible when false, swipe-down / scrim / back cannot hide the sheet, so the
 *   principal must actively choose (use for flows where a decision is mandatory).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ConsentSheet(
    viewModel: ConsentViewModel,
    onDecision: (ConsentDecision) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
    theme: ConsentTheme = ConsentTheme(),
    dismissible: Boolean = true,
    showLanguagePicker: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible || it != SheetValue.Hidden },
    )
    ApplyConsentTheme(theme) {
        ModalBottomSheet(
            onDismissRequest = { if (dismissible) onDismissRequest() },
            sheetState = sheetState,
            modifier = modifier,
        ) {
            ConsentNotice(
                viewModel = viewModel,
                onDecision = onDecision,
                strings = strings,
                showLanguagePicker = showLanguagePicker,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

/**
 * The sheet's content without the bottom sheet, for embedding in an onboarding screen or a
 * full-screen dialog. Loads the notice on first composition.
 */
@Composable
public fun ConsentNotice(
    viewModel: ConsentViewModel,
    onDecision: (ConsentDecision) -> Unit,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
    theme: ConsentTheme = ConsentTheme(),
    showLanguagePicker: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.loadPrincipal() // capabilities and acting_as decide what is enabled
        if (viewModel.state.value.notice == null && !viewModel.state.value.noticeLoading) viewModel.loadNotice()
    }
    LaunchedEffect(state.decision) {
        state.decision?.let {
            onDecision(it)
            viewModel.consumeDecision()
        }
    }
    ApplyConsentTheme(theme) {
        ConsentNoticeContent(
            state = state,
            strings = strings,
            showLanguagePicker = showLanguagePicker,
            onLanguage = viewModel::selectLanguage,
            onToggle = viewModel::setPurposeSelected,
            onAccept = viewModel::acceptSelected,
            onReject = viewModel::rejectAll,
            onRetry = { viewModel.loadNotice() },
            modifier = modifier,
        )
    }
}

/** Stateless notice UI. Exposed for previews and custom state holders. */
@Composable
public fun ConsentNoticeContent(
    state: ConsentUiState,
    strings: ConsentStrings,
    onLanguage: (Language) -> Unit,
    onToggle: (purposeId: String, selected: Boolean) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showLanguagePicker: Boolean = true,
) {
    val notice = state.notice
    Column(
        modifier
            .fillMaxWidth()
            .testTag(ConsentTestTags.SHEET)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notice?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (showLanguagePicker) {
                LanguagePicker(
                    selected = notice?.language ?: state.language ?: Language.EN,
                    strings = strings,
                    enabled = !state.noticeLoading && !state.submitting,
                    onSelect = onLanguage,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        state.principal?.takeIf { it.isGuardianSession }?.let { GuardianContext(strings.actingAsGuardian(it)) }

        when {
            state.noticeLoading && notice == null -> LoadingRow(strings)
            notice == null -> {
                state.error?.let { ErrorText(strings.errorMessage(it)) }
                TextButton(onClick = onRetry) { Text(strings.retry) }
            }
            else -> {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(notice.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        strings.purposesHeading,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(strings.purposesHint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    notice.purposes.forEach { purpose ->
                        PurposeRow(
                            purpose = purpose,
                            checked = purpose.id in state.selectedPurposeIds,
                            enabled = !state.submitting && state.canGiveConsent,
                            strings = strings,
                            onToggle = { onToggle(purpose.id, it) },
                        )
                        HorizontalDivider()
                    }
                }
                state.error?.let { ErrorText(strings.errorMessage(it)) }
                if (state.principal != null && !state.canGiveConsent) {
                    InfoText(strings.consentNotPermitted, Modifier.testTag(ConsentTestTags.CONSENT_NOT_PERMITTED))
                }
                Spacer(Modifier.size(12.dp))
                // Equal prominence: same component, same width, same colours.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onReject,
                        enabled = !state.submitting,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(ConsentTestTags.REJECT),
                    ) { Text(strings.rejectAll) }
                    OutlinedButton(
                        onClick = onAccept,
                        enabled = !state.submitting && state.canGiveConsent,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(ConsentTestTags.ACCEPT),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp).semantics { contentDescription = strings.loading },
                            )
                        } else {
                            Text(strings.acceptSelected)
                        }
                    }
                }
            }
        }
        Text(
            strings.poweredBy,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
        )
    }
}

@Composable
private fun PurposeRow(
    purpose: NoticePurpose,
    checked: Boolean,
    enabled: Boolean,
    strings: ConsentStrings,
    onToggle: (Boolean) -> Unit,
) {
    val description = strings.purposeSwitchDescription(purpose.name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // The whole row is one switch for TalkBack and a large touch target.
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = description }
            .testTag(ConsentTestTags.purpose(purpose.id))
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(purpose.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(purpose.description, style = MaterialTheme.typography.bodySmall)
            if (purpose.dataCategories.isNotEmpty()) {
                Text(
                    "${strings.dataCategories}: ${purpose.dataCategories.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            purpose.retentionDays?.let {
                Text(strings.retention(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        // onCheckedChange = null: the row handles the toggle, so it is announced once.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LanguagePicker(selected: Language, strings: ConsentStrings, enabled: Boolean, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier
                .testTag(ConsentTestTags.LANGUAGE)
                .semantics {
                    contentDescription = strings.chooseLanguage
                    stateDescription = "${strings.languageLabel}: ${selected.englishName}"
                },
        ) { Text(selected.nativeName) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Language.supported.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(if (lang.nativeName == lang.englishName) lang.nativeName else "${lang.nativeName} · ${lang.englishName}") },
                    onClick = {
                        open = false
                        onSelect(lang)
                    },
                )
            }
        }
    }
}

@Composable
internal fun LoadingRow(strings: ConsentStrings) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = strings.loading })
    }
}

@Composable
internal fun GuardianContext(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp).testTag(ConsentTestTags.GUARDIAN_CONTEXT),
    )
}

@Composable
internal fun InfoText(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
internal fun ErrorText(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .testTag(ConsentTestTags.ERROR)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
