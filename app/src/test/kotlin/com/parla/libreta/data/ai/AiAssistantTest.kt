package com.parla.libreta.data.ai

import com.parla.libreta.core.ai.Speaker
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService
import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.NotebookIndex
import com.parla.libreta.data.local.FileNoteRepository
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
        assertEquals(AiAssistant.CONVERT_MODEL.name, body.getString("model"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
    }

    // --- Tools (docs/12-preguntar.md) ---

    private fun toolsOn(dir: java.io.File): Pair<NotebookTools, FileNoteRepository> {
        val notes = FileNoteRepository(dir)
        val noteService = NoteService(notes, ConflictNamer { "2026-10-12" })
        val inkNotes = InkNoteService(notes, noteService, { ByteArray(0) }, { "k3" })
        val index = NotebookIndex(notes, inkNotes, InkTranscriptService(notes), exists = notes::exists)
        return NotebookTools(notes, noteService, inkNotes, index, listing = { notes.summaries().map { it.path to it.modifiedAt } }) to notes
    }

    private fun toolCall(name: String, arguments: String) = sse(
        """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c1","name":"$name","arguments":${JSONObject.quote(arguments)}}}""",
        """{"type":"response.completed","response":{"status":"completed"}}""",
    )

    @Test
    fun `a tool call runs, its result goes back, and the answer comes after`() {
        val (tools, notes) = toolsOn(dir.newFolder("notes"))
        notes.write(NotePath("Casa/Compra.md"), "# Compra\n\npan y leche".toByteArray())
        server.enqueue(toolCall(NotebookTools.SEARCH, """{"consulta":"leche"}"""))
        server.enqueue(answer("En [Compra](<nota:Casa/Compra.md>)."))
        val called = mutableListOf<String>()

        val text = assistant().send(AiConversation(null), "¿Dónde apunté la leche?", TurnHandle(), tools, { name, _ -> called += name }) {}

        assertEquals("En [Compra](<nota:Casa/Compra.md>).", text)
        assertEquals(listOf(NotebookTools.SEARCH), called)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(5, first.getJSONArray("tools").length())
        val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
        val output = second.getJSONObject(second.length() - 1)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("c1", output.getString("call_id"))
        assertTrue(output.getString("output"), output.getString("output").contains("Casa/Compra.md"))
    }

    @Test
    fun `a refused write changes nothing and the model is told`() {
        val (tools, notes) = toolsOn(dir.newFolder("notes"))
        tools.confirm = { _, _, _ -> false }
        server.enqueue(toolCall(NotebookTools.CREATE, """{"titulo":"Pendientes","texto":"- llamar"}"""))
        server.enqueue(answer("Vale, no la creo."))

        assistant().send(AiConversation(null), "Crea una nota de pendientes", TurnHandle(), tools) {}

        assertTrue(notes.list().isEmpty())
        server.takeRequest()
        val input = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
        assertTrue(input.getJSONObject(input.length() - 1).getString("output").contains("no lo permitió"))
    }

    @Test
    fun `an allowed write creates the note`() {
        val (tools, notes) = toolsOn(dir.newFolder("notes"))
        tools.confirm = { _, _, _ -> true }
        server.enqueue(toolCall(NotebookTools.CREATE, """{"titulo":"Pendientes","texto":"- llamar","categoria":"Casa"}"""))
        server.enqueue(answer("Hecho."))

        assistant().send(AiConversation(null), "Crea una nota de pendientes", TurnHandle(), tools) {}

        assertEquals("# Pendientes\n\n- llamar\n", String(notes.read(NotePath("Casa/Pendientes.md")).bytes))
    }

    @Test
    fun `if a later round fails, the whole message and its tool calls are taken back`() {
        val (tools, _) = toolsOn(dir.newFolder("notes"))
        val conversation = AiConversation(null)
        server.enqueue(toolCall(NotebookTools.LIST, "{}"))
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            assistant().send(conversation, "¿Qué notas tengo?", TurnHandle(), tools) {}
            fail()
        } catch (e: IOException) {
            assertTrue(conversation.messages.isEmpty())
        }
        server.enqueue(answer("Ninguna."))
        assistant().send(conversation, "¿Qué notas tengo?", TurnHandle(), tools) {}
        server.takeRequest()
        server.takeRequest()
        assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input").length())
    }
}

