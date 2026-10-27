// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Typed `details` for a rights request, mirroring the server's DETAILS_BY_TYPE. Constructors
 * validate the same limits the server enforces, so most mistakes fail before a network call.
 */
public sealed class DataRequestDetails {
    /** Request types these details are valid for. */
    public abstract val compatibleTypes: Set<RequestType>

    /** The type used by [ConsentSession.fileDataRequest] when none is given. */
    public val defaultType: RequestType get() = compatibleTypes.first()

    internal abstract fun toJson(): JsonObject

    /** s.11: summary of personal data processed and processing activities. */
    public data class Access(val note: String? = null) : DataRequestDetails() {
        init { note?.let { requireText("note", it, 1000) } }
        override val compatibleTypes: Set<RequestType> = setOf(RequestType.ACCESS)
        override fun toJson(): JsonObject = buildJsonObject { note?.let { put("note", it.trim()) } }
    }

    /**
     * s.12(1)-(2): correction, completion or updating. [fields] maps a snake_case field name
     * (e.g. `"address_line_1"`) to its corrected value.
     */
    public data class Correction(val fields: Map<String, JsonElement>, val note: String? = null) : DataRequestDetails() {
        init {
            require(fields.size in 1..50) { "fields must contain between 1 and 50 entries" }
            fields.keys.forEach { require(FIELD_NAME.matches(it)) { "invalid field name '$it' (use lower snake_case)" } }
            note?.let { requireText("note", it, 1000) }
        }

        override val compatibleTypes: Set<RequestType> =
            linkedSetOf(RequestType.CORRECTION, RequestType.COMPLETION, RequestType.UPDATING)

        override fun toJson(): JsonObject = buildJsonObject {
            put("fields", JsonObject(fields))
            note?.let { put("note", it.trim()) }
        }

        public companion object {
            /** Convenience for string-valued corrections. */
            public fun of(fields: Map<String, String>, note: String? = null): Correction =
                Correction(fields.mapValues { JsonPrimitive(it.value) }, note)

            private val FIELD_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
        }
    }

    /** s.12(3): erasure. */
    public data class Erasure(val reason: String? = null) : DataRequestDetails() {
        init { reason?.let { requireText("reason", it, 1000) } }
        override val compatibleTypes: Set<RequestType> = setOf(RequestType.ERASURE)
        override fun toJson(): JsonObject = buildJsonObject { reason?.let { put("reason", it.trim()) } }
    }

    /** s.13: grievance. [relatedRequestId] must be one of the principal's own requests. */
    public data class Grievance(val description: String, val relatedRequestId: String? = null) : DataRequestDetails() {
        init {
            requireText("description", description, 4000)
            relatedRequestId?.let { require(UUID.matches(it)) { "relatedRequestId must be a UUID" } }
        }
        override val compatibleTypes: Set<RequestType> = setOf(RequestType.GRIEVANCE)
        override fun toJson(): JsonObject = buildJsonObject {
            put("description", description.trim())
            relatedRequestId?.let { put("related_request_id", it) }
        }
    }

    /** s.14: nominate a person. At least one of [nomineeEmail] / [nomineePhone] (E.164) is required. */
    public data class Nomination(
        val nomineeName: String,
        val relationship: String,
        val nomineeEmail: String? = null,
        val nomineePhone: String? = null,
    ) : DataRequestDetails() {
        init {
            requireText("nomineeName", nomineeName, 200)
            requireText("relationship", relationship, 128)
            require(!nomineeEmail.isNullOrBlank() || !nomineePhone.isNullOrBlank()) { "nomineeEmail or nomineePhone is required" }
            nomineeEmail?.let { require(EMAIL.matches(it.trim())) { "nomineeEmail is not a valid email address" } }
            nomineePhone?.let { require(PHONE.matches(it)) { "nomineePhone must be E.164, e.g. +919812345678" } }
        }
        override val compatibleTypes: Set<RequestType> = setOf(RequestType.NOMINATION)
        override fun toJson(): JsonObject = buildJsonObject {
            put("nominee_name", nomineeName.trim())
            put("relationship", relationship.trim())
            nomineeEmail?.takeIf { it.isNotBlank() }?.let { put("nominee_email", it.trim()) }
            nomineePhone?.takeIf { it.isNotBlank() }?.let { put("nominee_phone", it) }
        }
    }

    private companion object {
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val PHONE = Regex("^\\+[1-9][0-9]{6,14}$")
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun requireText(name: String, value: String, max: Int) {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "$name must not be blank" }
            require(trimmed.length <= max) { "$name must be at most $max characters" }
        }
    }
}
