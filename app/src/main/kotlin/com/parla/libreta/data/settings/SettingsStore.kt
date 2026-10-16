package com.parla.libreta.data.settings

import android.content.Context
import com.parla.libreta.data.remote.ServerConfig

/**
 * Server settings in the app's private SharedPreferences.
 *
 * The app password is stored in clear text inside the app's sandbox: the tablet is not rooted, `allowBackup` is
 * off, and the Android Keystore on API 19 offers no symmetric keys and can be wiped by Samsung's lock-screen changes.
 * The password is a revocable Nextcloud app password, never the account password.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val aiPrefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /** Whether a question circled on a handwritten page is answered on the page (docs/guide/assistant.md). */
    var answersCircledQuestions: Boolean
        get() = aiPrefs.getBoolean(KEY_CIRCLED_QUESTIONS, true)
        set(value) = aiPrefs.edit().putBoolean(KEY_CIRCLED_QUESTIONS, value).apply()

    fun load(): ServerConfig = ServerConfig(
        serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
        user = prefs.getString(KEY_USER, "").orEmpty(),
        appPassword = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        notesFolder = prefs.getString(KEY_FOLDER, ServerConfig.DEFAULT_NOTES_FOLDER).orEmpty(),
        certificateName = prefs.getString(KEY_CERTIFICATE_NAME, "").orEmpty(),
    )

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_URL, config.serverUrl.trim())
            .putString(KEY_USER, config.user.trim())
            .putString(KEY_PASSWORD, config.appPassword)
            .putString(KEY_FOLDER, config.notesFolder.trim())
            .putString(KEY_CERTIFICATE_NAME, config.certificateName.trim())
            .apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "app_password"
        const val KEY_FOLDER = "notes_folder"
        const val KEY_CERTIFICATE_NAME = "certificate_name"
        const val KEY_CIRCLED_QUESTIONS = "answer_circled_questions"
    }
}
