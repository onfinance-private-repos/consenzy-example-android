// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import java.io.IOException

/** Base class of every exception thrown by this SDK. */
public open class ConsentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One entry of a FastAPI 422 validation error (`detail[]`). */
public data class FieldError(val location: List<String>, val message: String, val type: String?)

/**
 * The server answered with a non-2xx status. [detail] is the server's `detail` message
 * (validation errors are joined; the individual entries are in [fieldErrors]).
 */
public class ConsentApiException(
    public val status: Int,
    public val detail: String,
    public val fieldErrors: List<FieldError> = emptyList(),
) : ConsentException("HTTP $status: $detail") {
    public val isUnauthorized: Boolean get() = status == 401
    /** The session lacks the capability for this call (e.g. `requests:write`). */
    public val isForbidden: Boolean get() = status == 403
    public val isNotFound: Boolean get() = status == 404
    public val isConflict: Boolean get() = status == 409
    public val isValidationError: Boolean get() = status == 422
    public val isRateLimited: Boolean get() = status == 429
    public val isServerError: Boolean get() = status >= 500

    /**
     * The principal is a child or a person with disability and this session was not minted
     * with `acting_as=guardian`: a parent or guardian must give this consent (s.9).
     */
    public val isGuardianRequired: Boolean
        get() = status == 422 && fieldErrors.isEmpty() && detail.contains("acting_as=guardian")

    /** A guardian session, but the guardian has not yet been verified (DPDP Rules r.10). */
    public val isGuardianVerificationRequired: Boolean
        get() = status == 422 && fieldErrors.isEmpty() && detail.contains("parent or lawful guardian")
}

/** The request never produced an HTTP response (no network, TLS / pinning failure, timeout). */
public class ConsentNetworkException(cause: IOException) :
    ConsentException("network error: ${cause.message ?: cause.javaClass.simpleName}", cause)

/**
 * The session provider returned something that is not a principal session token. In
 * particular an organisation API key (`cdm_…`) must never reach a device.
 */
public class InvalidSessionTokenException(message: String) : ConsentException(message)
