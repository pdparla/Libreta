package com.parla.libreta.data.stt

import com.parla.libreta.data.local.writeAtomically
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Where the dictation service is and how to reach it (docs/07-ia.md, part 2): its HTTPS address, the token it
 * expects and its certificate, the only one trusted for it. `scripts/stt-pair.sh` brings them from the server.
 * [toString] leaves the token out.
 */
class SttSettings(val url: String, val token: String, val certificatePem: String) {

    fun toJson(): String = JSONObject().put("url", url).put("token", token).put("cert", certificatePem).toString()

    override fun toString() = "SttSettings(url=$url)"

    companion object {
        /** @throws IllegalArgumentException if a field is missing or the address is not HTTPS. */
        fun fromJson(json: String): SttSettings {
            val o = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Not dictation settings", e)
            }
            fun field(name: String) = o.optString(name).takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Dictation settings without $name")
            val url = field("url").trimEnd('/')
            require(url.startsWith("https://")) { "Dictation address is not HTTPS" }
            val cert = field("cert")
            require("BEGIN CERTIFICATE" in cert) { "Dictation settings without a PEM certificate" }
            return SttSettings(url, field("token"), cert)
        }
    }
}

/** The settings in the app's private storage (`files/stt.json`). */
class SttSettingsStore(private val file: File) {

    fun load(): SttSettings? {
        if (!file.isFile) return null
        return try {
            SttSettings.fromJson(file.readText())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun save(settings: SttSettings) = writeAtomically(file, settings.toJson())
}
