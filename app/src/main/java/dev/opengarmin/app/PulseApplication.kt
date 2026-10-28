package dev.opengarmin.app

import android.app.Application
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.i18n.AppLanguage
import dev.opengarmin.app.i18n.applyAppLocale
import dev.opengarmin.app.settings.PulseSettings

/**
 * Installs the local diagnostics log as early as possible so uncaught
 * exceptions from the UI and from WorkManager workers are captured.
 */
class PulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved UI language before any Activity, worker or
        // notification builds strings or formats a date.
        val prefs = getSharedPreferences(PulseSettings.PREFS_NAME, MODE_PRIVATE)
        applyAppLocale(
            AppLanguage.fromTag(prefs.getString(PulseSettings.KEY_APP_LANGUAGE, AppLanguage.SYSTEM.tag)),
        )
        Diagnostics.install(this)
    }
}
