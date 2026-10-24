package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.HealthWindow
import dev.opengarmin.app.data.HrvNight
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.util.formatWellnessDate
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
internal fun HrvDetailScreen(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    latestDate: LocalDate,
    onBack: () -> Unit,
) {
    var windowName by rememberSaveable { mutableStateOf(HealthWindow.DAY.name) }
    var anchorEpoch by rememberSaveable { mutableStateOf(latestDate.toEpochDay()) }
    val window = HealthWindow.entries.firstOrNull { it.name == windowName } ?: HealthWindow.DAY
    val anchor = LocalDate.ofEpochDay(anchorEpoch).coerceAtMost(latestDate)
    val earliest = days.minOfOrNull { it.date }?.let { HealthMetrics.parseDate(it) } ?: latestDate
    val day = days.firstOrNull { it.date == anchor.toString() }
    val hrvSamples = samples.filter { it.kind == "hrv" && it.value > 0.0 }.sortedBy { it.timestamp }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthDetailHeader(
            metric = HealthMetric.HRV,
            subtitle = hrvSubtitle(anchor, latestDate, window),
            onBack = onBack,
        )
        RangeSelector(
            options = listOf(HealthWindow.DAY, HealthWindow.WEEK, HealthWindow.FOUR_WEEKS).map { it.label },
            selectedIndex = window.ordinal,
            onSelected = {
                windowName = HealthWindow.entries[it].name
                anchorEpoch = latestDate.toEpochDay()
            },
        )
        DateNavigator(
            label = hrvNavigatorLabel(anchor, latestDate, window),
            canGoBack = HealthMetrics.rangeStart(anchor, window.days) > earliest,
            canGoForward = anchor < latestDate,
            onBack = { anchorEpoch = shift(anchor, earliest, latestDate, -window.days).toEpochDay() },
            onForward = { anchorEpoch = shift(anchor, earliest, latestDate, window.days).toEpochDay() },
        )

        if (day == null || (day.hrvLastNight == null && day.hrvStatus == null)) {
            EmptyMetricCard("HRV has not synced for this date. It comes from the overnight recording.")
        } else if (window == HealthWindow.DAY) {
            val lastNight = day.hrvLastNight ?: hrvSamples.map { it.value }.takeIf { it.isNotEmpty() }?.average()
            val weekly = HealthMetrics.hrvNights(days, anchor, 7)
                .mapNotNull { it.value }
                .takeIf { it.isNotEmpty() }
                ?.average()
            HrvDay(
                day = day,
                lastNight = lastNight,
                weekly = weekly,
                samples = hrvSamples,
                days = days,
                anchor = anchor,
            )
        } else {
            val nights = HealthMetrics.hrvNights(days, anchor, window.days)
            HrvHistory(nights = nights, days = days)
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun HrvDay(
    day: WellnessDayEntity,
    lastNight: Double?,
    weekly: Double?,
    samples: List<WellnessSampleEntity>,
    days: List<WellnessDayEntity>,
    anchor: LocalDate,
) {
    val status = day.hrvStatus
    val low = day.hrvBaselineLow
    val high = day.hrvBaselineHigh
    val center = weekly ?: lastNight
    val delta = if (lastNight != null && weekly != null) lastNight - weekly else null
    val peak = samples.maxByOrNull { it.value }
    val baselineText = if (low != null && high != null) "Baseline range is ${"%.0f".format(low)}–${"%.0f".format(high)} ms." else "No baseline range reported by the watch."

    MetricCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            if (center != null && low != null && high != null) {
                val domainLow = minOf(low - 10.0, center - 5.0, 0.0).coerceAtLeast(0.0)
                val domainHigh = maxOf(high + 15.0, center + 5.0)
                val span = (domainHigh - domainLow).coerceAtLeast(1.0)
                val lowEnd = ((low * 0.9 - domainLow) / span).toFloat().coerceIn(0f, 1f)
                val balancedStart = ((low - domainLow) / span).toFloat().coerceIn(0f, 1f)
                val balancedEnd = ((high - domainLow) / span).toFloat().coerceIn(0f, 1f)
                val bands = listOf(
                    GaugeBand(lowEnd, PulseColors.Coral),
                    GaugeBand((balancedStart - lowEnd).coerceAtLeast(0f), PulseColors.Orange),
                    GaugeBand((balancedEnd - balancedStart).coerceAtLeast(0f), PulseColors.Green),
                    GaugeBand((1f - balancedEnd).coerceAtLeast(0f), PulseColors.Orange),
                )
                BandGauge(
                    bands = bands,
                    marker = ((center - domainLow) / span).toFloat(),
                    value = "%.0f".format(center),
                    unit = "ms",
                    caption = "7-day average",
                    size = 190.dp,
                )
            } else if (center != null) {
                ScoreGauge(
                    progress = (center / 100.0).toFloat(),
                    value = "%.0f".format(center),
                    unit = "ms",
                    caption = "7-day average",
                    color = status?.let { hrvStatusColor(it) } ?: PulseColors.Green,
                    size = 180.dp,
                )
            }
            if (status != null) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(hrvStatusColor(status).copy(alpha = 0.16f))
                        .padding(horizontal = PulseSpacing.lg, vertical = 5.dp),
                ) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = hrvStatusColor(status), fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Training load and recovery are reflected in overnight HRV. $baselineText",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
                modifier = Modifier.padding(horizontal = PulseSpacing.md),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl),
            ) {
                HrvCell(
                    "Last night",
                    lastNight?.let { "%.0f ms".format(it) } ?: "—",
                    delta?.let {
                        val sign = if (it >= 0) "+" else "−"
                        "$sign${"%.0f".format(kotlin.math.abs(it))} vs avg"
                    },
                    if (delta != null && delta >= 0) PulseColors.Green else PulseColors.Coral,
                    Modifier.weight(1f),
                )
                HrvCell(
                    "5-min peak",
                    peak?.let { "%.0f ms".format(it.value) } ?: "—",
                    peak?.let { sampleTime(it.timestamp) },
                    PulseColors.TextMuted,
                    Modifier.weight(1f),
                )
            }
        }
    }

    MetricCard {
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            CardTitle("Overnight history", "HRV in ms")
            HrvOvernightChart(
                samples = samples,
                baselineLow = low,
                baselineHigh = high,
                average = lastNight,
            )
            if (samples.size >= 2) {
                Text(
                    "The line is the overnight rMSSD; the shaded band is your balanced baseline.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }

    SectionHeader("Recent nights")
    HealthMetrics.hrvNights(days, anchor, 7).sortedByDescending { it.date }.take(5).forEach { night ->
        val average = rollingHrvAverage(days, night.date)
        MetricDayRow(
            name = HealthMetrics.formatDayName(night.date),
            sub = HealthMetrics.formatDayDate(night.date),
            left = average?.let { "%.0f ms".format(it) } ?: "—",
            leftLabel = "7-day avg",
            right = night.value?.let { "%.0f ms".format(it) } ?: "—",
            rightLabel = "last night",
            leftColor = hrvStatusColor(effectiveHrvStatus(night)),
        )
    }
}

@Composable
private fun HrvHistory(
    nights: List<HrvNight>,
    days: List<WellnessDayEntity>,
) {
    if (nights.isEmpty()) {
        EmptyMetricCard("No HRV history in this range.")
        return
    }
    val inRange = HealthMetrics.hrvInRangeCount(nights)
    val streak = HealthMetrics.currentInRangeStreak(nights)
    val latest = nights.lastOrNull()
    val baseline = nights.lastOrNull { it.baselineLow != null && it.baselineHigh != null }
    val hasReference = baseline != null || nights.any { it.status != null }

    if (hasReference) {
        MetricCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("In range for", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$inRange", style = MaterialTheme.typography.displaySmall, color = PulseColors.Text)
                        Text(
                            " of ${nights.size} days",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
                latest?.let { effectiveHrvStatus(it) }?.let { status ->
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(hrvStatusColor(status).copy(alpha = 0.16f))
                            .padding(horizontal = PulseSpacing.md, vertical = 5.dp),
                    ) {
                        Text("● $status", style = MaterialTheme.typography.labelSmall, color = hrvStatusColor(status), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        MetricCard {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                CardTitle("Nightly HRV vs baseline")
                Legend(
                    listOf(
                        Triple("Balanced", PulseColors.Green, false),
                        Triple("Unbalanced", PulseColors.Orange, false),
                        Triple("Low", PulseColors.Coral, false),
                        Triple("Baseline", PulseColors.Text.copy(alpha = 0.35f), false),
                    ),
                )
                HrvStatusChart(
                    values = nights.map { effectiveHrvStatus(it) to it.value },
                    baselineLow = baseline?.baselineLow,
                    baselineHigh = baseline?.baselineHigh,
                )
                InsightLine(
                    text = buildAnnotatedString {
                        append("You have been in your baseline range for ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$streak days") }
                        append(" in a row, $inRange of ${nights.size} recorded days in this range.")
                    },
                )
            }
        }
    } else {
        MetricCard {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                CardTitle("Nightly HRV")
                LineTrendChart(
                    values = nights.mapNotNull { it.value?.toFloat() },
                    labels = listOf(dayLabel(nights.first().date), dayLabel(nights.last().date)),
                    color = PulseColors.Green,
                )
                InsightLine(
                    text = buildAnnotatedString {
                        append("Your watch did not report its HRV baseline, so status shapes are not shown. The line is your nightly rMSSD average.")
                    },
                )
            }
        }
    }

    val baseLow = baseline?.baselineLow
    val baseHigh = baseline?.baselineHigh
    MetricCard {
        ValueGrid(
            listOf(
                "Range average" to (HealthMetrics.average(nights.mapNotNull { it.value })?.roundToInt()?.let { "$it ms" } ?: "—"),
                if (baseLow != null && baseHigh != null) {
                    "Baseline" to "%.0f–%.0f ms".format(baseLow, baseHigh)
                } else {
                    "Nights recorded" to nights.size.toString()
                },
            ),
            columns = 2,
        )
    }

    SectionHeader("Recent nights")
    nights.sortedByDescending { it.date }.take(5).forEach { night ->
        val average = rollingHrvAverage(days, night.date)
        MetricDayRow(
            name = HealthMetrics.formatDayName(night.date),
            sub = HealthMetrics.formatDayDate(night.date),
            left = average?.let { "%.0f ms".format(it) } ?: "—",
            leftLabel = "7-day avg",
            right = night.value?.let { "%.0f ms".format(it) } ?: "—",
            rightLabel = "last night",
            leftColor = hrvStatusColor(effectiveHrvStatus(night)),
        )
    }
}

@Composable
private fun HrvCell(label: String, value: String, sub: String?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

/** Falls back to the baseline range when the watch did not store a status. */
private fun effectiveHrvStatus(night: HrvNight): String? {
    night.status?.let { return it }
    val value = night.value ?: return null
    val low = night.baselineLow ?: return null
    val high = night.baselineHigh ?: return null
    return when {
        value < low -> "Low"
        value > high -> "Unbalanced"
        else -> "Balanced"
    }
}

private fun rollingHrvAverage(days: List<WellnessDayEntity>, date: LocalDate): Double? =
    days.filter { it.date <= date.toString() }
        .sortedByDescending { it.date }
        .take(7)
        .mapNotNull { it.hrvLastNight }
        .takeIf { it.isNotEmpty() }
        ?.average()

private fun hrvSubtitle(
    anchor: LocalDate,
    latestDate: LocalDate,
    window: HealthWindow,
): String {
    if (window == HealthWindow.DAY) {
        val date = formatWellnessDate(anchor.toString())
        return if (anchor == latestDate) "$date · overnight" else date
    }
    return "${if (window == HealthWindow.WEEK) "7 days" else "4 weeks"} · ${HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)}"
}

private fun hrvNavigatorLabel(anchor: LocalDate, latestDate: LocalDate, window: HealthWindow): String {
    if (window == HealthWindow.DAY) {
        return if (anchor == latestDate) "Today" else HealthMetrics.formatRange(anchor, anchor)
    }
    return HealthMetrics.formatRange(HealthMetrics.rangeStart(anchor, window.days), anchor)
}

private fun shift(anchor: LocalDate, earliest: LocalDate, latest: LocalDate, days: Int): LocalDate =
    anchor.plusDays(days.toLong()).coerceIn(earliest, latest)
