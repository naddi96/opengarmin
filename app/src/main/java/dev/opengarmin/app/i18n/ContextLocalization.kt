package dev.opengarmin.app.i18n

import android.content.Context
import dev.opengarmin.app.settings.PulseSettings

/** The language selected in Settings, read without constructing PulseSettings. */
fun Context.appLanguage(): AppLanguage {
    val prefs = getSharedPreferences(PulseSettings.PREFS_NAME, Context.MODE_PRIVATE)
    return AppLanguage.fromTag(prefs.getString(PulseSettings.KEY_APP_LANGUAGE, AppLanguage.SYSTEM.tag))
}

fun Context.localized(text: String, vararg args: Any?): String =
    AppStrings.format(text, appLanguage(), *args)
