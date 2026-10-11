package com.parla.libreta.data.ai

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ResponsesClientTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val server = MockWebServer()
    private val now = System.currentTimeMillis()

    @After
    fun tearDown() = server.shutdown()

    private fun client(): ResponsesClient {
        val auth = ChatGptAuth(OkHttpClient(), storedSession(dir.root, expiresAt = now + 3_600_000), server.url("/auth/"))
        return ResponsesClient(OkHttpClient(), auth, server.url("/v1/"))
    }

    private val model = AiModel("gpt-6.1-sol", "medium")
    private val input = JSONArray().put(JSONObject().put("type", "message").put("role", "user").put("content", "Hola"))

    private val answer = arrayOf(
        """{"type":"response.created","response":{"id":"r1"}}""",
        """{"type":"response.output_item.done","item":{"type":"reasoning","encrypted_content":"xyz"}}""",
        """{"type":"response.output_text.delta","delta":"Hola, "}""",
        """{"type":"response.output_text.delta","delta":"Ana."}""",
        """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hola, Ana."}]}}""",
        """{"type":"response.completed","response":{"id":"r1","status":"completed"}}""",
    )

    @Test
    fun `streams the text and keeps the output items for the next turn`() {
        server.enqueue(sse(*answer))
        val pieces = mutableListOf<String>()

        val result = client().respond(model, "Sé breve.", input) { pieces += it }

        assertEquals(listOf("Hola, ", "Ana."), pieces)
        assertEquals("Hola, Ana.", result.text)
        assertEquals(listOf("reasoning", "message"), (0 until result.output.length()).map { result.output.getJSONObject(it).getString("type") })

        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertEquals("Sé breve.", body.getString("instructions"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(1, body.getJSONArray("input").length())
        assertFalse(body.has("previous_response_id"))
    }

    @Test
    fun `a 401 refreshes the token once and retries`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(tokenResponse("access-2", "refresh-2"))
        server.enqueue(sse(*answer))

        assertEquals("Hola, Ana.", client().respond(model, "x", input).text)

        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/oauth/token", server.takeRequest().path)
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the plan's usage limit is reported as such, even mid-stream`() {
        server.enqueue(
            sse(
                """{"type":"response.output_text.delta","delta":"Ho"}""",
                """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""",
            ),
        )

        try {
            client().respond(model, "x", input)
            fail()
        } catch (e: ResponseFailedException) {
            assertTrue(e.isUsageLimit)
        }
    }

    @Test
    fun `an HTTP error carries OpenAI's code`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad","type":"invalid_request_error","code":"unsupported_parameter"}}"""))

        try {
            client().respond(model, "x", input)
            fail()
        } catch (e: OpenAiHttpException) {
            assertEquals(400, e.code)
            assertEquals("unsupported_parameter", e.error)
        }
    }

    @Test(expected = IOException::class)
    fun `a stream that ends without response completed is a failure`() {
        server.enqueue(sse("""{"type":"response.output_text.delta","delta":"Ho"}"""))

        client().respond(model, "x", input)
    }
}
