package com.parla.libreta.data.ai

import java.io.IOException

/** There is no ChatGPT session on the tablet: sign in from the PC with `scripts/chatgpt-login.sh`. */
class NotSignedInException : IOException("Not signed in with ChatGPT")

/** The session was refused (expired, revoked or disconnected) and has been deleted: sign in again. */
class SessionExpiredException(message: String) : IOException(message)

/** OpenAI answered an unexpected HTTP status. [error] is OpenAI's error code, when it sent one. */
class OpenAiHttpException(val code: Int, val error: String?) : IOException("OpenAI HTTP $code${error?.let { " ($it)" }.orEmpty()}")

/**
 * The model call failed after it started (`response.failed`, `response.incomplete` or an `error` event). [code] is
 * OpenAI's error code, e.g. [USAGE_LIMIT] when the ChatGPT plan has run out.
 */
class ResponseFailedException(val code: String?, message: String) : IOException(message) {
    val isUsageLimit: Boolean get() = code == USAGE_LIMIT
    val isUsageUnavailable: Boolean get() = code == USAGE_UNAVAILABLE

    companion object {
        const val USAGE_LIMIT = "subscription_sharing_usage_limit_exceeded"
        const val USAGE_UNAVAILABLE = "subscription_sharing_usage_unavailable"
    }
}
