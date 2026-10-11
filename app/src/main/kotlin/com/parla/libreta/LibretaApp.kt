package com.parla.libreta

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.parla.libreta.core.ai.ConversationNotes
import com.parla.libreta.core.ink.InkNoteService
import com.parla.libreta.core.ink.InkTranscriptService
import com.parla.libreta.core.note.ConflictNamer
import com.parla.libreta.core.note.NoteService
import com.parla.libreta.core.note.NotebookIndex
import com.parla.libreta.core.note.SystemToday
import com.parla.libreta.data.ai.AiAssistant
import com.parla.libreta.data.ai.ChatGptAuth
import com.parla.libreta.data.ai.ChatGptSessionStore
import com.parla.libreta.data.ai.ResponsesClient
import com.parla.libreta.data.local.FileNoteRepository
import com.parla.libreta.data.stt.SttClient
import com.parla.libreta.data.stt.SttSettingsStore
import com.parla.libreta.data.local.FileSyncStateStore
import com.parla.libreta.data.remote.NextcloudClientFactory
import com.parla.libreta.data.remote.Tls
import com.parla.libreta.data.settings.SettingsStore
import com.parla.libreta.sync.SyncCoordinator
import com.parla.libreta.sync.SyncService
import com.parla.libreta.ui.BackgroundRunner
import com.parla.libreta.ui.ink.InkRenderer
import com.parla.libreta.ui.ink.InkTranscriber
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Composition root: the object graph, built once per process. */
class AppContainer(context: Context) {

    private val conflictNamer = ConflictNamer(SystemToday)

    /** Notes as files, one subfolder per category, like `Notas/` in Nextcloud. */
    val notesDir = File(context.filesDir, "notes")
    val notes = FileNoteRepository(notesDir)
    val noteService = NoteService(notes, conflictNamer)
    val inkNotes = InkNoteService(notes, noteService, InkRenderer, newFolderId = ::randomFolderId)
    val conversationNotes = ConversationNotes(notes, newFolderId = ::randomFolderId)
    val settings = SettingsStore(context)
    val background = BackgroundRunner(Executors.newSingleThreadExecutor())

    /** Model calls take seconds: their own thread, so they never hold up the settings screen's work. */
    val aiBackground = BackgroundRunner(Executors.newSingleThreadExecutor())

    /** The ChatGPT session file; cheap to read, so the UI can check it on the main thread. */
    val chatGptSessions = ChatGptSessionStore(File(context.filesDir, "chatgpt-session.json"))

    private val userAgent = "Libreta/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

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
            userAgent = userAgent,
        )
    }

    /**
     * Client for api.openai.com and auth.openai.com, built on first use off the main thread like [clients]. It
     * trusts only the public roots those hosts chain to (res/raw/openai_roots.pem, see docs/07-ia.md), never the
     * Nextcloud CA, and checks host names the standard way.
     */
    private val openAiHttp: OkHttpClient by lazy {
        val trustManager = context.resources.openRawResource(R.raw.openai_roots).use(Tls::trustManagerFor)
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Between streamed events; reasoning can keep the stream quiet for a while before the answer.
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(Tls.socketFactory(trustManager, Conscrypt.newProvider()), trustManager)
            .connectionSpecs(listOf(Tls.CONNECTION_SPEC))
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
    }

    val chatGpt: ChatGptAuth by lazy { ChatGptAuth(openAiHttp, chatGptSessions) }

    val ai: AiAssistant by lazy {
        AiAssistant(ResponsesClient(openAiHttp, chatGpt))
    }

    /** What the handwriting says, page by page, for search (docs/11-busqueda.md). */
    val transcripts = InkTranscriptService(notes)
    val notebookIndex = NotebookIndex(notes, inkNotes, transcripts, exists = notes::exists)
    val inkTranscriber = InkTranscriber(
        notes = notes,
        inkNotes = inkNotes,
        transcripts = transcripts,
        ai = { ai },
        isSignedIn = { chatGptSessions.load() != null },
        preferences = context.getSharedPreferences("search", Context.MODE_PRIVATE),
    )

    /** Dictation (docs/07-ia.md, part 2): the service's address, token and certificate, from scripts/stt-pair.sh. */
    val sttSettings = SttSettingsStore(File(context.filesDir, "stt.json"))
    val stt = SttClient(sttSettings, provider = { Conscrypt.newProvider() })
    val sttBackground = BackgroundRunner(Executors.newSingleThreadExecutor())

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
