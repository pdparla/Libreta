package com.parla.libreta.data.ai

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatGptAuthTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val server = MockWebServer()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() = server.shutdown()

    private fun auth(store: ChatGptSessionStore) = ChatGptAuth(OkHttpClient(), store, server.url("/api/accounts/"), now = { now })

    @Test
    fun `a fresh token is used as it is`() {
        val auth = auth(storedSession(dir.root, expiresAt = now + 30 * 60_000))

        assertEquals("access-1", auth.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a token about to expire is refreshed and the rotated tokens are stored`() {
        val store = storedSession(dir.root, expiresAt = now + 60_000)
        server.enqueue(tokenResponse("access-2", "refresh-2"))

        assertEquals("access-2", auth(store).accessToken())

        val request = server.takeRequest()
        assertEquals("/api/accounts/oauth/token", request.path)
        val form = request.body.readUtf8()
        assertTrue(form, "grant_type=refresh_token" in form)
        assertTrue(form, "client_id=oaiapp_test" in form)
        assertTrue(form, "refresh_token=refresh-1" in form)
        assertTrue(form, "resource=https%3A%2F%2Fapi.openai.com%2Fv1" in form)
        val saved = store.load()!!
        assertEquals("refresh-2", saved.refreshToken)
        assertEquals("access-2", saved.accessToken)
        assertEquals(now + 3_600_000, saved.expiresAt)
        assertEquals("oaiapp_test", saved.clientId)
    }

    @Test
    fun `a refused refresh token ends the session`() {
        val store = storedSession(dir.root, expiresAt = now - 1)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        try {
            auth(store).accessToken()
            fail()
        } catch (e: SessionExpiredException) {
            assertNull(store.load())
        }
    }

    @Test
    fun `without a session there is nothing to refresh`() {
        try {
            auth(ChatGptSessionStore(dir.root.resolve("none.json"))).accessToken()
            fail()
        } catch (e: NotSignedInException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `sign out revokes the refresh token and deletes the session, even when revoking fails`() {
        val store = storedSession(dir.root, expiresAt = now + 30 * 60_000)
        server.enqueue(MockResponse())

        assertTrue(auth(store).signOut())
        val request = server.takeRequest()
        assertEquals("/api/accounts/oauth/revoke", request.path)
        val form = request.body.readUtf8()
        assertTrue(form, "token=refresh-1" in form && "token_type_hint=refresh_token" in form && "client_id=oaiapp_test" in form)
        assertNull(store.load())

        val other = storedSession(dir.newFolder(), expiresAt = now + 30 * 60_000)
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(auth(other).signOut())
        assertNull(other.load())
    }

    @Test
    fun `a session file never prints its tokens`() {
        val session = storedSession(dir.root, expiresAt = now).load()!!

        assertFalse(session.toString().contains("access-1"))
        assertFalse(session.toString().contains("refresh-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a session without plan usage is refused`() {
        ChatGptSession.fromJson(
            """{"client_id":"c","ext_agent_host_id":"h","subject":"s","access_token":"a","refresh_token":"r","scope":"openid email","expires_at":1}""",
        )
    }
}
