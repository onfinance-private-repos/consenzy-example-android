package com.example.consenzy.session

import `in`.onfinance.consent.core.ActingAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * "Session server" mode: talks to the example session server
 * (https://github.com/never2average/consenzy-example-session-server). The server holds the org API key and runs in
 * demo mode (its own mock of /v1/me) or live mode (a real Consenzy organisation, e.g. https://consenzy.onfinance.ai).
 *
 * This client sends no credentials of its own. A production app would send its own user's login token here, and the
 * backend would derive the customer from it instead of trusting `external_ref` from the client.
 */
class SessionServerBackend(
    serverUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : SessionBackend {
    private val base = serverUrl.trim().trimEnd('/').toHttpUrl()

    override val mode: AppMode = AppMode.SESSION_SERVER

    // Share the connection pool with the SDK. The SDK still applies its own timeouts and redirect policy.
    override val sdkHttpClient: OkHttpClient = client

    override suspend fun demoCustomers(): List<DemoCustomer> =
        BackendJson.decodeFromString(ListSerializer(DemoCustomer.serializer()), get("demo-customers"))

    /** `POST /session`: the backend mints `POST /v1/principal-sessions` with its org key and returns only the `cps_` token. */
    override suspend fun mintSession(externalRef: String, actingAs: ActingAs): MintedSession {
        val body = buildJsonObject {
            put("external_ref", externalRef)
            put("acting_as", actingAs.wire)
        }
        return BackendJson.decodeFromString(MintedSession.serializer(), post("session", body.toString()))
    }

    /** `POST /consent-check`: the backend calls `POST /v1/consents/check` before it processes data for a purpose. */
    override suspend fun consentCheck(externalRef: String, purposeCode: String): ConsentCheckResult {
        val body = buildJsonObject {
            put("external_ref", externalRef)
            put("purpose_code", purposeCode)
        }
        return BackendJson.decodeFromString(ConsentCheckResult.serializer(), post("consent-check", body.toString()))
    }

    private suspend fun get(path: String): String = execute(Request.Builder().url(base.newBuilder().addPathSegments(path).build()).get().build())

    private suspend fun post(path: String, json: String): String = execute(
        Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .post(json.toRequestBody(JSON))
            .build(),
    )

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { BackendJson.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                throw BackendException(response.code, detail ?: "session server answered HTTP ${response.code}")
            }
            text
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
