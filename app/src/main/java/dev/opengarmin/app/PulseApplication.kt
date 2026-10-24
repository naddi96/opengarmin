package dev.opengarmin.app

import android.app.Application
import dev.opengarmin.app.diagnostics.Diagnostics

/**
 * Installs the local diagnostics log as early as possible so uncaught
 * exceptions from the UI and from WorkManager workers are captured.
 */
class PulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
