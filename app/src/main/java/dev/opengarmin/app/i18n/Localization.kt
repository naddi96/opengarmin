package dev.opengarmin.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * The language selected by the user. [AppLanguage.SYSTEM] is resolved against
 * the device locale by [AppStrings.translate].
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.SYSTEM }

/** Translate an English source string in the current Compose language. */
@Composable
fun tr(text: String): String = AppStrings.translate(text, LocalAppLanguage.current)

/**
 * Translate a template that uses [java.util.Formatter] placeholders, for
 * example `tr("Last imported: %s", label)`. Translations may reorder
 * arguments with positional placeholders such as `%1$s`.
 */
@Composable
fun tr(text: String, vararg args: Any?): String =
    AppStrings.format(text, LocalAppLanguage.current, *args)

/** Change the process locale so dates, numbers and plural forms follow the UI. */
fun applyAppLocale(language: AppLanguage) {
    Locale.setDefault(
        when (language) {
            AppLanguage.SYSTEM -> AppLanguage.systemLocale()
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.ITALIAN -> Locale.ITALIAN
        },
    )
}
