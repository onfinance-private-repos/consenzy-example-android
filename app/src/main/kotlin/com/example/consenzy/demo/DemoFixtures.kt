// DEMO ONLY. Synthetic data for the in-app demo mode; never used when the app talks to a session server.
package com.example.consenzy.demo

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `assets/demo-fixtures.json`: an unchanged copy of `src/fixtures.json` from the example session server, so the
 * in-app demo and the server's demo mode show the same fictional "Demo Bank" notice, customers and requests.
 */
@Serializable
data class DemoFixtures(
    val principals: List<DemoPrincipal>,
    val notice: DemoNotice,
    val purposes: List<DemoPurpose>,
    @SerialName("seed_data_requests") val seedDataRequests: List<DemoSeedRequest> = emptyList(),
    @SerialName("request_sla_days") val requestSlaDays: Int,
    @SerialName("status_progression_seconds") val statusProgressionSeconds: DemoStatusProgression,
) {
    fun principalByRef(externalRef: String): DemoPrincipal? = principals.firstOrNull { it.externalRef == externalRef }

    companion object {
        const val ASSET = "demo-fixtures.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DemoFixtures = json.decodeFromString(serializer(), text)

        fun fromAssets(context: Context): DemoFixtures =
            parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

@Serializable
data class DemoPrincipal(
    @SerialName("external_ref") val externalRef: String,
    @SerialName("principal_id") val principalId: String,
    val label: String,
    @SerialName("preferred_language") val preferredLanguage: String,
    @SerialName("acts_through_guardian") val actsThroughGuardian: Boolean,
    @SerialName("guardian_verified") val guardianVerified: Boolean,
)

@Serializable
data class DemoNotice(val id: String, val version: Int, val translations: Map<String, DemoNoticeTranslation>)

@Serializable
data class DemoNoticeTranslation(val title: String, val body: String, val purposes: Map<String, DemoPurposeText>)

@Serializable
data class DemoPurposeText(val name: String, val description: String)

@Serializable
data class DemoPurpose(
    val id: String,
    val code: String,
    @SerialName("data_categories") val dataCategories: List<String>,
    @SerialName("retention_days") val retentionDays: Int,
)

@Serializable
data class DemoSeedRequest(
    @SerialName("external_ref") val externalRef: String,
    val id: String,
    @SerialName("request_type") val requestType: String,
    val status: String? = null,
    val channel: String,
    @SerialName("channel_reference") val channelReference: String? = null,
    val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("received_days_ago") val receivedDaysAgo: Int,
)

@Serializable
data class DemoStatusProgression(
    @SerialName("in_progress") val inProgress: Int,
    val completed: Int,
)
