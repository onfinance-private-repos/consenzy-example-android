package com.example.consenzy.session

import com.example.consenzy.BuildConfig
import `in`.onfinance.consent.core.ActingAs
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.core.ConsentSessionOptions
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicReference

/** A signed-in customer: who it is, how the session was minted, and the SDK client for it. */
class SignedInSession(
    val externalRef: String,
    val actingAs: ActingAs,
    val backend: SessionBackend,
    /** The first `POST /session` response (base URL, capabilities, expiry). */
    val minted: MintedSession,
    /** The Consenzy SDK client. Every screen uses this. */
    val consent: ConsentSession,
) {
    /** `POST /v1/me/logout`: revokes the current `cps_` token on the server and forgets it. */
    suspend fun logout() = consent.logout()
}

/**
 * Session / token provider: how the app gets a [ConsentSession] without ever holding an org API key.
 *
 * 1. Ask the backend for a principal session (`POST /session`). The response carries the `cps_…` token and the
 *    `base_url` the SDK must use (the session server's own mock in its demo mode, your Consenzy deployment such as
 *    https://consenzy.onfinance.ai in live mode, or the in-app demo's fake host).
 * 2. Build the SDK client with a `sessionProvider` that hands out that first token once, then mints a *fresh* one each
 *    time it is called. The SDK calls it again (once) on HTTP 401, so it must never return a cached token.
 */
object ConsenzySessions {
    suspend fun open(
        backend: SessionBackend,
        externalRef: String,
        actingAs: ActingAs = ActingAs.DATA_PRINCIPAL,
    ): SignedInSession {
        val first = backend.mintSession(externalRef, actingAs)
        val unused = AtomicReference<String?>(first.token)
        val consent = ConsentSession(
            baseUrl = first.baseUrl,
            // suspend () -> String. Returns "cps_…"; the SDK rejects anything else (and cdm_ org keys) before any call.
            sessionProvider = { unused.getAndSet(null) ?: backend.mintSession(externalRef, actingAs).token },
            options = ConsentSessionOptions(
                // Plain http:// only for the local dev session server in debug builds (the debug network security
                // config also allows it, for 10.0.2.2 and localhost only). Release builds are HTTPS-only.
                allowCleartext = BuildConfig.DEBUG && !first.baseUrl.toHttpUrl().isHttps,
                okHttpClient = backend.sdkHttpClient,
            ),
        )
        return SignedInSession(externalRef, actingAs, backend, first, consent)
    }
}
