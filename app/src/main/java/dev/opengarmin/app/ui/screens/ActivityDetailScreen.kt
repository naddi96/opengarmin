package dev.opengarmin.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opengarmin.app.coach.ChartPoint
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.SportCategory
import dev.opengarmin.app.coach.categoryForSport
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityExport
import dev.opengarmin.app.data.ActivityExportFormat
import dev.opengarmin.app.data.ActivityInsights
import dev.opengarmin.app.data.ActivitySample
import dev.opengarmin.app.data.ActivityZoneSet
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.LapInsights
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.data.RunWalkSplit
import dev.opengarmin.app.util.formatPacePerKm
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.util.formatDistanceKm
import dev.opengarmin.app.util.formatElapsedTime
import dev.opengarmin.app.ui.components.DividerLine
import dev.opengarmin.app.ui.components.EmptyState
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.InteractiveChart
import dev.opengarmin.app.ui.components.InteractiveChartStyle
import dev.opengarmin.app.ui.components.InteractiveMovementChart
import dev.opengarmin.app.ui.components.InteractiveSeries
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.RouteMap
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.components.TinyStat
import dev.opengarmin.app.ui.components.ZoneBar
import dev.opengarmin.app.ui.components.ZoneLegend
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SECTION_OVERVIEW = 0
private const val SECTION_STATS = 1
private const val SECTION_CHARTS = 2
private const val SECTION_LAPS = 3

@Composable
fun ActivityDetailScreen(
    activity: ActivityEntity,
    records: List<RecordEntity>,
    laps: List<LapEntity>,
    insights: ActivityInsights?,
    dynamics: RunningDynamicsSummary?,
    gps: GpsSummary?,
    maxHeartRate: Int?,
    fitExportAvailable: Boolean,
    exportStatus: String?,
    exportBusy: Boolean,
    onExport: (ActivityExportFormat, Uri) -> Unit,
    onBack: () -> Unit,
    onExploreData: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable(activity.id) { mutableIntStateOf(SECTION_OVERVIEW) }
    val scrollState = rememberScrollState()
    val configuredZones = remember(records, maxHeartRate) { heartRateZoneSet(records, maxHeartRate) }
    val fallbackZones = remember(records, maxHeartRate) { zoneSummary(records, maxHeartRate) }
    val hrZones = if (maxHeartRate != null) configuredZones else insights?.hrZones
    val gpsAvailable = ActivityExport.hasGps(records)
    LaunchedEffect(section) {
        scrollState.animateScrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = PulseSpacing.lg, end = PulseSpacing.lg, top = PulseSpacing.md),
    ) {
        ActivityHeader(
            activity = activity,
            onBack = onBack,
            onExploreData = onExploreData,
        )
        Spacer(Modifier.height(PulseSpacing.md))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            SegmentedControl(
                options = listOf("Overview", "Stats", "Charts", "Laps"),
                selectedIndex = section,
                onSelected = { section = it },
            )
            when (section) {
                SECTION_OVERVIEW -> ActivityOverview(
                    activity = activity,
                    insights = insights,
                    zones = hrZones,
                    fallbackZones = fallbackZones,
                    maxHeartRate = maxHeartRate,
                    gps = gps,
                    records = records,
                    fitExportAvailable = fitExportAvailable,
                    gpsAvailable = gpsAvailable,
                    exportStatus = exportStatus,
                    exportBusy = exportBusy,
                    onExport = onExport,
                    onOpenSettings = onOpenSettings,
                )
                SECTION_STATS -> ActivityStats(
                    activity = activity,
                    insights = insights,
                    dynamics = dynamics,
                )
                SECTION_CHARTS -> ActivityCharts(
                    activity = activity,
                    insights = insights,
                    zones = hrZones,
                    powerZones = insights?.powerZones,
                    fallbackZones = fallbackZones,
                    maxHeartRate = maxHeartRate,
                    onOpenSettings = onOpenSettings,
                )
                SECTION_LAPS -> ActivityLaps(activity = activity, insights = insights, laps = laps)
            }
            Spacer(Modifier.height(PulseSpacing.xl))
        }
    }
}

@Composable
private fun ActivityHeader(
    activity: ActivityEntity,
    onBack: () -> Unit,
    onExploreData: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        IconButtonRound(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back to activities",
            onClick = onBack,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                activityTitle(activity),
                style = MaterialTheme.typography.titleLarge,
                color = PulseColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDate(activity.startTime)} · ${categoryForSport(activity.sport, activity.subSport).label}",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PrimaryButton("All data", onExploreData)
    }
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

@Composable
private fun ActivityOverview(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    zones: ActivityZoneSet?,
    fallbackZones: ZoneSummary?,
    maxHeartRate: Int?,
    gps: GpsSummary?,
    records: List<RecordEntity>,
    fitExportAvailable: Boolean,
    gpsAvailable: Boolean,
    exportStatus: String?,
    exportBusy: Boolean,
    onExport: (ActivityExportFormat, Uri) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var mapFullscreen by rememberSaveable(activity.id) { mutableStateOf(false) }
    val session = insights?.session
    val isRide = categoryForSport(activity.sport, activity.subSport) == SportCategory.Ride
    val distance = session?.distanceMeters ?: activity.distanceMeters
    val timer = session?.timerSeconds ?: activity.durationSeconds
    val elapsed = session?.elapsedSeconds ?: activity.elapsedSeconds
    val moving = session?.movingSeconds
    val avgPace = session?.avgPaceSecondsPerKm ?: if (distance > 0.0) timer / (distance / 1000.0) else null
    val avgSpeed = session?.avgSpeedKmh ?: if (distance > 0.0 && timer > 0.0) distance / timer * 3.6 else null

    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                TinyStat("Distance", formatDistanceKm(distance), Modifier.weight(1f))
                TinyStat("Duration", formatElapsedTime(timer), Modifier.weight(1f))
                when {
                    isRide -> TinyStat("Avg speed", formatSpeed(avgSpeed), Modifier.weight(1f))
                    distance > 0.0 -> TinyStat("Avg pace", formatPaceSeconds(avgPace), Modifier.weight(1f))
                    else -> TinyStat("Elapsed", formatElapsedTime(elapsed), Modifier.weight(1f))
                }
                TinyStat("Calories", session?.totalCalories?.let { "$it kcal" } ?: activity.calories?.let { "$it kcal" } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                TinyStat("Avg HR", session?.avgHeartRate?.let { "$it bpm" } ?: activity.avgHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                TinyStat("Max HR", session?.maxHeartRate?.let { "$it bpm" } ?: activity.maxHeartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
                TinyStat("Ascent", formatMeters(session?.ascentMeters ?: activity.ascentMeters?.toDouble()), Modifier.weight(1f))
                TinyStat("Descent", formatMeters(session?.descentMeters ?: activity.descentMeters?.toDouble()), Modifier.weight(1f))
            }
            if (moving != null || session?.maxSpeedKmh != null || session?.avgCadenceSpm != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    TinyStat("Moving time", moving?.let { formatElapsedTime(it) } ?: "—", Modifier.weight(1f))
                    TinyStat("Elapsed", formatElapsedTime(elapsed), Modifier.weight(1f))
                    TinyStat(
                        "Max km/h",
                        session.maxSpeedKmh?.let { "%.1f".format(it) } ?: "—",
                        Modifier.weight(1f),
                    )
                    TinyStat(
                        "Avg cadence",
                        session.avgCadenceSpm?.let { "%.0f spm".format(it) } ?: "—",
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (gps?.available == true) {
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Track",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                    )
                    IconButtonRound(
                        icon = Icons.Outlined.Fullscreen,
                        contentDescription = "Open map full screen",
                        onClick = { mapFullscreen = true },
                        background = PulseColors.SurfaceElevated,
                    )
                }
                RouteMap(records, Modifier.fillMaxWidth().height(260.dp))
                TrackSpeedLegend()
            }
        }
        if (mapFullscreen) {
            MapFullscreenDialog(records = records, onDismiss = { mapFullscreen = false })
        }
    }

    if (session?.aerobicTrainingEffect != null || session?.anaerobicTrainingEffect != null) {
        TrainingEffectCard(
            aerobic = session.aerobicTrainingEffect,
            anaerobic = session.anaerobicTrainingEffect,
            primaryBenefit = session.primaryBenefit,
            exerciseLoad = session.exerciseLoad,
            recoveryMinutes = session.recoveryMinutes,
        )
    }

    HeartRateZonesCard(
        zones = zones,
        fallback = fallbackZones,
        maxHeartRate = maxHeartRate,
        compact = true,
        onOpenSettings = onOpenSettings,
    )

    ActivityExportCard(
        activity = activity,
        fitExportAvailable = fitExportAvailable,
        gpsAvailable = gpsAvailable,
        exportStatus = exportStatus,
        exportBusy = exportBusy,
        onExport = onExport,
    )
}

@Composable
private fun TrackSpeedLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = PulseSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Slow",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(PulseColors.RouteSlow, PulseColors.RouteFast),
                    ),
                ),
        )
        Text(
            "Fast",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
    }
}

@Composable
private fun MapFullscreenDialog(records: List<RecordEntity>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = PulseColors.Background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Track",
                            style = MaterialTheme.typography.titleMedium,
                            color = PulseColors.Text,
                        )
                        Text(
                            "Speed-coloured route",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    IconButtonRound(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close full-screen map",
                        onClick = onDismiss,
                        background = PulseColors.SurfaceElevated,
                    )
                }
                Spacer(Modifier.height(6.dp))
                RouteMap(records, Modifier.fillMaxWidth().weight(1f))
                TrackSpeedLegend()
            }
        }
    }
}

@Composable
private fun ActivityExportCard(
    activity: ActivityEntity,
    fitExportAvailable: Boolean,
    gpsAvailable: Boolean,
    exportStatus: String?,
    exportBusy: Boolean,
    onExport: (ActivityExportFormat, Uri) -> Unit,
) {
    val fitExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ActivityExportFormat.FIT.mimeType),
    ) { uri -> uri?.let { onExport(ActivityExportFormat.FIT, it) } }
    val gpxExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ActivityExportFormat.GPX.mimeType),
    ) { uri -> uri?.let { onExport(ActivityExportFormat.GPX, it) } }
    val tcxExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ActivityExportFormat.TCX.mimeType),
    ) { uri -> uri?.let { onExport(ActivityExportFormat.TCX, it) } }
    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ActivityExportFormat.CSV.mimeType),
    ) { uri -> uri?.let { onExport(ActivityExportFormat.CSV, it) } }

    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Export", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Text(
                "Files are written to the location you choose with the system document picker. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                PrimaryButton(
                    "FIT",
                    onClick = {
                        fitExporter.launch(ActivityExport.fileName(activity, ActivityExportFormat.FIT))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = fitExportAvailable && !exportBusy,
                )
                PrimaryButton(
                    "GPX",
                    onClick = {
                        gpxExporter.launch(ActivityExport.fileName(activity, ActivityExportFormat.GPX))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = gpsAvailable && !exportBusy,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                PrimaryButton(
                    "TCX",
                    onClick = {
                        tcxExporter.launch(ActivityExport.fileName(activity, ActivityExportFormat.TCX))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !exportBusy,
                )
                PrimaryButton(
                    "CSV",
                    onClick = {
                        csvExporter.launch(ActivityExport.fileName(activity, ActivityExportFormat.CSV))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !exportBusy,
                )
            }
            if (!fitExportAvailable) {
                Text(
                    "The original FIT file is no longer on this device. Re-sync the activity or import its FIT file to export FIT.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
            if (!gpsAvailable) {
                Text(
                    "GPX is unavailable because this activity has no valid GPS points. TCX and CSV still export the available data.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
            if (exportBusy) {
                Text("Exporting…", style = MaterialTheme.typography.labelMedium, color = PulseColors.Teal)
            } else {
                exportStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.startsWith("Could not")) PulseColors.Coral else PulseColors.Teal,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

private data class StatRow(val label: String, val value: String)
private data class StatGroup(val title: String, val color: Color, val rows: List<StatRow>)

@Composable
private fun ActivityStats(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    dynamics: RunningDynamicsSummary?,
) {
    val groups = remember(activity, insights, dynamics) {
        buildStatGroups(activity, insights, dynamics)
    }
    if (groups.isEmpty()) {
        EmptyState(
            title = "No detailed statistics",
            message = "This activity was imported without session metrics or record samples. Re-sync the activity or import its original FIT file.",
        )
        return
    }
    groups.forEach { group ->
        StatGroupCard(group)
    }
    val session = insights?.session
    if (session?.movingDerived == true || insights?.runWalk?.estimated == true) {
        Text(
            "Moving pace, moving time and run/walk time are derived from the recorded speed samples when the watch did not store them.",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
    }
    if (session != null && (session.activeCalories == null || session.restingCalories == null)) {
        Text(
            "Only the metrics recorded in the FIT file are shown. The resting/active calorie split, sweat loss, intensity minutes and wind data are not included in this activity.",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
    }
}

@Composable
private fun StatGroupCard(group: StatGroup) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(group.color))
                Spacer(Modifier.width(PulseSpacing.sm))
                Text(group.title, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            }
            group.rows.forEachIndexed { index, row ->
                if (index > 0) DividerLine()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = PulseSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.value == "—") PulseColors.TextMuted else PulseColors.Text,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun buildStatGroups(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    dynamics: RunningDynamicsSummary?,
): List<StatGroup> {
    val session = insights?.session
    val category = categoryForSport(activity.sport, activity.subSport)
    val isRun = category == SportCategory.Run
    val isWalk = category == SportCategory.Walk
    val isRide = category == SportCategory.Ride
    val distance = session?.distanceMeters ?: activity.distanceMeters
    val timer = session?.timerSeconds ?: activity.durationSeconds
    val elapsed = session?.elapsedSeconds ?: activity.elapsedSeconds
    val moving = session?.movingSeconds
    val avgPace = session?.avgPaceSecondsPerKm
        ?: if (distance > 0.0 && timer > 0.0) timer / (distance / 1000.0) else null
    val movingPace = session?.movingPaceSecondsPerKm
    val bestPace = session?.bestPaceSecondsPerKm
    val avgSpeed = session?.avgSpeedKmh
        ?: if (distance > 0.0 && timer > 0.0) distance / timer * 3.6 else null
    val runWalk = insights?.runWalk
    val groups = mutableListOf<StatGroup>()

    fun group(title: String, color: Color, rows: List<StatRow?>) {
        val present = rows.filterNotNull()
        if (present.any { it.value != "—" }) groups.add(StatGroup(title, color, present))
    }

    fun row(label: String, value: String?): StatRow? = value?.let { StatRow(label, it) }

    if (!isRide && distance > 0.0 && (isRun || avgPace != null)) {
        group(
            "Pace",
            PulseColors.Blue,
            listOf(
                row("Average pace", formatPaceSeconds(avgPace)),
                row("Moving pace", formatPaceSeconds(movingPace)),
                row("Best pace", formatPaceSeconds(bestPace)),
            ),
        )
    }
    if (avgSpeed != null || session?.maxSpeedKmh != null) {
        group(
            "Speed",
            PulseColors.Teal,
            listOf(
                row("Average speed", formatSpeed(avgSpeed)),
                row("Moving speed", formatSpeed(session?.movingSpeedKmh)),
                row("Maximum speed", formatSpeed(session?.maxSpeedKmh)),
            ),
        )
    }
    group(
        "Time",
        PulseColors.Accent,
        listOf(
            row("Total time", formatElapsedTime(timer)),
            row("Moving time", moving?.let { formatElapsedTime(it) }),
            row("Elapsed time", formatElapsedTime(elapsed)),
        ),
    )
    if ((isRun || isWalk) && runWalk != null) {
        group(
            "Run / walk",
            PulseColors.Orange,
            listOf(
                row("Running time", formatElapsedTime(runWalk.runningSeconds)),
                row("Walking time", formatElapsedTime(runWalk.walkingSeconds)),
                row("Idle time", formatElapsedTime(runWalk.idleSeconds)),
            ),
        )
    }
    group(
        "Heart rate",
        PulseColors.Coral,
        listOf(
            row("Average heart rate", session?.avgHeartRate?.let { "$it bpm" } ?: activity.avgHeartRate?.let { "$it bpm" }),
            row("Maximum heart rate", session?.maxHeartRate?.let { "$it bpm" } ?: activity.maxHeartRate?.let { "$it bpm" }),
            row("Minimum heart rate", session?.minHeartRate?.let { "$it bpm" }),
        ),
    )
    if (session != null && (
        session.aerobicTrainingEffect != null ||
            session.anaerobicTrainingEffect != null ||
            session.exerciseLoad != null ||
            session.primaryBenefit != null ||
            session.recoveryMinutes != null ||
            session.performanceCondition != null ||
            session.vo2Max != null
        )
    ) {
        group(
            "Training effect",
            PulseColors.Accent,
            listOf(
                row("Primary benefit", session.primaryBenefit),
                row("Aerobic", session.aerobicTrainingEffect?.let { "%.1f".format(it) }),
                row("Anaerobic", session.anaerobicTrainingEffect?.let { "%.1f".format(it) }),
                row("Exercise load", session.exerciseLoad?.let { "%.0f".format(it) }),
                row("Recovery time", formatRecovery(session.recoveryMinutes)),
                row("Performance condition", session.performanceCondition?.let { "%+d".format(it) }),
                row("VO₂ max", session.vo2Max?.let { "%.1f ml/kg/min".format(it) }),
            ),
        )
    }
    if (session?.avgPowerW != null || session?.maxPowerW != null || session?.normalizedPowerW != null) {
        group(
            "Power",
            PulseColors.Magenta,
            listOf(
                row("Average power", session.avgPowerW?.let { "%.0f W".format(it) }),
                row("Maximum power", session.maxPowerW?.let { "%.0f W".format(it) }),
                row("Normalized power", session.normalizedPowerW?.let { "%.0f W".format(it) }),
            ),
        )
    }
    if (session != null && (
        session.avgCadenceSpm != null ||
            session.maxCadenceSpm != null ||
            session.avgStrideLengthM != null ||
            session.avgVerticalRatioPct != null ||
            session.avgVerticalOscillationMm != null ||
            session.avgGroundContactMs != null
        )
    ) {
        group(
            "Running dynamics",
            PulseColors.Orange,
            listOf(
                row("Average cadence", session.avgCadenceSpm?.let { "%.0f spm".format(it) }),
                row("Maximum cadence", session.maxCadenceSpm?.let { "%.0f spm".format(it) }),
                row("Average stride length", session.avgStrideLengthM?.let { "%.2f m".format(it) }),
                row("Average vertical ratio", session.avgVerticalRatioPct?.let { "%.1f%%".format(it) }),
                row("Average vertical oscillation", session.avgVerticalOscillationMm?.let { "%.1f cm".format(it / 10.0) }),
                row("Average ground contact time", session.avgGroundContactMs?.let { "%.0f ms".format(it) }),
            ),
        )
    }
    if (session != null && (
        session.ascentMeters != null ||
            session.descentMeters != null ||
            session.minAltitudeMeters != null ||
            session.maxAltitudeMeters != null
        )
    ) {
        group(
            "Elevation",
            PulseColors.Accent,
            listOf(
                row("Total ascent", formatMeters(session.ascentMeters)),
                row("Total descent", formatMeters(session.descentMeters)),
                row("Minimum elevation", formatMeters(session.minAltitudeMeters)),
                row("Maximum elevation", formatMeters(session.maxAltitudeMeters)),
            ),
        )
    }
    if (isRun && distance > 0.0) {
        group(
            "Training intervals",
            PulseColors.Blue,
            listOf(
                row("Run time", formatElapsedTime(timer)),
                row("Run distance", formatDistanceKm(distance)),
                row("Run pace", formatPaceSeconds(avgPace)),
            ),
        )
    }
    if (session != null && (
        session.totalCalories != null ||
            session.fatCalories != null ||
            session.activeCalories != null ||
            session.restingCalories != null
        )
    ) {
        group(
            "Calories",
            PulseColors.Coral,
            listOf(
                row("Total calories", session.totalCalories?.let { "$it kcal" }),
                row("Active calories", session.activeCalories?.let { "$it kcal" }),
                row("Resting calories", session.restingCalories?.let { "$it kcal" }),
                row("Fat calories", session.fatCalories?.let { "$it kcal" }),
            ),
        )
    }
    if (session != null && (
        session.bodyBatteryStart != null ||
            session.bodyBatteryEnd != null ||
            session.bodyBatteryNet != null
        )
    ) {
        group(
            "Body Battery",
            PulseColors.Accent,
            listOf(
                row("Start", session.bodyBatteryStart?.let { "$it" }),
                row("End", session.bodyBatteryEnd?.let { "$it" }),
                row("Net impact", session.bodyBatteryNet?.let { "%+d".format(it) }),
            ),
        )
    }
    if (session != null && (
        session.avgTemperatureC != null ||
            session.minTemperatureC != null ||
            session.maxTemperatureC != null
        )
    ) {
        group(
            "Environment",
            PulseColors.Yellow,
            listOf(
                row("Average temperature", session.avgTemperatureC?.let { "%.0f °C".format(it) }),
                row("Minimum temperature", session.minTemperatureC?.let { "%.0f °C".format(it) }),
                row("Maximum temperature", session.maxTemperatureC?.let { "%.0f °C".format(it) }),
            ),
        )
    }
    if (session != null && (
        session.avgRespiration != null ||
            session.avgSpo2 != null ||
            session.avgStress != null
        )
    ) {
        group(
            "Breathing and stress",
            PulseColors.Teal,
            listOf(
                row("Average respiration", session.avgRespiration?.let { "%.1f brpm".format(it) }),
                row("Average SpO₂", session.avgSpo2?.let { "$it%" }),
                row("Average stress", session.avgStress?.let { "$it" }),
            ),
        )
    }
    if (insights == null) {
        group(
            "Session",
            PulseColors.Accent,
            listOf(
                row("Distance", formatDistanceKm(distance)),
                row("Total time", formatElapsedTime(timer)),
                row("Elapsed time", formatElapsedTime(elapsed)),
                row("Average heart rate", activity.avgHeartRate?.let { "$it bpm" }),
                row("Maximum heart rate", activity.maxHeartRate?.let { "$it bpm" }),
                row("Calories", activity.calories?.let { "$it kcal" }),
                row("Ascent", activity.ascentMeters?.let { "$it m" }),
                row("Descent", activity.descentMeters?.let { "$it m" }),
            ),
        )
    }
    if (insights == null && dynamics?.available == true) {
        group(
            "Recorded metrics",
            PulseColors.Orange,
            listOf(
                row("Cadence", dynamics.cadenceSpm?.let { "%.0f spm".format(it) }),
                row("Ground contact time", dynamics.groundContactTimeMs?.let { "%.0f ms".format(it) }),
                row("Vertical oscillation", dynamics.verticalOscillationMm?.let { "%.1f cm".format(it / 10.0) }),
                row("Vertical ratio", dynamics.verticalRatioPct?.let { "%.1f%%".format(it) }),
                row("Stride length", dynamics.strideLengthMm?.let { "%.2f m".format(it / 1000.0) }),
                row("Power", dynamics.powerW?.let { "$it W" }),
                row("Training effect", dynamics.trainingEffect?.let { "%.1f".format(it) }),
                row("Anaerobic TE", dynamics.anaerobicTrainingEffect?.let { "%.1f".format(it) }),
                row("Moving time", dynamics.movingTimeSeconds?.let { formatElapsedTime(it) }),
            ),
        )
    }
    return groups
}

// ---------------------------------------------------------------------------
// Charts
// ---------------------------------------------------------------------------

private data class ActivityChartSpec(
    val title: String,
    val color: Color,
    val points: List<ChartPoint>,
    val unit: String,
    val xTitle: String,
    val xFormatter: (Float) -> String,
    val yFormatter: (Float) -> String,
    val primaryLabel: String,
    val primaryValue: String,
    val secondaryLabel: String?,
    val secondaryValue: String?,
    val invertY: Boolean = false,
    val baseline: Float? = null,
    val caption: String? = null,
)

@Composable
private fun ActivityCharts(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    zones: ActivityZoneSet?,
    powerZones: ActivityZoneSet?,
    fallbackZones: ZoneSummary?,
    maxHeartRate: Int?,
    onOpenSettings: () -> Unit,
) {
    val samples = insights?.samples.orEmpty()
    var useDistance by rememberSaveable(activity.id) { mutableIntStateOf(0) }
    val distanceAvailable = samples.count { it.distanceMeters != null } >= 2
    val charts = remember(insights, useDistance) {
        buildActivityCharts(activity, insights, useDistance == 1)
    }
    if (distanceAvailable) {
        SegmentedControl(
            options = listOf("Time", "Distance"),
            selectedIndex = useDistance,
            onSelected = { useDistance = it },
        )
    }
    if (charts.isEmpty()) {
        EmptyState(
            title = "No chart samples",
            message = "This activity has no record samples for heart rate, pace, elevation or other metrics. Re-sync it or import the original FIT file.",
        )
    } else {
        charts.forEach { chart -> ActivityChartCard(chart) }
        Text(
            "Charts use recorded samples only; gaps are not filled. Pinch to zoom, drag to pan and tap to read a value.",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
    }
    val isRunOrWalk = categoryForSport(activity.sport, activity.subSport) in
        setOf(SportCategory.Run, SportCategory.Walk)
    if (isRunOrWalk) {
        insights?.runWalk?.let { runWalk ->
            if (insights.movementSegments.isNotEmpty()) {
                RunWalkCard(
                    runWalk = runWalk,
                    segments = insights.movementSegments,
                    useDistance = useDistance == 1,
                )
            }
        }
    }
    HeartRateZonesCard(
        zones = zones,
        fallback = fallbackZones,
        maxHeartRate = maxHeartRate,
        compact = false,
        onOpenSettings = onOpenSettings,
    )
    if (powerZones != null) {
        PowerZonesCard(powerZones)
    }
}

@Composable
private fun ActivityChartCard(chart: ActivityChartSpec) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(chart.color))
                Spacer(Modifier.width(PulseSpacing.sm))
                Text(chart.title, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xl)) {
                TinyStat(chart.primaryLabel, chart.primaryValue, Modifier.weight(1f))
                chart.secondaryLabel?.let { label ->
                    TinyStat(label, chart.secondaryValue ?: "—", Modifier.weight(1f))
                }
            }
            InteractiveChart(
                series = listOf(InteractiveSeries(chart.title, chart.color, chart.points)),
                style = InteractiveChartStyle.Line,
                unit = chart.unit,
                xTitle = chart.xTitle,
                xValueFormatter = chart.xFormatter,
                yValueFormatter = chart.yFormatter,
                invertY = chart.invertY,
                baseline = chart.baseline,
                fill = true,
                accessibility = "${chart.title}, ${chart.primaryLabel} ${chart.primaryValue}",
                title = chart.title,
            )
            chart.caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
    }
}

@Composable
private fun RunWalkCard(
    runWalk: RunWalkSplit,
    segments: List<dev.opengarmin.app.data.MovementSegment>,
    useDistance: Boolean,
) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Run / walk", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                TinyStat("Running", formatElapsedTime(runWalk.runningSeconds), Modifier.weight(1f))
                TinyStat("Walking", formatElapsedTime(runWalk.walkingSeconds), Modifier.weight(1f))
                TinyStat("Idle", formatElapsedTime(runWalk.idleSeconds), Modifier.weight(1f))
            }
            InteractiveMovementChart(
                segments = segments,
                useDistance = useDistance,
                xValueFormatter = { value ->
                    if (useDistance) "%.1f".format(value) else formatElapsedTick(value)
                },
                xTitle = if (useDistance) "Distance (km)" else "Elapsed",
                title = "Run / walk",
            )
            Text(
                "Estimated from the recorded speed samples because the FIT file does not contain the watch's own run/walk detection.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
    }
}

private fun buildActivityCharts(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    useDistance: Boolean,
): List<ActivityChartSpec> {
    if (insights == null) return emptyList()
    val samples = insights.samples
    val session = insights.session
    val isRide = categoryForSport(activity.sport, activity.subSport) == SportCategory.Ride
    val xTitle = if (useDistance) "Distance (km)" else "Elapsed"
    val xFormatter: (Float) -> String = if (useDistance) {
        { value -> "%.1f".format(value) }
    } else {
        { value -> formatElapsedTick(value) }
    }

    fun points(selector: (ActivitySample) -> Double?): List<ChartPoint> {
        val mapped = samples.mapNotNull { sample ->
            val y = selector(sample) ?: return@mapNotNull null
            val x = if (useDistance) sample.distanceMeters?.div(1000.0) else sample.elapsedSeconds
            if (x == null || !x.isFinite() || !y.isFinite()) return@mapNotNull null
            ChartPoint(x.toFloat(), y.toFloat())
        }.sortedBy { it.x }
        return downsample(mapped, 900)
    }

    val charts = mutableListOf<ActivityChartSpec>()
    val pacePoints = points { sample ->
        sample.speedMps?.takeIf { it >= 1.0 }?.let { 1000.0 / it }
    }
    if (pacePoints.size >= 2 && session.avgPaceSecondsPerKm != null && !isRide) {
        charts += ActivityChartSpec(
            title = "Pace",
            color = PulseColors.Blue,
            points = pacePoints,
            unit = "min/km",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> formatPaceAxis(value) },
            primaryLabel = "Average",
            primaryValue = formatPaceSeconds(session.avgPaceSecondsPerKm),
            secondaryLabel = "Best",
            secondaryValue = formatPaceSeconds(session.bestPaceSecondsPerKm),
            invertY = true,
            caption = "Faster pace is higher on the chart. Derived from the recorded speed samples.",
        )
    }
    val speedPoints = points { sample -> sample.speedMps?.times(3.6) }
    if (speedPoints.size >= 2 && speedPoints.any { it.y > 0.1f }) {
        charts += ActivityChartSpec(
            title = "Speed",
            color = PulseColors.Teal,
            points = speedPoints,
            unit = "km/h",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.1f".format(value) },
            primaryLabel = "Average",
            primaryValue = formatSpeed(session.avgSpeedKmh),
            secondaryLabel = "Maximum",
            secondaryValue = formatSpeed(session.maxSpeedKmh),
            baseline = 0f,
        )
    }
    val hrPoints = points { sample -> sample.heartRate?.toDouble() }
    if (hrPoints.size >= 2) {
        charts += ActivityChartSpec(
            title = "Heart rate",
            color = PulseColors.Coral,
            points = hrPoints,
            unit = "bpm",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgHeartRate?.let { "$it bpm" } ?: "—",
            secondaryLabel = "Maximum",
            secondaryValue = session.maxHeartRate?.let { "$it bpm" } ?: "—",
        )
    }
    val elevationPoints = points { sample -> sample.altitudeMeters }
    if (elevationPoints.size >= 2) {
        charts += ActivityChartSpec(
            title = "Elevation",
            color = PulseColors.Accent,
            points = elevationPoints,
            unit = "m",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Minimum",
            primaryValue = formatMeters(session.minAltitudeMeters),
            secondaryLabel = "Maximum",
            secondaryValue = formatMeters(session.maxAltitudeMeters),
        )
    }
    val powerPoints = points { sample -> sample.powerW }
    if (powerPoints.size >= 2 && powerPoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Power",
            color = PulseColors.Magenta,
            points = powerPoints,
            unit = "W",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgPowerW?.let { "%.0f W".format(it) } ?: "—",
            secondaryLabel = "Maximum",
            secondaryValue = session.maxPowerW?.let { "%.0f W".format(it) } ?: "—",
            baseline = 0f,
        )
    }
    val cadencePoints = points { sample -> sample.cadenceSpm }
    if (cadencePoints.size >= 2 && cadencePoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Cadence",
            color = PulseColors.Orange,
            points = cadencePoints,
            unit = "spm",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgCadenceSpm?.let { "%.0f spm".format(it) } ?: "—",
            secondaryLabel = "Maximum",
            secondaryValue = session.maxCadenceSpm?.let { "%.0f spm".format(it) } ?: "—",
        )
    }
    val stridePoints = points { sample -> sample.strideLengthM }
    if (stridePoints.size >= 2 && stridePoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Stride length",
            color = PulseColors.Violet,
            points = stridePoints,
            unit = "m",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.2f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgStrideLengthM?.let { "%.2f m".format(it) } ?: "—",
            secondaryLabel = null,
            secondaryValue = null,
        )
    }
    val verticalRatioPoints = points { sample -> sample.verticalRatioPct }
    if (verticalRatioPoints.size >= 2 && verticalRatioPoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Vertical ratio",
            color = PulseColors.Yellow,
            points = verticalRatioPoints,
            unit = "%",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.1f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgVerticalRatioPct?.let { "%.1f%%".format(it) } ?: "—",
            secondaryLabel = null,
            secondaryValue = null,
        )
    }
    val verticalOscillationPoints = points { sample -> sample.verticalOscillationMm?.div(10.0) }
    if (verticalOscillationPoints.size >= 2 && verticalOscillationPoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Vertical oscillation",
            color = PulseColors.Teal,
            points = verticalOscillationPoints,
            unit = "cm",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.1f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgVerticalOscillationMm?.let { "%.1f cm".format(it / 10.0) } ?: "—",
            secondaryLabel = null,
            secondaryValue = null,
        )
    }
    val groundContactPoints = points { sample -> sample.groundContactMs }
    if (groundContactPoints.size >= 2 && groundContactPoints.any { it.y > 0f }) {
        charts += ActivityChartSpec(
            title = "Ground contact time",
            color = PulseColors.Coral,
            points = groundContactPoints,
            unit = "ms",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Average",
            primaryValue = session.avgGroundContactMs?.let { "%.0f ms".format(it) } ?: "—",
            secondaryLabel = null,
            secondaryValue = null,
        )
    }
    val performancePoints = points { sample -> sample.performanceCondition?.toDouble() }
    if (performancePoints.size >= 2) {
        val values = performancePoints.map { it.y }
        charts += ActivityChartSpec(
            title = "Performance condition",
            color = PulseColors.TextMuted,
            points = performancePoints,
            unit = "",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%+.0f".format(value) },
            primaryLabel = "Final",
            primaryValue = session.performanceCondition?.let { "%+d".format(it) } ?: "—",
            secondaryLabel = "Range",
            secondaryValue = "${"%+.0f".format(values.min())} to ${"%+.0f".format(values.max())}",
            baseline = 0f,
        )
    }
    val bodyBatteryPoints = points { sample -> sample.bodyBattery?.toDouble() }
    if (bodyBatteryPoints.size >= 2) {
        charts += ActivityChartSpec(
            title = "Body Battery",
            color = PulseColors.Accent,
            points = bodyBatteryPoints,
            unit = "",
            xTitle = xTitle,
            xFormatter = xFormatter,
            yFormatter = { value -> "%.0f".format(value) },
            primaryLabel = "Start",
            primaryValue = session.bodyBatteryStart?.toString() ?: "—",
            secondaryLabel = "End",
            secondaryValue = session.bodyBatteryEnd?.toString() ?: "—",
        )
    }
    return charts
}

private fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
    if (points.size <= maxPoints) return points
    val bucketSize = ceil(points.size.toDouble() / (maxPoints / 2).coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val output = ArrayList<ChartPoint>(maxPoints + 4)
    var index = 0
    while (index < points.size) {
        val end = minOf(index + bucketSize, points.size)
        var minPoint = points[index]
        var maxPoint = points[index]
        for (position in index until end) {
            val point = points[position]
            if (point.y < minPoint.y) minPoint = point
            if (point.y > maxPoint.y) maxPoint = point
        }
        if (minPoint.x <= maxPoint.x) {
            output.add(minPoint)
            if (maxPoint != minPoint) output.add(maxPoint)
        } else {
            output.add(maxPoint)
            if (maxPoint != minPoint) output.add(minPoint)
        }
        index = end
    }
    if (output.firstOrNull() != points.first()) output.add(0, points.first())
    if (output.lastOrNull() != points.last()) output.add(points.last())
    return output
}

// ---------------------------------------------------------------------------
// Zones and training effect
// ---------------------------------------------------------------------------

@Composable
private fun TrainingEffectCard(
    aerobic: Double?,
    anaerobic: Double?,
    primaryBenefit: String?,
    exerciseLoad: Double?,
    recoveryMinutes: Int?,
) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Training effect", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            primaryBenefit?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Accent,
                )
            }
            aerobic?.let {
                TrainingEffectGauge("Aerobic", it, PulseColors.Blue)
            }
            anaerobic?.let {
                TrainingEffectGauge("Anaerobic", it, PulseColors.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                TinyStat("Exercise load", exerciseLoad?.let { "%.0f".format(it) } ?: "—", Modifier.weight(1f))
                TinyStat("Recovery", formatRecovery(recoveryMinutes) ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrainingEffectGauge(label: String, value: Double, color: Color) {
    val bands = listOf(
        PulseColors.BorderStrong,
        PulseColors.Blue,
        PulseColors.Accent,
        PulseColors.Orange,
        PulseColors.Coral,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted, modifier = Modifier.weight(1f))
            Text("%.1f".format(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        }
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxWidth().height(24.dp),
        ) {
            val gap = 3f
            val barHeight = 14.dp.toPx()
            val barTop = (size.height - barHeight) / 2f
            val bandWidth = (size.width - gap * (bands.size - 1)) / bands.size
            bands.forEachIndexed { index, bandColor ->
                val left = index * (bandWidth + gap)
                drawRoundRect(
                    color = bandColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left, barTop),
                    size = androidx.compose.ui.geometry.Size(bandWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                )
            }
            val fraction = (value / 5.0).coerceIn(0.0, 1.0).toFloat()
            val outerRadius = barHeight * 0.70f
            val innerRadius = barHeight * 0.58f
            val centerY = size.height / 2f
            val x = (fraction * size.width).coerceIn(
                outerRadius + 1f,
                (size.width - outerRadius - 1f).coerceAtLeast(outerRadius + 1f),
            )
            drawCircle(
                color = PulseColors.Background,
                radius = outerRadius,
                center = androidx.compose.ui.geometry.Offset(x, centerY),
            )
            drawCircle(
                color = color,
                radius = innerRadius,
                center = androidx.compose.ui.geometry.Offset(x, centerY),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            Spacer(Modifier.weight(1f))
            Text("5", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

@Composable
private fun HeartRateZonesCard(
    zones: ActivityZoneSet?,
    fallback: ZoneSummary?,
    maxHeartRate: Int?,
    compact: Boolean,
    onOpenSettings: () -> Unit,
) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Heart-rate zones", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            when {
                zones != null -> {
                    ZoneBar(
                        percentages = zones.zones.map { it.percent },
                        contentDescription = "Time in heart-rate zones",
                    )
                    if (compact) {
                        ZoneLegend(percentages = zones.zones.map { it.percent })
                    } else {
                        zones.zones.forEach { zone ->
                            ZoneRow(
                                zone = zone,
                                unit = "bpm",
                                color = PulseColors.ZoneColors[(zone.index - 1) % PulseColors.ZoneColors.size],
                            )
                        }
                    }
                    val basis = zones.maxHeartRate ?: maxHeartRate
                    val source = if (zones.fromWatch) "Watch zones" else "Computed from your max HR"
                    Text(
                        if (basis != null) {
                            "$source $basis bpm · total ${formatElapsedTime(zones.totalSeconds)}"
                        } else {
                            "$source · total ${formatElapsedTime(zones.totalSeconds)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                fallback != null -> {
                    ZoneBar(
                        percentages = fallback.percentages,
                        contentDescription = "Time in heart-rate zones",
                    )
                    if (compact) {
                        ZoneLegend(percentages = fallback.percentages)
                    } else {
                        fallback.percentages.forEachIndexed { index, percent ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(PulseColors.ZoneColors[index % PulseColors.ZoneColors.size]),
                                )
                                Spacer(Modifier.width(PulseSpacing.sm))
                                Text(
                                    "Zone ${index + 1}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PulseColors.TextMuted,
                                )
                                Text(
                                    "${percent.roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PulseColors.Text,
                                )
                            }
                        }
                    }
                    Text(
                        "Estimated from heart-rate samples and max HR ${fallback.maxHeartRate} bpm.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                else -> {
                    Text(
                        if (maxHeartRate == null) {
                            "Add your max heart rate in Settings to calculate zones. No estimate is invented."
                        } else {
                            "This activity has no heart-rate samples, so zones cannot be calculated."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    if (maxHeartRate == null) {
                        PrimaryButton("Set max heart rate", onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerZonesCard(zones: ActivityZoneSet) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Power zones", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            ZoneBar(
                percentages = zones.zones.map { it.percent },
                contentDescription = "Time in power zones",
            )
            zones.zones.forEach { zone ->
                ZoneRow(
                    zone = zone,
                    unit = "W",
                    color = PulseColors.ZoneColors[(zone.index - 1) % PulseColors.ZoneColors.size],
                )
            }
            Text(
                zones.thresholdPowerW?.let {
                    "Watch zones · FTP $it W · total ${formatElapsedTime(zones.totalSeconds)}"
                } ?: "Watch zones · total ${formatElapsedTime(zones.totalSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
    }
}

@Composable
private fun ZoneRow(
    zone: dev.opengarmin.app.data.ActivityZone,
    unit: String,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Z${zone.index} · ${zone.label}",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Text,
            )
            val range = if (zone.lowBoundary != null && zone.highBoundary != null) {
                when {
                    zone.index == 5 -> "${zone.lowBoundary}+ $unit"
                    zone.highBoundary - 1 >= zone.lowBoundary ->
                        "${zone.lowBoundary}–${zone.highBoundary - 1} $unit"
                    else -> "${zone.lowBoundary}–${zone.highBoundary} $unit"
                }
            } else {
                null
            }
            if (range != null) {
                Text(range, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatElapsedTime(zone.seconds), style = MaterialTheme.typography.bodySmall, color = PulseColors.Text)
            Text("${zone.percent.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        }
    }
}

// ---------------------------------------------------------------------------
// Laps
// ---------------------------------------------------------------------------

@Composable
private fun ActivityLaps(
    activity: ActivityEntity,
    insights: ActivityInsights?,
    laps: List<LapEntity>,
) {
    val isRide = categoryForSport(activity.sport, activity.subSport) == SportCategory.Ride
    val rows = remember(insights, laps) {
        insights?.laps?.takeIf { it.isNotEmpty() } ?: laps.sortedBy { it.lapIndex }.map { lap ->
            LapInsights(
                index = lap.lapIndex,
                distanceMeters = lap.distanceMeters,
                durationSeconds = lap.durationSeconds,
                paceSecondsPerKm = if (lap.distanceMeters > 0.0 && lap.durationSeconds > 0.0) {
                    lap.durationSeconds / (lap.distanceMeters / 1000.0)
                } else {
                    null
                },
                avgHeartRate = lap.avgHeartRate,
                maxHeartRate = lap.maxHeartRate,
                calories = lap.calories,
                ascentMeters = lap.ascentMeters,
                descentMeters = lap.descentMeters,
                cadenceSpm = null,
                strideLengthM = null,
                verticalOscillationMm = null,
                verticalRatioPct = null,
                groundContactMs = null,
                powerW = null,
            )
        }
    }
    if (rows.isEmpty()) {
        EmptyState(
            title = "No laps recorded",
            message = "This activity was saved as a single session without lap markers.",
        )
        return
    }
    val hasCadence = rows.any { it.cadenceSpm != null }
    val hasStride = rows.any { it.strideLengthM != null }
    val hasVerticalOscillation = rows.any { it.verticalOscillationMm != null }
    val hasVerticalRatio = rows.any { it.verticalRatioPct != null }
    val hasGroundContact = rows.any { it.groundContactMs != null }
    val hasPower = rows.any { it.powerW != null }

    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text("Laps", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Column(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LapCell("#", 34.dp, header = true)
                    LapCell("Distance", 92.dp, header = true)
                    LapCell("Time", 74.dp, header = true)
                    LapCell(if (isRide) "Speed" else "Pace", 88.dp, header = true)
                    LapCell("Avg HR", 66.dp, header = true)
                    LapCell("Max HR", 66.dp, header = true)
                    LapCell("Cal", 52.dp, header = true)
                    LapCell("Ascent", 60.dp, header = true)
                    LapCell("Descent", 66.dp, header = true)
                    if (hasCadence) LapCell("Cadence", 76.dp, header = true)
                    if (hasStride) LapCell("Stride", 66.dp, header = true)
                    if (hasVerticalOscillation) LapCell("Vert. osc.", 74.dp, header = true)
                    if (hasVerticalRatio) LapCell("Vert. ratio", 76.dp, header = true)
                    if (hasGroundContact) LapCell("Contact", 70.dp, header = true)
                    if (hasPower) LapCell("Power", 62.dp, header = true)
                }
                rows.forEach { lap ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LapCell("${lap.index + 1}", 34.dp)
                        LapCell(formatDistanceKm(lap.distanceMeters), 92.dp)
                        LapCell(formatElapsedTime(lap.durationSeconds), 74.dp)
                        LapCell(
                            if (isRide) {
                                formatSpeed(
                                    if (lap.durationSeconds > 0.0 && lap.distanceMeters > 0.0) {
                                        lap.distanceMeters / lap.durationSeconds * 3.6
                                    } else {
                                        null
                                    },
                                )
                            } else {
                                formatPaceSeconds(lap.paceSecondsPerKm)
                            },
                            88.dp,
                        )
                        LapCell(lap.avgHeartRate?.toString() ?: "—", 66.dp)
                        LapCell(lap.maxHeartRate?.toString() ?: "—", 66.dp)
                        LapCell(lap.calories?.toString() ?: "—", 52.dp)
                        LapCell(lap.ascentMeters?.toString() ?: "—", 60.dp)
                        LapCell(lap.descentMeters?.toString() ?: "—", 66.dp)
                        if (hasCadence) LapCell(lap.cadenceSpm?.let { "%.0f".format(it) } ?: "—", 76.dp)
                        if (hasStride) LapCell(lap.strideLengthM?.let { "%.2f".format(it) } ?: "—", 66.dp)
                        if (hasVerticalOscillation) {
                            LapCell(lap.verticalOscillationMm?.let { "%.1f".format(it / 10.0) } ?: "—", 74.dp)
                        }
                        if (hasVerticalRatio) {
                            LapCell(lap.verticalRatioPct?.let { "%.1f".format(it) } ?: "—", 76.dp)
                        }
                        if (hasGroundContact) LapCell(lap.groundContactMs?.let { "%.0f".format(it) } ?: "—", 70.dp)
                        if (hasPower) LapCell(lap.powerW?.let { "%.0f".format(it) } ?: "—", 62.dp)
                    }
                }
            }
            val units = buildList {
                add(if (isRide) "speed in km/h" else "pace in min/km")
                if (hasCadence) add("cadence in spm")
                if (hasStride) add("stride in m")
                if (hasVerticalOscillation) add("vertical oscillation in cm")
                if (hasVerticalRatio) add("vertical ratio in %")
                if (hasGroundContact) add("contact in ms")
                if (hasPower) add("power in W")
            }
            Text(
                "Swipe the table sideways. Units: ${units.joinToString()}.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
    }
}

@Composable
private fun LapCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(vertical = PulseSpacing.xs),
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        color = if (header) PulseColors.TextMuted else PulseColors.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private fun formatSpeed(kmh: Double?): String =
    if (kmh == null || !kmh.isFinite()) "—" else "%.1f km/h".format(kmh)

private fun formatMeters(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val rounded = value.roundToInt()
    return if (rounded == 0) "0 m" else "$rounded m"
}

private fun formatPaceSeconds(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "—"
    return "${formatPacePerKm(seconds.roundToInt())} /km"
}

private fun formatPaceAxis(seconds: Float): String {
    if (!seconds.isFinite() || seconds <= 0f) return "—"
    return formatPacePerKm(seconds.roundToInt())
}

private fun formatElapsedTick(seconds: Float): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    return if (total >= 3600L) {
        "%d:%02d".format(total / 3600, (total % 3600) / 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}

private fun formatRecovery(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    return if (minutes >= 60) {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "${hours} h" else "${hours} h ${rest} min"
    } else {
        "$minutes min"
    }
}
