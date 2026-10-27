// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Tuning for [ConsentSession]. The defaults suit a mobile app talking to a bank-hosted API. */
public data class ConsentSessionOptions(
    /**
     * Retries after the first attempt, for GETs and Idempotency-Key POSTs only, on network
     * errors and HTTP 408/429/5xx. Non-idempotent calls (withdraw, logout) are never retried.
     */
    val maxRetries: Int = 3,
    val initialBackoffMillis: Long = 300,
    val maxBackoffMillis: Long = 5_000,
    val connectTimeoutMillis: Long = 10_000,
    val readTimeoutMillis: Long = 20_000,
    /**
     * Allow `http://` base URLs on non-loopback hosts. Off by default: session tokens and
     * personal data must only travel over TLS. Loopback hosts are always allowed for local dev.
     */
    val allowCleartext: Boolean = false,
    /** Sent as User-Agent, which the server records as evidence of how consent was given. */
    val userAgent: String = "consent-data-master-android/${ConsentSession.SDK_VERSION}",
    /**
     * Optional client to derive from (shares its connection pool and interceptors). The SDK
     * always applies its own timeouts, pinner and redirect policy on a derived copy.
     */
    val okHttpClient: OkHttpClient? = null,
) {
    init {
        require(maxRetries in 0..10) { "maxRetries must be between 0 and 10" }
        require(initialBackoffMillis >= 0 && maxBackoffMillis >= initialBackoffMillis) { "invalid backoff bounds" }
    }
}

/**
 * Client for the principal (`/v1/me`) API, authenticated with a short-lived principal session
 * token (`cps_…`) that the bank's own backend mints with `POST /v1/principal-sessions` after it
 * authenticates the customer.
 *
 * [sessionProvider] is called lazily for the first request and again, once, when the server
 * answers 401 (expired or revoked token); it must then fetch a *fresh* token from your backend.
 * Organisation API keys (`cdm_…`) are rejected with [InvalidSessionTokenException] before any
 * network call: they must never be shipped to a device.
 *
 * All functions are main-safe (non-blocking) and cancellable.
 *
 * @param baseUrl e.g. `https://consent.bank.example` (a path prefix is allowed).
 * @param certificatePinner optional TLS pins, e.g.
 *   `CertificatePinner.Builder().add("consent.bank.example", "sha256/AAAA…").build()`.
 */
public class ConsentSession(
    baseUrl: String,
    private val sessionProvider: suspend () -> String,
    certificatePinner: CertificatePinner? = null,
    private val options: ConsentSessionOptions = ConsentSessionOptions(),
) {
    public companion object {
        public const val SDK_VERSION: String = "0.1.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,128}$")
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        internal val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** A fresh random Idempotency-Key (UUID v4). */
        public fun newIdempotencyKey(): String = UUID.randomUUID().toString()
    }

    private val base: HttpUrl = baseUrl.trim().toHttpUrl().also { url ->
        require(url.isHttps || options.allowCleartext || url.host in LOOPBACK || url.host.startsWith("127.")) {
            "baseUrl must use https (set ConsentSessionOptions.allowCleartext only for development)"
        }
        require(url.username.isEmpty() && url.password.isEmpty()) { "baseUrl must not contain credentials" }
    }.newBuilder().query(null).fragment(null).build()

    private val client: OkHttpClient = (options.okHttpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(options.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(options.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(options.readTimeoutMillis, TimeUnit.MILLISECONDS)
        // Never follow redirects with a bearer token attached.
        .followRedirects(false)
        .followSslRedirects(false)
        .apply { if (certificatePinner != null) certificatePinner(certificatePinner) }
        .build()

    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    // ------------------------------------------------------------------ public API

    /** `GET /v1/me`. */
    public suspend fun me(): Principal =
        call(Spec("GET", "v1/me", retryable = true), Principal.serializer())

    /**
     * `GET /v1/me/notice`: latest notice in [language], or the principal's preferred language
     * when null; the server falls back to English when no translation is published.
     */
    public suspend fun currentNotice(language: Language? = null): Notice {
        require(language != Language.UNKNOWN) { "Language.UNKNOWN cannot be requested" }
        return call(
            Spec("GET", "v1/me/notice", query = listOfNotNull(language?.let { "language" to it.wire }), retryable = true),
            Notice.serializer(),
        )
    }

    /** `GET /v1/me/consents`, newest first. */
    public suspend fun listConsents(activeOnly: Boolean = false, limit: Int = 50, offset: Int = 0): List<Consent> {
        checkPage(limit, offset)
        val query = listOf("active_only" to activeOnly.toString(), "limit" to limit.toString(), "offset" to offset.toString())
        return call(Spec("GET", "v1/me/consents", query = query, retryable = true), ListSerializer(Consent.serializer()))
    }

    /**
     * `POST /v1/me/consents` with `channel = "mobile_app"`: records consent for exactly the
     * purposes the principal switched on. Safe to retry: the same [idempotencyKey] is sent on
     * every attempt, so a retried submission never creates a duplicate consent artefact.
     * Persist and pass your own key if the submission must survive process death.
     */
    public suspend fun giveConsent(
        noticeId: String,
        purposeIds: List<String>,
        idempotencyKey: String = newIdempotencyKey(),
    ): List<Consent> {
        require(purposeIds.isNotEmpty()) { "select at least one purpose" }
        require(purposeIds.size <= 20) { "at most 20 purposes per request" }
        require(purposeIds.toSet().size == purposeIds.size) { "duplicate purpose ids" }
        checkIdempotencyKey(idempotencyKey)
        val body = json.encodeToString(GrantBody.serializer(), GrantBody(noticeId, purposeIds))
        return call(
            Spec("POST", "v1/me/consents", body = body, idempotencyKey = idempotencyKey, retryable = true),
            ListSerializer(Consent.serializer()),
        )
    }

    /** `POST /v1/me/consents/{id}/withdraw`. Not retried automatically. */
    public suspend fun withdrawConsent(consentId: String, reason: String? = null): Consent {
        require(consentId.isNotBlank()) { "consentId must not be blank" }
        val cleaned = reason?.trim()?.takeIf { it.isNotEmpty() }
        require(cleaned == null || cleaned.length <= 1000) { "reason must be at most 1000 characters" }
        val body = json.encodeToString(WithdrawBody.serializer(), WithdrawBody(cleaned))
        return call(
            Spec("POST", "v1/me/consents/{}/withdraw", pathParam = consentId, body = body, retryable = false),
            Consent.serializer(),
        )
    }

    /** `GET /v1/me/data-requests`, newest first. */
    public suspend fun listDataRequests(limit: Int = 50, offset: Int = 0): List<DataRequest> {
        checkPage(limit, offset)
        val query = listOf("limit" to limit.toString(), "offset" to offset.toString())
        return call(Spec("GET", "v1/me/data-requests", query = query, retryable = true), ListSerializer(DataRequest.serializer()))
    }

    /**
     * `POST /v1/me/data-requests`. [details] must suit [type] (e.g. [DataRequestDetails.Correction]
     * for CORRECTION / COMPLETION / UPDATING). Idempotent like [giveConsent].
     */
    public suspend fun fileDataRequest(
        type: RequestType,
        details: DataRequestDetails,
        idempotencyKey: String = newIdempotencyKey(),
    ): DataRequest {
        require(type in details.compatibleTypes) {
            "${details.javaClass.simpleName} details cannot be used for request type ${type.wire}"
        }
        checkIdempotencyKey(idempotencyKey)
        val body = json.encodeToString(DataRequestBody.serializer(), DataRequestBody(type, details.toJson()))
        return call(
            Spec("POST", "v1/me/data-requests", body = body, idempotencyKey = idempotencyKey, retryable = true),
            DataRequest.serializer(),
        )
    }

    /** Files a request of [DataRequestDetails.defaultType]. */
    public suspend fun fileDataRequest(details: DataRequestDetails, idempotencyKey: String = newIdempotencyKey()): DataRequest =
        fileDataRequest(details.defaultType, details, idempotencyKey)

    /**
     * `GET /v1/me/breach-notices`: personal data breach intimations sent to this principal
     * (DPDP Rules r.7(1)), newest first. Every session can read them, like the profile.
     */
    public suspend fun listBreachNotices(limit: Int = 50, offset: Int = 0): List<BreachNotice> {
        checkPage(limit, offset)
        val query = listOf("limit" to limit.toString(), "offset" to offset.toString())
        return call(
            Spec("GET", "v1/me/breach-notices", query = query, retryable = true),
            ListSerializer(BreachNotice.serializer()),
        ).sortedNewestFirst()
    }

    /**
     * `POST /v1/me/breach-notices/{breach_id}/acknowledge`: records that the principal has read
     * the notice ("I have read this"). [breachId] must be a UUID. Idempotent on the server (the
     * first acknowledgement time is kept), so it is safe to retry and to call again.
     */
    public suspend fun acknowledgeBreachNotice(breachId: String): BreachNotice {
        require(isUuid(breachId)) { "breachId must be a UUID" }
        return call(
            Spec("POST", "v1/me/breach-notices/{}/acknowledge", pathParam = breachId, body = "", retryable = true),
            BreachNotice.serializer(),
        )
    }

    /**
     * `POST /v1/me/logout`: revokes the current session token server-side and forgets it.
     * A token that has already expired counts as logged out. Does nothing if no token was fetched.
     */
    public suspend fun logout() {
        val token = cachedToken ?: return
        try {
            send(Spec("POST", "v1/me/logout", body = "", retryable = false, refreshOn401 = false), token)
        } catch (e: ConsentApiException) {
            if (e.status != 401) throw e
        } finally {
            tokenMutex.withLock { if (cachedToken == token) cachedToken = null }
        }
    }

    /** Drop the cached token so the next call asks [sessionProvider] again (e.g. after a user switch). */
    public fun invalidateToken() {
        cachedToken = null
    }

    // ------------------------------------------------------------------ token handling

    private suspend fun currentToken(): String =
        cachedToken ?: tokenMutex.withLock { cachedToken ?: fetchToken() }

    /** Called after a 401 with [rejected]. Concurrent callers share a single refresh. */
    private suspend fun refreshToken(rejected: String): String = tokenMutex.withLock {
        val current = cachedToken
        if (current != null && current != rejected) current else {
            cachedToken = null
            fetchToken()
        }
    }

    private suspend fun fetchToken(): String {
        val token = sessionProvider().trim()
        when {
            token.startsWith("cdm_") -> throw InvalidSessionTokenException(
                "an organisation API key (cdm_…) was supplied; it must never be shipped to a device. " +
                    "Mint a principal session (cps_…) on your backend with POST /v1/principal-sessions.",
            )
            !token.startsWith("cps_") -> throw InvalidSessionTokenException("session token must be a principal session token (cps_…)")
            token.length > 128 || token.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e } ->
                throw InvalidSessionTokenException("malformed principal session token")
        }
        cachedToken = token
        return token
    }

    // ------------------------------------------------------------------ transport

    private class Spec(
        val method: String,
        val path: String,
        val query: List<Pair<String, String>> = emptyList(),
        val pathParam: String? = null,
        val body: String? = null,
        val idempotencyKey: String? = null,
        val retryable: Boolean,
        val refreshOn401: Boolean = true,
    )

    private suspend fun <T> call(spec: Spec, serializer: KSerializer<T>): T {
        val text = send(spec, currentToken())
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            throw ConsentException("unexpected response from ${spec.method} /${spec.path}: ${e.message}", e)
        }
    }

    /** Executes with 401-refresh and bounded retries. Returns the body of a 2xx response. */
    private suspend fun send(spec: Spec, initialToken: String): String {
        var token = initialToken
        var retries = 0
        var refreshed = false
        while (true) {
            val status: Int
            val text: String
            val retryAfter: String?
            try {
                val response = client.newCall(buildRequest(spec, token)).await()
                status = response.status
                text = response.body
                retryAfter = response.retryAfter
            } catch (e: IOException) {
                if (spec.retryable && retries < options.maxRetries) {
                    delay(backoffMillis(retries++, null))
                    continue
                }
                throw ConsentNetworkException(e)
            }
            if (status in 200..299) return text
            if (status == 401 && spec.refreshOn401 && !refreshed) {
                refreshed = true
                token = refreshToken(token)
                continue
            }
            if (spec.retryable && status in RETRYABLE_STATUS && retries < options.maxRetries) {
                delay(backoffMillis(retries++, retryAfter))
                continue
            }
            throw parseError(status, text)
        }
    }

    private fun buildRequest(spec: Spec, token: String): Request {
        val url = base.newBuilder().apply {
            spec.path.split('/').forEach { segment ->
                if (segment == "{}") addPathSegment(requireNotNull(spec.pathParam)) else addPathSegment(segment)
            }
            spec.query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", options.userAgent)
            .apply { spec.idempotencyKey?.let { header("Idempotency-Key", it) } }
            .method(spec.method, spec.body?.toRequestBody(JSON_MEDIA))
            .build()
    }

    /** Exponential backoff with jitter; honours a numeric Retry-After, capped at maxBackoffMillis. */
    private fun backoffMillis(retry: Int, retryAfter: String?): Long {
        retryAfter?.trim()?.toLongOrNull()?.let { seconds ->
            return (seconds * 1000).coerceIn(0, options.maxBackoffMillis)
        }
        val ceiling = (options.initialBackoffMillis shl retry.coerceAtMost(20)).coerceAtMost(options.maxBackoffMillis)
        return if (ceiling <= 1) ceiling else ceiling / 2 + Random.nextLong(ceiling / 2 + 1)
    }

    private fun parseError(status: Int, text: String): ConsentApiException {
        val detail: JsonElement? = try {
            (json.parseToJsonElement(text) as? JsonObject)?.get("detail")
        } catch (_: IllegalArgumentException) {
            null
        }
        return when (detail) {
            is JsonPrimitive -> ConsentApiException(status, detail.contentOrNull ?: "HTTP $status")
            is JsonArray -> {
                val errors = detail.mapNotNull { entry ->
                    val obj = entry as? JsonObject ?: return@mapNotNull null
                    FieldError(
                        location = (obj["loc"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                        message = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: obj.toString(),
                        type = (obj["type"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
                val message = errors.joinToString("; ") { e ->
                    val loc = e.location.filter { it != "body" }.joinToString(".")
                    if (loc.isEmpty()) e.message else "$loc: ${e.message}"
                }
                ConsentApiException(status, message.ifEmpty { detail.toString() }, errors)
            }
            null -> ConsentApiException(status, text.take(500).ifBlank { "HTTP $status" })
            else -> ConsentApiException(status, detail.toString())
        }
    }

    private fun checkPage(limit: Int, offset: Int) {
        require(limit in 1..200) { "limit must be between 1 and 200" }
        require(offset in 0..10_000) { "offset must be between 0 and 10000" }
    }

    private fun checkIdempotencyKey(key: String) {
        require(IDEMPOTENCY_KEY.matches(key)) { "idempotencyKey must match ${IDEMPOTENCY_KEY.pattern}" }
    }
}

/** A fully read HTTP response. */
internal class RawResponse(val status: Int, val body: String, val retryAfter: String?)

/**
 * Suspends until the call completes; cancelling the coroutine cancels the HTTP call. The body
 * is read on OkHttp's dispatcher thread, never on the caller's (possibly main) thread.
 */
internal suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val raw = try {
                response.use { RawResponse(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
            } catch (e: IOException) {
                onFailure(call, e)
                return
            }
            cont.resume(raw)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
