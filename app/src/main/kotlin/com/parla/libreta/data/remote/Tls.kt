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
 * TLS setup that does not depend on the tablet's TLS: the protocol comes from the [Provider] passed in (Conscrypt
 * on the device), because KitKat's own TLS lacks the AES-GCM and ChaCha20 suites modern servers require. Each
 * client chooses its roots: a CA the user imported, or the system's ([systemTrustManager]). See docs/design/tls.md.
 */
object Tls {

    /** TLS 1.2 only, with forward-secret AEAD suites; many servers accept nothing older (docs/design/tls.md). */
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

    /** Trust manager for the roots in Android's (or, on the JVM, the JDK's) own trust store. */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
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
 * For a server reached by IP whose certificate names no IP address, or by a `.local` name KitKat cannot resolve
 * (it has no mDNS), the standard check cannot pass. The chain is still verified against the trusted roots, so
 * only a certificate those roots issued to that name is accepted; with an imported private CA as the only root,
 * that can only be the user's own server.
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
