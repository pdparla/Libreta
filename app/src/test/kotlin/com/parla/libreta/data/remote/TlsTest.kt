package com.parla.libreta.data.remote

import com.parla.libreta.core.note.StoreUnavailableException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The TLS policy end to end over real HTTPS on the JVM, with certificates shaped like a typical home server's:
 * issued by a private CA, with the name only in the CN and no subject alternative names.
 */
class TlsTest {

    private val ourCa = ca("Home CA")
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun ca(name: String) = HeldCertificate.Builder().certificateAuthority(0).commonName(name).rsa2048().build()

    private fun serverCertificate(commonName: String, issuer: HeldCertificate) =
        HeldCertificate.Builder().commonName(commonName).signedBy(issuer).rsa2048().build()

    private fun serve(certificate: HeldCertificate, issuer: HeldCertificate) {
        val handshake = HandshakeCertificates.Builder().heldCertificate(certificate, issuer.certificate()).build()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"/>"""))
        server.start()
    }

    /** A client set up exactly as in the app, with the JDK provider instead of Conscrypt. */
    private fun remote(certificateName: String, caPem: String? = ourCa.certificatePem()): NextcloudWebDavSync {
        val factory = NextcloudClientFactory(caPem = { caPem }, provider = { null }, userAgent = "test")
        val config = ServerConfig(
            serverUrl = server.url("/").toString(),
            user = "alice",
            appPassword = "secret",
            certificateName = certificateName,
        )
        return factory.create(config)
    }

    private fun failureOf(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: StoreUnavailableException) {
            return e.cause!!
        }
        fail("Expected the connection to be refused")
        throw AssertionError()
    }

    @Test
    fun `server certificate from our CA is accepted by its common name although we dial an IP-like host`() {
        serve(serverCertificate("nextcloud.home.arpa", ourCa), ourCa)

        remote(certificateName = "nextcloud.home.arpa").checkConnection()

        val request = server.takeRequest()
        assertEquals("TLSv1.2", request.tlsVersion.javaName())
        assertTrue(request.handshake!!.cipherSuite().javaName().contains("GCM"))
    }

    @Test
    fun `without a certificate name the standard host check refuses a certificate without SAN`() {
        serve(serverCertificate("nextcloud.home.arpa", ourCa), ourCa)

        assertTrue(failureOf { remote(certificateName = "").checkConnection() } is SSLPeerUnverifiedException)
    }

    @Test
    fun `certificate from our CA for another name is refused`() {
        serve(serverCertificate("impostor.local", ourCa), ourCa)

        assertTrue(failureOf { remote(certificateName = "nextcloud.home.arpa").checkConnection() } is SSLPeerUnverifiedException)
    }

    @Test
    fun `certificate with the right name from another CA is refused`() {
        val otherCa = ca("Some Other CA")
        serve(serverCertificate("nextcloud.home.arpa", otherCa), otherCa)

        assertTrue(failureOf { remote(certificateName = "nextcloud.home.arpa").checkConnection() } is SSLHandshakeException)
    }

    @Test
    fun `names are read from the common name and the DNS alternative names`() {
        val withSan = HeldCertificate.Builder().commonName("cn.local").addSubjectAlternativeName("san.local")
            .signedBy(ourCa).rsa2048().build()

        assertEquals(listOf("san.local", "cn.local"), CertificateNameVerifier.namesOf(withSan.certificate()))
    }

    @Test
    fun `without an imported CA the system roots are used and a private CA is refused`() {
        serve(serverCertificate("nextcloud.home.arpa", ourCa), ourCa)

        assertTrue(failureOf { remote(certificateName = "nextcloud.home.arpa", caPem = null).checkConnection() } is SSLHandshakeException)
    }

    @Test
    fun `a newly imported CA takes effect on the next client`() {
        serve(serverCertificate("nextcloud.home.arpa", ourCa), ourCa)
        var imported: String? = null
        val factory = NextcloudClientFactory(caPem = { imported }, provider = { null }, userAgent = "test")
        val config = ServerConfig(server.url("/").toString(), "alice", "secret", certificateName = "nextcloud.home.arpa")

        assertTrue(failureOf { factory.create(config).checkConnection() } is SSLHandshakeException)
        imported = ourCa.certificatePem()
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"/>"""))
        factory.create(config).checkConnection()
    }
}
