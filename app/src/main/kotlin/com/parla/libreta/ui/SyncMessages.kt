package com.parla.libreta.ui

import android.content.Context
import android.text.format.DateFormat
import com.parla.libreta.R
import com.parla.libreta.data.remote.HttpStatusException
import com.parla.libreta.sync.SyncStatus
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** User-facing texts for sync outcomes and connection errors. */
object SyncMessages {

    fun status(context: Context, status: SyncStatus): String? = when (status) {
        SyncStatus.Idle -> null
        SyncStatus.NotConfigured -> context.getString(R.string.sync_not_configured)
        SyncStatus.Running -> context.getString(R.string.sync_running)
        is SyncStatus.Failed -> context.getString(R.string.sync_failed, describe(context, status.error))
        is SyncStatus.Finished -> {
            val report = status.report
            val aborted = report.abortedBy
            val time = DateFormat.getTimeFormat(context).format(Date(status.at))
            when {
                aborted != null -> context.getString(R.string.sync_failed, describe(context, aborted))
                report.failures.isNotEmpty() ->
                    context.resources.getQuantityString(R.plurals.sync_done_with_failures, report.failures.size, time, report.failures.size)
                else -> context.getString(R.string.sync_done, time)
            }
        }
    }

    fun describe(context: Context, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        chain.firstNotNullOfOrNull { it as? HttpStatusException }?.let { http ->
            return when (http.code) {
                401 -> context.getString(R.string.error_credentials)
                404 -> context.getString(R.string.error_notes_folder)
                503 -> context.getString(R.string.error_maintenance)
                else -> context.getString(R.string.error_http, http.code)
            }
        }
        return when {
            chain.any { it is SSLPeerUnverifiedException } -> context.getString(R.string.error_certificate_name)
            chain.any { it is SSLHandshakeException } -> context.getString(R.string.error_tls_handshake)
            chain.any { it is SSLException } -> context.getString(R.string.error_tls)
            chain.any {
                it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException
            } -> context.getString(R.string.error_unreachable)
            chain.any { it is LinkageError } -> context.getString(R.string.error_conscrypt)
            chain.any { it is IllegalArgumentException } -> context.getString(R.string.error_settings)
            else -> error.message ?: error.javaClass.simpleName
        }
    }
}
