package com.parla.libreta.data.stt

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.net.ssl.SSLHandshakeException

/** The dictation client over real HTTPS on the JVM, with a self-signed certificate like the service's. */
class SttClientTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val server = MockWebServer()
    private val serviceCertificate = selfSigned()

    @After
    fun tearDown() = server.shutdown()

    private fun selfSigned() = HeldCertificate.Builder().commonName("libreta-stt").addSubjectAlternativeName("localhost").ecdsa256().build()

    private fun serve(certificate: HeldCertificate, vararg responses: MockResponse) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        responses.forEach(server::enqueue)
        server.start()
    }

    /** A client trusting [trusted], as `scripts/stt-pair.sh` leaves it, with the JDK provider instead of Conscrypt. */
    private fun client(trusted: HeldCertificate): SttClient {
        val store = SttSettingsStore(File(dir.root, "stt.json"))
        store.save(SttSettings(server.url("/").toString().replace("127.0.0.1", "localhost"), "tok", trusted.certificatePem()))
        return SttClient(store, provider = { null })
    }

    private fun wav() = File(dir.root, "a.wav").apply { writeBytes(WavRecorder.header(0)) }

    @Test
    fun `sends the recording with the token and returns the text`() {
        serve(serviceCertificate, MockResponse().setBody("""{"text":" Hola, Ana. ","duration":1.5,"took":0.4}"""))

        assertEquals("Hola, Ana.", client(serviceCertificate).transcribe(wav()))

        val request = server.takeRequest()
        assertEquals("/v1/transcribe", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("audio/wav", request.getHeader("Content-Type"))
        assertEquals(44L, request.bodySize)
    }

    @Test
    fun `a refused token says so`() {
        serve(serviceCertificate, MockResponse().setResponseCode(401))

        try {
            client(serviceCertificate).transcribe(wav())
            fail()
        } catch (e: SttUnauthorizedException) {
            // expected
        }
    }

    @Test
    fun `any other certificate is refused`() {
        serve(selfSigned(), MockResponse().setBody("""{"text":"x"}"""))

        try {
            client(serviceCertificate).transcribe(wav())
            fail()
        } catch (e: SSLHandshakeException) {
            // expected
        }
    }

    @Test
    fun `without pairing there is nothing to call`() {
        try {
            SttClient(SttSettingsStore(File(dir.root, "none.json")), provider = { null }).transcribe(wav())
            fail()
        } catch (e: SttNotConfiguredException) {
            // expected
        }
    }

    @Test
    fun `settings never print the token and must be HTTPS`() {
        assertFalse(SttSettings("https://h:8443", "secret-token", "-----BEGIN CERTIFICATE-----").toString().contains("secret"))
        try {
            SttSettings.fromJson("""{"url":"http://h:8443","token":"t","cert":"-----BEGIN CERTIFICATE-----"}""")
            fail()
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `the WAV header describes 16 kHz 16-bit mono PCM`() {
        val header = WavRecorder.header(32000)

        assertEquals(44, header.size)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertEquals(36 + 32000, le32(header, 4))
        assertEquals(1, le16(header, 20)) // PCM
        assertEquals(1, le16(header, 22)) // mono
        assertEquals(16000, le32(header, 24))
        assertEquals(32000, le32(header, 28)) // bytes per second
        assertEquals(16, le16(header, 34))
        assertEquals(32000, le32(header, 40))
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)
}
