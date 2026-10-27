// DEMO ONLY. An in-memory stand-in for the Consenzy server, used by the in-app demo mode.
package com.example.consenzy.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** A status code and JSON body, as the real API would answer. */
class DemoResult(val status: Int, val body: JsonElement?)

/**
 * Kotlin port of the example session server's `src/mock.js`: the principal API (`/v1/me/...`) plus the two org-key
 * calls a backend makes (minting a session, checking consent). It follows the rules the example journeys exercise:
 *
 * - notices in `en` and `hi`, falling back to English for any other language;
 * - consent grants that supersede an earlier active consent for the same purpose, with Idempotency-Key replay;
 * - withdrawal (409 when the consent is not active);
 * - rights requests with the server's detail validation, and a status that moves received → in_progress → completed
 *   with time (fixtures: 20 s and 60 s), so the status-tracking screen has something to show;
 * - the s.9 guardian rule: a child's data_principal session gets HTTP 422 on a grant; a guardian session works;
 * - session capabilities (403 without them) and logout (the token is revoked).
 *
 * Everything is synthetic and in memory. Thread-safe: OkHttp calls the demo transport from its dispatcher threads.
 */
class DemoStore(
    val fixtures: DemoFixtures,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        val ALL_CAPABILITIES = listOf("consents:write", "requests:read", "requests:write")
        val REQUEST_TYPES = listOf("access", "correction", "completion", "updating", "erasure", "grievance", "nomination")
        const val GUARDIAN_REQUIRED =
            "this principal acts through a guardian: the session must be minted with acting_as=guardian (s.9)"
        private const val DAY_MS = 86_400_000L
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val FIELD_RE = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun iso(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun detail(status: Int, message: String) = DemoResult(status, buildJsonObject { put("detail", message) })

        private fun fieldError(loc: List<String>, message: String, type: String) = DemoResult(
            422,
            buildJsonObject {
                putJsonArray("detail") {
                    addJsonObject {
                        putJsonArray("loc") { loc.forEach { add(it) } }
                        put("msg", message)
                        put("type", type)
                    }
                }
            },
        )
    }

    class Session(
        val token: String,
        val sessionId: String,
        val principal: DemoPrincipal,
        val expiresAtMs: Long,
        val actingAs: String,
        val capabilities: List<String>,
    ) {
        @Volatile var revoked: Boolean = false
    }

    private class ConsentRow(
        val id: String,
        val principalId: String,
        val purposeId: String,
        val purposeCode: String,
        val noticeId: String,
        var status: String,
        val channel: String,
        val givenAt: String,
        val expiresAt: String?,
        var withdrawnAt: String? = null,
        var withdrawalReason: String? = null,
        val createdAt: String,
        var updatedAt: String,
        val ref: String,
        val ledgerEventId: Long,
        val ledgerHash: String,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("principal_id", principalId)
            put("purpose_id", purposeId)
            put("purpose_code", purposeCode)
            put("notice_id", noticeId)
            put("status", status)
            put("channel", channel)
            put("consent_manager_ref", JsonNull)
            put("application_id", JsonNull)
            put("given_at", givenAt)
            put("expires_at", expiresAt)
            put("withdrawn_at", withdrawnAt)
            put("withdrawal_reason", withdrawalReason)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private class RequestRow(
        val id: String,
        val principalId: String,
        val requestType: String,
        var status: String,
        val channel: String,
        val channelReference: String?,
        val details: JsonObject,
        var response: JsonObject? = null,
        val dueAt: String,
        val receivedAt: String,
        var closedAt: String? = null,
        var updatedAt: String,
        val ref: String,
        val receivedMs: Long,
        val fixedStatus: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("principal_id", principalId)
            put("request_type", requestType)
            put("status", status)
            put("channel", channel)
            put("channel_reference", channelReference)
            put("details", details)
            put("response", response ?: JsonNull)
            put("assigned_to", JsonNull)
            put("due_at", dueAt)
            put("received_at", receivedAt)
            put("closed_at", closedAt)
            put("created_at", receivedAt)
            put("updated_at", updatedAt)
            put("api_key_id", JsonNull)
        }
    }

    private val sessions = HashMap<String, Session>()
    private val consents = mutableListOf<ConsentRow>()
    private val requests = mutableListOf<RequestRow>()
    private val idempotency = HashMap<String, DemoResult>()

    init {
        for (seed in fixtures.seedDataRequests) {
            val p = fixtures.principalByRef(seed.externalRef) ?: continue
            val receivedMs = now() - seed.receivedDaysAgo * DAY_MS
            requests += requestRow(p, seed.requestType, seed.details, seed.channel, seed.channelReference, receivedMs, seed.id, seed.status)
        }
    }

    private fun requestRow(
        p: DemoPrincipal,
        type: String,
        details: JsonObject,
        channel: String,
        channelReference: String?,
        receivedMs: Long,
        id: String = UUID.randomUUID().toString(),
        fixedStatus: String? = null,
    ) = RequestRow(
        id = id,
        principalId = p.principalId,
        requestType = type,
        status = fixedStatus ?: "received",
        channel = channel,
        channelReference = channelReference,
        details = details,
        dueAt = iso(receivedMs + fixtures.requestSlaDays * DAY_MS),
        receivedAt = iso(receivedMs),
        updatedAt = iso(receivedMs),
        ref = p.externalRef,
        receivedMs = receivedMs,
        fixedStatus = fixedStatus,
    )

    /** Demo requests move on by themselves: received → in_progress → completed. */
    private fun progressed(row: RequestRow): RequestRow {
        if (row.fixedStatus != null) return row
        val ageSeconds = (now() - row.receivedMs) / 1000.0
        val ip = fixtures.statusProgressionSeconds.inProgress
        val done = fixtures.statusProgressionSeconds.completed
        if (ageSeconds >= done) {
            row.status = "completed"
            if (row.closedAt == null) row.closedAt = iso(row.receivedMs + done * 1000L)
            if (row.response == null) row.response = buildJsonObject { put("summary", "Demo: your ${row.requestType} request was completed.") }
            row.updatedAt = row.closedAt!!
        } else if (ageSeconds >= ip) {
            row.status = "in_progress"
            row.updatedAt = iso(row.receivedMs + ip * 1000L)
        }
        return row
    }

    // ------------------------------------------------------------------ org-key calls (your backend's job)

    /** `POST /v1/principal-sessions`: what your backend does with its org key. */
    @Synchronized
    fun mint(externalRef: String, actingAs: String = "data_principal", capabilities: List<String>? = null, ttlSeconds: Int = 900): DemoResult {
        val p = fixtures.principalByRef(externalRef) ?: return detail(404, "principal not found")
        if (actingAs == "guardian" && !p.actsThroughGuardian) return detail(422, "principal does not act through a guardian")
        val caps = capabilities ?: ALL_CAPABILITIES
        if (caps.any { it !in ALL_CAPABILITIES }) return detail(403, "cannot grant capabilities the minting key lacks")
        val token = "cps_demo_" + UUID.randomUUID().toString().replace("-", "")
        val session = Session(token, UUID.randomUUID().toString(), p, now() + ttlSeconds * 1000L, actingAs, caps.sorted())
        sessions[token] = session
        return DemoResult(
            201,
            buildJsonObject {
                put("token", token)
                put("session_id", session.sessionId)
                put("principal_id", p.principalId)
                put("expires_at", iso(session.expiresAtMs))
                put("acting_as", actingAs)
                put("capabilities", buildJsonArray { session.capabilities.forEach { add(it) } })
                put("application_id", JsonNull)
            },
        )
    }

    /** `POST /v1/consents/check`: what your backend asks before processing data for a purpose. */
    @Synchronized
    fun check(externalRef: String, purposeCode: String): DemoResult {
        val p = fixtures.principalByRef(externalRef) ?: return detail(404, "principal not found")
        val c = consents.firstOrNull { it.ref == p.externalRef && it.purposeCode == purposeCode && it.status == "active" }
            ?: return DemoResult(
                200,
                buildJsonObject {
                    put("allowed", false)
                    put("basis", JsonNull)
                    put("consent_id", JsonNull)
                    put("expires_at", JsonNull)
                    put("ledger_event_id", JsonNull)
                    put("ledger_hash", JsonNull)
                },
            )
        return DemoResult(
            200,
            buildJsonObject {
                put("allowed", true)
                put("basis", "consent")
                put("consent_id", c.id)
                put("expires_at", c.expiresAt)
                put("ledger_event_id", c.ledgerEventId)
                put("ledger_hash", c.ledgerHash)
            },
        )
    }

    // ------------------------------------------------------------------ principal API (/v1/me)

    /** The live session for [token], or null when unknown, revoked or expired (→ HTTP 401). */
    @Synchronized
    fun session(token: String): Session? {
        val s = sessions[token] ?: return null
        return if (s.revoked || s.expiresAtMs <= now()) null else s
    }

    @Synchronized
    fun logout(token: String) {
        sessions[token]?.revoked = true
    }

    fun me(s: Session): JsonObject = buildJsonObject {
        val p = s.principal
        put("principal_id", p.principalId)
        put("external_ref", p.externalRef)
        put("preferred_language", p.preferredLanguage)
        put("acts_through_guardian", p.actsThroughGuardian)
        put("guardian_verified", p.guardianVerified)
        put("acting_as", s.actingAs)
        put("capabilities", buildJsonArray { s.capabilities.forEach { add(it) } })
    }

    /** The notice in [language], or English when that translation is not published (the server's fallback). */
    fun notice(language: String): JsonObject {
        val translations = fixtures.notice.translations
        val lang = if (translations.containsKey(language)) language else "en"
        val tr = translations.getValue(lang)
        return buildJsonObject {
            put("id", fixtures.notice.id)
            put("version", fixtures.notice.version)
            put("language", lang)
            put("title", tr.title)
            put("body", tr.body)
            putJsonArray("purposes") {
                fixtures.purposes.forEach { pp ->
                    addJsonObject {
                        put("id", pp.id)
                        put("code", pp.code)
                        put("name", tr.purposes.getValue(pp.code).name)
                        put("description", tr.purposes.getValue(pp.code).description)
                        putJsonArray("data_categories") { pp.dataCategories.forEach { add(it) } }
                        put("retention_days", pp.retentionDays)
                    }
                }
            }
        }
    }

    @Synchronized
    fun listConsents(s: Session, activeOnly: Boolean): List<JsonObject> =
        consents
            .filter { it.ref == s.principal.externalRef && (!activeOnly || it.status == "active") }
            .sortedByDescending { it.createdAt }
            .map { it.toJson() }

    @Synchronized
    fun grant(s: Session, body: JsonObject?): DemoResult {
        if ("consents:write" !in s.capabilities) return detail(403, "session lacks the consents:write capability")
        if (s.principal.actsThroughGuardian && s.actingAs != "guardian") return detail(422, GUARDIAN_REQUIRED)
        if (body.string("notice_id") != fixtures.notice.id) return detail(404, "notice not found")
        val ids = (body?.get("purpose_ids") as? JsonArray)?.map { (it as? JsonPrimitive)?.content }
        if (ids.isNullOrEmpty()) return fieldError(listOf("body", "purpose_ids"), "List should have at least 1 item", "too_short")
        val purposes = ids.map { id -> fixtures.purposes.firstOrNull { it.id == id } }
        if (purposes.any { it == null }) return detail(422, "purpose not in notice")
        val t = now()
        val out = purposes.filterNotNull().map { pp ->
            consents.filter { it.ref == s.principal.externalRef && it.purposeCode == pp.code && it.status == "active" }.forEach { old ->
                old.status = "superseded"
                old.updatedAt = iso(t)
            }
            val id = UUID.randomUUID().toString()
            val row = ConsentRow(
                id = id,
                principalId = s.principal.principalId,
                purposeId = pp.id,
                purposeCode = pp.code,
                noticeId = fixtures.notice.id,
                status = "active",
                channel = body.string("channel") ?: "mobile_app",
                givenAt = iso(t),
                expiresAt = body.string("expires_at"),
                createdAt = iso(t),
                updatedAt = iso(t),
                ref = s.principal.externalRef,
                ledgerEventId = consents.size + 1L,
                ledgerHash = sha256Hex("$id:$t"),
            )
            consents += row
            row.toJson()
        }
        return DemoResult(201, JsonArray(out))
    }

    @Synchronized
    fun withdraw(s: Session, consentId: String, reason: String?): DemoResult {
        if ("consents:write" !in s.capabilities) return detail(403, "session lacks the consents:write capability")
        val c = consents.firstOrNull { it.id == consentId && it.ref == s.principal.externalRef }
            ?: return detail(404, "consent not found")
        if (c.status != "active") return detail(409, "consent is not active")
        val t = iso(now())
        c.status = "withdrawn"
        c.withdrawnAt = t
        c.withdrawalReason = reason
        c.updatedAt = t
        return DemoResult(200, c.toJson())
    }

    @Synchronized
    fun listRequests(s: Session): DemoResult {
        if ("requests:read" !in s.capabilities) return detail(403, "session lacks the requests:read capability")
        val rows = requests
            .filter { it.ref == s.principal.externalRef }
            .map(::progressed)
            .sortedByDescending { it.receivedAt }
            .map { it.toJson() }
        return DemoResult(200, JsonArray(rows))
    }

    @Synchronized
    fun fileRequest(s: Session, body: JsonObject?): DemoResult {
        if ("requests:write" !in s.capabilities) return detail(403, "session lacks the requests:write capability")
        val type = body.string("request_type")
        if (type == null || type !in REQUEST_TYPES) {
            return fieldError(listOf("body", "request_type"), "Input should be ${REQUEST_TYPES.joinToString(", ")}", "literal_error")
        }
        val details = body?.get("details") as? JsonObject ?: JsonObject(emptyMap())
        validateDetails(type, details)?.let { (field, msg) -> return fieldError(listOf("body", "details", field), msg, "value_error") }
        val row = requestRow(s.principal, type, details, body.string("channel") ?: "app", null, now())
        requests += row
        return DemoResult(201, row.toJson())
    }

    /** Replays the first successful answer for the same principal, endpoint and Idempotency-Key. */
    @Synchronized
    fun idempotent(s: Session, endpoint: String, key: String?, run: () -> DemoResult): DemoResult {
        if (key == null) return run()
        val k = "${s.principal.principalId}:$endpoint:$key"
        idempotency[k]?.let { return it }
        val res = run()
        if (res.status < 300) idempotency[k] = res
        return res
    }

    private fun validateDetails(type: String, d: JsonObject): Pair<String, String>? {
        fun short(field: String) = d.string(field).let { it == null || it.length <= 500 }
        return when (type) {
            "access" -> if (short("note")) null else "note" to "String should have at most 500 characters"
            "correction", "completion", "updating" -> {
                val f = d["fields"] as? JsonObject
                when {
                    f == null || f.isEmpty() -> "fields" to "Dictionary should have at least 1 item"
                    else -> f.keys.firstOrNull { !FIELD_RE.matches(it) }?.let { "fields" to "invalid field name: $it" }
                }
            }
            "erasure" -> if (short("reason")) null else "reason" to "String should have at most 500 characters"
            "grievance" -> {
                val description = d.string("description")
                val related = d.string("related_request_id")
                when {
                    description.isNullOrBlank() -> "description" to "Field required"
                    related != null && !UUID_RE.matches(related) -> "related_request_id" to "Input should be a valid UUID"
                    else -> null
                }
            }
            "nomination" -> when {
                d.string("nominee_name").isNullOrEmpty() -> "nominee_name" to "Field required"
                d.string("relationship").isNullOrEmpty() -> "relationship" to "Field required"
                d.string("nominee_email").isNullOrEmpty() && d.string("nominee_phone").isNullOrEmpty() ->
                    "nominee_email" to "nominee_email or nominee_phone is required"
                else -> null
            }
            else -> null
        }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** A string field of a JSON object, or null when missing, null or not a primitive. */
internal fun JsonObject?.string(key: String): String? {
    val v = this?.get(key) as? JsonPrimitive ?: return null
    return if (v is JsonNull) null else v.content
}
