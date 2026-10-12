package com.parla.libreta.ui.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.core.ai.Speaker
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.core.note.readOrNull
import com.parla.libreta.data.ai.AiAssistant
import com.parla.libreta.data.ai.AiConversation
import com.parla.libreta.data.ai.MathTask
import com.parla.libreta.data.ai.NotebookTools
import com.parla.libreta.data.ai.TurnHandle
import com.parla.libreta.ui.Markdown
import com.parla.libreta.ui.dictation.Dictation
import com.parla.libreta.ui.openNote
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * A conversation with the AI, about a selection of a handwritten page (docs/design/ai.md) or, without one, about the whole
 * notebook (docs/design/ai.md). Either way the AI can use the notebook's tools: a grey line shows each one, the
 * ones that write ask first, and its citations open the notes. It lives in memory only: the screen handles its own
 * configuration changes, so turning the tablet does not recreate it (docs/design/ink.md). "Guardar como nota"
 * keeps it as a note, with the selection if there is one.
 */
class AiChatActivity : Activity() {

    private lateinit var conversation: AiConversation
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private var category = ""
    private var turn: TurnHandle? = null
    private val mainThread = Handler(Looper.getMainLooper())

    /** The scheduled render of the answer being streamed, if any (main thread only). */
    private var pendingRender: Runnable? = null
    private val markwon by lazy { Markdown.forChat(this, ::openCitation, ::showWebLink) }
    private lateinit var tools: NotebookTools

    /** Set when the chat opened to solve or check mathematics; the whole conversation then thinks harder. */
    private var task: MathTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(if (intent.hasExtra(EXTRA_IMAGE)) R.string.ai_chat_title else R.string.ai_notebook_title)
        scroll = findViewById(R.id.ai_scroll)
        messages = findViewById(R.id.ai_messages)
        input = findViewById(R.id.ai_input)
        send = findViewById(R.id.ai_send)
        category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()

        val image = intent.getStringExtra(EXTRA_IMAGE)?.let { path ->
            try {
                File(path).readBytes()
            } catch (e: IOException) {
                null
            }
        }
        conversation = AiConversation(image)
        val imageView = findViewById<ImageView>(R.id.ai_image)
        if (image != null) imageView.setImageBitmap(BitmapFactory.decodeByteArray(image, 0, image.size)) else imageView.visibility = View.GONE
        if (image == null) input.setHint(R.string.ai_notebook_hint)
        tools = NotebookTools(
            notes = container.notes,
            noteService = container.noteService,
            inkNotes = container.inkNotes,
            index = container.notebookIndex,
            listing = { container.notes.summaries().map { it.path to it.modifiedAt } },
        )
        tools.confirm = ::askPermission

        send.setOnClickListener { if (turn != null) turn?.cancel() else send() }
        // Opened to solve or check the selection's mathematics: that is the first message, sent at once.
        task = intent.getStringExtra(EXTRA_MATH)?.let { name -> MathTask.values().firstOrNull { it.name == name } }
        task?.let { if (savedInstanceState == null && image != null) send(it.label, it.request) }
        findViewById<View>(R.id.ai_dictate).setOnClickListener {
            // Into the field, to check before sending.
            Dictation.start(this) { text ->
                val current = input.text.toString().trimEnd()
                input.setText(if (current.isEmpty()) text else "$current $text")
                input.setSelection(input.text.length)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingRender?.let(mainThread::removeCallbacks)
        pendingRender = null
        turn?.cancel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_save_note -> saveAsNote()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Sends what is in the field, or [typed] with [request] for the model (a [MathTask]). */
    private fun send(typed: String? = null, request: String? = null) {
        if (container.chatGptSessions.load() == null) return AiMessages.showSignInNeeded(this)
        val text = typed ?: input.text.toString()
        val shown = text.trim().ifEmpty { if (conversation.messages.isEmpty() && conversation.image != null) AiAssistant.EXPLAIN_REQUEST else return }
        val question = addMessage(Speaker.USER, shown)
        val answer = addMessage(Speaker.ASSISTANT, getString(R.string.ai_thinking))
        input.setText("")
        val handle = TurnHandle()
        turn = handle
        send.setText(R.string.ai_stop)

        // The text arrives in pieces of a few characters; formatting everything received so far for each one would
        // cost more and more on the main thread. The pieces are collected at once and shown at most every STREAM_RENDER_MS.
        val received = StringBuilder()
        val render = Runnable {
            pendingRender = null
            if (turn !== handle) return@Runnable
            show(answer, Speaker.ASSISTANT, received.toString())
            scrollToEnd()
        }
        val toolLines = ArrayList<View>()
        val onToolCall = { name: String, arguments: JSONObject ->
            mainThread.post { toolLines += addToolLine(describeTool(name, arguments), before = answer) }
            Unit
        }
        container.aiBackground.execute({
            val model = if (task != null) AiAssistant.MATH_MODEL else AiAssistant.CHAT_MODEL
            container.ai.send(conversation, text, handle, tools, onToolCall = onToolCall, model = model, request = request) { piece ->
                mainThread.post {
                    if (turn !== handle) return@post
                    received.append(piece)
                    if (pendingRender == null) {
                        pendingRender = render
                        mainThread.postDelayed(render, STREAM_RENDER_MS)
                    }
                }
            }
        }) { result ->
            // A render still waiting must not overwrite the final text, the error or a removed view.
            pendingRender?.let(mainThread::removeCallbacks)
            pendingRender = null
            turn = null
            if (isDestroyed) return@execute
            send.setText(R.string.ai_send)
            result
                .onSuccess { show(answer, Speaker.ASSISTANT, it) }
                .onFailure { error ->
                    // The message is out of the conversation again: give it back to send later.
                    messages.removeView(question)
                    toolLines.forEach(messages::removeView)
                    if (typed == null) input.setText(text)
                    if (handle.isCancelled) {
                        messages.removeView(answer)
                    } else {
                        answer.text = AiMessages.failure(this, error)
                        answer.setTextColor(ERROR_COLOR)
                    }
                }
            scrollToEnd()
        }
    }

    private fun addMessage(speaker: Speaker, text: String): TextView {
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            if (speaker == Speaker.USER) {
                setTextIsSelectable(true)
            } else {
                // Citations are links: tappable instead of selectable, and a long press copies the whole answer.
                movementMethod = LinkMovementMethod.getInstance()
                setOnLongClickListener { copy(this.text.toString()); true }
            }
            textSize = 16f
            val padding = (10 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(if (speaker == Speaker.USER) USER_BACKGROUND else ASSISTANT_BACKGROUND)
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = (8 * density).toInt()
        messages.addView(view, params)
        show(view, speaker, text)
        scrollToEnd()
        return view
    }

    /** A grey line saying what the AI is consulting or changing, placed [before] its answer. */
    private fun addToolLine(text: String, before: View): View {
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(TOOL_COLOR)
            setPadding((10 * density).toInt(), (4 * density).toInt(), 0, 0)
        }
        messages.addView(view, messages.indexOfChild(before).coerceAtLeast(0))
        scrollToEnd()
        return view
    }

    private fun describeTool(name: String, arguments: JSONObject): String = when (name) {
        NotebookTools.SEARCH -> getString(R.string.ai_tool_search, arguments.optString("consulta"))
        NotebookTools.LIST -> arguments.optString("categoria").let { if (it.isBlank()) getString(R.string.ai_tool_list) else getString(R.string.ai_tool_list_category, it) }
        NotebookTools.READ -> getString(R.string.ai_tool_read, noteTitle(arguments.optString("nota")))
        NotebookTools.CREATE -> getString(R.string.ai_tool_create, arguments.optString("titulo"))
        NotebookTools.APPEND -> getString(R.string.ai_tool_append, noteTitle(arguments.optString("nota")))
        AiAssistant.WEB_SEARCH -> getString(R.string.ai_tool_web, arguments.optString("consulta"))
        else -> name
    }

    private fun noteTitle(raw: String) = raw.removePrefix(Markdown.NOTE_LINK).substringBefore('#').substringAfterLast('/').substringBeforeLast('.')

    /**
     * Asks before a tool writes, and waits: called on the AI's thread, the dialog lives on the main one. Stopping the
     * answer or leaving the screen counts as no.
     */
    private fun askPermission(name: String, arguments: JSONObject, handle: TurnHandle): Boolean {
        val answer = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        val question = when (name) {
            NotebookTools.CREATE -> {
                val category = arguments.optString("categoria").trim()
                if (category.isEmpty()) getString(R.string.ai_permission_create, arguments.optString("titulo"))
                else getString(R.string.ai_permission_create_in, arguments.optString("titulo"), category)
            }
            else -> getString(R.string.ai_permission_append, noteTitle(arguments.optString("nota")))
        }
        mainThread.post {
            if (isFinishing || isDestroyed) return@post answer.countDown()
            AlertDialog.Builder(this)
                .setTitle(R.string.ai_permission_title)
                .setMessage(question + "\n\n" + arguments.optString("texto").take(PREVIEW))
                .setPositiveButton(R.string.ai_permission_allow) { _, _ -> allowed.set(true) }
                .setNegativeButton(R.string.ai_permission_deny, null)
                .setOnDismissListener { answer.countDown() }
                .show()
        }
        while (!answer.await(200, TimeUnit.MILLISECONDS)) {
            if (handle.isCancelled || isFinishing) return false
        }
        return allowed.get()
    }

    /** Opens the note a citation points to (`Casa/Compra.md#2`), at its page if handwritten. */
    private fun openCitation(target: String) {
        val path = NotePath.parseOrNull(target.substringBefore('#'))?.takeIf { it.isNote }
        val page = (target.substringAfter('#', "").toIntOrNull() ?: 1) - 1
        if (path == null || !openNote(path, page)) Toast.makeText(this, R.string.ai_citation_missing, Toast.LENGTH_SHORT).show()
    }

    /**
     * A page the AI cites (docs/design/ai.md): its address, to open in the tablet's browser if there is one, or
     * to copy.
     */
    private fun showWebLink(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_web_link)
            .setMessage(url)
            .setPositiveButton(R.string.ai_web_open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.ai_web_no_browser, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.ai_copy) { _, _ -> copy(url) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ai_assistant), text))
        Toast.makeText(this, R.string.ai_copied, Toast.LENGTH_SHORT).show()
    }

    /** Shows [text] in [view] under "Tú" or "IA" in bold; the AI's Markdown is shown formatted. */
    private fun show(view: TextView, speaker: Speaker, text: String) {
        val label = getString(if (speaker == Speaker.USER) R.string.ai_you else R.string.ai_assistant)
        val out = SpannableStringBuilder(label).append('\n')
        out.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (speaker == Speaker.USER) {
            view.text = out.append(text)
        } else {
            // Through Markwon, so that spans which need the TextView (tables) are laid out.
            markwon.setParsedMarkdown(view, out.append(markwon.toMarkdown(text)))
        }
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveAsNote() {
        // While an answer streams in, the conversation is still changing on the AI thread.
        if (turn != null) return
        if (conversation.messages.isEmpty()) {
            Toast.makeText(this, R.string.ai_nothing_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        val title = getString(R.string.ai_conversation_title, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date()))
        try {
            val path = container.conversationNotes.save(category, title, conversation.image, conversation.messages)
            Toast.makeText(this, getString(R.string.ai_saved_as_note, path.baseName), Toast.LENGTH_LONG).show()
        } catch (e: NoteStoreException) {
            Toast.makeText(this, R.string.error_saving, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** Where the handwritten note leaves the selection for this screen, in the cache folder. */
        const val SELECTION_FILE = "ai-selection.png"
        private const val EXTRA_IMAGE = "image"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_MATH = "math"
        private const val USER_BACKGROUND = 0xFFE8F0FE.toInt()
        private const val ASSISTANT_BACKGROUND = 0xFFF1F3F4.toInt()
        private const val ERROR_COLOR = 0xFFC5221F.toInt()
        private const val TOOL_COLOR = 0xFF80868B.toInt()
        private const val PREVIEW = 400

        /** The longest an answer being streamed goes without showing the text received since. */
        private const val STREAM_RENDER_MS = 100L

        /** A conversation about the whole notebook, with no selection (docs/design/ai.md). */
        fun intentForNotebook(context: Context, category: String): Intent =
            Intent(context, AiChatActivity::class.java).putExtra(EXTRA_CATEGORY, category)

        /**
         * A conversation about [image] (PNG); "Guardar como nota" saves it in [category]. With [task], it starts by
         * solving or checking the image's mathematics.
         */
        fun intent(context: Context, image: File, category: String, task: MathTask? = null): Intent =
            Intent(context, AiChatActivity::class.java)
                .putExtra(EXTRA_IMAGE, image.path)
                .putExtra(EXTRA_CATEGORY, category)
                .putExtra(EXTRA_MATH, task?.name)
    }
}
