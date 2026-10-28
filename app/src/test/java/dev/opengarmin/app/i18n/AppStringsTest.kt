package dev.opengarmin.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsTest {

    @Test
    fun englishUsesSourceText() {
        assertEquals("Settings", AppStrings.translate("Settings", AppLanguage.ENGLISH))
        assertEquals("Untranslated copy", AppStrings.translate("Untranslated copy", AppLanguage.ENGLISH))
    }

    @Test
    fun italianTranslatesKnownCopy() {
        assertEquals("Impostazioni", AppStrings.translate("Settings", AppLanguage.ITALIAN))
        assertEquals("Oggi", AppStrings.translate("Today", AppLanguage.ITALIAN))
        assertEquals("Nessuna attività", AppStrings.translate("No activities yet", AppLanguage.ITALIAN))
    }

    @Test
    fun unknownCopyFallsBackToEnglish() {
        assertEquals(
            "A string that only exists in a future build",
            AppStrings.translate("A string that only exists in a future build", AppLanguage.ITALIAN),
        )
    }

    @Test
    fun formatterReplacesPlaceholders() {
        assertEquals(
            "Ultimo importato: Corsa mattutina",
            AppStrings.format("Last imported: %s", AppLanguage.ITALIAN, "Corsa mattutina"),
        )
        assertEquals(
            "3 di 10",
            AppStrings.format("%s of %s", AppLanguage.ITALIAN, 3, 10),
        )
    }

    @Test
    fun missingArgumentsDoNotFormatTheTemplate() {
        assertEquals(
            "Sincronizzazione…",
            AppStrings.format("Syncing…", AppLanguage.ITALIAN),
        )
    }

    @Test
    fun languageTagsRoundTrip() {
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag("it"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("system"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("not-a-language"))
    }

    @Test
    fun explicitLanguagesResolveToThemselves() {
        assertEquals(AppLanguage.ITALIAN, AppLanguage.ITALIAN.resolve())
        assertEquals(AppLanguage.ENGLISH, AppLanguage.ENGLISH.resolve())
    }

    @Test
    fun aiInstructionsFollowTheSelectedLanguage() {
        assertTrue(AppLanguage.ITALIAN.coachLanguageInstruction().contains("Italian (italiano)"))
        assertTrue(AppLanguage.ITALIAN.analysisLanguageInstruction().contains("**Passo**"))
        assertEquals("Analizza questa attività.", AppLanguage.ITALIAN.analysisRequest())
        assertTrue(AppLanguage.ENGLISH.coachLanguageInstruction().contains("English"))
        assertEquals("Analyze this activity.", AppLanguage.ENGLISH.analysisRequest())
    }

    @Test
    fun translationsKeepTheSamePlaceholderCountAsTheSource() {
        AppStrings.catalogFor(AppLanguage.ITALIAN).forEach { (source, translation) ->
            assertEquals(
                "Placeholder mismatch for: $source",
                placeholderCount(source),
                placeholderCount(translation),
            )
        }
    }

    private fun placeholderCount(text: String): Int =
        text.replace("%%", "")
            .let { Regex("""%(?:\d+\$)?[-+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""").findAll(it).count() }
}
