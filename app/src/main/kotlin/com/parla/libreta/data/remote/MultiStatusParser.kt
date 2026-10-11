package com.parla.libreta.data.remote

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** One `<d:response>` of a WebDAV PROPFIND answer. [href] is percent-encoded, as sent by the server. */
internal data class DavResource(val href: String, val etag: String?, val isCollection: Boolean)

/**
 * Streaming parser for `207 Multi-Status` bodies. SAX is used because it exists both on Android 4.4 and on the JVM
 * where the tests run, and it does not keep the whole document in memory.
 */
internal object MultiStatusParser {

    private const val DAV = "DAV:"

    fun parse(body: InputStream): List<DavResource> {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val handler = Handler()
        factory.newSAXParser().parse(body, handler)
        return handler.resources
    }

    private class Handler : DefaultHandler() {
        val resources = mutableListOf<DavResource>()
        private val text = StringBuilder()
        private var href: String? = null
        private var etag: String? = null
        private var isCollection = false

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (uri != DAV) return
            when (localName) {
                "response" -> {
                    href = null
                    etag = null
                    isCollection = false
                }
                "collection" -> isCollection = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri != DAV) return
            when (localName) {
                "href" -> if (href == null) href = text.toString().trim()
                // Properties the server lacks come back empty in a 404 propstat; ignore those.
                "getetag" -> text.toString().trim().takeIf { it.isNotEmpty() }?.let { etag = it }
                "response" -> href?.let { resources += DavResource(it, etag, isCollection) }
            }
        }
    }
}
