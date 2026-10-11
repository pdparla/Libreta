package com.parla.libreta.ui.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.parla.libreta.R
import com.parla.libreta.data.ai.NotSignedInException
import com.parla.libreta.data.ai.OpenAiHttpException
import com.parla.libreta.data.ai.ResponseFailedException
import com.parla.libreta.data.ai.SessionExpiredException
import com.parla.libreta.ui.SyncMessages

/** User-facing texts for the AI. Connection and TLS errors read the same as the sync's. */
object AiMessages {

    fun describe(context: Context, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        chain.firstNotNullOfOrNull { it as? ResponseFailedException }?.let { failed ->
            return when {
                failed.isUsageLimit -> context.getString(R.string.ai_error_usage_limit)
                failed.isUsageUnavailable -> context.getString(R.string.ai_error_usage_unavailable)
                else -> context.getString(R.string.ai_error_failed, failed.code ?: failed.message)
            }
        }
        chain.firstNotNullOfOrNull { it as? OpenAiHttpException }?.let { return context.getString(R.string.ai_error_http, it.code) }
        return when {
            chain.any { it is SessionExpiredException || it is NotSignedInException } -> context.getString(R.string.ai_error_session_expired)
            else -> SyncMessages.describe(context, error)
        }
    }

    /** The full sentence for an error, e.g. "No se pudo: has alcanzado el límite…". */
    fun failure(context: Context, error: Throwable): String = context.getString(R.string.ai_error, describe(context, error))

    /** Explains how to sign in, which can only be done from the PC. */
    fun showSignInNeeded(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.ai_sign_in_title)
            .setMessage(R.string.ai_sign_in_needed)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
