package com.example.consenzy.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.onfinance.consent.core.ConsentApiException
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.ui.ConsentViewModel

/** A short, readable description of an SDK or backend failure. */
fun Throwable.describe(): String = when (this) {
    is ConsentApiException -> "HTTP $status: $detail"
    is IllegalArgumentException -> "invalid input: ${message ?: "?"}"
    else -> message ?: javaClass.simpleName
}

/**
 * The SDK's Compose view model for one [ConsentSession]. Keyed by the session, so signing in as someone else
 * never reuses a view model bound to the previous customer's session.
 */
@Composable
fun rememberConsentViewModel(session: ConsentSession): ConsentViewModel {
    val key = remember(session) { "consenzy-vm-" + System.identityHashCode(session) }
    return viewModel(key = key, factory = ConsentViewModel.factory(session))
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ErrorMessage(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** A label and a monospace value, for ids, hashes and timestamps. */
@Composable
fun Field(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "(none)", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** A row of labelled options; the selected one is a filled button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            if (option == selected) {
                Button(onClick = { onSelect(option) }, enabled = enabled) { Text(label(option)) }
            } else {
                OutlinedButton(onClick = { onSelect(option) }, enabled = enabled) { Text(label(option)) }
            }
        }
    }
}

/** Switches between the headless example (your own UI on the SDK's suspend API) and the SDK's drop-in Compose UI. */
enum class UiVariant(val label: String) {
    HEADLESS("Headless (your UI)"),
    DROP_IN("SDK drop-in UI"),
}
