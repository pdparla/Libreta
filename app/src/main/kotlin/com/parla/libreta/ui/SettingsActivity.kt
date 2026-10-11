package com.parla.libreta.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.parla.libreta.R
import com.parla.libreta.container
import com.parla.libreta.data.ai.AiAssistant
import com.parla.libreta.data.remote.HttpStatusException
import com.parla.libreta.data.remote.ServerConfig
import com.parla.libreta.sync.SyncService

/**
 * Server settings. The stored app password is never shown: leaving the field empty keeps it. "Probar conexión"
 * checks TLS, credentials and the notes folder without saving anything. The AI section shows the ChatGPT session,
 * which can only be started from the PC (docs/07-ia.md), and closes it.
 */
class SettingsActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var folder: EditText
    private lateinit var certificateName: EditText
    private lateinit var status: TextView
    private lateinit var buttons: List<Button>
    private lateinit var stored: ServerConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        url = findViewById(R.id.settings_url)
        user = findViewById(R.id.settings_user)
        password = findViewById(R.id.settings_password)
        folder = findViewById(R.id.settings_folder)
        certificateName = findViewById(R.id.settings_certificate)
        status = findViewById(R.id.settings_status)
        val test = findViewById<Button>(R.id.settings_test)
        val save = findViewById<Button>(R.id.settings_save)
        buttons = listOf(test, save)

        stored = container.settings.load()
        if (savedInstanceState == null) {
            url.setText(stored.serverUrl)
            user.setText(stored.user)
            folder.setText(stored.notesFolder)
            certificateName.setText(stored.certificateName)
        }
        if (stored.appPassword.isNotEmpty()) password.setHint(R.string.settings_password_kept)

        test.setOnClickListener { testConnection() }
        save.setOnClickListener { saveAndSync() }

        val homeMode = findViewById<CheckBox>(R.id.settings_home_mode)
        homeMode.isChecked = HomeMode.isEnabled(this)
        homeMode.setOnCheckedChangeListener { _, enabled ->
            HomeMode.setEnabled(this, enabled)
            if (enabled) HomeMode.showHomeChooser(this)
        }
        findViewById<Button>(R.id.settings_android).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<Button>(R.id.settings_ai_sign_out).setOnClickListener { confirmSignOut() }
        showAiSession(null)
        findViewById<Button>(R.id.settings_stt_test).setOnClickListener { testDictation() }
        showDictation(null)
    }

    /** Where dictation goes, after [message] if there is one (the outcome of a test). */
    private fun showDictation(message: String?) {
        val settings = container.sttSettings.load()
        val state = if (settings != null) getString(R.string.settings_stt_configured, settings.url) else getString(R.string.stt_not_configured)
        findViewById<TextView>(R.id.settings_stt_status).text = listOfNotNull(message, state).joinToString("\n\n")
        findViewById<Button>(R.id.settings_stt_test).isEnabled = settings != null
    }

    private fun testDictation() {
        val button = findViewById<Button>(R.id.settings_stt_test)
        button.isEnabled = false
        findViewById<TextView>(R.id.settings_stt_status).setText(R.string.settings_stt_testing)
        container.sttBackground.execute({ container.stt.health() }) { result ->
            if (isDestroyed) return@execute
            showDictation(
                result.fold(
                    { getString(R.string.settings_stt_ok, it) },
                    { getString(R.string.settings_stt_failed, SyncMessages.describe(this, it)) },
                ),
            )
        }
    }

    /** The session state, after [message] if there is one (the outcome of signing out). */
    private fun showAiSession(message: String?) {
        val session = container.chatGptSessions.load()
        val state = if (session != null) {
            getString(
                R.string.settings_ai_signed_in,
                session.email,
                AiAssistant.CHAT_MODEL.name,
                AiAssistant.CHAT_MODEL.reasoningEffort,
                AiAssistant.CONVERT_MODEL.name,
                AiAssistant.CONVERT_MODEL.reasoningEffort,
            )
        } else {
            getString(R.string.settings_ai_signed_out)
        }
        findViewById<TextView>(R.id.settings_ai_status).text = listOfNotNull(message, state).joinToString("\n\n")
        findViewById<Button>(R.id.settings_ai_sign_out).isEnabled = session != null
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_ai_sign_out_confirm)
            .setPositiveButton(R.string.settings_ai_sign_out) { _, _ ->
                findViewById<Button>(R.id.settings_ai_sign_out).isEnabled = false
                container.aiBackground.execute({ container.chatGpt.signOut() }) { result ->
                    if (isDestroyed) return@execute
                    val revoked = result.getOrDefault(false)
                    showAiSession(getString(if (revoked) R.string.settings_ai_signed_out_revoked else R.string.settings_ai_signed_out_local))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    private fun formConfig() = ServerConfig(
        serverUrl = url.text.toString().trim(),
        user = user.text.toString().trim(),
        appPassword = password.text.toString().ifEmpty { stored.appPassword },
        notesFolder = folder.text.toString().trim(),
        certificateName = certificateName.text.toString().trim(),
    )

    private fun testConnection() {
        val config = formConfig()
        if (config.notesUrl() == null) return showStatus(getString(R.string.settings_invalid))
        busy(true)
        showStatus(getString(R.string.settings_testing))
        container.background.execute({
            // Use the folder the Nextcloud Notes app is configured with, so the notes show up there.
            val notesAppFolder = container.clients.notesAppSettings(config).notesPath()
            val effective = if (notesAppFolder != null) config.copy(notesFolder = notesAppFolder) else config
            container.clients.create(effective).checkConnection()
            notesAppFolder
        }) { result ->
            if (isDestroyed) return@execute
            busy(false)
            val notesAppFolder = result.getOrNull()
            if (notesAppFolder != null && notesAppFolder != folder.text.toString().trim()) folder.setText(notesAppFolder)
            val error = result.exceptionOrNull()
            showStatus(
                when {
                    error == null && notesAppFolder != null -> getString(R.string.settings_test_ok_with_folder, notesAppFolder)
                    error == null -> getString(R.string.settings_test_ok)
                    // Without a password nothing is sent, so a 401 proves that TLS worked.
                    config.appPassword.isEmpty() && (error.cause as? HttpStatusException)?.code == 401 ->
                        getString(R.string.settings_test_tls_only)
                    else -> SyncMessages.describe(this, error).replaceFirstChar { it.uppercase() }
                },
            )
        }
    }

    private fun saveAndSync() {
        val config = formConfig()
        if (!config.isComplete) return showStatus(getString(R.string.settings_incomplete))
        busy(true)
        // Waits for a running sync to finish before changing the account, hence off the main thread.
        container.background.execute({ container.sync.changeAccount(config) }) { result ->
            if (isDestroyed) return@execute
            busy(false)
            result
                .onSuccess {
                    SyncService.request(this)
                    finish()
                }
                .onFailure { showStatus(SyncMessages.describe(this, it)) }
        }
    }

    private fun busy(isBusy: Boolean) = buttons.forEach { it.isEnabled = !isBusy }

    private fun showStatus(text: String) {
        status.text = text
    }
}
