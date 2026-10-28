package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.TrainingPoint
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.Vo2Band
import dev.opengarmin.app.data.Vo2Window
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun Vo2MaxDetailScreen(
    training: TrainingSummary,
    birthYear: Int?,
    biologicalSex: String?,
    onBack: () -> Unit,
) {
    var windowName by rememberSaveable { mutableStateOf(Vo2Window.SIX_MONTHS.name) }
    val window = Vo2Window.entries.firstOrNull { it.name == windowName } ?: Vo2Window.SIX_MONTHS
    val value = training.latestVo2Max
    val reference = HealthMetrics.vo2Reference(value, birthYear, biologicalSex)
    val nowSeconds = System.currentTimeMillis() / 1000
    val history = HealthMetrics.vo2HistoryInWindow(training.vo2History, nowSeconds, window.days)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthDetailHeader(
            metric = HealthMetric.VO2_MAX,
            subtitle = vo2Subtitle(history),
            onBack = onBack,
        )
        RangeSelector(
            options = Vo2Window.entries.map { it.label },
            selectedIndex = window.ordinal,
            onSelected = { windowName = Vo2Window.entries[it].name },
        )
        Text(
            vo2RangeLabel(history, window),
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        if (value == null) {
            EmptyMetricCard("VO₂ max is written after an outdoor run with a valid estimate. Import or sync a session first.")
        } else {
            MetricCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    if (reference != null) {
                        val thresholds = reference.thresholds
                        val domainLow = (thresholds.fair - 8.0).coerceAtLeast(0.0)
                        val domainHigh = thresholds.superior + 8.0
                        val span = (domainHigh - domainLow).coerceAtLeast(1.0)
                        fun fraction(number: Double) = ((number - domainLow) / span).toFloat().coerceIn(0f, 1f)
                        val bands = listOf(
                            GaugeBand(fraction(thresholds.fair), PulseColors.Coral),
                            GaugeBand((fraction(thresholds.good) - fraction(thresholds.fair)).coerceAtLeast(0f), PulseColors.Orange),
                            GaugeBand((fraction(thresholds.excellent) - fraction(thresholds.good)).coerceAtLeast(0f), PulseColors.Green),
                            GaugeBand((fraction(thresholds.superior) - fraction(thresholds.excellent)).coerceAtLeast(0f), PulseColors.Blue),
                            GaugeBand((1f - fraction(thresholds.superior)).coerceAtLeast(0f), PulseColors.Violet),
                        )
                        BandGauge(
                            bands = bands,
                            marker = fraction(value),
                            value = "%.1f".format(value),
                            unit = "",
                            caption = "ml/kg/min",
                            size = 200.dp,
                        )
                    } else {
                        ScoreGauge(
                            progress = (value / 70.0).toFloat().coerceIn(0f, 1f),
                            value = "%.1f".format(value),
                            unit = "",
                            caption = "ml/kg/min",
                            color = PulseColors.Accent,
                            size = 190.dp,
                        )
                    }
                    reference?.band?.let { band ->
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(vo2BandColor(band).copy(alpha = 0.16f))
                                .padding(horizontal = PulseSpacing.lg, vertical = 5.dp),
                        ) {
                            Text(
                                band.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = vo2BandColor(band),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        if (reference != null) {
                            "Fitness band from age and sex reference values. The watch's proprietary percentile is not available offline."
                        } else {
                            "Add birth year and biological sex in Settings to see the fitness band."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = PulseSpacing.md),
                    )
                }
            }

            MetricCard {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    CardTitle("Trend", vo2WindowDescription(window))
                    LineTrendChart(
                        values = history.map { it.value.toFloat() },
                        labels = if (history.size >= 2) {
                            listOf(dateLabel(history.first().startTime), dateLabel(history.last().startTime))
                        } else {
                            emptyList()
                        },
                        color = PulseColors.Accent,
                    )
                    ValueGrid(
                        listOf(
                            "Change" to vo2ChangeLabel(history),
                            "Range low" to (history.minOfOrNull { it.value }?.let { "%.1f".format(it) } ?: "—"),
                            "Range high" to (history.maxOfOrNull { it.value }?.let { "%.1f".format(it) } ?: "—"),
                        ),
                    )
                }
            }

            if (reference != null) {
                MetricCard {
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        Text(
                            "Reference bands",
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                        listOf(
                            "Superior" to reference.thresholds.superior,
                            "Excellent" to reference.thresholds.excellent,
                            "Good" to reference.thresholds.good,
                            "Fair" to reference.thresholds.fair,
                        ).forEach { (label, threshold) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.foundation.layout.Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(vo2BandColor(bandForThreshold(label))),
                                    )
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PulseColors.TextMuted,
                                        modifier = Modifier.padding(start = PulseSpacing.sm),
                                    )
                                }
                                Text(
                                    "≥ %.1f".format(threshold),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PulseColors.Text,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

private fun vo2BandColor(band: Vo2Band): androidx.compose.ui.graphics.Color = when (band) {
    Vo2Band.SUPERIOR -> PulseColors.Violet
    Vo2Band.EXCELLENT -> PulseColors.Blue
    Vo2Band.GOOD -> PulseColors.Green
    Vo2Band.FAIR -> PulseColors.Orange
    Vo2Band.NEEDS_WORK -> PulseColors.Coral
}

private fun bandForThreshold(label: String): Vo2Band = when (label) {
    "Superior" -> Vo2Band.SUPERIOR
    "Excellent" -> Vo2Band.EXCELLENT
    "Good" -> Vo2Band.GOOD
    else -> Vo2Band.FAIR
}

private fun vo2ChangeLabel(history: List<TrainingPoint>): String {
    if (history.size < 2) return "—"
    val change = history.last().value - history.first().value
    return if (change >= 0) "+%.1f".format(change) else "−%.1f".format(abs(change))
}

@Composable
private fun vo2WindowDescription(window: Vo2Window): String = when (window) {
    Vo2Window.NOW -> tr("Latest reading")
    Vo2Window.FOUR_WEEKS -> tr("4 weeks")
    Vo2Window.TWELVE_WEEKS -> tr("12 weeks")
    Vo2Window.SIX_MONTHS -> tr("6 months")
}

@Composable
private fun vo2RangeLabel(history: List<TrainingPoint>, window: Vo2Window): String {
    if (window == Vo2Window.NOW || history.size < 2) {
        return history.lastOrNull()?.let {
            tr("Latest reading · %s", dateLabel(it.startTime))
        } ?: tr("No reading yet")
    }
    return "${dateLabel(history.first().startTime)} – ${dateLabel(history.last().startTime)}"
}

@Composable
private fun vo2Subtitle(history: List<TrainingPoint>): String {
    val latest = history.lastOrNull() ?: return tr("Fitness level")
    return tr("Fitness level · %s", dateLabel(latest.startTime))
}

private fun dateLabel(unixSeconds: Long): String {
    val date = Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
    return dayLabel(date)
}
