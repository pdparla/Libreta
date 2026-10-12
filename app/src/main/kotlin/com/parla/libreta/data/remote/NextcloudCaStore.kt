package com.parla.libreta.data.remote

import com.parla.libreta.data.local.writeAtomically
import java.io.File
import java.security.cert.CertificateException
import javax.security.auth.x500.X500Principal

/**
 * The CA certificates the user imported for Nextcloud with `scripts/nextcloud-ca.sh` (docs/setup/nextcloud.md), in
 * the app's private storage. When there are some, they are the only roots trusted for Nextcloud; when there are
 * none, Android's own trust store is used (see [NextcloudClientFactory]).
 */
class NextcloudCaStore(private val file: File) {

    /** The imported PEM, or null to use the system's certificates. */
    fun load(): String? = if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null

    /**
     * Replaces the imported certificates with [pem]; a blank [pem] removes them.
     *
     * @throws IllegalArgumentException if [pem] is not blank and holds no readable certificate.
     */
    fun save(pem: String) {
        if (pem.isBlank()) {
            file.delete()
            return
        }
        subjectsOf(pem)
        writeAtomically(file, pem)
    }

    /** Common names (or whole subjects) of the imported certificates; empty when there are none. */
    fun subjects(): List<String> = load()?.let { subjectsOf(it) }.orEmpty()

    companion object {
        private val COMMON_NAME = Regex("""(?:^|,)CN=((?:\\.|[^,\\])+)""")

        /** @throws IllegalArgumentException if [pem] holds no readable certificate. */
        fun subjectsOf(pem: String): List<String> {
            val issuers = try {
                Tls.trustManagerFor(pem.byteInputStream()).acceptedIssuers
            } catch (e: CertificateException) {
                throw IllegalArgumentException("Not a PEM certificate", e)
            }
            return issuers.map { certificate ->
                val subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253)
                COMMON_NAME.find(subject)?.groupValues?.get(1)?.replace("\\", "") ?: subject
            }
        }
    }
}
