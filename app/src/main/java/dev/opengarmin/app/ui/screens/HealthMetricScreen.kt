package dev.opengarmin.app.ui.screens

import androidx.compose.runtime.Composable
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.SleepStageEntity
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import java.time.LocalDate

/**
 * Routes a Health tile to its metric detail screen. Window and date selection
 * live inside each screen so switching metric does not reset the others.
 */
@Composable
fun HealthMetricScreen(
    metric: HealthMetric,
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    sleepStages: List<SleepStageEntity>,
    loadWellnessSamples: suspend (String) -> List<WellnessSampleEntity>,
    activities: List<ActivityEntity>,
    training: TrainingSummary,
    birthYear: Int?,
    biologicalSex: String?,
    onBack: () -> Unit,
    onOpenMetric: (HealthMetric) -> Unit,
) {
    val latestDate = HealthMetrics.latestDate(days) ?: LocalDate.now()
    when (metric) {
        HealthMetric.BODY_BATTERY -> BodyBatteryDetailScreen(
            days = days,
            samples = samples,
            activities = activities,
            latestDate = latestDate,
            onBack = onBack,
        )
        HealthMetric.SLEEP -> SleepDetailScreen(
            days = days,
            samples = samples,
            loadWellnessSamples = loadWellnessSamples,
            sleepStages = sleepStages,
            latestDate = latestDate,
            birthYear = birthYear,
            onBack = onBack,
            onOpenMetric = onOpenMetric,
        )
        HealthMetric.HRV -> HrvDetailScreen(
            days = days,
            samples = samples,
            latestDate = latestDate,
            onBack = onBack,
        )
        HealthMetric.STRESS -> StressDetailScreen(
            days = days,
            samples = samples,
            activities = activities,
            latestDate = latestDate,
            onBack = onBack,
        )
        HealthMetric.VO2_MAX -> Vo2MaxDetailScreen(
            training = training,
            birthYear = birthYear,
            biologicalSex = biologicalSex,
            onBack = onBack,
        )
    }
}
