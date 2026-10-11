package com.parla.libreta.data.ai

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Keeps the ChatGPT session's access token fresh. Refreshes run one at a time and the rotated tokens are stored
 * before they are used: once a refresh succeeds, the old refresh token is no longer valid.
 *
 * @param authBase `https://auth.openai.com/api/accounts/` in the app; a mock server in tests.
 */
class ChatGptAuth(
    private val client: OkHttpClient,
    private val store: ChatGptSessionStore,
    private val authBase: HttpUrl = DEFAULT_AUTH_BASE,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * An access token valid for a few more minutes, refreshed first when needed or when [forceRefresh] (after a
     * 401). @throws NotSignedInException, SessionExpiredException, IOException
     */
    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String {
        val session = store.load() ?: throw NotSignedInException()
        if (!forceRefresh && session.expiresAt - now() > REFRESH_MARGIN_MS) return session.accessToken
        return refresh(session).accessToken
    }

    /**
     * Revokes the refresh token and deletes the session. Returns whether OpenAI confirmed the revocation; the
     * session is deleted either way, and without confirmation the user can disconnect the app in ChatGPT.
     */
    @Synchronized
    fun signOut(): Boolean {
        val session = store.load() ?: return true
        val revoked = try {
            val body = FormBody.Builder()
                .add("token", session.refreshToken)
                .add("token_type_hint", "refresh_token")
                .add("client_id", session.clientId)
                .build()
            client.newCall(Request.Builder().url(authBase.resolve("oauth/revoke")!!).post(body).build()).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
        store.delete()
        return revoked
    }

    private fun refresh(session: ChatGptSession): ChatGptSession {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", session.clientId)
            .add("refresh_token", session.refreshToken)
            .add("resource", RESOURCE)
            .build()
        val request = Request.Builder().url(authBase.resolve("oauth/token")!!).post(body).build()
        val sentAt = now()
        client.newCall(request).execute().use { response ->
            val text = response.body()?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                null
            }
            if (!response.isSuccessful) {
                val error = json?.optString("error")?.takeIf { it.isNotEmpty() }
                // The refresh token is no longer valid: only signing in again helps.
                if (error == "invalid_grant" || response.code() == 401) {
                    store.delete()
                    throw SessionExpiredException("ChatGPT refresh refused: ${error ?: response.code()}")
                }
                throw OpenAiHttpException(response.code(), error)
            }
            val access = json?.optString("access_token").orEmpty()
            val refresh = json?.optString("refresh_token").orEmpty()
            if (access.isEmpty() || refresh.isEmpty()) throw IOException("ChatGPT refresh without tokens")
            val refreshed = session.refreshed(
                accessToken = access,
                refreshToken = refresh,
                idToken = json?.optString("id_token")?.takeIf { it.isNotEmpty() },
                scope = json?.optString("scope")?.takeIf { it.isNotEmpty() },
                expiresAt = sentAt + json!!.optLong("expires_in", DEFAULT_LIFETIME_S) * 1000,
            )
            store.save(refreshed)
            return refreshed
        }
    }

    companion object {
        val DEFAULT_AUTH_BASE: HttpUrl = HttpUrl.get("https://auth.openai.com/api/accounts/")
        const val RESOURCE = "https://api.openai.com/v1"

        /** Refresh when the access token has less than this left, so it cannot expire during a long answer. */
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
        private const val DEFAULT_LIFETIME_S = 3600L
    }
}
