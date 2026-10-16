package com.parla.libreta.data.ai

import com.parla.libreta.core.ai.ChatMessage
import com.parla.libreta.core.ai.Speaker
import com.parla.libreta.core.ink.InkFigures
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
     * The handwriting in [png] as plain text, to typeset it in place of the strokes (docs/design/ink.md): line breaks
     * kept, no Markdown, formulas in Unicode symbols. Empty if there is no legible text.
     */
    fun transcribeForTyping(png: ByteArray, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText(CONVERT_REQUEST)).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, TYPE_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * The handwriting in [png] translated into [language] (its name in Spanish, such as "inglés"), as plain text like
     * [transcribeForTyping], to put on the page next to the original (docs/design/ai.md#translation). Empty if there
     * is no legible text.
     */
    fun translateForTyping(png: ByteArray, language: String, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText("Traduce este recorte al $language.")).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, TRANSLATE_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * The table drawn by hand in [png] as a Markdown table ([com.parla.libreta.core.ink.InkTable.parse]), to draw it
     * clean and add it to the note (docs/design/ink.md#clean-tables). No table: no answer, or text without one.
     */
    fun readTable(png: ByteArray, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText("Lee esta tabla.")).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, TABLE_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * The diagram drawn by hand in [png] as JSON for [com.parla.libreta.core.ink.InkDiagram.parse]: its nodes, with
     * shape, text and box in thousandths of the image, and the lines between them (docs/design/ai.md#clean-diagrams).
     */
    fun readDiagram(png: ByteArray, handle: TurnHandle): String {
        val input = JSONArray().put(userMessage(JSONArray().put(inputText("Lee este diagrama.")).put(inputImage(png))))
        return responses.respond(CONVERT_MODEL, DIAGRAM_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * The answer to what the user circled on a page (docs/design/ai.md#circled-questions): [circled] is the circled
     * area and [page] the whole page, for context. Plain text of a few sentences, or null if what is circled is not
     * a question or a request.
     */
    fun answerCircled(circled: ByteArray, page: ByteArray, handle: TurnHandle): String? {
        val content = JSONArray()
            .put(inputText("Lo que he rodeado:")).put(inputImage(circled))
            .put(inputText("La página entera:")).put(inputImage(page))
        val answer = responses.respond(CHAT_MODEL, CIRCLED_INSTRUCTIONS, JSONArray().put(userMessage(content)), handle).text.trim().let(::withoutCodeFence)
        return answer.takeUnless { it.isBlank() || it.trimEnd('.', '!', ' ').equals(NOT_A_QUESTION, ignoreCase = true) }
    }

    /**
     * Documentation in Markdown from the [pages] of a handwritten note (docs/design/ai.md#clean-copy): the model sees
     * each page, understands it and writes it out organised by topic, with each drawing as an [InkFigures]
     * placeholder. [titles], marked by the user (docs/design/ink.md), are its main sections.
     */
    fun cleanCopy(pages: List<CleanCopyPage>, titles: List<String>, handle: TurnHandle): String {
        val content = JSONArray()
        content.put(
            inputText(
                buildString {
                    append("Páginas de la nota: ").append(pages.size).append('.')
                    if (titles.isNotEmpty()) append(" Títulos marcados por el usuario: ").append(titles.joinToString("; ")).append('.')
                },
            ),
        )
        for (page in pages) {
            content.put(
                inputText(
                    buildString {
                        append("Página ").append(page.number).append('.')
                        page.transcript?.takeIf { it.isNotBlank() }?.let { append(" Transcripción de apoyo:\n").append(it.trim()) }
                    },
                ),
            )
            content.put(inputImage(page.image))
        }
        val input = JSONArray().put(userMessage(content))
        return responses.respond(DOC_MODEL, CLEAN_INSTRUCTIONS, input, handle).text.trim().let(::withoutCodeFence)
    }

    /**
     * Sends [text] in [conversation] and adds the answer, streaming its pieces to [onText]. When the model asks for
     * [tools], they run (each announced to [onToolCall]) and their results go back to it, round after round, until it
     * answers. With [webSearch] it can also search the web (docs/design/ai.md): each search is announced to
     * [onToolCall] as [WEB_SEARCH], and the pages it cites without a link in the text are added as sources. If
     * anything fails, the message and all it added are taken back out so it can be sent again.
     *
     * The model gets [request] instead of [text] when there is one: [text] is what the conversation shows, as for a
     * [MathTask], whose instructions the user does not need to read.
     */
    fun send(
        conversation: AiConversation,
        text: String,
        handle: TurnHandle,
        tools: NotebookTools? = null,
        onToolCall: (name: String, arguments: JSONObject) -> Unit = { _, _ -> },
        webSearch: Boolean = true,
        model: AiModel = CHAT_MODEL,
        request: String? = null,
        onText: (String) -> Unit,
    ): String {
        val trimmed = text.trim()
        val modelText = request
            ?: if (trimmed.isEmpty() && conversation.messages.isEmpty() && conversation.image != null) EXPLAIN_REQUEST else trimmed
        val mark = conversation.mark()
        conversation.addUser(trimmed.ifEmpty { modelText }, modelText)
        val instructions = (if (conversation.image != null) CHAT_INSTRUCTIONS else NOTEBOOK_INSTRUCTIONS) + LATEX_INSTRUCTIONS +
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
                val result = responses.respond(model, instructions, conversation.input(), handle, definitions, onItem, onText)
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
                "títulos claros. Escribe las fórmulas en LaTeX, entre $ en línea y entre $$ si ocupan su propia línea. " +
                "Ignora los dibujos. Devuelve solo el Markdown, sin comentarios ni bloques de código. " +
                "Si no hay texto legible, no devuelvas nada."
        private const val CONVERT_REQUEST = "Transcribe este recorte."

        private const val TYPE_INSTRUCTIONS =
            "Transcribe el texto manuscrito de la imagen como texto plano, para ponerlo en letra de imprenta en lugar " +
                "de lo escrito a mano. Reproduce exactamente lo escrito, en su idioma y con sus saltos de línea; " +
                "corrige solo las faltas evidentes. No uses Markdown: ni #, ni asteriscos, ni guiones de lista; si hay " +
                "una lista, empieza cada elemento con «• ». Escribe las fórmulas con símbolos Unicode (x², √, ≤, π, " +
                "½), no en LaTeX. Ignora los dibujos. Devuelve solo el texto, sin comentarios. " +
                "Si no hay texto legible, no devuelvas nada."

        /** What the model answers when the circle holds no question. */
        private const val NOT_A_QUESTION = "NADA"

        private const val CIRCLED_INSTRUCTIONS =
            "Eres el asistente de Libreta, una libreta en la que el usuario escribe a mano. Ha rodeado algo con un " +
                "círculo en su página. La primera imagen es lo rodeado; la segunda, la página entera, como contexto. " +
                "Si lo rodeado es una pregunta o una petición dirigida a ti, contéstala en español como lo haría un " +
                "compañero en el margen: breve (de una a tres frases), directa y exacta. Devuelve texto plano, sin " +
                "Markdown, con las fórmulas en símbolos Unicode (x², √, ≤). Si no es una pregunta ni una petición " +
                "(una palabra o un título destacados, un dibujo, un número), responde exactamente NADA."

        private const val DIAGRAM_INSTRUCTIONS =
            "La imagen es un diagrama dibujado a mano: un esquema, un diagrama de flujo, un árbol o un mapa " +
                "conceptual. Devuelve solo JSON, sin comentarios ni bloques de código, con esta forma: " +
                "{\"nodos\":[{\"id\":\"n1\",\"texto\":\"…\",\"forma\":\"rectangulo\",\"caja\":[x1,y1,x2,y2]}]," +
                "\"conexiones\":[{\"desde\":\"n1\",\"hasta\":\"n2\",\"flecha\":\"fin\",\"texto\":\"\"}]}\n" +
                "- Un nodo es cada caja, círculo, rombo o texto suelto que forma parte del diagrama. forma es la figura " +
                "que lo rodea: rectangulo, elipse, rombo, o ninguna si es texto sin figura. texto es lo escrito " +
                "dentro, tal cual, corrigiendo solo faltas evidentes, con \\n para los saltos de línea.\n" +
                "- caja es el rectángulo que ocupa la figura (o el texto, si no tiene figura), en milésimas del ancho " +
                "y del alto de la imagen, de 0 a 1000: x1,y1 es la esquina superior izquierda y x2,y2 la inferior " +
                "derecha. Sé preciso: el diagrama limpio se dibuja en esos sitios.\n" +
                "- Cada línea o flecha que une dos nodos es una conexión: desde el nodo del que sale hasta el nodo al " +
                "que llega. flecha dice dónde tiene punta: fin (en el nodo de llegada), ambas o ninguna. texto es lo " +
                "escrito junto a la línea, o vacío. Si una línea se ramifica, pon una conexión por rama.\n" +
                "Si en la imagen no hay un diagrama, devuelve {\"nodos\":[]}."

        private const val TABLE_INSTRUCTIONS =
            "La imagen es una tabla dibujada a mano. Devuélvela como una tabla de Markdown con exactamente las mismas " +
                "filas y columnas, en el mismo orden: la primera fila de la tabla dibujada es la cabecera. Copia el " +
                "texto de cada celda tal como está escrito, corrigiendo solo faltas evidentes; deja vacías las celdas " +
                "vacías y usa <br> para un salto de línea dentro de una celda. Escribe las fórmulas con símbolos " +
                "Unicode (x², √). Devuelve solo la tabla, sin comentarios ni bloques de código. " +
                "Si en la imagen no hay una tabla, no devuelvas nada."

        private const val TRANSLATE_INSTRUCTIONS =
            "Lee el texto manuscrito de la imagen y tradúcelo al idioma que se pide, con naturalidad y sin añadir " +
                "nada. Devuelve texto plano, para ponerlo en letra de imprenta junto al original: conserva los saltos " +
                "de línea, no uses Markdown (ni #, ni asteriscos, ni guiones de lista; si hay una lista, empieza cada " +
                "elemento con «• ») y escribe las fórmulas con símbolos Unicode (x², √, ≤), sin traducir nombres " +
                "propios ni fórmulas. Ignora los dibujos. Devuelve solo la traducción, sin comentarios. " +
                "Si no hay texto legible, no devuelvas nada."

        private const val CHAT_INSTRUCTIONS =
            "Eres el asistente de Libreta, una libreta de notas a mano en una tablet. El usuario te enseña un recorte " +
                "de lo que ha escrito o dibujado y conversa contigo sobre él. Responde en español, de forma breve y " +
                "clara, en Markdown (párrafos, listas y negrita; tablas solo si ayudan). Si no puedes leer algo del recorte, dilo."
        private const val NOTEBOOK_INSTRUCTIONS =
            "Eres el asistente de Libreta, la libreta de notas de una tablet (notas de texto y notas escritas a mano, " +
                "sincronizadas con Nextcloud). Ayudas al usuario con sus notas: encontrar, resumir, relacionar y " +
                "ordenar lo que ha apuntado. Responde en español, de forma breve y clara, en Markdown (párrafos, " +
                "listas y negrita; tablas solo si ayudan)."

        /** The formatted view and the chat draw LaTeX (docs/design/architecture.md#formulas). */
        private const val LATEX_INSTRUCTIONS =
            " Escribe las fórmulas en LaTeX, entre $ en línea y entre $$ si ocupan su propia línea; no uses \\( ni \\[."

        /** Mathematics deserves more thought: a wrong step or a missed error is worse than a slower answer. */
        val MATH_MODEL = AiModel("gpt-6.1-sol", "high")

        private const val TOOLS_INSTRUCTIONS =
            " Tienes herramientas para buscar, listar, leer, crear notas y añadir texto a una nota. Úsalas en lugar de " +
                "suponer lo que dicen las notas; si no encuentras algo, dilo. Antes de escribir, ten claro qué quiere " +
                "el usuario: crear y añadir piden su permiso, y si lo niega, no insistas. Cita cada nota que uses con " +
                "un enlace Markdown cuyo destino es nota: más su ruta exacta entre < >, por ejemplo " +
                "[Compra](<nota:Casa/Compra.md>); si lo citado está en una página escrita a mano, añade #página, por " +
                "ejemplo [Compra](<nota:Casa/Compra.md#2>). Dentro del texto de una nota que crees o amplíes, enlaza otras " +
                "notas con su título entre dobles corchetes, por ejemplo [[Compra]], o [[Casa/Compra]] si hay varias con ese título."

        private const val WEB_INSTRUCTIONS =
            " Puedes buscar en la web cuando haga falta información actual o que no esté en las notas ni en el recorte; " +
                "para lo que es del usuario, usa antes sus notas. Cita las páginas que uses con enlaces Markdown."

        /** A clean copy is one request that reads and organises the whole note: worth more thought than a chat turn. */
        val DOC_MODEL = AiModel("gpt-6.1-sol", "high")

        private const val CLEAN_INSTRUCTIONS =
            "Recibes, en orden, las páginas de una nota escrita a mano en una tablet, como imágenes. Algunas traen " +
                "una transcripción de apoyo, que puede tener errores: manda lo que ves en la imagen. Entiende el " +
                "contenido y conviértelo en una documentación de calidad en Markdown, como unos apuntes pasados a " +
                "limpio por alguien que domina el tema.\n" +
                "- Organiza la información por temas, con secciones (##) y subsecciones (###) que reflejen su " +
                "estructura. Agrupa lo relacionado aunque esté en páginas distintas y no repitas nada. Los títulos que " +
                "marcó el usuario son las secciones principales.\n" +
                "- Redacta con frases claras y completas lo que esté en notas sueltas o abreviado, sin cambiar su " +
                "sentido. Usa listas, listas numeradas para pasos, tablas para datos comparables y negrita para los " +
                "conceptos clave.\n" +
                "- Conserva toda la información: datos, nombres, fechas, cifras, ejemplos y fórmulas. Corrige las " +
                "faltas y los errores evidentes, pero no inventes datos ni añadas información que no esté en la nota. " +
                "Si algo es ilegible, escribe [ilegible].\n" +
                "- Escribe las fórmulas en LaTeX, entre $ en línea y entre $$ si ocupan su propia línea; no uses \\( ni \\[.\n" +
                "- Cada dibujo, esquema, diagrama, gráfica o figura va como imagen donde corresponda, en una línea " +
                "propia: ![Descripción breve](dibujo:P,X1,Y1,X2,Y2). P es el número de la página; X1,Y1 es la esquina " +
                "superior izquierda y X2,Y2 la inferior derecha del dibujo, en milésimas del ancho y del alto de la " +
                "imagen de esa página (de 0 a 1000). El rectángulo abarca el dibujo entero con sus etiquetas, pero no " +
                "el texto de alrededor. Debajo, explica en una o dos frases lo que muestra. No pongas como imagen el " +
                "texto normal, las fórmulas que puedas escribir en LaTeX ni los tachones o adornos.\n" +
                "- Empieza con un resumen de una o dos frases en cursiva. No añadas un título general: lo pone la app.\n" +
                "Devuelve solo el Markdown, sin comentarios ni bloques de código."

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

/**
 * A page of a handwritten note for [AiAssistant.cleanCopy]: its 1-based [number], its [image] (PNG, see
 * [com.parla.libreta.core.ink.InkFrame.ofPage]) and, when it is up to date, its [transcript] for search as a hint.
 */
class CleanCopyPage(val number: Int, val image: ByteArray, val transcript: String?)

/**
 * What to do with handwritten mathematics in a selection (docs/guide/assistant.md#solve-or-check-math): the
 * conversation shows [label] as the user's message, and the model gets [request].
 */
enum class MathTask(val label: String, val request: String) {
    SOLVE(
        "Resuélvelo.",
        "Lee las matemáticas del recorte. Escribe primero, en LaTeX, lo que has leído, para que pueda ver si lo has " +
            "entendido bien. Después resuélvelo paso a paso, con una frase que explique cada paso, y termina con el " +
            "resultado en negrita. Si el enunciado es ambiguo o falta algún dato, dilo y resuelve el caso más razonable.",
    ),
    CHECK(
        "Comprueba mis cálculos.",
        "Lee las matemáticas del recorte: son cálculos o pasos que he hecho yo. Escribe primero, en LaTeX, lo que has " +
            "leído. Después revisa cada paso. Si todo está bien, dilo. Si hay un error, señala el primer paso " +
            "equivocado, explica por qué está mal y escribe cómo sería el paso correcto, sin rehacer todo el " +
            "ejercicio. Si no puedes leer algo, dilo en lugar de suponerlo.",
    ),
}
