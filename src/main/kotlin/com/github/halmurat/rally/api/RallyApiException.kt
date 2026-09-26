package com.github.halmurat.rally.api

/**
 * Exception thrown when Rally API operations fail
 */
open class RallyApiException : Exception {
    val statusCode: Int?
    val responseBody: String?

    constructor(message: String) : super(message) {
        this.statusCode = null
        this.responseBody = null
    }

    constructor(message: String, cause: Throwable) : super(message, cause) {
        this.statusCode = null
        this.responseBody = null
    }

    constructor(message: String, statusCode: Int, responseBody: String?) : super(message) {
        this.statusCode = statusCode
        this.responseBody = responseBody
    }

    constructor(message: String, statusCode: Int, responseBody: String?, cause: Throwable) : super(message, cause) {
        this.statusCode = statusCode
        this.responseBody = responseBody
    }

    override fun toString(): String {
        return buildString {
            append("RallyApiException: $message")
            if (statusCode != null) {
                append(" (HTTP $statusCode)")
            }
        }
    }
}

/**
 * Exception for authentication failures
 */
class RallyAuthenticationException(message: String, statusCode: Int = 401, responseBody: String? = null)
    : RallyApiException(message, statusCode, responseBody)

/**
 * Exception for connection failures.
 *
 * Forwards to the no-cause or with-cause base constructor depending on what the
 * caller supplies — the previous `cause ?: Exception(message)` synthesized a
 * fake cause whose stack trace pointed inside this class, which made
 * `exception.cause` checks misleading for callers that actually wanted to know
 * whether a real underlying throwable existed.
 */
class RallyConnectionException : RallyApiException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/**
 * No Rally user matches the configured username. A distinct type so callers can tell
 * "that user doesn't exist" apart from a failed lookup.
 */
class RallyUserNotFoundException(message: String) : RallyApiException(message)

/**
 * Exception for security violations (e.g., request to unexpected host)
 */
class RallySecurityException(message: String) : RallyApiException(message)
