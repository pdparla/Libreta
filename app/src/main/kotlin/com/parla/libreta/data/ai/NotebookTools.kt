package com.parla.libreta.data.ai

import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.note.NoteNaming
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.note.NoteSearch
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.NotebookIndex
import com.parla.libreta.core.note.SaveResult
import com.parla.libreta.core.note.SearchHit
import com.parla.libreta.core.note.readOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools the AI can use on the notes in the tablet (docs/12-preguntar.md): search, list and read them, create a
 * note and add text to one. The two that write ask the user first through [confirm]. Results are small JSON
 * objects, because the whole conversation, tool results included, is sent again every turn.
 *
 * @param listing every note with when it was last changed (epoch ms), for the most recent first.
 */
class NotebookTools(
    private val notes: NoteRepository,
    private val noteService: NoteService,
    private val inkNotes: InkNoteService,
    private val index: NotebookIndex,
    private val listing: () -> List<Pair<NotePath, Long>>,
) {

    /**
     * Asks the user whether a tool may write, waiting for the answer; false when they refuse, cancel or leave.
     * Called on the AI's thread; set by the screen that shows the conversation.
     */
    @Volatile
    var confirm: (name: String, arguments: JSONObject, handle: TurnHandle) -> Boolean = { _, _, _ -> false }

    val definitions: JSONArray = JSONArray()
        .put(function(SEARCH, "Busca notas que contengan un texto, también en lo escrito a mano.", "consulta" to "Palabras a buscar"))
        .put(function(LIST, "Lista las notas más recientes, de una categoría o de todas, y las categorías que existen.", "categoria" to "Categoría (opcional; vacía para todas)", required = emptyList()))
        .put(function(READ, "Lee una nota: su texto y, si es a mano, lo que dice cada página.", "nota" to "Ruta de la nota, como la devuelven buscar_notas y listar_notas"))
        .put(function(CREATE, "Crea una nota de texto. Pide permiso al usuario.", "titulo" to "Título", "texto" to "Texto en Markdown", "categoria" to "Categoría (opcional)", required = listOf("titulo", "texto")))
        .put(function(APPEND, "Añade texto al final de una nota (encima de las páginas si es a mano). Pide permiso al usuario.", "nota" to "Ruta de la nota", "texto" to "Texto en Markdown"))

    /** Runs tool [name] with [arguments]; the result, or an error the model can act on, as JSON. */
    fun call(name: String, arguments: JSONObject, handle: TurnHandle): String = try {
        when (name) {
            SEARCH -> search(arguments.optString("consulta"))
            LIST -> list(arguments.optString("categoria"))
            READ -> read(pathOf(arguments))
            CREATE -> if (!confirm(name, arguments, handle)) refused() else create(arguments)
            APPEND -> {
                val path = pathOf(arguments)
                if (!confirm(name, arguments, handle)) refused() else append(path, arguments.optString("texto"))
            }
            else -> error("No existe la herramienta $name")
        }
    } catch (e: IllegalArgumentException) {
        error(e.message ?: "Datos no válidos")
    } catch (e: Exception) {
        error("No se pudo: ${e.javaClass.simpleName}")
    }.toString()

    private fun search(query: String): JSONObject {
        val hits = NoteSearch.search(index.all().map { it.searchable }, query).take(MAX_HITS)
        return JSONObject().put("resultados", JSONArray(hits.map { hit ->
            JSONObject()
                .put("nota", hit.path.value)
                .put("titulo", hit.path.baseName)
                .put("donde", when (hit.where) {
                    SearchHit.Where.TITLE -> "título"
                    SearchHit.Where.TEXT -> "texto"
                    SearchHit.Where.PAGE -> "página ${hit.page + 1} escrita a mano"
                })
                .put("fragmento", hit.snippet)
        }))
    }

    private fun list(category: String): JSONObject {
        val wanted = NoteNaming.categoryName(category)
        val all = listing().filter { it.first.isNote }
        val shown = all.filter { wanted.isEmpty() || it.first.category == wanted }.sortedByDescending { it.second }.take(MAX_LISTED)
        val categories = all.map { it.first.category }.filter { it.isNotEmpty() }.distinct().sorted()
        return JSONObject()
            .put("notas", JSONArray(shown.map { (path, _) -> JSONObject().put("nota", path.value).put("titulo", path.baseName).put("categoria", path.category) }))
            .put("categorias", JSONArray(categories))
    }

    private fun read(path: NotePath): JSONObject {
        val markdown = notes.readOrNull(path)?.text ?: throw IllegalArgumentException("No existe la nota ${path.value}")
        val entry = index.entry(path, markdown)
        var budget = MAX_READ
        fun take(text: String): String = text.take(budget.coerceAtLeast(0)).also { budget -= it.length }
        val result = JSONObject()
            .put("nota", path.value)
            .put("titulo", path.baseName)
            .put("categoria", path.category)
            .put("a_mano", entry.isInk)
            .put("texto", take(entry.searchable.text))
        if (entry.isInk) result.put("paginas", JSONArray(entry.searchable.pages.map { take(it) }))
        if (budget <= 0) result.put("recortada", true)
        return result
    }

    private fun create(arguments: JSONObject): JSONObject {
        val title = arguments.optString("titulo").trim()
        require(title.isNotEmpty()) { "Falta el título" }
        val content = "# $title\n\n${arguments.optString("texto").trim()}\n"
        val entry = noteService.create(content, arguments.optString("categoria"))
        return JSONObject().put("ok", true).put("nota", entry.path.value)
    }

    private fun append(path: NotePath, text: String): JSONObject {
        require(text.isNotBlank()) { "Falta el texto" }
        val markdown = notes.readOrNull(path)?.text ?: throw IllegalArgumentException("No existe la nota ${path.value}")
        if (index.entry(path, markdown).isInk) {
            inkNotes.addText(inkNotes.open(path), text)
        } else {
            val draft = noteService.open(path)
            val saved = noteService.save(draft.edit(draft.content.trimEnd() + "\n\n" + text.trim() + "\n"))
            if (saved is SaveResult.SavedAsConflictCopy) return JSONObject().put("ok", true).put("nota", saved.note.path.value).put("aviso", "La nota cambió mientras tanto: se guardó como copia")
        }
        return JSONObject().put("ok", true).put("nota", path.value)
    }

    private fun pathOf(arguments: JSONObject): NotePath {
        val raw = arguments.optString("nota").trim().removePrefix("nota:").substringBefore('#')
        val path = NotePath.parseOrNull(raw)
        require(path != null && path.isNote) { "La ruta «$raw» no es una nota" }
        return path
    }

    private fun refused() = JSONObject().put("ok", false).put("motivo", "El usuario no lo permitió")

    private fun error(message: String) = JSONObject().put("error", message)

    private fun function(name: String, description: String, vararg parameters: Pair<String, String>, required: List<String>? = null): JSONObject {
        val properties = JSONObject()
        parameters.forEach { (key, about) -> properties.put(key, JSONObject().put("type", "string").put("description", about)) }
        return JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required ?: parameters.map { it.first })))
    }

    companion object {
        const val SEARCH = "buscar_notas"
        const val LIST = "listar_notas"
        const val READ = "leer_nota"
        const val CREATE = "crear_nota"
        const val APPEND = "anadir_a_nota"
        private const val MAX_HITS = 10
        private const val MAX_LISTED = 50
        private const val MAX_READ = 6000
    }
}
