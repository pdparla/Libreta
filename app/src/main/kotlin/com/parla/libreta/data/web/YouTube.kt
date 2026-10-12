package com.parla.libreta.data.web

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * YouTube links (docs/design/ai.md#youtube-videos). The tablet cannot play YouTube: its WebView is too old for the
 * player and there is no app, so a link shows a card with the video's title, channel and thumbnail, and a QR code to
 * watch it on a phone.
 */
object YouTubeLinks {

    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com")
    private val ID = Regex("""[A-Za-z0-9_-]{11}""")

    /**
     * The video id in [url]: `youtube.com/watch?v=…`, `youtu.be/…`, and `/shorts/`, `/live/`, `/embed/` or `/v/`
     * paths, on the usual hosts. Null for anything else, channels and playlists included.
     */
    fun videoId(url: String): String? {
        val parsed = HttpUrl.parse(url.trim()) ?: return null
        val host = parsed.host().lowercase()
        val segments = parsed.pathSegments().filter { it.isNotEmpty() }
        val id = when {
            host == "youtu.be" -> segments.firstOrNull()
            host !in HOSTS -> null
            segments.firstOrNull() == "watch" -> parsed.queryParameter("v")
            segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v") -> segments[1]
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** The short address of video [id], for the QR code and the clipboard. */
    fun shortUrl(id: String): String = "https://youtu.be/$id"
}

/** What YouTube says about a video: null fields when it would not say (a private or removed video). */
class VideoInfo(val title: String?, val channel: String?)

/**
 * Title, channel and thumbnail of a video, from YouTube's public oEmbed endpoint and its image host. No account and
 * no key; YouTube only learns that someone looked at the link.
 */
class YouTubeClient(
    private val http: OkHttpClient,
    private val site: HttpUrl = HttpUrl.parse("https://www.youtube.com/")!!,
    private val images: HttpUrl = HttpUrl.parse("https://i.ytimg.com/")!!,
) {

    /** @throws IOException if YouTube cannot be reached; a video it will not describe gives empty [VideoInfo]. */
    fun info(id: String): VideoInfo {
        val url = site.newBuilder().addPathSegment("oembed")
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
            .addQueryParameter("format", "json")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code() in 400..499) return VideoInfo(null, null)
            if (!response.isSuccessful) throw IOException("YouTube answered ${response.code()}")
            val json = try {
                JSONObject(response.body()!!.string())
            } catch (e: JSONException) {
                throw IOException("Not oEmbed", e)
            }
            return VideoInfo(json.optString("title").ifBlank { null }, json.optString("author_name").ifBlank { null })
        }
    }

    /** The 320×180 thumbnail (JPEG), small enough for the tablet's memory. */
    fun thumbnail(id: String): ByteArray {
        val url = images.newBuilder().addPathSegment("vi").addPathSegment(id).addPathSegment("mqdefault.jpg").build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("No thumbnail: ${response.code()}")
            return response.body()!!.bytes()
        }
    }
}
