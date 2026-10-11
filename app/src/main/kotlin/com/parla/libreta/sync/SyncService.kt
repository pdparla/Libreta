package com.parla.libreta.sync

import android.app.IntentService
import android.content.Context
import android.content.Intent
import com.parla.libreta.LibretaApp
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs syncs in a Service, so a sync started when the app goes to the background is not killed with the
 * activity. WorkManager needs API 21+; an IntentService serialises requests on its own worker thread.
 */
@Suppress("DEPRECATION") // IntentService is deprecated on API 30+, irrelevant on this API 19 device.
class SyncService : IntentService("SyncService") {

    override fun onHandleIntent(intent: Intent?) {
        // Requests that piled up while a sync was running are served by one more run, not one each.
        if (pendingRequests.decrementAndGet() > 0) return
        (application as LibretaApp).container.sync.syncNow()
    }

    companion object {
        private val pendingRequests = AtomicInteger()

        fun request(context: Context) {
            pendingRequests.incrementAndGet()
            context.startService(Intent(context, SyncService::class.java))
        }
    }
}
