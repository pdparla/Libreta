package com.parla.libreta.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.editor.AbstractEditHandler
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.PersistedSpans
import io.noties.markwon.editor.handler.EmphasisEditHandler
import io.noties.markwon.editor.handler.StrongEmphasisEditHandler
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.MediaDecoder
import io.noties.markwon.image.destination.ImageDestinationProcessorRelativeToAbsolute
import io.noties.markwon.image.file.FileSchemeHandler
import java.io.File
import java.io.InputStream

/**
 * Markdown shown formatted, with Markwon: CommonMark plus GitHub's tables and strikethrough, the Markdown that
 * Nextcloud Notes writes and that the model answers in.
 */
object Markdown {

    /**
     * For the AI's answers: text only. With [onNoteLink], links to `nota:<path>` (the AI's citations,
     * docs/12-preguntar.md) go there, with what follows `nota:`; other links do nothing, as the tablet has no browser.
     */
    fun forChat(context: Context, onNoteLink: ((String) -> Unit)? = null): Markwon =
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> if (link.startsWith(NOTE_LINK)) onNoteLink?.invoke(link.removePrefix(NOTE_LINK)) }
                }
            })
            .build()

    /** How the AI cites a note: `nota:Casa/Compra.md`, or `nota:Casa/Compra.md#2` for page 2 of a handwritten one. */
    const val NOTE_LINK = "nota:"

    /**
     * For a note stored in [folder]: also shows its images, whose links are relative to the note (for example a
     * handwritten note's `.attachments.k3/pagina-1.png`). Only local files are loaded.
     */
    fun forNote(context: Context, folder: File): Markwon =
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(
                ImagesPlugin.create { plugin ->
                    plugin.addSchemeHandler(FileSchemeHandler.create())
                    plugin.defaultMediaDecoder(FitWidthDecoder(context.resources))
                },
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.imageDestinationProcessor(ImageDestinationProcessorRelativeToAbsolute.create("file://${folder.absolutePath}/"))
                }
            })
            .build()

    /**
     * Highlighting while the Markdown is typed: the syntax in grey, and bold, italics and headings shown as such.
     * The text itself does not change.
     */
    fun editor(context: Context): MarkwonEditor =
        MarkwonEditor.builder(forChat(context))
            .useEditHandler(StrongEmphasisEditHandler())
            .useEditHandler(EmphasisEditHandler())
            .useEditHandler(HeadingEditHandler())
            .build()

    /** A heading's whole line (`# Título`) in bold and larger; Markwon's editor has no handler for headings. */
    private class HeadingEditHandler : AbstractEditHandler<HeadingSpan>() {

        override fun configurePersistedSpans(builder: PersistedSpans.Builder) {
            builder.persistSpan(StyleSpan::class.java) { StyleSpan(Typeface.BOLD) }
            builder.persistSpan(RelativeSizeSpan::class.java) { RelativeSizeSpan(HEADING_SIZE) }
        }

        override fun handleMarkdownSpan(
            persistedSpans: PersistedSpans,
            editable: Editable,
            input: String,
            span: HeadingSpan,
            spanStart: Int,
            spanTextLength: Int,
        ) {
            val start = input.lastIndexOf('\n', spanStart - 1) + 1
            val end = input.indexOf('\n', spanStart).let { if (it < 0) input.length else it }
            editable.setSpan(persistedSpans.get(StyleSpan::class.java), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editable.setSpan(persistedSpans.get(RelativeSizeSpan::class.java), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        override fun markdownSpanType(): Class<HeadingSpan> = HeadingSpan::class.java
    }

    /**
     * Decodes images no wider than the screen, halving them as often as needed (`inSampleSize`). A page of a
     * handwritten note is 1200×1800 pixels (docs/09-zoom.md): about 8 MB decoded whole, with 64 MB for the app.
     */
    private class FitWidthDecoder(private val resources: Resources) : MediaDecoder() {

        override fun decode(contentType: String?, inputStream: InputStream): Drawable {
            val bytes = inputStream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= resources.displayMetrics.widthPixels) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("Not an image")
            return BitmapDrawable(resources, bitmap)
        }

        override fun supportedTypes(): Collection<String> = emptySet()
    }

    private const val HEADING_SIZE = 1.25f
}
