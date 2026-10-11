package com.parla.libreta.sync

import android.os.Handler
import android.os.Looper
import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NoteRepository
import com.parla.libreta.core.sync.SyncEngine
import com.parla.libreta.core.sync.SyncReport
import com.parla.libreta.core.sync.SyncStrategy
import com.parla.libreta.data.local.FileSyncStateStore
import com.parla.libreta.data.remote.ServerConfig
import com.parla.libreta.data.settings.SettingsStore
import java.util.concurrent.CopyOnWriteArrayList

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object NotConfigured : SyncStatus()
    data object Running : SyncStatus()
    data class Finished(val report: SyncReport, val at: Long) : SyncStatus()
    data class Failed(val error: Throwable, val at: Long) : SyncStatus()
}

/**
 * Runs syncs with the current settings and tells the screens how they went. Listeners are called on the main
 * thread. Runs are serialised; the account can only be reset between runs.
 */
class SyncCoordinator(
    private val notes: NoteRepository,
    private val stateStore: FileSyncStateStore,
    private val settings: SettingsStore,
    private val conflictNamer: ConflictNamer,
    private val remoteFor: (ServerConfig) -> SyncStrategy,
) {

    private val mainThread = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SyncStatus) -> Unit>()

    @Volatile
    var status: SyncStatus = SyncStatus.Idle
        private set

    fun addListener(listener: (SyncStatus) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (SyncStatus) -> Unit) {
        listeners -= listener
    }

    /** Runs one sync on the calling thread, which must not be the main one. */
    @Synchronized
    fun syncNow() {
        val config = settings.load()
        if (!config.isComplete) return publish(SyncStatus.NotConfigured)
        publish(SyncStatus.Running)
        val outcome = try {
            val report = SyncEngine(notes, remoteFor(config), stateStore, conflictNamer).sync()
            SyncStatus.Finished(report, System.currentTimeMillis())
        } catch (e: Exception) {
            SyncStatus.Failed(e, System.currentTimeMillis())
        } catch (e: LinkageError) {
            // Conscrypt's native library failed to load.
            SyncStatus.Failed(e, System.currentTimeMillis())
        }
        publish(outcome)
    }

    /** Saves [config]; if it points at another set of notes, forgets the old sync state first. */
    @Synchronized
    fun changeAccount(config: ServerConfig) {
        if (config.accountKey != settings.load().accountKey) stateStore.clear()
        settings.save(config)
    }

    private fun publish(newStatus: SyncStatus) {
        status = newStatus
        mainThread.post { listeners.forEach { it(newStatus) } }
    }
}
