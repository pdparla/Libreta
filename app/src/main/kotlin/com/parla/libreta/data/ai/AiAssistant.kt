package com.parla.libreta.data.ai

import com.parla.libreta.core.ai.ChatMessage
import com.parla.libreta.core.ai.Speaker
import okio.ByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * A conversation with the AI, about a selection of a handwritten page ([image]) or about the whole notebook (no
 * image): what the user sees ([messages]) and what is sent back to the model every turn ([items]: the user's
 * messages, with the selection in the first one, the model's output items, reasoning and tool calls included, and
 * the tools' results). Lives in memory only; "Guardar como nota" turns it into a note.
 */
class AiConversation(val image: ByteArray?) {

    private val items = JSONArray()
    private val shown = ArrayList<ChatMessage>()

    val messages: List<ChatMessage> get() = shown

    /** Adds the user's [text]; the first message also carries the image. */
    internal fun addUser(text: String, modelText: String) {
        val content = JSONArray().put(inputText(modelText))
        if (items.length() == 0 && image != null) content.put(inputImage(image))
        items.put(userMessage(content))
        shown += ChatMessage(Speaker.USER, text)
    }

    /** The model's output items of one round: reasoning, tool calls, the answer. */
    internal fun addOutput(result: TurnResult) {
        for (i in 0 until result.output.length()) items.put(result.output.get(i))
    }

    internal fun addToolResult(callId: String, output: String) {
        items.put(JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output))
    }

    internal fun addAnswer(text: String) {
        shown += ChatMessage(Speaker.ASSISTANT, text)
    }

    /** Where the conversation stands, to [rollBack] to if a message fails. */
    internal fun mark(): Pair<Int, Int> = items.length() to shown.size

    /** Drops everything added since [mark]: the user's message and whatever the failed answer had added. */
    internal fun rollBack(mark: Pair<Int, Int>) {
        while (items.length() > mark.first) items.remove(items.length() - 1)
        while (shown.size > mark.second) shown.removeAt(shown.size - 1)
    }

    /** A copy of what the next turn sends. */
    internal fun input(): JSONArray = JSONArray(items.toString())
}

/**
 * What the app asks the model: transcribe a selection ([CONVERT_MODEL]), and talk ([CHAT_MODEL]) about a selection or
 * the whole notebook, using the notebook's tools (docs/design/ai.md).
 */
class AiAssistant(private val responses: ResponsesClient) {

    /** The handwriting in [png] as Markdown; empty if there is no legible text. */
    fun convertToText(png: ByteArray, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText(CONVERT_REQUEST)).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, CONVERT_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * One clean Markdown text from the transcripts of several handwritten [pages], in order (docs/design/ai.md):
     * joined without repeats or sentences cut between pages, structured, and with nothing that is not written.
     * [titles], marked by the user (docs/design/ink.md), guide the structure.
     */
    fun cleanCopy(pages: List<String>, titles: List<String>, handle: TurnHandle): String {
        val request = buildString {
            if (titles.isNotEmpty()) append("Títulos marcados por el usuario: ").append(titles.joinToString("; ")).append("\n\n")
            pages.forEachIndexed { i, text -> append("Página ").append(i + 1).append(":\n").append(text.trim()).append("\n\n") }
        }
        val input = JSONArray().put(userMessage(JSONArray().put(inputText(request))))
        return responses.respond(CHAT_MODEL, CLEAN_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * Sends [text] in [conversation] and adds the answer, streaming its pieces to [onText]. When the model asks for
     * [tools], they run (each announced to [onToolCall]) and their results go back to it, round after round, until it
     * answers. With [webSearch] it can also search the web (docs/design/ai.md): each search is announced to
     * [onToolCall] as [WEB_SEARCH], and the pages it cites without a link in the text are added as sources. If
     * anything fails, the message and all it added are taken back out so it can be sent again.
     */
    fun send(
        conversation: AiConversation,
        text: String,
        handle: TurnHandle,
        tools: NotebookTools? = null,
        onToolCall: (name: String, arguments: JSONObject) -> Unit = { _, _ -> },
        webSearch: Boolean = true,
        onText: (String) -> Unit,
    ): String {
        val trimmed = text.trim()
        val modelText = if (trimmed.isEmpty() && conversation.messages.isEmpty() && conversation.image != null) EXPLAIN_REQUEST else trimmed
        val mark = conversation.mark()
        conversation.addUser(trimmed.ifEmpty { modelText }, modelText)
        val instructions = (if (conversation.image != null) CHAT_INSTRUCTIONS else NOTEBOOK_INSTRUCTIONS) +
            (if (tools != null) TOOLS_INSTRUCTIONS else "") + (if (webSearch) WEB_INSTRUCTIONS else "")
        val definitions = JSONArray()
        tools?.definitions?.let { for (i in 0 until it.length()) definitions.put(it.get(i)) }
        if (webSearch) definitions.put(WEB_SEARCH_TOOL)
        val onItem = { item: JSONObject ->
            val action = item.optJSONObject("action")
            val query = action?.optString("query").orEmpty()
            if (item.optString("type") == "web_search_call" && query.isNotBlank()) onToolCall(WEB_SEARCH, JSONObject().put("consulta", query))
        }
        try {
            repeat(MAX_TOOL_ROUNDS) {
                val result = responses.respond(CHAT_MODEL, instructions, conversation.input(), handle, definitions, onItem, onText)
                conversation.addOutput(result)
                val calls = result.calls
                if (calls.isEmpty() || tools == null) {
                    val answer = withSources(result.text, result.citations)
                    conversation.addAnswer(answer)
                    return answer
                }
                for (call in calls) {
                    val arguments = try {
                        JSONObject(call.optString("arguments").ifEmpty { "{}" })
                    } catch (e: JSONException) {
                        JSONObject()
                    }
                    val name = call.optString("name")
                    onToolCall(name, arguments)
                    conversation.addToolResult(call.optString("call_id"), tools.call(name, arguments, handle))
                }
            }
            throw IOException("The AI kept calling tools without answering")
        } catch (e: Exception) {
            conversation.rollBack(mark)
            throw e
        }
    }

    companion object {
        /** How a web search is announced to `onToolCall`, with the query as `consulta`. */
        const val WEB_SEARCH = "web_search"

        /** OpenAI's own search (docs/design/ai.md), with results that make sense from Spain. */
        private val WEB_SEARCH_TOOL: JSONObject
            get() = JSONObject().put("type", "web_search")
                .put("user_location", JSONObject().put("type", "approximate").put("country", "ES"))

        val CHAT_MODEL = AiModel("gpt-6.1-sol", "medium")

        /** The same as for talking: gpt-6-luna was tried for transcribing and misread handwriting too often. */
        val CONVERT_MODEL = AiModel("gpt-6.1-sol", "medium")

        private const val CONVERT_INSTRUCTIONS =
            "Transcribe el texto manuscrito de la imagen a Markdown. Reproduce exactamente lo escrito, en su idioma y " +
                "sin corregirlo: respeta los saltos de línea, usa listas de Markdown para las listas y # solo para " +
                "títulos claros. Ignora los dibujos. Devuelve solo el Markdown, sin comentarios ni bloques de código. " +
                "Si no hay texto legible, no devuelvas nada."
        private const val CONVERT_REQUEST = "Transcribe este recorte."

        private const val CHAT_INSTRUCTIONS =
            "Eres el asistente de Libreta, una libreta de notas a mano en una tablet. El usuario te enseña un recorte " +
                "de lo que ha escrito o dibujado y conversa contigo sobre él. Responde en español, de forma breve y " +
                "clara, en Markdown (párrafos, listas y negrita; tablas solo si ayudan). Si no puedes leer algo del recorte, dilo."
        private const val NOTEBOOK_INSTRUCTIONS =
            "Eres el asistente de Libreta, la libreta de notas de una tablet (notas de texto y notas escritas a mano, " +
                "sincronizadas con Nextcloud). Ayudas al usuario con sus notas: encontrar, resumir, relacionar y " +
                "ordenar lo que ha apuntado. Responde en español, de forma breve y clara, en Markdown (párrafos, " +
                "listas y negrita; tablas solo si ayudan)."

        private const val TOOLS_INSTRUCTIONS =
            " Tienes herramientas para buscar, listar, leer, crear notas y añadir texto a una nota. Úsalas en lugar de " +
                "suponer lo que dicen las notas; si no encuentras algo, dilo. Antes de escribir, ten claro qué quiere " +
                "el usuario: crear y añadir piden su permiso, y si lo niega, no insistas. Cita cada nota que uses con " +
                "un enlace Markdown cuyo destino es nota: más su ruta exacta entre < >, por ejemplo " +
                "[Compra](<nota:Casa/Compra.md>); si lo citado está en una página escrita a mano, añade #página, por " +
                "ejemplo [Compra](<nota:Casa/Compra.md#2>)."

        private const val WEB_INSTRUCTIONS =
            " Puedes buscar en la web cuando haga falta información actual o que no esté en las notas ni en el recorte; " +
                "para lo que es del usuario, usa antes sus notas. Cita las páginas que uses con enlaces Markdown."

        private const val CLEAN_INSTRUCTIONS =
            "Recibes, en orden, lo transcrito de las páginas de una nota escrita a mano. Únelo en una sola nota en " +
                "Markdown, limpia y bien estructurada: títulos de sección con ##, listas y tablas donde el original las " +
                "tenga. No repitas nada, no cortes frases entre páginas, corrige solo errores evidentes de lectura y no " +
                "añadas nada que no esté escrito. Conserva todo el texto de todas las páginas, también los títulos que " +
                "marcó el usuario, que van como títulos de sección. No añadas un título general para toda la nota: lo " +
                "pone la app. Escribe las fórmulas en LaTeX entre $. Devuelve solo el Markdown, sin comentarios."

        /** Rounds of tool calls in one answer before giving up: a model that never answers must not loop forever. */
        private const val MAX_TOOL_ROUNDS = 8

        const val EXPLAIN_REQUEST = "¿Qué hay en este recorte? Explícalo brevemente."

        /** [text] with the [citations] it does not link to already, as a list of sources at the end. */
        internal fun withSources(text: String, citations: List<Pair<String, String>>): String {
            val missing = citations.filter { (_, url) -> !text.contains(url) }
            if (missing.isEmpty()) return text
            return text.trimEnd() + "\n\n**Fuentes:**\n" + missing.joinToString("\n") { (title, url) -> "- [${title.replace("]", "")}](<$url>)" }
        }

        private val CODE_FENCE = Regex("""^```[a-zA-Z]*\n([\s\S]*?)\n?```$""")

        /** The model sometimes wraps Markdown in a ``` block despite the instructions. */
        internal fun withoutCodeFence(text: String): String {
            val match = CODE_FENCE.find(text.trim()) ?: return text
            return match.groupValues[1].trim()
        }
    }
}

private fun userMessage(content: JSONArray) = JSONObject().put("type", "message").put("role", "user").put("content", content)

private fun inputText(text: String) = JSONObject().put("type", "input_text").put("text", text)

private fun inputImage(png: ByteArray) =
    JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64," + ByteString.of(*png).base64())
