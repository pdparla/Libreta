package com.parla.libreta.ui

import android.app.Activity
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
import com.parla.libreta.data.remote.HttpStatusException
import com.parla.libreta.data.remote.ServerConfig
import com.parla.libreta.sync.SyncService

/**
 * Server settings. The stored app password is never shown: leaving the field empty keeps it. "Probar conexión"
 * checks TLS, credentials and the notes folder without saving anything.
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
