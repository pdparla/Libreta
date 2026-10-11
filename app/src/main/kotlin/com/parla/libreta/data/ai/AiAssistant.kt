package com.parla.libreta.data.ai

import com.parla.libreta.core.ai.ChatMessage
import com.parla.libreta.core.ai.Speaker
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation about a selection of a handwritten page: what the user sees ([messages]) and what is sent back to
 * the model every turn ([items]: the user's messages, with the selection in the first one, and the model's output
 * items, reasoning included). Lives in memory only; "Guardar como nota" turns it into a note.
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

    internal fun addAnswer(result: TurnResult) {
        for (i in 0 until result.output.length()) items.put(result.output.get(i))
        shown += ChatMessage(Speaker.ASSISTANT, result.text)
    }

    /** Drops the last user message, when its answer failed, so the user can send it again. */
    internal fun dropUnanswered() {
        if (shown.lastOrNull()?.speaker != Speaker.USER) return
        shown.removeAt(shown.size - 1)
        items.remove(items.length() - 1)
    }

    /** A copy of what the next turn sends. */
    internal fun input(): JSONArray = JSONArray(items.toString())
}

/** The two things the app asks the model: transcribe a selection ([CONVERT_MODEL]), and talk about it ([CHAT_MODEL]). */
class AiAssistant(private val responses: ResponsesClient) {

    /** The handwriting in [png] as Markdown; empty if there is no legible text. */
    fun convertToText(png: ByteArray, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText(CONVERT_REQUEST)).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, CONVERT_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * Sends [text] in [conversation] and adds the answer, streaming its pieces to [onText]. If it fails, the
     * message is taken back out so it can be sent again.
     */
    fun send(conversation: AiConversation, text: String, handle: TurnHandle, onText: (String) -> Unit): String {
        val trimmed = text.trim()
        val modelText = if (trimmed.isEmpty() && conversation.messages.isEmpty()) EXPLAIN_REQUEST else trimmed
        conversation.addUser(trimmed.ifEmpty { modelText }, modelText)
        try {
            val result = responses.respond(CHAT_MODEL, CHAT_INSTRUCTIONS, conversation.input(), handle, onText)
            conversation.addAnswer(result)
            return result.text
        } catch (e: Exception) {
            conversation.dropUnanswered()
            throw e
        }
    }

    companion object {
        val CHAT_MODEL = AiModel("gpt-6.1-sol", "medium")

        /** Transcribing needs less than talking: a cheaper, faster model. */
        val CONVERT_MODEL = AiModel("gpt-6-luna", "medium")

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
        const val EXPLAIN_REQUEST = "¿Qué hay en este recorte? Explícalo brevemente."

        /** The model sometimes wraps Markdown in a ``` block despite the instructions. */
        internal fun withoutCodeFence(text: String): String {
            val match = Regex("""^```[a-zA-Z]*\n([\s\S]*?)\n?```$""").find(text.trim()) ?: return text
            return match.groupValues[1].trim()
        }
    }
}

private fun userMessage(content: JSONArray) = JSONObject().put("type", "message").put("role", "user").put("content", content)

private fun inputText(text: String) = JSONObject().put("type", "input_text").put("text", text)

private fun inputImage(png: ByteArray) =
    JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64," + ByteString.of(*png).base64())
