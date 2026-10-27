package com.example.consenzy.ui.rights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.core.DataRequest
import `in`.onfinance.consent.core.DataRequestDetails
import `in`.onfinance.consent.core.RequestType
import `in`.onfinance.consent.ui.RightsRequestForm
import kotlinx.coroutines.launch

/**
 * Journey 4: Data Principal rights requests, headless. `DataRequestDetails` applies the server's limits in its
 * constructors (bad input fails early with IllegalArgumentException), and every filing carries an automatic
 * Idempotency-Key. Requests filed through the SDK are recorded with channel `app`.
 */
object RightsRequests {
    /** The request types this screen offers (the SDK's `RequestType.fileable` also has completion, updating, nomination). */
    val TYPES = listOf(RequestType.ACCESS, RequestType.CORRECTION, RequestType.ERASURE, RequestType.GRIEVANCE)

    /** s.11: a summary of my personal data. */
    suspend fun fileAccess(session: ConsentSession, note: String?): DataRequest =
        session.fileDataRequest(DataRequestDetails.Access(note = note.orNullIfBlank()))

    /** s.12(1): correct one field. `Correction` also covers COMPLETION and UPDATING, so the type is passed explicitly. */
    suspend fun fileCorrection(session: ConsentSession, field: String, value: String, note: String?): DataRequest =
        session.fileDataRequest(
            RequestType.CORRECTION,
            DataRequestDetails.Correction.of(mapOf(field.trim() to value), note = note.orNullIfBlank()),
        )

    /** s.12(3): erase my data. */
    suspend fun fileErasure(session: ConsentSession, reason: String?): DataRequest =
        session.fileDataRequest(DataRequestDetails.Erasure(reason = reason.orNullIfBlank()))

    /** s.13: a grievance, optionally about one of my earlier requests. */
    suspend fun fileGrievance(session: ConsentSession, description: String, relatedRequestId: String?): DataRequest =
        session.fileDataRequest(DataRequestDetails.Grievance(description, relatedRequestId = relatedRequestId))

    /**
     * `GET /v1/me/data-requests`, newest first: every request of this customer, including ones your backend recorded
     * from WhatsApp, email, a branch and so on (`channel`, `channelReference`).
     */
    suspend fun history(session: ConsentSession): List<DataRequest> = session.listDataRequests()

    /** e.g. "erasure · in_progress · whatsapp · 2026-11-22T…" */
    fun summary(r: DataRequest): String =
        listOfNotNull(r.requestType.wire, r.status.wire, r.channel, r.dueAt).joinToString(" · ")

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

@Composable
fun RightsRequestsScreen(signedIn: SignedInSession) {
    var variant by rememberSaveable { mutableStateOf(UiVariant.HEADLESS) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) { ChoiceRow(UiVariant.entries, variant, { it.label }, { variant = it }) }
        when (variant) {
            UiVariant.HEADLESS -> HeadlessRights(signedIn.consent)
            UiVariant.DROP_IN -> DropInRightsForm(signedIn)
        }
    }
}

@Composable
private fun HeadlessRights(session: ConsentSession) {
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableStateOf(RequestType.ACCESS) }
    var text by rememberSaveable { mutableStateOf("") }
    var field by rememberSaveable { mutableStateOf("address_line_1") }
    var value by rememberSaveable { mutableStateOf("") }
    var linkLatest by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var filed by remember { mutableStateOf<DataRequest?>(null) }
    var requests by remember { mutableStateOf<List<DataRequest>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // Status tracking: reload the list on open, after each filing and on Refresh.
    LaunchedEffect(reload) {
        try {
            requests = RightsRequests.history(session)
        } catch (e: Exception) {
            error = e.describe()
        }
    }

    fun submit() {
        submitting = true
        error = null
        val latest = requests.firstOrNull()
        scope.launch {
            try {
                filed = when (type) {
                    RequestType.ACCESS -> RightsRequests.fileAccess(session, note = text)
                    RequestType.CORRECTION -> RightsRequests.fileCorrection(session, field, value, note = text)
                    RequestType.ERASURE -> RightsRequests.fileErasure(session, reason = text)
                    RequestType.GRIEVANCE -> RightsRequests.fileGrievance(session, text, if (linkLatest) latest?.id else null)
                    else -> throw IllegalStateException("${type.wire} is not offered on this screen")
                }
                text = ""
                value = ""
                reload++
            } catch (e: Exception) {
                error = e.describe()
            } finally {
                submitting = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("File a request") {
            ChoiceRow(RightsRequests.TYPES, type, { it.wire }, { type = it })
            when (type) {
                RequestType.CORRECTION -> {
                    OutlinedTextField(field, { field = it }, label = { Text("Field (snake_case)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value, { value = it }, label = { Text("Correct value") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                }
                RequestType.GRIEVANCE -> {
                    OutlinedTextField(text, { text = it }, label = { Text("What went wrong?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    requests.firstOrNull()?.let { latest ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = linkLatest, onCheckedChange = { linkLatest = it })
                            Text("About my latest request (${latest.requestType.wire}, ${latest.status.wire})")
                        }
                    }
                }
                RequestType.ERASURE ->
                    OutlinedTextField(text, { text = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
                else ->
                    OutlinedTextField(text, { text = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = ::submit, enabled = !submitting, modifier = Modifier.fillMaxWidth()) { Text("Submit ${type.wire} request") }
            ErrorMessage(error)
            filed?.let { Hint("Filed ${it.requestType.wire} request ${it.id} (channel ${it.channel}), due ${it.dueAt}.") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My requests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { reload++ }) { Text("Refresh") }
        }
        Hint("Demo requests move from received to in_progress after 20 s and to completed after 60 s. Refresh to see it.")
        requests.forEach { r ->
            SectionCard("${r.requestType.wire} · ${r.status.wire}${if (r.status.isOpen) " (open)" else ""}") {
                Field("Received via", listOfNotNull(r.channel, r.channelReference).joinToString(" · "))
                Field("Due (statutory deadline)", r.dueAt)
                r.closedAt?.let { Field("Closed", it) }
                Hint(RightsRequests.summary(r))
            }
        }
    }
}

/** The SDK's drop-in `RightsRequestForm`: type picker, detail fields, submit, and history with status and channel. */
@Composable
private fun DropInRightsForm(signedIn: SignedInSession) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        confirmation?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
        Box(Modifier.weight(1f)) {
            RightsRequestForm(
                viewModel = rememberConsentViewModel(signedIn.consent),
                types = RightsRequests.TYPES,
                onSubmitted = { request -> confirmation = "Request received, due ${request.dueAt}" },
            )
        }
    }
}
