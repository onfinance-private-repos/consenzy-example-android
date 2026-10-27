package com.example.consenzy.session

import `in`.onfinance.consent.core.ActingAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The app's backend: the only place that may hold a Consenzy org API key (`cdm_…`).
 *
 * The app needs exactly two things from it, mirroring the example session server
 * (https://github.com/never2average/consenzy-example-session-server):
 *
 * - `POST /session {external_ref, acting_as?}` → a short-lived principal session token (`cps_…`) plus the base URL
 *   the SDK must talk to. In a real app your backend derives the customer from its own login.
 * - `POST /consent-check {external_ref, purpose_code}` → whether the ledger allows processing for a purpose, with
 *   evidence (`consent_id`, `ledger_hash`). Consent checks happen on your backend, never in the app.
 *
 * Two implementations: [SessionServerBackend] (HTTP to the session server) and
 * [com.example.consenzy.demo.DemoBackend] (in-app demo, no network at all).
 */
interface SessionBackend {
    val mode: AppMode

    /** OkHttp client the SDK should derive from (the demo uses it to plug in its fake transport). */
    val sdkHttpClient: OkHttpClient?

    suspend fun demoCustomers(): List<DemoCustomer>

    suspend fun mintSession(externalRef: String, actingAs: ActingAs = ActingAs.DATA_PRINCIPAL): MintedSession

    suspend fun consentCheck(externalRef: String, purposeCode: String): ConsentCheckResult
}

enum class AppMode(val label: String, val description: String) {
    IN_APP_DEMO(
        "In-app demo",
        "Zero setup. A fake HTTP transport inside the app answers /v1/me with synthetic data, so the real SDK code runs.",
    ),
    SESSION_SERVER(
        "Session server",
        "The app asks the example session server for cps_ tokens and consent checks. The server runs in demo or live mode.",
    ),
}

/** `POST /session` response. `token` is a principal session token (`cps_…`), never an org key. */
@Serializable
data class MintedSession(
    val token: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("principal_id") val principalId: String,
    @SerialName("acting_as") val actingAs: String = "data_principal",
    val capabilities: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String,
    val mode: String = "demo",
)

/** `POST /consent-check` response (Consenzy's ConsentCheckOut). */
@Serializable
data class ConsentCheckResult(
    val allowed: Boolean,
    val basis: String? = null,
    @SerialName("consent_id") val consentId: String? = null,
    @SerialName("ledger_event_id") val ledgerEventId: Long? = null,
    @SerialName("ledger_hash") val ledgerHash: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** `GET /demo-customers`: synthetic customers offered on the sign-in screen. */
@Serializable
data class DemoCustomer(
    @SerialName("external_ref") val externalRef: String,
    val label: String,
    @SerialName("acts_through_guardian") val actsThroughGuardian: Boolean = false,
)

/** The backend refused or failed. An IOException, so the SDK UI treats a failed token fetch as a network error. */
class BackendException(val status: Int, message: String) : IOException(message)

internal val BackendJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
