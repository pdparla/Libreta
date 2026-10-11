package com.parla.libreta

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.SystemToday
import com.parla.libreta.data.local.FileNoteRepository
import com.parla.libreta.data.local.FileSyncStateStore
import com.parla.libreta.data.remote.NextcloudClientFactory
import com.parla.libreta.data.remote.Tls
import com.parla.libreta.data.settings.SettingsStore
import com.parla.libreta.sync.SyncCoordinator
import com.parla.libreta.sync.SyncService
import com.parla.libreta.ui.BackgroundRunner
import com.parla.libreta.ui.ink.InkRenderer
import org.conscrypt.Conscrypt
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Composition root: the object graph, built once per process. */
class AppContainer(context: Context) {

    private val conflictNamer = ConflictNamer(SystemToday)

    val notes = FileNoteRepository(File(context.filesDir, "notes"))
    val noteService = NoteService(notes, conflictNamer)
    val inkNotes = InkNoteService(notes, noteService, InkRenderer, newFolderId = ::randomFolderId)
    val settings = SettingsStore(context)
    val background = BackgroundRunner(Executors.newSingleThreadExecutor())

    /**
     * Built on first use, off the main thread: loading Conscrypt's native library and parsing the CA take a
     * moment. Conscrypt is passed to the SSLContext explicitly rather than installed as the default provider, so
     * nothing else in the process changes.
     */
    val clients: NextcloudClientFactory by lazy {
        val trustManager = context.resources.openRawResource(R.raw.nextcloud_ca).use(Tls::trustManagerFor)
        NextcloudClientFactory(
            socketFactory = Tls.socketFactory(trustManager, Conscrypt.newProvider()),
            trustManager = trustManager,
            userAgent = "Libreta/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
        )
    }

    val sync = SyncCoordinator(
        notes = notes,
        stateStore = FileSyncStateStore(File(context.filesDir, "sync-state.tsv")),
        settings = settings,
        conflictNamer = conflictNamer,
        remoteFor = { config -> clients.create(config) },
    )
}

/** Id for a note's `.attachments.<id>` folder: 10 base-36 characters, unique enough for one person's notes. */
private fun randomFolderId(): String {
    val random = SecureRandom()
    return (1..10).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")
}

class LibretaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(SyncOnOpenAndClose())
    }

    /**
     * Syncs when the app comes to the foreground and when it leaves it. The editor saves in onPause, before its
     * onStop, so the closing sync already includes the last edit. Rotations do not count. Turning the screen off
     * stops the activity too, so as the home screen (single-app mode) Libreta still syncs on screen off and on.
     */
    private inner class SyncOnOpenAndClose : ActivityLifecycleCallbacks {
        private var started = 0
        private var recreating = false

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0 && !recreating) SyncService.request(this@LibretaApp)
            recreating = false
        }

        override fun onActivityStopped(activity: Activity) {
            recreating = activity.isChangingConfigurations
            if (--started == 0 && !recreating) SyncService.request(this@LibretaApp)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

val Activity.container: AppContainer get() = (application as LibretaApp).container
