package com.parla.libreta.data.ai

import com.parla.libreta.core.ai.Speaker
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AiAssistantTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun assistant(): AiAssistant {
        val auth = ChatGptAuth(OkHttpClient(), storedSession(dir.root, expiresAt = System.currentTimeMillis() + 3_600_000), server.url("/auth/"))
        return AiAssistant(ResponsesClient(OkHttpClient(), auth, server.url("/v1/")))
    }

    private fun answer(text: String) = sse(
        """{"type":"response.output_text.delta","delta":${JSONObject.quote(text)}}""",
        """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":${JSONObject.quote(text)}}]}}""",
        """{"type":"response.completed","response":{"status":"completed"}}""",
    )

    @Test
    fun `the image goes with the first message only, and every turn resends the whole conversation`() {
        server.enqueue(answer("Una lista."))
        server.enqueue(answer("Dos cosas."))
        val conversation = AiConversation(byteArrayOf(1, 2, 3))
        val assistant = assistant()

        assistant.send(conversation, "", TurnHandle()) {}
        assistant.send(conversation, "¿Cuántas?", TurnHandle()) {}

        val firstBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("gpt-6.1-sol", firstBody.getString("model"))
        val first = firstBody.getJSONArray("input")
        assertEquals(1, first.length())
        val firstContent = first.getJSONObject(0).getJSONArray("content")
        assertEquals(AiAssistant.EXPLAIN_REQUEST, firstContent.getJSONObject(0).getString("text"))
        assertEquals("data:image/png;base64,AQID", firstContent.getJSONObject(1).getString("image_url"))

        val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
        assertEquals(listOf("user", "assistant", "user"), (0 until second.length()).map { second.getJSONObject(it).getString("role") })
        assertEquals(1, second.getJSONObject(2).getJSONArray("content").length())
        assertEquals(
            listOf(Speaker.USER, Speaker.ASSISTANT, Speaker.USER, Speaker.ASSISTANT),
            conversation.messages.map { it.speaker },
        )
        assertEquals(AiAssistant.EXPLAIN_REQUEST, conversation.messages[0].text)
    }

    @Test
    fun `a failed message is taken back out of the conversation`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val conversation = AiConversation(byteArrayOf(1))

        try {
            assistant().send(conversation, "Hola", TurnHandle()) {}
            fail()
        } catch (e: IOException) {
            assertTrue(conversation.messages.isEmpty())
        }
        server.enqueue(answer("Hola."))
        assistant().send(conversation, "Hola", TurnHandle()) {}
        server.takeRequest() // the failed one
        val retried = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(1, retried.getJSONArray("input").length())
    }

    @Test
    fun `convert returns the Markdown without a code fence`() {
        server.enqueue(answer("```markdown\n# Compra\n\n- pan\n```"))

        assertEquals("# Compra\n\n- pan", assistant().convertToText(byteArrayOf(1), TurnHandle()))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("gpt-6-luna", body.getString("model"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
    }
}
