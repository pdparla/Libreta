package com.parla.libreta.data.remote

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import java.io.InputStream
import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * TLS setup that does not depend on the tablet: the only trusted root is the CA bundled with the app, and the
 * protocol comes from the [Provider] passed in (Conscrypt on the device), because KitKat's own TLS lacks the
 * AES-GCM and ChaCha20 suites the server requires.
 */
object Tls {

    /** TLS 1.2 only, with forward-secret AEAD suites; the server accepts nothing older (docs/00-diagnostico.md). */
    val CONNECTION_SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        )
        .build()

    /** Trust manager that accepts only chains issued by the PEM certificates in [caCertificates]. */
    fun trustManagerFor(caCertificates: InputStream): X509TrustManager {
        val certificates = caCertificates.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        require(certificates.isNotEmpty()) { "No CA certificate found" }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates.forEachIndexed { i, certificate -> keyStore.setCertificateEntry("ca$i", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    /** Socket factory from [provider] (null for the platform default) that trusts only [trustManager]. */
    fun socketFactory(trustManager: X509TrustManager, provider: Provider?): SSLSocketFactory {
        val context = if (provider == null) SSLContext.getInstance("TLS") else SSLContext.getInstance("TLS", provider)
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        return context.socketFactory
    }
}

/**
 * Accepts the server when its certificate is issued to [expectedName], whatever host or IP address was dialled.
 *
 * The tablet cannot resolve `nextcloud.local` (no mDNS on KitKat) and the certificate names no IP address, so
 * the standard check cannot pass. This is safe only together with [Tls.trustManagerFor]: when the only trusted
 * root is our own CA, a certificate for that name can only come from our server.
 */
class CertificateNameVerifier(private val expectedName: String) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: SSLPeerUnverifiedException) {
            null
        } ?: return false
        return namesOf(leaf).any { it.equals(expectedName, ignoreCase = true) }
    }

    companion object {
        private const val SAN_DNS_NAME = 2
        private val COMMON_NAME = Regex("""(?:^|,)CN=((?:\\.|[^,\\])+)""")

        /** DNS names in the subject alternative names, then the subject's common name. */
        fun namesOf(certificate: X509Certificate): List<String> {
            val dnsNames = certificate.subjectAlternativeNames.orEmpty()
                .filter { it.size >= 2 && it[0] == SAN_DNS_NAME }
                .mapNotNull { it[1] as? String }
            val subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253)
            val commonName = COMMON_NAME.find(subject)?.groupValues?.get(1)?.replace("\\", "")
            return dnsNames + listOfNotNull(commonName)
        }
    }
}
