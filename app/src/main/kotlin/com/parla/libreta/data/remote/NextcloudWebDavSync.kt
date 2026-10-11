package com.parla.libreta.data.remote

import com.parla.libreta.core.note.NoteEntry
import com.parla.libreta.core.note.NoteLockedException
import com.parla.libreta.core.note.NoteNotFoundException
import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.PreconditionFailedException
import com.parla.libreta.core.note.StoreUnavailableException
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.VersionedContent
import com.parla.libreta.core.note.WritePrecondition
import com.parla.libreta.core.sync.SyncStrategy
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.xml.sax.SAXException
import java.io.IOException

/** The server answered with an unexpected HTTP status. */
class HttpStatusException(val code: Int, val method: String, url: HttpUrl) :
    IOException("$method ${url.encodedPath()} -> HTTP $code")

/**
 * [SyncStrategy] over WebDAV on a Nextcloud `Notes/` folder, the same files the Nextcloud Notes app shows.
 *
 * - Versions are the server's ETags (PROPFIND `getetag`, and the `ETag` header of GET and PUT).
 * - Preconditions travel as `If-Match` / `If-None-Match: *`, so the server rejects a write that lost a race.
 * - Categories are folders; they are created with MKCOL when a note is written into a new one.
 *
 * @param notesUrl collection holding the notes, e.g. `https://host/remote.php/dav/files/USER/Notes/`.
 */
class NextcloudWebDavSync(
    private val client: OkHttpClient,
    private val notesUrl: HttpUrl,
) : SyncStrategy {

    override val name = "Nextcloud WebDAV"

    private val rootSegments = segmentsOf(notesUrl)

    /** Category folders known to exist on the server; "" is the notes folder itself. */
    private val knownFolders = mutableSetOf("")

    private sealed class PutResult {
        class Stored(val etag: Version?) : PutResult()
        object FolderMissing : PutResult()
    }

    override fun checkConnection() {
        propfind(folderUrl(""), depth = 0) ?: throw notesFolderMissing()
    }

    /** Walks the folder tree one level at a time; Nextcloud may refuse `Depth: infinity`. */
    override fun list(): List<NoteEntry> {
        val notes = mutableListOf<NoteEntry>()
        val pending = ArrayDeque(listOf(""))
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            // A missing notes folder must never look like "every note was deleted". A missing category was
            // deleted while we were walking, and its notes are gone indeed.
            val resources = propfind(folderUrl(folder), depth = 1)
                ?: if (folder.isEmpty()) throw notesFolderMissing() else continue
            for (resource in resources) {
                val relative = relativePathOf(resource.href) ?: continue
                if (relative == folder) continue
                if (resource.isCollection) {
                    // Categories and `.attachments.*` folders; other hidden folders are not part of the notes tree.
                    if (!NotePath.isSyncedFolder(relative)) continue
                    knownFolders += relative
                    pending += relative
                } else {
                    val path = NotePath.parseOrNull(relative) ?: continue
                    val etag = resource.etag ?: throw StoreUnavailableException("No ETag for $relative")
                    notes += NoteEntry(path, Version(etag))
                }
            }
        }
        return notes
    }

    override fun read(path: NotePath): VersionedContent {
        val url = noteUrl(path)
        val (content, etag) = execute(Request.Builder().url(url).get().build()) { response ->
            when (response.code()) {
                200 -> response.body()!!.bytes() to etagOf(response)
                404 -> throw NoteNotFoundException(path)
                else -> throw statusError(response)
            }
        }
        return VersionedContent(content, etag ?: currentEtag(url))
    }

    override fun write(path: NotePath, content: ByteArray, precondition: WritePrecondition): Version {
        ensureFolder(path.category)
        var result = put(path, content, precondition)
        if (result is PutResult.FolderMissing && path.category.isNotEmpty()) {
            // The category was deleted on the server since it was listed: create it again and retry once.
            knownFolders.removeAll { it == path.category || it.startsWith(path.category + "/") }
            ensureFolder(path.category)
            result = put(path, content, precondition)
        }
        return when (result) {
            is PutResult.Stored -> result.etag ?: currentEtag(noteUrl(path))
            PutResult.FolderMissing -> throw notesFolderMissing()
        }
    }

    override fun delete(path: NotePath, precondition: WritePrecondition) {
        val request = Request.Builder().url(noteUrl(path)).delete().precondition(precondition).build()
        execute(request) { response ->
            when (response.code()) {
                200, 204 -> Unit
                404 -> throw NoteNotFoundException(path)
                412 -> throw PreconditionFailedException(path)
                423 -> throw NoteLockedException(path)
                else -> throw statusError(response)
            }
        }
    }

    private fun put(path: NotePath, content: ByteArray, precondition: WritePrecondition): PutResult {
        val request = Request.Builder()
            .url(noteUrl(path))
            .put(RequestBody.create(mediaTypeOf(path), content))
            .precondition(precondition)
            .build()
        return execute(request) { response ->
            when (response.code()) {
                200, 201, 204 -> PutResult.Stored(etagOf(response))
                404, 409 -> PutResult.FolderMissing
                412 -> throw PreconditionFailedException(path)
                423 -> throw NoteLockedException(path)
                else -> throw statusError(response)
            }
        }
    }

    /** Creates [category] and its parents if the server does not have them yet. */
    private fun ensureFolder(category: String) {
        if (category in knownFolders) return
        val levels = category.split('/')
        for (depth in 1..levels.size) {
            val folder = levels.take(depth).joinToString("/")
            if (folder in knownFolders) continue
            execute(Request.Builder().url(folderUrl(folder)).method("MKCOL", null).build()) { response ->
                // 405 Method Not Allowed: the folder already exists.
                if (response.code() != 201 && response.code() != 405) throw statusError(response)
            }
            knownFolders += folder
        }
    }

    /** The resources at [url] (itself first), or null if it does not exist. */
    private fun propfind(url: HttpUrl, depth: Int): List<DavResource>? {
        val request = Request.Builder()
            .url(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
            .build()
        return execute(request) { response ->
            when (response.code()) {
                207 -> MultiStatusParser.parse(response.body()!!.byteStream())
                404 -> null
                else -> throw statusError(response)
            }
        }
    }

    /** ETag from a PROPFIND, for servers that do not send one with GET or PUT. */
    private fun currentEtag(url: HttpUrl): Version {
        val etag = propfind(url, depth = 0)?.firstOrNull()?.etag ?: throw StoreUnavailableException("No ETag for $url")
        return Version(etag)
    }

    private inline fun <T> execute(request: Request, handle: (Response) -> T): T =
        try {
            client.newCall(request).execute().use(handle)
        } catch (e: IOException) {
            throw StoreUnavailableException("${request.method()} ${request.url().encodedPath()} failed: ${e.message}", e)
        } catch (e: SAXException) {
            throw StoreUnavailableException("Unreadable answer to ${request.method()} ${request.url().encodedPath()}", e)
        }

    private fun statusError(response: Response): StoreUnavailableException {
        val request = response.request()
        val error = HttpStatusException(response.code(), request.method(), request.url())
        return StoreUnavailableException(error.message ?: "HTTP ${response.code()}", error)
    }

    private fun notesFolderMissing() =
        StoreUnavailableException("Notes folder not found", HttpStatusException(404, "PROPFIND", notesUrl))

    private fun noteUrl(path: NotePath): HttpUrl = urlOf(path.value.split('/'), trailingSlash = false)

    private fun folderUrl(folder: String): HttpUrl =
        urlOf(if (folder.isEmpty()) emptyList() else folder.split('/'), trailingSlash = true)

    private fun urlOf(segments: List<String>, trailingSlash: Boolean): HttpUrl {
        val builder = notesUrl.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        if (trailingSlash && segments.isNotEmpty()) builder.addPathSegment("")
        return builder.build()
    }

    /** Path of [href] relative to the notes folder, decoded, or null if it lies outside. */
    private fun relativePathOf(href: String): String? {
        val segments = segmentsOf(notesUrl.resolve(href) ?: return null)
        if (segments.size < rootSegments.size || segments.subList(0, rootSegments.size) != rootSegments) return null
        return segments.drop(rootSegments.size).joinToString("/")
    }

    private fun segmentsOf(url: HttpUrl): List<String> = url.pathSegments().filter { it.isNotEmpty() }

    private fun mediaTypeOf(path: NotePath): MediaType = MEDIA_TYPES[path.extension.lowercase()] ?: OCTET_STREAM

    private fun etagOf(response: Response): Version? =
        (response.header("ETag") ?: response.header("OC-ETag"))?.takeIf { it.isNotEmpty() }?.let(::Version)

    private fun Request.Builder.precondition(precondition: WritePrecondition): Request.Builder = when (precondition) {
        WritePrecondition.None -> this
        WritePrecondition.MustNotExist -> header("If-None-Match", "*")
        is WritePrecondition.MustMatch -> header("If-Match", precondition.version.value)
    }

    private companion object {
        val MEDIA_TYPES: Map<String, MediaType> = mapOf(
            "md" to "text/markdown; charset=utf-8",
            "txt" to "text/plain; charset=utf-8",
            "json" to "application/json",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "m4a" to "audio/mp4",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "pdf" to "application/pdf",
        ).mapValues { MediaType.get(it.value) }
        val OCTET_STREAM: MediaType = MediaType.get("application/octet-stream")
        val XML: MediaType = MediaType.get("application/xml; charset=utf-8")
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>"""
    }
}
