package com.parla.libreta.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.web.YouTubeLinks
import io.nayuki.qrcodegen.QrCode

/**
 * The card for a YouTube link (docs/guide/assistant.md#youtube-videos): the tablet cannot play the video, so it shows
 * what the video is and a QR code to watch it on a phone. The QR code is drawn at once; the title, channel and
 * thumbnail arrive from YouTube in the background, and the card stays useful without them.
 */
fun Activity.showYouTubeCard(url: String) {
    val id = YouTubeLinks.videoId(url) ?: return
    val shortUrl = YouTubeLinks.shortUrl(id)
    val view = layoutInflater.inflate(R.layout.dialog_youtube, null)
    val thumbnail = view.findViewById<ImageView>(R.id.youtube_thumbnail)
    val title = view.findViewById<TextView>(R.id.youtube_title)
    val channel = view.findViewById<TextView>(R.id.youtube_channel)
    view.findViewById<ImageView>(R.id.youtube_qr).setImageBitmap(QrImage.of(shortUrl, resources.displayMetrics.density))
    view.findViewById<TextView>(R.id.youtube_hint).text = getString(R.string.youtube_hint, shortUrl)

    val viewer = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val builder = AlertDialog.Builder(this)
        .setView(view)
        .setPositiveButton(R.string.youtube_close, null)
        .setNeutralButton(R.string.youtube_copy) { _, _ ->
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(shortUrl, shortUrl))
            Toast.makeText(this, R.string.ai_copied, Toast.LENGTH_SHORT).show()
        }
    // Only if something on the tablet can open it: hidden or missing browsers and apps are the usual case.
    if (viewer.resolveActivity(packageManager) != null) builder.setNegativeButton(R.string.youtube_open) { _, _ -> startActivity(viewer) }
    val dialog = builder.show()

    container.background.execute({
        val info = container.youTube.info(id)
        val image = runCatching { container.youTube.thumbnail(id) }.getOrNull()
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }
        info to image
    }) { result ->
        if (isFinishing || isDestroyed || !dialog.isShowing) return@execute
        result
            .onSuccess { (info, image) ->
                info.title?.let { title.text = it }
                channel.text = info.channel ?: getString(R.string.youtube_unknown)
                image?.let {
                    thumbnail.setImageBitmap(it)
                    thumbnail.visibility = View.VISIBLE
                }
            }
            .onFailure { channel.text = getString(R.string.youtube_offline) }
    }
}

/** A QR code as a bitmap: black modules on white with the standard quiet zone, about 4 dp per module. */
object QrImage {

    private const val QUIET_ZONE = 4
    private const val MODULE_DP = 4f

    fun of(text: String, density: Float): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val scale = (MODULE_DP * density).toInt().coerceAtLeast(2)
        val modules = qr.size + 2 * QUIET_ZONE
        val pixels = IntArray(modules * scale * modules * scale) { Color.WHITE }
        val width = modules * scale
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (!qr.getModule(x, y)) continue
            for (dy in 0 until scale) {
                val row = ((y + QUIET_ZONE) * scale + dy) * width + (x + QUIET_ZONE) * scale
                pixels.fill(Color.BLACK, row, row + scale)
            }
        }
        return Bitmap.createBitmap(pixels, width, width, Bitmap.Config.RGB_565)
    }
}
