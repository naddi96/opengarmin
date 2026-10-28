package dev.opengarmin.app.analysis

import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.i18n.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAnalysisPromptTest {

    private val activity = ActivityEntity(
        id = 7L,
        sourceKey = "run-7",
        fileName = "run.fit",
        startTime = 1_727_000_000L,
        sport = 1,
        subSport = 0,
        distanceMeters = 6_550.0,
        durationSeconds = 2_819.0,
        elapsedSeconds = 2_819.0,
        avgHeartRate = 144,
        maxHeartRate = 170,
        calories = 497,
        ascentMeters = 8,
        descentMeters = 12,
        importedAt = 1_727_100_000L,
    )

    @Test
    fun `system prompt defines the cached markdown layout`() {
        val prompt = ActivityAnalysisPrompt.SYSTEM

        assertTrue(prompt.contains("**Pacing**"))
        assertTrue(prompt.contains("**Heart rate**"))
        assertTrue(prompt.contains("**Load**"))
        assertTrue(prompt.contains("**Next:**"))
        assertTrue(prompt.contains("not medical advice"))
        assertTrue(prompt.contains("untrusted_data"))
    }

    @Test
    fun `user prompt includes the activity and profile without raw GPS`() {
        val prompt = ActivityAnalysisPrompt.user(
            activity = activity,
            insights = null,
            profile = ActivityAnalysisProfile(
                maxHeartRate = 190,
                restingHeartRate = 48,
                weightKg = 72.5,
            ),
        )

        assertTrue(prompt.contains("\"sport\": \"Running\""))
        assertTrue(prompt.contains("\"category\": \"Run\""))
        assertTrue(prompt.contains("\"max_heart_rate_bpm\": 190"))
        assertTrue(prompt.contains("\"resting_heart_rate_bpm\": 48"))
        assertTrue(prompt.contains("\"weight_kg\": 72.5"))
        assertFalse(prompt.contains("latitude"))
        assertFalse(prompt.contains("longitude"))
        assertTrue(prompt.contains("Analyze this activity."))
    }

    @Test
    fun `italian system prompt asks for italian output and labels`() {
        val prompt = ActivityAnalysisPrompt.system(AppLanguage.ITALIAN)

        assertTrue(prompt.contains("Italian (italiano)"))
        assertTrue(prompt.contains("**Passo**"))
        assertTrue(prompt.contains("**Frequenza cardiaca**"))
        assertTrue(prompt.contains("**Carico**"))
        assertTrue(prompt.contains("**Prossimo:**"))
    }

    @Test
    fun `italian user prompt asks in italian`() {
        val prompt = ActivityAnalysisPrompt.user(
            activity = activity,
            insights = null,
            language = AppLanguage.ITALIAN,
        )

        assertTrue(prompt.contains("Analizza questa attività."))
    }
}
