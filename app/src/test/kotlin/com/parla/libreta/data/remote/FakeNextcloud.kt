package com.parla.libreta.data.remote

import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.TreeMap
import java.util.TreeSet

/**
 * In-memory WebDAV server that behaves like Nextcloud for the requests the app makes: ETags on every file,
 * If-Match / If-None-Match on PUT and DELETE, MKCOL, 409 when the parent folder is missing, percent-encoded hrefs.
 */
class FakeNextcloud(
    private val user: String = "alice",
    private val password: String = "app-password",
) : Dispatcher() {

    private class StoredFile(val content: ByteArray, val etag: String)

    private val files = TreeMap<String, StoredFile>()
    private val folders = TreeSet(listOf(""))
    private var nextEtag = 1

    /** Set to false to imitate a server that sends no ETag header on PUT. */
    var sendEtagOnWrite = true

    /** Set to false to imitate a missing `Notes/` folder. */
    var notesFolderExists = true

    /** Files open in an editor on the web: writing or deleting them answers 423 Locked. */
    val locked = mutableSetOf<String>()

    val basePath = "/remote.php/dav/files/$user/Notes/"

    fun start(server: MockWebServer) {
        server.setDispatcher(this)
    }

    // Test helpers acting "on the server", like another client would.

    fun put(relative: String, content: String) = putBytes(relative, content.toByteArray(Charsets.UTF_8))

    fun putBytes(relative: String, content: ByteArray) {
        relative.substringBeforeLast('/', "").let { if (it.isNotEmpty()) addFolders(it) }
        files[relative] = StoredFile(content, newEtag())
    }

    fun remove(relative: String) {
        files.remove(relative)
    }

    fun removeFolder(relative: String) {
        folders.removeAll { it == relative || it.startsWith("$relative/") }
        files.keys.removeAll { it.startsWith("$relative/") }
    }

    fun contentOf(relative: String): String? = files[relative]?.content?.let { String(it, Charsets.UTF_8) }

    fun bytesOf(relative: String): ByteArray? = files[relative]?.content

    fun etagOf(relative: String): String? = files[relative]?.etag

    fun snapshot(): Map<String, String> = files.mapValues { String(it.value.content, Charsets.UTF_8) }

    fun hasFolder(relative: String) = relative in folders

    override fun dispatch(request: RecordedRequest): MockResponse {
        val expected = okhttp3.Credentials.basic(user, password)
        if (request.getHeader("Authorization") != expected) return MockResponse().setResponseCode(401)
        val segments = request.requestUrl.pathSegments().filter { it.isNotEmpty() }
        val baseSegments = basePath.split('/').filter { it.isNotEmpty() }
        if (segments.take(baseSegments.size) != baseSegments) return MockResponse().setResponseCode(404)
        if (!notesFolderExists) return MockResponse().setResponseCode(404)
        val relative = segments.drop(baseSegments.size).joinToString("/")
        return when (request.method) {
            "PROPFIND" -> propfind(relative, request.getHeader("Depth"))
            "GET" -> files[relative]?.let { MockResponse().setBody(Buffer().write(it.content)).setHeader("ETag", it.etag) } ?: status(404)
            "PUT" -> put(relative, request)
            "DELETE" -> delete(relative, request)
            "MKCOL" -> mkcol(relative)
            else -> status(405)
        }
    }

    private fun propfind(relative: String, depth: String?): MockResponse {
        val file = files[relative]
        val body = StringBuilder("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
        when {
            file != null -> body.append(fileResponse(relative, file))
            relative in folders -> {
                body.append(folderResponse(relative))
                if (depth == "1") {
                    childrenOf(relative).forEach { child ->
                        files[child]?.let { body.append(fileResponse(child, it)) } ?: body.append(folderResponse(child))
                    }
                }
            }
            else -> return status(404)
        }
        body.append("</d:multistatus>")
        return MockResponse().setResponseCode(207).setBody(body.toString())
    }

    private fun put(relative: String, request: RecordedRequest): MockResponse {
        if (parentOf(relative) !in folders) return status(409)
        if (relative in locked) return status(423)
        val current = files[relative]
        if (!preconditionHolds(current, request)) return status(412)
        val stored = StoredFile(request.body.readByteArray(), newEtag())
        files[relative] = stored
        val response = status(if (current == null) 201 else 204)
        return if (sendEtagOnWrite) response.setHeader("ETag", stored.etag) else response
    }

    private fun delete(relative: String, request: RecordedRequest): MockResponse {
        val current = files[relative] ?: return status(404)
        if (relative in locked) return status(423)
        if (!preconditionHolds(current, request)) return status(412)
        files.remove(relative)
        return status(204)
    }

    private fun mkcol(relative: String): MockResponse = when {
        relative in folders || relative in files -> status(405)
        parentOf(relative) !in folders -> status(409)
        else -> {
            folders += relative
            status(201)
        }
    }

    private fun preconditionHolds(current: StoredFile?, request: RecordedRequest): Boolean {
        request.getHeader("If-None-Match")?.let { if (it == "*" && current != null) return false }
        request.getHeader("If-Match")?.let { if (current == null || current.etag != it) return false }
        return true
    }

    private fun childrenOf(folder: String): List<String> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val names = (files.keys + folders).filter { it.startsWith(prefix) && it != folder }
            .map { prefix + it.removePrefix(prefix).substringBefore('/') }
        return names.distinct()
    }

    private fun fileResponse(relative: String, file: StoredFile) =
        "<d:response><d:href>${hrefOf(relative, folder = false)}</d:href><d:propstat><d:prop>" +
            "<d:getetag>${file.etag}</d:getetag><d:resourcetype/></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun folderResponse(relative: String) =
        "<d:response><d:href>${hrefOf(relative, folder = true)}</d:href><d:propstat><d:prop>" +
            "<d:getetag>\"folder-${relative.hashCode()}\"</d:getetag><d:resourcetype><d:collection/></d:resourcetype>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun hrefOf(relative: String, folder: Boolean): String {
        val builder = HttpUrl.get("http://server$basePath").newBuilder()
        if (relative.isNotEmpty()) relative.split('/').forEach { builder.addPathSegment(it) }
        if (folder && relative.isNotEmpty()) builder.addPathSegment("")
        return builder.build().encodedPath()
    }

    private fun addFolders(relative: String) {
        val levels = relative.split('/')
        for (n in 1..levels.size) folders += levels.take(n).joinToString("/")
    }

    private fun parentOf(relative: String) = relative.substringBeforeLast('/', "")

    private fun newEtag() = "\"etag-${nextEtag++}\""

    private fun status(code: Int) = MockResponse().setResponseCode(code)
}
