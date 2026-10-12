package com.parla.libreta.data.ai

import com.parla.libreta.data.local.writeAtomically
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * A Sign in with ChatGPT session authorised for plan usage (docs/07-ia.md). The tokens are secrets: [toString]
 * leaves them out so a log line can never print them.
 *
 * @param expiresAt when [accessToken] expires, in epoch milliseconds.
 */
class ChatGptSession(
    val clientId: String,
    val hostId: String,
    val subject: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val scope: String,
    val expiresAt: Long,
) {

    /** The same session with the tokens of a refresh, which replace the old ones together. */
    fun refreshed(accessToken: String, refreshToken: String, idToken: String?, scope: String?, expiresAt: Long) = ChatGptSession(
        clientId, hostId, subject, email, accessToken, refreshToken, idToken ?: this.idToken, scope ?: this.scope, expiresAt,
    )

    fun toJson(): String = JSONObject()
        .put("client_id", clientId)
        .put("ext_agent_host_id", hostId)
        .put("subject", subject)
        .put("email", email)
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("id_token", idToken)
        .put("scope", scope)
        .put("expires_at", expiresAt / 1000)
        .toString()

    override fun toString() = "ChatGptSession(email=$email, clientId=$clientId, expiresAt=$expiresAt)"

    companion object {
        /** The scope that lets the app spend the user's ChatGPT plan; an ID token alone does not. */
        const val PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"

        /**
         * Reads a session as written by [toJson] or by `scripts/ChatGptLogin.java` (`expires_at` in epoch seconds).
         * @throws IllegalArgumentException if a field is missing or plan usage was not granted.
         */
        fun fromJson(json: String): ChatGptSession {
            val o = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Not a ChatGPT session", e)
            }
            fun field(name: String) = o.optString(name).takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("ChatGPT session without $name")
            val scope = field("scope")
            require(PLAN_USAGE_SCOPE in scope.split(' ')) { "ChatGPT session without plan usage ($PLAN_USAGE_SCOPE)" }
            val expiresAt = o.optLong("expires_at", 0L).takeIf { it > 0 } ?: throw IllegalArgumentException("ChatGPT session without expires_at")
            return ChatGptSession(
                clientId = field("client_id"),
                hostId = field("ext_agent_host_id"),
                subject = field("subject"),
                email = o.optString("email"),
                accessToken = field("access_token"),
                refreshToken = field("refresh_token"),
                idToken = o.optString("id_token"),
                scope = scope,
                expiresAt = expiresAt * 1000,
            )
        }
    }
}

/** The session in the app's private storage (`files/chatgpt-session.json`), written atomically. */
class ChatGptSessionStore(private val file: File) {

    /** A parsed session with the file's modification time and size it was parsed from. */
    private class Cached(val modified: Long, val length: Long, val session: ChatGptSession)

    private var cached: Cached? = null

    /**
     * The stored session, or null without one. An unreadable file counts as no session. The parsed session is kept
     * until the file changes, as this is asked on the main thread to know whether the user is signed in.
     */
    @Synchronized
    fun load(): ChatGptSession? {
        if (!file.isFile) {
            cached = null
            return null
        }
        val modified = file.lastModified()
        val length = file.length()
        cached?.let { if (it.modified == modified && it.length == length) return it.session }
        cached = null
        val session = try {
            ChatGptSession.fromJson(file.readText())
        } catch (e: IllegalArgumentException) {
            return null
        }
        cached = Cached(modified, length, session)
        return session
    }

    @Synchronized
    fun save(session: ChatGptSession) {
        cached = null
        writeAtomically(file, session.toJson())
    }

    @Synchronized
    fun delete() {
        cached = null
        file.delete()
    }
}
