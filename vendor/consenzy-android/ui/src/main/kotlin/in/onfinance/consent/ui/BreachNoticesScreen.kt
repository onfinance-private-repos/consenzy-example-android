// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.onfinance.consent.core.BreachNotice

/**
 * Lists the personal data breach notices sent to the principal (DPDP Rules r.7(1)), newest
 * first, each with an "I have read this" button until it is acknowledged. Subject and body are
 * shown as plain [Text] exactly as the server sent them; they are never parsed as HTML.
 */
@Composable
public fun BreachNoticesScreen(
    viewModel: ConsentViewModel,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
    theme: ConsentTheme = ConsentTheme(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshBreachNotices() }

    ApplyConsentTheme(theme) {
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag(ConsentTestTags.BREACH_NOTICES),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    strings.breachNoticesTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    strings.breachNoticesIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { error ->
                item {
                    ErrorText(strings.errorMessage(error))
                    if (state.breachNotices.isEmpty()) {
                        TextButton(onClick = viewModel::refreshBreachNotices) { Text(strings.retry) }
                    }
                }
            }
            val notices = state.breachNotices
            when {
                state.breachNoticesLoading && notices.isEmpty() -> item { LoadingRow(strings) }
                notices.isEmpty() && state.error == null ->
                    item { Text(strings.noBreachNotices, style = MaterialTheme.typography.bodyMedium) }
                else -> items(notices, key = { it.noticeId }) { notice ->
                    BreachNoticeCard(
                        notice = notice,
                        acknowledging = state.acknowledgingBreachId == notice.breachId,
                        enabled = state.acknowledgingBreachId == null,
                        strings = strings,
                        onAcknowledge = { viewModel.acknowledgeBreachNotice(notice.breachId) },
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
    }
}

@Composable
private fun BreachNoticeCard(
    notice: BreachNotice,
    acknowledging: Boolean,
    enabled: Boolean,
    strings: ConsentStrings,
    onAcknowledge: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().testTag(ConsentTestTags.breachNotice(notice.breachId))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!notice.isAcknowledged) {
                Text(
                    strings.breachUnread,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(notice.subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                strings.breachReceivedOn(isoDate(notice.generatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(notice.body, style = MaterialTheme.typography.bodyMedium)
            if (notice.dataCategories.isNotEmpty()) {
                Text(
                    strings.breachDataAffected(notice.dataCategories.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                notice.isAcknowledged -> Text(
                    strings.breachAcknowledgedOn(isoDate(notice.acknowledgedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                acknowledging -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = strings.loading })
                else -> {
                    val description = strings.breachAcknowledgeDescription(notice.subject)
                    Button(
                        onClick = onAcknowledge,
                        enabled = enabled,
                        modifier = Modifier
                            .testTag(ConsentTestTags.breachAcknowledge(notice.breachId))
                            .semantics { contentDescription = description },
                    ) { Text(strings.breachAcknowledge) }
                }
            }
        }
    }
}

/**
 * A small count pill for a menu item or tab, e.g.
 * `BreachNoticesBadge(state.unacknowledgedBreachNotices)`. Draws nothing when [count] is 0;
 * shows "99+" above 99. Screen readers hear [ConsentStrings.breachBadgeDescription].
 */
@Composable
public fun BreachNoticesBadge(
    count: Int,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
) {
    if (count <= 0) return
    val description = strings.breachBadgeDescription(count)
    Box(
        modifier = modifier
            .testTag(ConsentTestTags.BREACH_BADGE)
            .clearAndSetSemantics { contentDescription = description }
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}
