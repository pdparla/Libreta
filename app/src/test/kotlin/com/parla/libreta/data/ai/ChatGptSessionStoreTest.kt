package com.parla.libreta.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatGptSessionStoreTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun session(token: String = "a") = ChatGptSession(
        "client", "host", "subject", "me@example.com", token, "refresh", "id", ChatGptSession.PLAN_USAGE_SCOPE, 1_800_000_000_000L,
    )

    private fun file(): File = File(dir.root, "chatgpt-session.json")

    @Test
    fun `without a file there is no session`() {
        assertNull(ChatGptSessionStore(file()).load())
    }

    @Test
    fun `a saved session is read back and kept until the file changes`() {
        val store = ChatGptSessionStore(file())
        store.save(session("one"))

        val first = store.load()!!
        assertEquals("one", first.accessToken)
        assertSame(first, store.load())
    }

    @Test
    fun `saving replaces what was loaded`() {
        val store = ChatGptSessionStore(file())
        store.save(session("one"))
        store.load()

        store.save(session("two"))

        assertEquals("two", store.load()!!.accessToken)
    }

    @Test
    fun `deleting forgets the session`() {
        val store = ChatGptSessionStore(file())
        store.save(session())
        store.load()

        store.delete()

        assertNull(store.load())
    }

    @Test
    fun `a file changed by someone else is read again`() {
        val store = ChatGptSessionStore(file())
        store.save(session("one"))
        store.load()

        file().writeText(session("three-longer").toJson())
        file().setLastModified(file().lastModified() + 5_000)

        assertEquals("three-longer", store.load()!!.accessToken)
    }

    @Test
    fun `a file removed behind the store's back means no session`() {
        val store = ChatGptSessionStore(file())
        store.save(session())
        store.load()

        file().delete()

        assertNull(store.load())
    }

    @Test
    fun `a corrupt file counts as no session and a later good one is read`() {
        val store = ChatGptSessionStore(file())
        file().writeText("not json")
        assertNull(store.load())
        assertNull(store.load())

        store.save(session("fixed"))

        assertEquals("fixed", store.load()!!.accessToken)
    }

    @Test
    fun `a session without plan usage counts as no session`() {
        val noPlan = ChatGptSession("c", "h", "s", "e", "a", "r", "i", "openid", 1_800_000_000_000L)
        file().writeText(noPlan.toJson())

        assertNull(ChatGptSessionStore(file()).load())
    }
}
