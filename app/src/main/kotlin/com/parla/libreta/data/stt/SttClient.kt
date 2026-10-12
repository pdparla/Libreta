package com.parla.libreta.data.stt

import com.parla.libreta.data.remote.Tls
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.Provider
import java.util.concurrent.TimeUnit

/** The dictation service is not set up on this tablet: run `scripts/stt-pair.sh` on the PC. */
class SttNotConfiguredException : IOException("Dictation is not set up")

/** The service refused the token: pair the tablet again. */
class SttUnauthorizedException : IOException("Dictation token refused")

/** The service answered an unexpected HTTP status. */
class SttHttpException(val code: Int) : IOException("Dictation HTTP $code")

/**
 * Sends a recording to Libreta's dictation service (server/stt) and returns the text. The connection trusts only
 * the service's own certificate, from the settings, through [provider] (Conscrypt on the device) with the same TLS
 * 1.2 suites as Nextcloud. Its clients derive from [base] with their own socket factory, so they share the pool
 * and threads but never a connection with a different trust configuration.
 */
class SttClient(
    private val store: SttSettingsStore,
    private val provider: () -> Provider?,
    /** The app's shared base client (connection pool and dispatcher); each certificate's client derives from it. */
    private val base: () -> OkHttpClient = { OkHttpClient() },
) {

    private var cached: Pair<String, OkHttpClient>? = null

    /** @throws SttNotConfiguredException, SttUnauthorizedException, SttHttpException, IOException */
    fun transcribe(wav: File, onCall: (Call) -> Unit = {}): String {
        val settings = store.load() ?: throw SttNotConfiguredException()
        val request = Request.Builder()
            .url("${settings.url}/v1/transcribe")
            .header("Authorization", "Bearer ${settings.token}")
            .post(RequestBody.create(WAV, wav))
            .build()
        val call = clientFor(settings).newCall(request)
        onCall(call)
        call.execute().use { response ->
            when (response.code()) {
                200 -> Unit
                401 -> throw SttUnauthorizedException()
                else -> throw SttHttpException(response.code())
            }
            return try {
                JSONObject(response.body()!!.string()).getString("text").trim()
            } catch (e: JSONException) {
                throw IOException("Unreadable dictation answer", e)
            }
        }
    }

    /** The service's model, from `/health`; checks the address, TLS and certificate (not the token). */
    fun health(): String {
        val settings = store.load() ?: throw SttNotConfiguredException()
        clientFor(settings).newCall(Request.Builder().url("${settings.url}/health").build()).execute().use { response ->
            if (response.code() != 200) throw SttHttpException(response.code())
            return try {
                JSONObject(response.body()!!.string()).optString("model")
            } catch (e: JSONException) {
                throw IOException("Unreadable dictation answer", e)
            }
        }
    }

    /** One client per certificate, rebuilt only if pairing brought a new one. */
    @Synchronized
    private fun clientFor(settings: SttSettings): OkHttpClient {
        cached?.let { (pem, client) -> if (pem == settings.certificatePem) return client }
        val trustManager = Tls.trustManagerFor(settings.certificatePem.byteInputStream())
        val client = base().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Five minutes of audio take a while to transcribe on the server's CPU.
            .readTimeout(5, TimeUnit.MINUTES)
            .sslSocketFactory(Tls.socketFactory(trustManager, provider()), trustManager)
            .connectionSpecs(listOf(Tls.CONNECTION_SPEC))
            .build()
        cached = settings.certificatePem to client
        return client
    }

    private companion object {
        val WAV: MediaType = MediaType.get("audio/wav")
    }
}
