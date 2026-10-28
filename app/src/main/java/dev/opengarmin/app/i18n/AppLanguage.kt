package dev.opengarmin.app.i18n

import java.util.Locale

/**
 * Languages the app can display. Adding a language is a two-step change:
 * add an entry here, then provide a catalog in [AppStrings]. Anything that is
 * not translated falls back to the English source string.
 */
enum class AppLanguage(
    val tag: String,
    /** Name shown in the language picker, in the language itself. */
    val nativeName: String,
    val locale: Locale,
) {
    SYSTEM("system", "System default", Locale.getDefault()),
    ENGLISH("en", "English", Locale.ENGLISH),
    ITALIAN("it", "Italiano", Locale.ITALIAN);

    /**
     * The concrete language used for this selection. [SYSTEM] follows the
     * device language when we have a catalog for it, otherwise English.
     * [deviceLocale] is captured before we ever override the process locale,
     * so switching back to "System default" is deterministic.
     */
    fun resolve(): AppLanguage = when (this) {
        SYSTEM -> if (deviceLocale.language == ITALIAN.locale.language) ITALIAN else ENGLISH
        else -> this
    }

    companion object {
        private val deviceLocale: Locale = Locale.getDefault()

        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM

        internal fun systemLocale(): Locale = deviceLocale
    }
}
