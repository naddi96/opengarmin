package dev.opengarmin.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSplit
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PaceRunScreen(
    plan: PacePlanEntity,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val splits = plan.splits
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedBeforePause by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var distanceMeters by remember { mutableStateOf(0.0) }
    var speedMps by remember { mutableStateOf(0.0) }
    var gpsLocked by remember { mutableStateOf(false) }
    var lastFix by remember { mutableStateOf<Location?>(null) }
    var completedSplitTimes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var manualLaps by remember { mutableStateOf(0) }
    var showStopDialog by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        running = true
        paused = false
        elapsedBeforePause = 0L
        startedAt = SystemClock.elapsedRealtime()
    }

    fun start() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            running = true
            paused = false
            elapsedBeforePause = 0L
            startedAt = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(running, paused) {
        while (running && !paused) {
            elapsedMillis = elapsedBeforePause + (SystemClock.elapsedRealtime() - startedAt)
            delay(200)
        }
    }

    DisposableEffect(running) {
        val activity = context as? Activity
        if (running) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(running, paused, permissionGranted) {
        if (!running || paused || !permissionGranted) return@DisposableEffect onDispose {}
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = LocationListener { location ->
            gpsLocked = location.accuracy <= 50f
            if (paused) return@LocationListener
            val previous = lastFix
            if (previous == null) {
                lastFix = location
                return@LocationListener
            }
            val dt = (location.time - previous.time) / 1000.0
            if (dt <= 0.0 || dt > 30.0) return@LocationListener
            val delta = previous.distanceTo(location).toDouble()
            if (delta in 0.0..500.0) {
                distanceMeters += delta
                val measured = if (location.hasSpeed() && location.speed > 0f) {
                    location.speed.toDouble()
                } else {
                    delta / dt
                }
                speedMps = if (speedMps > 0.0) speedMps * 0.65 + measured * 0.35 else measured
            }
            lastFix = location
            val elapsedSeconds = (
                elapsedBeforePause + (SystemClock.elapsedRealtime() - startedAt)
                ).coerceAtLeast(0L) / 1000
            val updated = completedSplitTimes.toMutableMap()
            splits.forEachIndexed { index, split ->
                if (index !in updated && distanceMeters >= split.cumulativeDistanceMeters) {
                    updated[index] = elapsedSeconds.toInt()
                }
            }
            if (updated.size != completedSplitTimes.size) completedSplitTimes = updated
        }
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                1f,
                listener,
                Looper.getMainLooper(),
            )
        }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }

    BackHandler {
        if (running) showStopDialog = true else onStop()
    }

    val elapsedSeconds = (elapsedMillis / 1000).toInt()
    val currentIndex = splits.indexOfFirst { distanceMeters < it.cumulativeDistanceMeters }
        .let { if (it < 0) splits.lastIndex.coerceAtLeast(0) else it }
    val currentSplit = splits.getOrNull(currentIndex)
    val targetPace = currentSplit?.paceSecondsPerKm() ?: 0
    val currentPace = when {
        speedMps > 0.5 -> (1000.0 / speedMps).roundToInt()
        distanceMeters > 100.0 -> (elapsedSeconds / (distanceMeters / 1000.0)).roundToInt()
        else -> 0
    }
    val expectedSeconds = expectedSecondsAt(splits, distanceMeters)
    val overallDiff = elapsedSeconds - expectedSeconds
    val splitStartTarget = if (currentIndex == 0) 0 else splits[currentIndex - 1].cumulativeSeconds
    val splitStartActual = if (currentIndex == 0) {
        0
    } else {
        completedSplitTimes[currentIndex - 1] ?: elapsedSeconds
    }
    val splitExpected = expectedSeconds - splitStartTarget
    val splitActual = elapsedSeconds - splitStartActual
    val splitDiff = splitActual - splitExpected
    val finishEstimate = (plan.goalSeconds + overallDiff).coerceAtLeast(0)

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, "Back", {
                    if (running) showStopDialog = true else onStop()
                })
                Spacer(Modifier.width(PulseSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("PacePro · ${plan.courseName}", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Text(
                        "Target ${PacePlanCalculator.formatClock(plan.goalSeconds)} · ${"%.2f".format(plan.distanceMeters / 1000.0)} km",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    when {
                        !permissionGranted -> "GPS off"
                        gpsLocked -> "GPS locked"
                        running -> "Waiting for GPS"
                        else -> "Ready"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (gpsLocked) PulseColors.TextOnAccent else PulseColors.TextMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (gpsLocked) PulseColors.Accent else PulseColors.SurfaceElevated)
                        .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.xs),
                )
            }

            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Split ${currentIndex + 1} · current pace",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            "Target ${PacePlanCalculator.formatPace(targetPace)} /km",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (currentPace > 0) PacePlanCalculator.formatPace(currentPace) else "—:—",
                            style = MaterialTheme.typography.displayMedium,
                            color = PulseColors.Text,
                        )
                        Text(
                            " /km",
                            style = MaterialTheme.typography.bodyLarge,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    val ahead = splitDiff <= 0
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PulseColors.SurfaceElevated)
                            .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                    ) {
                        Text(
                            if (ahead) "↓" else "↑",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ahead) PulseColors.Accent else PulseColors.Coral,
                        )
                        Text(
                            "${if (ahead) "Ahead" else "Behind"} by ${PacePlanCalculator.formatClock(abs(splitDiff))} on this split",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ahead) PulseColors.Accent else PulseColors.Coral,
                        )
                    }
                }
            }

            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("This split", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            currentSplit?.let {
                                val left = (it.cumulativeDistanceMeters - distanceMeters).coerceAtLeast(0.0)
                                "%.2f km left".format(left / 1000.0)
                            } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    val progress = currentSplit?.let {
                        val start = it.cumulativeDistanceMeters - it.distanceMeters
                        ((distanceMeters - start) / it.distanceMeters).coerceIn(0.0, 1.0)
                    } ?: 0.0
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(PulseColors.RingTrack),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.toFloat())
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(PulseColors.Accent),
                        )
                    }
                }
            }

            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Overall", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            "${if (overallDiff <= 0) "Ahead" else "Behind"} ${PacePlanCalculator.formatClock(abs(overallDiff))}",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (overallDiff <= 0) PulseColors.Accent else PulseColors.Coral,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Finish estimate", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            PacePlanCalculator.formatClock(finishEstimate),
                            style = MaterialTheme.typography.titleLarge,
                            color = PulseColors.Text,
                        )
                    }
                }
            }

            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Text("Splits", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    SplitGrid(
                        splits = splits,
                        currentIndex = currentIndex,
                        completed = completedSplitTimes,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
                        Text("✓ on pace", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text("↓ ahead", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text("↑ behind", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                        Text("○ upcoming", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    }
                }
            }

            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Elevation", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                        Text(
                            nextClimbLabel(splits, currentIndex),
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    ElevationStrip(splits = splits, currentIndex = currentIndex)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                RunStat("Distance", "%.2f km".format(distanceMeters / 1000.0), Modifier.weight(1f))
                RunStat("Time", PacePlanCalculator.formatClock(elapsedSeconds), Modifier.weight(1f))
                RunStat("Heart rate", "—", Modifier.weight(1f))
            }
            Text(
                "Live heart rate needs the watch's Broadcast Heart Rate mode. Distance and pace come from phone GPS.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            if (manualLaps > 0) {
                Text(
                    "$manualLaps manual ${if (manualLaps == 1) "lap" else "laps"} recorded.",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
            }
            Spacer(Modifier.height(PulseSpacing.lg))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Background)
                .padding(horizontal = PulseSpacing.xxl, vertical = PulseSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            RunControl(
                label = when {
                    !running -> "Start"
                    paused -> "Resume"
                    else -> "Pause"
                },
                primary = false,
                onClick = {
                    when {
                        !running -> start()
                        paused -> {
                            startedAt = SystemClock.elapsedRealtime()
                            paused = false
                        }
                        else -> {
                            elapsedBeforePause = elapsedMillis
                            paused = true
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            RunControl(
                label = "Lap",
                primary = false,
                onClick = { if (running && !paused) manualLaps++ },
                modifier = Modifier.weight(1f),
            )
            RunControl(
                label = "Stop PacePro",
                primary = true,
                onClick = { showStopDialog = true },
                modifier = Modifier.weight(1.3f),
            )
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop PacePro?") },
            text = { Text("The run timer and GPS tracking will stop. Your watch activity is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    onStop()
                }) { Text("Stop", color = PulseColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Keep running") }
            },
        )
    }
}

@Composable
private fun SplitGrid(
    splits: List<PacePlanSplit>,
    currentIndex: Int,
    completed: Map<Int, Int>,
) {
    splits.chunked(10).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.forEach { split ->
                val index = split.index
                val state = when {
                    index == currentIndex -> "current"
                    completed.containsKey(index) -> {
                        val start = if (index == 0) 0 else completed[index - 1] ?: split.cumulativeSeconds
                        val actual = (completed[index] ?: split.cumulativeSeconds) - start
                        when {
                            actual < split.targetSeconds - 1 -> "ahead"
                            actual > split.targetSeconds + 1 -> "behind"
                            else -> "on"
                        }
                    }
                    else -> "upcoming"
                }
                val icon = when (state) {
                    "on" -> "✓"
                    "ahead" -> "↓"
                    "behind" -> "↑"
                    "current" -> "▸"
                    else -> "○"
                }
                val color = when (state) {
                    "ahead" -> PulseColors.Accent
                    "behind" -> PulseColors.Coral
                    "on" -> PulseColors.Teal
                    "current" -> PulseColors.Text
                    else -> PulseColors.TextMuted
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (state == "upcoming") Color.Transparent else PulseColors.SurfaceElevated)
                        .border(
                            if (state == "current") 2.dp else 1.dp,
                            if (state == "current") PulseColors.Text else PulseColors.Border,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        icon,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                    )
                }
            }
            repeat(10 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ElevationStrip(splits: List<PacePlanSplit>, currentIndex: Int) {
    val maxGain = (splits.maxOfOrNull { it.gainMeters } ?: 0.0).coerceAtLeast(1.0)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .semantics { contentDescription = "Elevation per split with the current split marked" },
    ) {
        if (splits.isEmpty()) return@Canvas
        val slot = size.width / splits.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        splits.forEachIndexed { index, split ->
            val fraction = (split.gainMeters / maxGain).toFloat().coerceIn(0f, 1f)
            val barHeight = (size.height * fraction).coerceAtLeast(3f)
            val fill = when (index) {
                currentIndex -> PulseColors.Accent
                currentIndex + 1 -> PulseColors.Zone4
                else -> PulseColors.BorderElevated
            }
            drawRoundRect(
                color = fill,
                topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

@Composable
private fun RunStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(PulseShapes.medium)
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.Border, PulseShapes.medium)
            .padding(PulseSpacing.md),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
    }
}

@Composable
private fun RunControl(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(PulseShapes.medium)
            .background(if (primary) PulseColors.Accent else PulseColors.SurfaceElevated)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) PulseColors.TextOnAccent else PulseColors.Text,
        )
    }
}

private fun expectedSecondsAt(splits: List<PacePlanSplit>, distance: Double): Int {
    if (splits.isEmpty() || distance <= 0.0) return 0
    for (split in splits) {
        if (distance <= split.cumulativeDistanceMeters) {
            val startDistance = split.cumulativeDistanceMeters - split.distanceMeters
            val startTime = split.cumulativeSeconds - split.targetSeconds
            val fraction = ((distance - startDistance) / split.distanceMeters).coerceIn(0.0, 1.0)
            return (startTime + split.targetSeconds * fraction).roundToInt()
        }
    }
    return splits.last().cumulativeSeconds
}

private fun nextClimbLabel(splits: List<PacePlanSplit>, currentIndex: Int): String {
    val next = splits.drop(currentIndex + 1).firstOrNull { it.gainMeters >= 5.0 }
        ?: return "No climbs ahead"
    return "Next climb: +${next.gainMeters.roundToInt()} m at split ${next.index + 1}"
}
