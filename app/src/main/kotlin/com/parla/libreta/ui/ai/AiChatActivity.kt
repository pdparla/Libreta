package com.parla.libreta.ui.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
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
import com.parla.libreta.core.note.NoteStoreException
import com.parla.libreta.data.ai.AiAssistant
import com.parla.libreta.data.ai.AiConversation
import com.parla.libreta.data.ai.TurnHandle
import com.parla.libreta.ui.Markdown
import com.parla.libreta.ui.dictation.Dictation
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A conversation with the AI about a selection of a handwritten page (docs/07-ia.md). It lives in memory only: the
 * screen handles its own configuration changes, like the handwritten note, so turning the tablet does not recreate
 * it (docs/10-orientacion.md). "Guardar como nota" keeps it as a note with the selection.
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
    private val markwon by lazy { Markdown.forChat(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.ai_chat_title)
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

        send.setOnClickListener { if (turn != null) turn?.cancel() else send() }
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

    private fun send() {
        if (container.chatGptSessions.load() == null) return AiMessages.showSignInNeeded(this)
        val text = input.text.toString()
        val shown = text.trim().ifEmpty { if (conversation.messages.isEmpty()) AiAssistant.EXPLAIN_REQUEST else return }
        val question = addMessage(Speaker.USER, shown)
        val answer = addMessage(Speaker.ASSISTANT, getString(R.string.ai_thinking))
        input.setText("")
        val handle = TurnHandle()
        turn = handle
        send.setText(R.string.ai_stop)

        val received = StringBuilder()
        container.aiBackground.execute({
            container.ai.send(conversation, text, handle) { piece ->
                mainThread.post {
                    received.append(piece)
                    show(answer, Speaker.ASSISTANT, received.toString())
                    scrollToEnd()
                }
            }
        }) { result ->
            turn = null
            if (isDestroyed) return@execute
            send.setText(R.string.ai_send)
            result
                .onSuccess { show(answer, Speaker.ASSISTANT, it) }
                .onFailure { error ->
                    // The message is out of the conversation again: give it back to send later.
                    messages.removeView(question)
                    input.setText(text)
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
            setTextIsSelectable(true)
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
        private const val USER_BACKGROUND = 0xFFE8F0FE.toInt()
        private const val ASSISTANT_BACKGROUND = 0xFFF1F3F4.toInt()
        private const val ERROR_COLOR = 0xFFC5221F.toInt()

        /** A conversation about [image] (PNG); "Guardar como nota" saves it in [category]. */
        fun intent(context: Context, image: File, category: String): Intent =
            Intent(context, AiChatActivity::class.java)
                .putExtra(EXTRA_IMAGE, image.path)
                .putExtra(EXTRA_CATEGORY, category)
    }
}
