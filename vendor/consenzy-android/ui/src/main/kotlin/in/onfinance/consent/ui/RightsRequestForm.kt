// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.onfinance.consent.core.DataRequest
import `in`.onfinance.consent.core.DataRequestDetails
import `in`.onfinance.consent.core.RequestType

/**
 * The form is replaced by [ConsentStrings.requestsNotPermitted] when the session lacks
 * `requests:write`, and the history is hidden when it lacks `requests:read`.
 *
 * Form for filing a Data Principal rights request (access, correction / completion /
 * updating, erasure, grievance, nomination), with the fields each type requires, followed
 * by the principal's previous requests and their statutory due dates.
 */
@Composable
public fun RightsRequestForm(
    viewModel: ConsentViewModel,
    modifier: Modifier = Modifier,
    strings: ConsentStrings = ConsentStrings(),
    theme: ConsentTheme = ConsentTheme(),
    types: List<RequestType> = RequestType.fileable,
    showHistory: Boolean = true,
    onSubmitted: (DataRequest) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, showHistory) {
        viewModel.loadPrincipal() // capabilities decide what is shown
        if (showHistory) viewModel.refreshDataRequests()
    }

    var type by rememberSaveable { mutableStateOf(types.first()) }
    var note by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var nomineeName by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }

    val filed = state.lastFiledRequest
    LaunchedEffect(filed?.id) {
        if (filed != null) {
            onSubmitted(filed)
            note = ""; field = ""; value = ""; description = ""; nomineeName = ""; relationship = ""; email = ""; phone = ""
        }
    }

    fun submit() {
        val details = try {
            when (type) {
                RequestType.ACCESS -> DataRequestDetails.Access(note.ifBlank { null })
                RequestType.CORRECTION, RequestType.COMPLETION, RequestType.UPDATING ->
                    DataRequestDetails.Correction.of(mapOf(fieldKey(field) to value.trim()), note.ifBlank { null })
                RequestType.ERASURE -> DataRequestDetails.Erasure(note.ifBlank { null })
                RequestType.GRIEVANCE -> DataRequestDetails.Grievance(description)
                RequestType.NOMINATION -> DataRequestDetails.Nomination(
                    nomineeName, relationship, email.ifBlank { null }, phone.replace(" ", "").ifBlank { null },
                )
                RequestType.UNKNOWN -> return
            }
        } catch (e: IllegalArgumentException) {
            viewModel.reportInvalidInput(e.message ?: strings.genericError)
            return
        }
        viewModel.fileDataRequest(type, details)
    }

    ApplyConsentTheme(theme) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(strings.rightsTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.size(12.dp))
            state.principal?.takeIf { it.isGuardianSession }?.let { GuardianContext(strings.actingAsGuardian(it)) }
            if (state.principal != null && !state.canFileRequests) {
                InfoText(strings.requestsNotPermitted, Modifier.testTag(ConsentTestTags.REQUESTS_NOT_PERMITTED))
                state.error?.let { ErrorText(strings.errorMessage(it)) }
            } else {
                Text(strings.requestTypeLabel, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    types.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = t == type, role = Role.RadioButton, onClick = { type = t; viewModel.clearError() })
                                .testTag(ConsentTestTags.requestType(t.wire)),
                        ) {
                            RadioButton(selected = t == type, onClick = null)
                            Text(strings.requestTypeName(t), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                when (type) {
                    RequestType.ACCESS, RequestType.ERASURE ->
                        Field(note, { note = it }, if (type == RequestType.ERASURE) strings.erasureReasonLabel else strings.noteLabel, max = 1000)
                    RequestType.CORRECTION, RequestType.COMPLETION, RequestType.UPDATING -> {
                        Field(field, { field = it }, strings.correctionFieldLabel, max = 64)
                        Field(value, { value = it }, strings.correctionValueLabel, max = 1000)
                        Field(note, { note = it }, strings.noteLabel, max = 1000)
                    }
                    RequestType.GRIEVANCE -> Field(description, { description = it }, strings.grievanceLabel, max = 4000, minLines = 4)
                    RequestType.NOMINATION -> {
                        Field(nomineeName, { nomineeName = it }, strings.nomineeNameLabel, max = 200)
                        Field(relationship, { relationship = it }, strings.nomineeRelationshipLabel, max = 128)
                        Field(email, { email = it }, strings.nomineeEmailLabel, max = 254, keyboard = KeyboardType.Email)
                        Field(phone, { phone = it }, strings.nomineePhoneLabel, max = 16, keyboard = KeyboardType.Phone)
                    }
                    RequestType.UNKNOWN -> Unit
                }
                state.error?.let { ErrorText(strings.errorMessage(it)) }
                filed?.let {
                    Text(
                        strings.requestSubmitted(isoDate(it.dueAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Button(
                    onClick = ::submit,
                    enabled = !state.filingRequest && state.canFileRequests,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp).testTag(ConsentTestTags.REQUEST_SUBMIT),
                ) {
                    if (state.filingRequest) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp).semantics { contentDescription = strings.loading })
                    } else {
                        Text(strings.submitRequest)
                    }
                }
            }

            if (showHistory && state.canReadRequests && state.dataRequests.isNotEmpty()) {
                Spacer(Modifier.size(24.dp))
                Text(
                    strings.previousRequests,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }.testTag(ConsentTestTags.REQUEST_HISTORY),
                )
                state.dataRequests.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(strings.requestTypeName(r.requestType), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                strings.requestStatusName(r.status),
                                r.channel?.let(strings.requestChannelName),
                                strings.dueBy(isoDate(r.dueAt)),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
            Text(
                strings.poweredBy,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    max: Int,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(max)) },
        label = { Text(label) },
        minLines = minLines,
        singleLine = minLines == 1 && keyboard != KeyboardType.Text,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** "Address line 1" -> "address_line_1", the snake_case key the API expects. */
internal fun fieldKey(label: String): String =
    label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        .let { if (it.isEmpty() || it.first().isLetter()) it else "field_$it" }.take(64)
