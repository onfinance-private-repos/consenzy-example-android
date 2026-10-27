// DEMO ONLY. The fake transport behind the in-app demo mode.
package com.example.consenzy.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * An OkHttp application [Interceptor] that answers the Consenzy principal API (`/v1/me/*`) from a [DemoStore]
 * instead of the network.
 *
 * It is passed to the SDK as `ConsentSessionOptions(okHttpClient = …)`, so the **real** SDK code path runs: token
 * checks (`cps_` only), JSON encoding, Idempotency-Key headers, retries, 401 refresh and error parsing. Only the
 * bytes on the wire are fake. Requests never leave the device (the demo base URL is on the reserved `.invalid` TLD).
 */
class DemoInterceptor(private val store: DemoStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val result = handle(request)
        val body = result.body?.toString().orEmpty()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.status)
            .message(reason(result.status))
            .header("Content-Type", "application/json")
            .body(body.toResponseBody(JSON))
            .build()
    }

    /** Routes one request like the mock router in the session server's `src/mock.js`. */
    fun handle(request: Request): DemoResult {
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "v1" || segments[1] != "me") return DemoStore.detail(404, "not found")

        val unauthorized = DemoStore.detail(401, "invalid or expired session token")
        val token = BEARER.matchEntire(request.header("Authorization").orEmpty())?.groupValues?.get(1) ?: return unauthorized
        val session = store.session(token) ?: return unauthorized

        val path = segments.drop(2)
        val method = request.method
        return when {
            method == "GET" && path.isEmpty() -> DemoResult(200, store.me(session))
            method == "GET" && path == listOf("notice") ->
                DemoResult(200, store.notice(request.url.queryParameter("language") ?: session.principal.preferredLanguage))
            method == "GET" && path == listOf("consents") -> {
                val activeOnly = request.url.queryParameter("active_only") == "true"
                DemoResult(200, JsonArray(page(request, store.listConsents(session, activeOnly))))
            }
            method == "POST" && path == listOf("consents") ->
                store.idempotent(session, "consents", request.header("Idempotency-Key")) { store.grant(session, bodyOf(request)) }
            method == "POST" && path.size == 3 && path[0] == "consents" && path[2] == "withdraw" ->
                store.withdraw(session, path[1], bodyOf(request).string("reason"))
            method == "GET" && path == listOf("data-requests") -> {
                val out = store.listRequests(session)
                if (out.status != 200) out else DemoResult(200, JsonArray(page(request, (out.body as JsonArray).toList())))
            }
            method == "POST" && path == listOf("data-requests") ->
                store.idempotent(session, "data-requests", request.header("Idempotency-Key")) { store.fileRequest(session, bodyOf(request)) }
            method == "GET" && path == listOf("breach-notices") -> DemoResult(200, JsonArray(emptyList()))
            method == "POST" && path.size == 3 && path[0] == "breach-notices" && path[2] == "acknowledge" ->
                DemoStore.detail(404, "breach notice not found")
            method == "POST" && path == listOf("logout") -> {
                store.logout(token)
                DemoResult(204, null)
            }
            else -> DemoStore.detail(404, "not found")
        }
    }

    private fun <T> page(request: Request, rows: List<T>): List<T> {
        val limit = (request.url.queryParameter("limit")?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (request.url.queryParameter("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        return rows.drop(offset).take(limit)
    }

    private fun bodyOf(request: Request): JsonObject? {
        val raw = request.body ?: return null
        val text = Buffer().also { raw.writeTo(it) }.readUtf8()
        if (text.isBlank()) return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        else -> "Demo"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val BEARER = Regex("^Bearer (cps_\\S+)$")
    }
}
