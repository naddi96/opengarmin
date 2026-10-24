package dev.opengarmin.app.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.HealthMetrics
import dev.opengarmin.app.data.SleepNight
import dev.opengarmin.app.data.TrainingSummary
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessMetrics
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.util.formatHoursMinutes
import dev.opengarmin.app.util.formatWellnessDate
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun HealthScreen(
    days: List<WellnessDayEntity>,
    samples: List<WellnessSampleEntity>,
    training: TrainingSummary,
    syncLabel: String,
    birthYear: Int?,
    biologicalSex: String?,
    onOpenTrends: () -> Unit,
    onOpenMetric: (HealthMetric) -> Unit,
    onAskCoach: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = WellnessMetrics.latestDay(days)
    val dateLabel = day?.let { formatWellnessDate(it.date) }
        ?: LocalDate.now().let { HealthMetrics.formatRange(it, it) }
    val sleepNights = HealthMetrics.sleepNights(days, day?.let { HealthMetrics.parseDate(it.date) } ?: LocalDate.now(), 7)
    val needHours = HealthMetrics.sleepNeedHours(birthYear)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        HealthHeader(dateLabel = dateLabel, syncLabel = syncLabel, onOpenTrends = onOpenTrends)

        if (day == null) {
            EmptyHealthCard(onSync = onSync)
            return@Column
        }

        TonightCard(nights = sleepNights, needHours = needHours)

        val previousDay = days.filter { it.date < day.date }.maxByOrNull { it.date }
        val overnight = if (previousDay?.bodyBatteryLatest != null && day.bodyBatteryStart != null) {
            day.bodyBatteryStart - previousDay.bodyBatteryLatest
        } else {
            null
        }
        BodyBatteryHubCard(
            day = day,
            samples = samples,
            overnight = overnight,
            onClick = { onOpenMetric(HealthMetric.BODY_BATTERY) },
        )

        val hrvNights = HealthMetrics.hrvNights(
            days,
            HealthMetrics.parseDate(day.date) ?: LocalDate.now(),
            7,
        )
        val hrvNight = hrvNights.lastOrNull()
        val hrvStatus = hrvNight?.let { HealthMetrics.effectiveHrvStatus(it) } ?: day.hrvStatus

        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            HubTile(
                label = "Sleep",
                icon = Icons.Outlined.Bedtime,
                accent = PulseColors.Blue,
                value = day.sleepScore?.toString() ?: "—",
                unit = "score",
                status = sleepStatus(day),
                statusColor = sleepScoreColor(day.sleepQuality),
                sparkline = sleepNights.mapNotNull { it.score?.toFloat() },
                bars = true,
                onClick = { onOpenMetric(HealthMetric.SLEEP) },
                modifier = Modifier.weight(1f),
            )
            HubTile(
                label = "HRV",
                icon = Icons.Outlined.FavoriteBorder,
                accent = PulseColors.Green,
                value = hrvNight?.weeklyAverage?.let { "%.0f".format(it) } ?: "—",
                unit = "ms",
                status = hrvStatus ?: "No status",
                statusColor = hrvStatusColor(hrvStatus),
                sparkline = hrvNights.mapNotNull { it.weeklyAverage?.toFloat() },
                bars = false,
                onClick = { onOpenMetric(HealthMetric.HRV) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            val stress = samples.filter { it.kind == "stress" && it.value in 0.0..100.0 }
            val breakdown = HealthMetrics.stressBreakdown(stress)
            val restPercent = breakdown?.percentFor(dev.opengarmin.app.data.StressLevel.REST)
            HubTile(
                label = "Stress",
                icon = Icons.Outlined.MonitorHeart,
                accent = PulseColors.Yellow,
                value = day.stressAvg?.toString() ?: "—",
                unit = "avg",
                status = when {
                    day.stressAvg == null -> "Not synced"
                    restPercent != null -> "Rest $restPercent% of day"
                    else -> stressLabel(day.stressAvg)
                },
                statusColor = if (restPercent != null && restPercent >= 50) PulseColors.Blue else PulseColors.Yellow,
                sparkline = HealthMetrics.stressDays(days, HealthMetrics.parseDate(day.date) ?: LocalDate.now(), 7)
                    .map { it.value.toFloat() },
                bars = true,
                onClick = { onOpenMetric(HealthMetric.STRESS) },
                modifier = Modifier.weight(1f),
            )
            val vo2Reference = HealthMetrics.vo2Reference(training.latestVo2Max, birthYear, biologicalSex)
            HubTile(
                label = "VO₂ max",
                icon = Icons.Outlined.Speed,
                accent = PulseColors.Accent,
                value = training.latestVo2Max?.let { "%.0f".format(it) } ?: "—",
                unit = "",
                status = vo2Reference?.band?.label ?: "No reference",
                statusColor = vo2BandColor(vo2Reference?.band),
                sparkline = training.vo2History.takeLast(7).map { it.value.toFloat() },
                bars = false,
                onClick = { onOpenMetric(HealthMetric.VO2_MAX) },
                modifier = Modifier.weight(1f),
            )
        }

        CoachInsightLine(
            text = buildAnnotatedString {
                append("Open a metric for the day timeline, history and what moved it. ")
                append("All values are read from your watch; nothing is estimated.")
            },
            onAskCoach = onAskCoach,
        )

        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun HealthHeader(
    dateLabel: String,
    syncLabel: String,
    onOpenTrends: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Health", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
            Text(
                "$dateLabel · $syncLabel",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }
        PillChip(
            label = "Trends",
            selected = false,
            onClick = onOpenTrends,
            leading = Icons.Outlined.TrendingUp,
        )
    }
}

@Composable
private fun EmptyHealthCard(onSync: () -> Unit) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xlPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("No health data yet", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
            Text(
                "Sync your watch to import steps, heart rate, Body Battery, stress, sleep, HRV and SpO₂. Nothing is estimated or zero-filled.",
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextMuted,
            )
            PrimaryButton("Sync watch", onSync)
        }
    }
}

@Composable
private fun TonightCard(
    nights: List<SleepNight>,
    needHours: Double,
) {
    val last = nights.lastOrNull()
    val usualWake = HealthMetrics.averageWakeTime(nights)
    val text = when {
        last?.asleepSeconds == null || usualWake == null -> {
            buildAnnotatedString {
                append("Sync after waking to get a sleep need for tonight. The target uses an age-based recommendation, not a Garmin value.")
            }
        }
        else -> {
            val asleep = last.asleepSeconds
            val needSeconds = (needHours * 3600).roundToInt()
            val short = (needSeconds - asleep).coerceAtLeast(0)
            val lightsOut = HealthMetrics.labelFromNoonMinutes(usualWake - needSeconds / 60)
            val wake = HealthMetrics.labelFromNoonMinutes(usualWake)
            buildAnnotatedString {
                if (short > 0) {
                    append("Last night was short (")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(formatHoursMinutes(asleep.toLong())) }
                    append("). Lights out around ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lightsOut) }
                    append(" gives you ${"%.0f".format(needHours)} h before your usual ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(wake) }
                    append(" wake-up.")
                } else {
                    append("Last night covered your ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("%.0f h".format(needHours)) }
                    append(" need. Keep lights out around ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lightsOut) }
                    append(" to stay on your usual ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(wake) }
                    append(" wake-up.")
                }
            }
        }
    }
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                Icon(Icons.Outlined.Bedtime, null, tint = PulseColors.Blue, modifier = Modifier.size(14.dp))
                Text("Tonight", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = PulseColors.Text)
        }
    }
}

@Composable
private fun BodyBatteryHubCard(
    day: WellnessDayEntity,
    samples: List<WellnessSampleEntity>,
    overnight: Int?,
    onClick: () -> Unit,
) {
    val battery = samples.filter { it.kind == "body_battery" && it.value in 1.0..100.0 }.sortedBy { it.timestamp }
    val current = day.bodyBatteryLatest ?: battery.lastOrNull()?.value?.roundToInt()
    val peak = HealthMetrics.peakBodyBattery(samples)
    val peakValue = day.bodyBatteryMax ?: peak?.value?.roundToInt()
    val deltas = bodyBatteryDeltas(battery.map { it.value })
    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            modifier = Modifier.padding(start = PulseSpacing.xlPlus, end = PulseSpacing.xlPlus, top = PulseSpacing.lg, bottom = PulseSpacing.lgPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    Icon(Icons.Outlined.Bolt, null, tint = PulseColors.Teal, modifier = Modifier.size(15.dp))
                    Text("Body Battery", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        peak != null -> "Peak ${peak.value.roundToInt()} at ${sampleTime(peak.timestamp)}"
                        peakValue != null -> "Peak $peakValue"
                        else -> "No peak"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    current?.toString() ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    color = PulseColors.Teal,
                )
                Text(
                    " / 100 · ${bodyBatteryLabel(current)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Sparkline(
                values = battery.map { it.value.toFloat() },
                color = PulseColors.Teal,
                height = 54.dp,
            )
            Row(Modifier.fillMaxWidth()) {
                TinyMetric("Overnight", overnightText(overnight), Modifier.weight(1f))
                TinyMetric("Charged", deltas.second?.let { "+$it" } ?: "—", Modifier.weight(1f), PulseColors.Teal)
                TinyMetric("Drained", deltas.first?.let { "−$it" } ?: "—", Modifier.weight(1f), PulseColors.Coral)
            }
        }
    }
}

@Composable
private fun HubTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    value: String,
    unit: String,
    status: String,
    statusColor: Color,
    sparkline: List<Float>,
    bars: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PulseCard(modifier = modifier.height(158.dp), onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PulseSpacing.lgPlus, vertical = PulseSpacing.mdPlus),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = PulseColors.TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge, color = PulseColors.Text)
                if (unit.isNotBlank()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Sparkline(values = sparkline, color = accent, bars = bars, height = 28.dp)
        }
    }
}

@Composable
private fun TinyMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = PulseColors.Text,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun CoachInsightLine(
    text: AnnotatedString,
    onAskCoach: () -> Unit,
) {
    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onAskCoach) {
        Row(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Icon(Icons.Outlined.Bolt, null, tint = PulseColors.Accent, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        }
    }
}

private fun sleepStatus(day: WellnessDayEntity): String {
    val quality = day.sleepQuality ?: "Not synced"
    val duration = WellnessMetrics.sleepSeconds(day)?.let { formatHoursMinutes(it.toLong()) }
    return if (duration != null) "$quality · $duration" else quality
}

private fun stressLabel(value: Int): String = when {
    value <= 25 -> "Rest"
    value <= 50 -> "Low"
    value <= 75 -> "Medium"
    else -> "High"
}

private fun bodyBatteryLabel(value: Int?): String = when {
    value == null -> "No reading"
    value >= 76 -> "High"
    value >= 51 -> "Medium"
    value >= 26 -> "Low"
    else -> "Very low"
}

private fun overnightText(value: Int?): String =
    value?.let { if (it >= 0) "+$it" else "−${-it}" } ?: "—"

private fun bodyBatteryDeltas(values: List<Double>): Pair<Int?, Int?> {
    if (values.size < 2) return null to null
    var drained = 0
    var recharged = 0
    for (index in 1 until values.size) {
        val diff = (values[index] - values[index - 1]).roundToInt()
        if (diff < 0) drained += -diff else recharged += diff
    }
    return drained to recharged
}

private fun vo2BandColor(band: dev.opengarmin.app.data.Vo2Band?): Color = when (band) {
    dev.opengarmin.app.data.Vo2Band.SUPERIOR -> PulseColors.Violet
    dev.opengarmin.app.data.Vo2Band.EXCELLENT -> PulseColors.Blue
    dev.opengarmin.app.data.Vo2Band.GOOD -> PulseColors.Green
    dev.opengarmin.app.data.Vo2Band.FAIR -> PulseColors.Orange
    dev.opengarmin.app.data.Vo2Band.NEEDS_WORK -> PulseColors.Coral
    null -> PulseColors.TextMuted
}
