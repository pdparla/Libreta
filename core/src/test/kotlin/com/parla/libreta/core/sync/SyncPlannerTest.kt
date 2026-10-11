package com.parla.libreta.core.sync

import com.parla.libreta.core.note.NotePath
import com.parla.libreta.core.note.Version
import com.parla.libreta.core.note.WritePrecondition.MustMatch
import com.parla.libreta.core.note.WritePrecondition.MustNotExist
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {

    private val path = NotePath("Ideas.md")
    private val base = SyncBase(localVersion = Version("L0"), remoteVersion = Version("R0"))

    private fun plan(local: String?, remote: String?, base: SyncBase?): SyncAction? =
        SyncPlanner.plan(
            local = listOfNotNull(local?.let { path to Version(it) }).toMap(),
            remote = listOfNotNull(remote?.let { path to Version(it) }).toMap(),
            base = listOfNotNull(base?.let { path to it }).toMap(),
        ).singleOrNull()

    // Never synced before

    @Test fun `new local note is created remotely`() =
        assertEquals(SyncAction.Upload(path, MustNotExist), plan(local = "L1", remote = null, base = null))

    @Test fun `new remote note is created locally`() =
        assertEquals(SyncAction.Download(path, MustNotExist), plan(local = null, remote = "R1", base = null))

    @Test fun `note created on both sides is a conflict`() =
        assertEquals(SyncAction.ResolveConflict(path), plan(local = "L1", remote = "R1", base = null))

    // Synced before: (local change) x (remote change)

    @Test fun `unchanged on both sides needs nothing`() =
        assertEquals(null, plan(local = "L0", remote = "R0", base = base))

    @Test fun `remote edit is downloaded if the local copy is still the synced one`() =
        assertEquals(SyncAction.Download(path, MustMatch(Version("L0"))), plan(local = "L0", remote = "R1", base = base))

    @Test fun `remote deletion is applied locally if the local copy is still the synced one`() =
        assertEquals(SyncAction.DeleteLocal(path, Version("L0")), plan(local = "L0", remote = null, base = base))

    @Test fun `local edit is uploaded with If-Match on the synced ETag`() =
        assertEquals(SyncAction.Upload(path, MustMatch(Version("R0"))), plan(local = "L1", remote = "R0", base = base))

    @Test fun `edits on both sides are a conflict`() =
        assertEquals(SyncAction.ResolveConflict(path), plan(local = "L1", remote = "R1", base = base))

    @Test fun `local edit wins over remote deletion`() =
        assertEquals(SyncAction.Upload(path, MustNotExist), plan(local = "L1", remote = null, base = base))

    @Test fun `local deletion is applied remotely with If-Match on the synced ETag`() =
        assertEquals(SyncAction.DeleteRemote(path, Version("R0")), plan(local = null, remote = "R0", base = base))

    @Test fun `remote edit wins over local deletion`() =
        assertEquals(SyncAction.Download(path, MustNotExist), plan(local = null, remote = "R1", base = base))

    @Test fun `deleted on both sides only forgets the state`() =
        assertEquals(SyncAction.Forget(path), plan(local = null, remote = null, base = base))

    @Test fun `actions cover every known path in order`() {
        val a = NotePath("a.md")
        val b = NotePath("Cat/b.md")
        val c = NotePath("c.md")
        val actions = SyncPlanner.plan(
            local = mapOf(c to Version("L")),
            remote = mapOf(a to Version("R")),
            base = mapOf(b to base),
        )
        assertEquals(listOf(b, a, c), actions.map { it.path })
    }
}
