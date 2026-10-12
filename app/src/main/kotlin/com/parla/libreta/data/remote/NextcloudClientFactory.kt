package com.parla.libreta.data.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Builds the Nextcloud clients for a [ServerConfig], with the app's own TLS setup. */
class NextcloudClientFactory(
    private val socketFactory: SSLSocketFactory,
    private val trustManager: X509TrustManager,
    private val userAgent: String,
) {

    // Shared so every client built from it reuses one connection pool and dispatcher.
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .sslSocketFactory(socketFactory, trustManager)
        .connectionSpecs(listOf(Tls.CONNECTION_SPEC))
        .followRedirects(false)
        .build()

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
        val builder = baseClient.newBuilder().addInterceptor { chain ->
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
