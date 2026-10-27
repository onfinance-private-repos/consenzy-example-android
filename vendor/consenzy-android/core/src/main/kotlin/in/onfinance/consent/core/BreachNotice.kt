// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * `GET /v1/me/breach-notices`: a personal data breach intimation sent to the signed-in
 * principal (DPDP Rules r.7(1)).
 *
 * [subject] and [body] are plain text written by the organisation. Show them as text; never
 * pass them to a WebView, `Html.fromHtml` or any other markup renderer.
 */
@Serializable
public data class BreachNotice(
    @SerialName("notice_id") val noticeId: String,
    @SerialName("breach_id") val breachId: String,
    @SerialName("language") val languageCode: String,
    val subject: String,
    val body: String,
    @SerialName("data_categories") val dataCategories: List<String> = emptyList(),
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
) {
    val language: Language? get() = Language.fromCode(languageCode)

    /** The principal has tapped "I have read this" (the server keeps the first time). */
    val isAcknowledged: Boolean get() = acknowledgedAt != null
}

/** Newest [BreachNotice.generatedAt] first; ties broken by notice id so the order is stable. */
public fun List<BreachNotice>.sortedNewestFirst(): List<BreachNotice> =
    sortedWith(
        compareByDescending<BreachNotice> { isoEpochMicros(it.generatedAt) ?: Long.MIN_VALUE }
            .thenBy { it.noticeId },
    )

/** How many notices are not yet acknowledged, e.g. for a badge on a menu item. */
public fun List<BreachNotice>.unacknowledgedCount(): Int = count { !it.isAcknowledged }

private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** True for a canonical, hyphenated UUID string. */
public fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|z|[+-]\d{2}:?\d{2})?$""")

/**
 * Microseconds since the epoch for an ISO-8601 timestamp, or null if it does not parse.
 * Hand-rolled because java.time needs API 26 (minSdk is 24) and the server's fraction
 * length varies, so plain string comparison is not reliable.
 */
internal fun isoEpochMicros(value: String): Long? {
    val m = ISO.matchEntire(value.trim()) ?: return null
    val g = m.groupValues
    val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
    }
    var seconds = cal.timeInMillis / 1000
    val zone = g[8]
    if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
        val digits = zone.substring(1).replace(":", "")
        val offset = digits.substring(0, 2).toInt() * 3600 + digits.substring(2, 4).toInt() * 60
        seconds -= if (zone[0] == '+') offset else -offset
    }
    val micros = g[7].padEnd(6, '0').take(6).ifEmpty { "0" }.toLong()
    return seconds * 1_000_000 + micros
}
