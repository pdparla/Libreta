package com.parla.libreta.data.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.Provider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds the Nextcloud clients for a [ServerConfig], with the app's own TLS setup. They trust only the CA
 * certificates in [caPem] when it returns some (imported with scripts/nextcloud-ca.sh), and the system's
 * otherwise; a new import takes effect on the next client built.
 */
class NextcloudClientFactory(
    private val caPem: () -> String?,
    /** TLS provider (Conscrypt on the device); null for the platform default. */
    private val provider: () -> Provider?,
    private val userAgent: String,
    /** The app's shared base client: its connection pool and dispatcher are reused by every client built here. */
    private val base: () -> OkHttpClient = { OkHttpClient() },
) {

    /** The base client for the CA it was built with, rebuilt only when the imported CA changes. */
    private var cached: Pair<String?, OkHttpClient>? = null

    @Synchronized
    private fun baseClient(): OkHttpClient {
        val pem = caPem()
        cached?.let { (cachedPem, client) -> if (cachedPem == pem) return client }
        val trustManager = if (pem == null) Tls.systemTrustManager() else Tls.trustManagerFor(pem.byteInputStream())
        val client = base().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(Tls.socketFactory(trustManager, provider()), trustManager)
            .connectionSpecs(listOf(Tls.CONNECTION_SPEC))
            .followRedirects(false)
            .build()
        cached = pem to client
        return client
    }

    // One listing cache per notes URL (i.e. per account), kept across syncs: each sync builds a new client.
    private val listingCaches = ConcurrentHashMap<String, ListingCache>()

    /** @throws IllegalArgumentException if the configuration has no valid HTTPS URL or user. */
    fun create(config: ServerConfig): NextcloudWebDavSync {
        val notesUrl = requireNotNull(config.notesUrl()) { "Invalid server configuration: $config" }
        return NextcloudWebDavSync(clientFor(config), notesUrl, listingCaches.getOrPut(notesUrl.toString()) { ListingCache() })
    }

    /** @throws IllegalArgumentException if the configuration has no valid HTTPS URL. */
    fun notesAppSettings(config: ServerConfig): NotesAppSettings {
        val server = requireNotNull(HttpUrl.parse(config.serverUrl.trim())) { "Invalid server URL: $config" }
        return NotesAppSettings(clientFor(config), server)
    }

    private fun clientFor(config: ServerConfig): OkHttpClient {
        val builder = baseClient().newBuilder().addInterceptor { chain ->
            val request = chain.request().newBuilder().header("User-Agent", userAgent)
            // Without a password, send no credentials: a test of TLS alone must not count as a failed login.
            if (config.appPassword.isNotEmpty()) {
                request.header("Authorization", Credentials.basic(config.user.trim(), config.appPassword))
            }
            chain.proceed(request.build())
        }
        if (config.certificateName.isNotBlank()) {
            builder.hostnameVerifier(CertificateNameVerifier(config.certificateName.trim()))
        }
        return builder.build()
    }
}
