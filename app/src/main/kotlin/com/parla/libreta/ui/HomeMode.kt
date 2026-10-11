package com.parla.libreta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Single-app mode on Android 4.4, which has neither screen pinning nor device-owner provisioning (both API 21):
 * an activity-alias with the HOME category makes Libreta the home screen. Switching it off disables the alias, so
 * Android falls back to Samsung's launcher, which stays installed for that purpose.
 */
object HomeMode {

    private fun alias(context: Context) = ComponentName(context, "com.parla.libreta.ui.HomeAlias")

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(alias(context), state, PackageManager.DONT_KILL_APP)
    }

    /** Opens the home chooser, where the user picks Libreta and "Siempre" once. */
    fun showHomeChooser(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Whether [intent] started the list as the home screen rather than from the app drawer. */
    fun isHomeIntent(intent: Intent?): Boolean = intent?.hasCategory(Intent.CATEGORY_HOME) == true
}
