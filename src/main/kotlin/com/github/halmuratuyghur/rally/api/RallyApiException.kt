package com.github.halmuratuyghur.rally.api

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
            if (responseBody != null) {
                append("\nResponse: $responseBody")
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
 * Exception for connection failures
 */
class RallyConnectionException(message: String, cause: Throwable? = null)
    : RallyApiException(message, cause ?: Exception(message))
