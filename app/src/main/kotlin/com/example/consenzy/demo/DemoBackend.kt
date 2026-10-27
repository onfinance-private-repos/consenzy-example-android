// DEMO ONLY. The in-app demo's stand-in for your backend; a real app calls its own backend (see SessionServerBackend).
package com.example.consenzy.demo

import android.content.Context
import com.example.consenzy.session.AppMode
import com.example.consenzy.session.BackendException
import com.example.consenzy.session.ConsentCheckResult
import com.example.consenzy.session.DemoCustomer
import com.example.consenzy.session.MintedSession
import com.example.consenzy.session.SessionBackend
import `in`.onfinance.consent.core.ActingAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * In-app demo mode: does locally what the example session server does in its demo mode.
 *
 * - "mints" `cps_demo_…` tokens and answers consent checks from the [DemoStore];
 * - gives the SDK an OkHttp client with the [DemoInterceptor], so `ConsentSession` calls never reach the network.
 *
 * No org API key exists anywhere in the demo, just as none may exist in a real app.
 */
class DemoBackend(val store: DemoStore) : SessionBackend {
    override val mode: AppMode = AppMode.IN_APP_DEMO

    override val sdkHttpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor(DemoInterceptor(store)).build()

    override suspend fun demoCustomers(): List<DemoCustomer> =
        store.fixtures.principals.map { DemoCustomer(it.externalRef, it.label, it.actsThroughGuardian) }

    override suspend fun mintSession(externalRef: String, actingAs: ActingAs): MintedSession {
        val out = store.mint(externalRef, actingAs.wire)
        val body = out.body as JsonObject
        if (out.status != 201) throw BackendException(out.status, body["detail"]?.jsonPrimitive?.content ?: "mint failed")
        return MintedSession(
            token = body.getValue("token").jsonPrimitive.content,
            baseUrl = BASE_URL,
            principalId = body.getValue("principal_id").jsonPrimitive.content,
            actingAs = body.getValue("acting_as").jsonPrimitive.content,
            capabilities = body.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content },
            expiresAt = body.getValue("expires_at").jsonPrimitive.content,
            mode = "in-app-demo",
        )
    }

    override suspend fun consentCheck(externalRef: String, purposeCode: String): ConsentCheckResult {
        val out = store.check(externalRef, purposeCode)
        if (out.status != 200) throw BackendException(out.status, (out.body as JsonObject)["detail"]?.jsonPrimitive?.content ?: "check failed")
        return json.decodeFromJsonElement(ConsentCheckResult.serializer(), out.body!!)
    }

    companion object {
        /** Never resolves (`.invalid` is reserved): the [DemoInterceptor] answers before any DNS lookup. */
        const val BASE_URL = "https://demo.consenzy.invalid"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromAssets(context: Context, now: () -> Long = System::currentTimeMillis) =
            DemoBackend(DemoStore(DemoFixtures.fromAssets(context), now))
    }
}
