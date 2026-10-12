package com.parla.libreta.data.remote

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NextcloudCaStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file by lazy { temp.root.resolve("nextcloud-ca.pem") }
    private val store by lazy { NextcloudCaStore(file) }

    private fun ca(name: String) = HeldCertificate.Builder().certificateAuthority(0).commonName(name).build().certificatePem()

    @Test
    fun `nothing imported means the system's certificates`() {
        assertNull(store.load())
        assertEquals(emptyList<String>(), store.subjects())
    }

    @Test
    fun `imported certificates are kept and named`() {
        val pem = ca("Home CA") + ca("Other CA")

        store.save(pem)

        assertEquals(pem, store.load())
        assertEquals(listOf("Home CA", "Other CA"), store.subjects())
    }

    @Test
    fun `a blank import removes the CA`() {
        store.save(ca("Home CA"))

        store.save("")

        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that is not a certificate is refused`() {
        store.save("-----BEGIN CERTIFICATE-----\nnot base64\n-----END CERTIFICATE-----\n")
    }

    @Test
    fun `a refused import keeps the previous CA`() {
        val pem = ca("Home CA")
        store.save(pem)

        runCatching { store.save("hello") }

        assertEquals(pem, store.load())
    }
}
