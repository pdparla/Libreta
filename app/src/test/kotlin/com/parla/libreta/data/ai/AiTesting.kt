package com.parla.libreta.data.ai

import okhttp3.mockwebserver.MockResponse
import java.io.File

/** A stored session whose access token expires at [expiresAt] (epoch ms). */
internal fun storedSession(dir: File, expiresAt: Long, refreshToken: String = "refresh-1"): ChatGptSessionStore {
    val store = ChatGptSessionStore(File(dir, "chatgpt-session.json"))
    store.save(
        ChatGptSession(
            clientId = "oaiapp_test", hostId = "urn:uuid:host", subject = "user-1", email = "ana@example.com",
            accessToken = "access-1", refreshToken = refreshToken, idToken = "id-1",
            scope = "openid offline_access ${ChatGptSession.PLAN_USAGE_SCOPE}", expiresAt = expiresAt,
        ),
    )
    return store
}

/** A streamed Responses API answer made of [events] (JSON objects). */
internal fun sse(vararg events: String): MockResponse =
    MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { event -> "event: x\ndata: $event\n\n" })

internal fun tokenResponse(access: String, refresh: String) =
    MockResponse().setBody("""{"access_token":"$access","refresh_token":"$refresh","id_token":"id-2","scope":"openid ${ChatGptSession.PLAN_USAGE_SCOPE}","expires_in":3600,"token_type":"Bearer"}""")
