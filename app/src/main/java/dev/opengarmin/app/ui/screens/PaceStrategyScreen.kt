package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import dev.opengarmin.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.PaceAlert
import dev.opengarmin.app.data.PaceCourse
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanCodec
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.PacePlanSplit
import dev.opengarmin.app.data.PaceRace
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val STEP_LABELS = listOf("1. Goal", "2. Course", "3. Strategy", "4. Preview")

@Composable
fun PaceStrategyScreen(
    plans: List<PacePlanEntity>,
    activities: List<ActivityEntity>,
    sendStatus: PacePlanSendStatus?,
    onBack: () -> Unit,
    onSend: (PacePlanEntity) -> Unit,
    onDelete: (Long) -> Unit,
    loadCourse: suspend (Long) -> PaceCourse?,
    onOpenRun: (PacePlanEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(0) }
    var race by remember { mutableStateOf(PaceRace.TEN_K) }
    var customMode by remember { mutableStateOf(false) }
    var customDistanceKm by remember { mutableStateOf("") }
    var goalSeconds by remember { mutableStateOf(0) }
    var activityCourseId by remember { mutableStateOf<Long?>(null) }
    var course by remember { mutableStateOf<PaceCourse?>(null) }
    var courseLoading by remember { mutableStateOf(false) }
    var splitSlider by remember { mutableStateOf(50) }
    var hillSlider by remember { mutableStateOf(50) }
    var alert by remember { mutableStateOf(PaceAlert.KILOMETRE) }
    var acceptedPlan by remember { mutableStateOf<PacePlanEntity?>(null) }
    var deletingPlan by remember { mutableStateOf<PacePlanEntity?>(null) }
    var goalTouched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val predictions = PacePlanCalculator.predictions(activities)
    val recentRuns = activities.filter { it.sport == 1 && it.distanceMeters >= 1_000.0 }
        .sortedByDescending { it.startTime }
        .take(5)
    val customDistanceMeters = customDistanceKm.replace(',', '.').toDoubleOrNull()?.times(1_000.0)
    val distanceMeters = if (customMode) customDistanceMeters ?: 0.0 else race.distanceMeters
    val raceLabel = if (customMode) "Custom" else race.label
    val estimatedGoal = PacePlanCalculator.estimateGoal(distanceMeters, predictions)

    LaunchedEffect(race, customMode, customDistanceMeters) {
        if (!goalTouched) {
            goalSeconds = if (customMode) estimatedGoal ?: 0 else predictions[race] ?: 0
        }
    }

    fun loadPlan(plan: PacePlanEntity) {
        goalTouched = true
        customMode = plan.race == "Custom"
        customDistanceKm = if (customMode) "%.2f".format(plan.distanceMeters / 1_000.0) else ""
        race = PaceRace.from(plan.race)
        goalSeconds = plan.goalSeconds
        splitSlider = plan.splitSlider
        hillSlider = plan.hillSlider
        alert = PaceAlert.from(plan.alert)
        acceptedPlan = plan
        activityCourseId = plan.courseActivityId
        if (plan.courseActivityId == null) {
            course = null
            step = 3
        } else {
            courseLoading = true
            scope.launch {
                course = loadCourse(plan.courseActivityId)
                courseLoading = false
                step = 3
            }
        }
    }

    fun pickActivityCourse(activity: ActivityEntity) {
        activityCourseId = activity.id
        courseLoading = true
        scope.launch {
            course = loadCourse(activity.id)
            courseLoading = false
        }
    }

    val splits = remember(distanceMeters, goalSeconds, splitSlider, hillSlider, course, alert) {
        if (goalSeconds > 0 && distanceMeters > 0.0) {
            PacePlanCalculator.buildSplits(
                raceDistanceMeters = distanceMeters,
                goalSeconds = goalSeconds,
                splitSlider = splitSlider,
                hillSlider = hillSlider,
                course = course,
                splitDistanceMeters = if (alert == PaceAlert.MILE) 1_609.344 else 1_000.0,
            )
        } else {
            emptyList()
        }
    }

    fun buildPlan(): PacePlanEntity {
        val now = System.currentTimeMillis() / 1000
        val existing = acceptedPlan
        return PacePlanEntity(
            id = existing?.id ?: 0L,
            name = "$raceLabel PacePro",
            race = if (customMode) "Custom" else race.id,
            distanceMeters = distanceMeters,
            goalSeconds = goalSeconds,
            courseName = course?.name ?: "Flat, no course",
            courseActivityId = activityCourseId,
            splitSlider = splitSlider,
            hillSlider = hillSlider,
            alert = alert.id,
            splitsJson = PacePlanCodec.encodeSplits(splits),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastSentAt = existing?.lastSentAt,
        )
    }

    fun accept() {
        if (goalSeconds <= 0 || splits.isEmpty()) return
        val plan = buildPlan()
        acceptedPlan = plan
        onSend(plan)
    }

    val statusForPlan = sendStatus?.takeIf { status ->
        acceptedPlan != null &&
            (status.planId == acceptedPlan?.id || status.name == acceptedPlan?.name)
    }
    LaunchedEffect(statusForPlan?.planId) {
        val savedId = statusForPlan?.planId ?: return@LaunchedEffect
        if (savedId != 0L) acceptedPlan = acceptedPlan?.copy(id = savedId)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, "Back to training", onBack)
                Spacer(Modifier.width(PulseSpacing.sm))
                Column {
                    Text("PacePro strategy", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                    Text(
                        "Split-by-split targets that follow the terrain",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }

            Stepper(step = step, onSelect = { step = it })

            when (step) {
                0 -> GoalStep(
                    race = race,
                    customMode = customMode,
                    customDistanceKm = customDistanceKm,
                    distanceMeters = distanceMeters,
                    estimatedGoal = estimatedGoal,
                    predictions = predictions,
                    goalSeconds = goalSeconds,
                    onRace = {
                        customMode = false
                        race = it
                        goalTouched = false
                        goalSeconds = predictions[it] ?: 0
                        acceptedPlan = null
                    },
                    onCustom = {
                        customMode = true
                        goalTouched = false
                        goalSeconds = estimatedGoal ?: 0
                        acceptedPlan = null
                    },
                    onCustomDistance = { value ->
                        customDistanceKm = value
                        acceptedPlan = null
                    },
                    onGoalMinus = {
                        goalTouched = true
                        val fallback = if (customMode) estimatedGoal ?: 1_800 else predictions[race] ?: 1_800
                        goalSeconds = (if (goalSeconds <= 0) fallback else goalSeconds - 15)
                            .coerceAtLeast(60)
                    },
                    onGoalPlus = {
                        goalTouched = true
                        val fallback = if (customMode) estimatedGoal ?: 1_800 else predictions[race] ?: 1_800
                        goalSeconds = (if (goalSeconds <= 0) fallback else goalSeconds + 15)
                    },
                    plans = plans,
                    onLoadPlan = { loadPlan(it) },
                    onDeletePlan = { deletingPlan = it },
                )
                1 -> CourseStep(
                    course = course,
                    activityCourseId = activityCourseId,
                    recentRuns = recentRuns,
                    loading = courseLoading,
                    onFlat = {
                        activityCourseId = null
                        course = null
                    },
                    onActivity = { pickActivityCourse(it) },
                )
                2 -> StrategyStep(
                    splitSlider = splitSlider,
                    hillSlider = hillSlider,
                    alert = alert,
                    onSplit = { splitSlider = it },
                    onHill = { hillSlider = it },
                    onAlert = { alert = it },
                )
                else -> PreviewStep(
                    raceLabel = raceLabel,
                    distanceMeters = distanceMeters,
                    course = course,
                    goalSeconds = goalSeconds,
                    splits = splits,
                    alert = alert,
                    splitSlider = splitSlider,
                    hillSlider = hillSlider,
                )
            }

            statusForPlan?.let { status ->
                SendStatusCard(status = status)
            }

            if (acceptedPlan != null && splits.isNotEmpty()) {
                PulseCard(elevated = true, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(PulseSpacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (statusForPlan?.success == true) "Plan sent to your watch" else "Plan saved on this phone",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PulseColors.Text,
                            )
                            Text(
                                "Keep this screen open while you preview the run.",
                                style = MaterialTheme.typography.labelMedium,
                                color = PulseColors.TextMuted,
                            )
                        }
                        Text(
                            "Preview run ›",
                            style = MaterialTheme.typography.labelLarge,
                            color = PulseColors.Accent,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { onOpenRun(acceptedPlan ?: buildPlan()) }
                                .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
                        )
                    }
                }
            }

            Spacer(Modifier.height(96.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Background)
                .padding(horizontal = PulseSpacing.xxl, vertical = PulseSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            if (step > 0) {
                PaceSecondaryButton(
                    label = "Back",
                    onClick = { step = (step - 1).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                step < 3 -> PrimaryButton(
                    text = "Continue",
                    onClick = { step = (step + 1).coerceAtMost(3) },
                    enabled = step != 0 || (goalSeconds > 0 && splits.isNotEmpty()),
                    modifier = Modifier.weight(2f),
                )
                else -> PrimaryButton(
                    text = if (statusForPlan?.sending == true) "Sending…" else "Accept and send to watch",
                    onClick = { if (statusForPlan?.sending != true) accept() },
                    enabled = goalSeconds > 0 && splits.isNotEmpty() && statusForPlan?.sending != true,
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }

    deletingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { deletingPlan = null },
            title = { Text("Delete strategy?") },
            text = {
                Text(
                    tr(
                        "%s will be removed from this phone. A copy already on the watch is not changed.",
                        plan.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(plan.id)
                    if (acceptedPlan?.id == plan.id) acceptedPlan = null
                    deletingPlan = null
                }) { Text("Delete", color = PulseColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deletingPlan = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Stepper(step: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        STEP_LABELS.forEachIndexed { index, label ->
            val selected = index == step
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PulseSpacing.touchTarget)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            selected -> PulseColors.Accent
                            index < step -> PulseColors.SurfaceElevated
                            else -> PulseColors.Surface
                        },
                    )
                    .border(
                        1.dp,
                        if (selected) PulseColors.Accent else PulseColors.BorderStrong,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(role = Role.Button) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) PulseColors.TextOnAccent else PulseColors.Text,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GoalStep(
    race: PaceRace,
    customMode: Boolean,
    customDistanceKm: String,
    distanceMeters: Double,
    estimatedGoal: Int?,
    predictions: Map<PaceRace, Int>,
    goalSeconds: Int,
    onRace: (PaceRace) -> Unit,
    onCustom: () -> Unit,
    onCustomDistance: (String) -> Unit,
    onGoalMinus: () -> Unit,
    onGoalPlus: () -> Unit,
    plans: List<PacePlanEntity>,
    onLoadPlan: (PacePlanEntity) -> Unit,
    onDeletePlan: (PacePlanEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
        if (plans.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                SectionLabel("Saved strategies")
                plans.take(3).forEach { plan ->
                    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = { onLoadPlan(plan) }) {
                        Row(
                            modifier = Modifier.padding(PulseSpacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = PulseColors.Accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(PulseSpacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    plan.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = PulseColors.Text,
                                    translate = false,
                                )
                                Text(
                                    plan.summary(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PulseColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(tr("Open strategy"), style = MaterialTheme.typography.labelLarge, color = PulseColors.Accent)
                            Spacer(Modifier.width(PulseSpacing.sm))
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = tr("Delete %s", plan.name),
                                tint = PulseColors.TextMuted,
                                modifier = Modifier
                                    .size(PulseSpacing.touchTarget)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { onDeletePlan(plan) }
                                    .padding(PulseSpacing.md),
                            )
                        }
                    }
                }
            }
        }

        SectionLabel("Race")
        Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            PaceRace.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    row.forEach { option ->
                        val selected = !customMode && option == race
                        val predicted = predictions[option]
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 72.dp)
                                .clip(PulseShapes.medium)
                                .background(PulseColors.Surface)
                                .border(
                                    1.dp,
                                    if (selected) PulseColors.Accent else PulseColors.BorderStrong,
                                    PulseShapes.medium,
                                )
                                .clickable(role = Role.Button) { onRace(option) }
                                .padding(PulseSpacing.lgPlus),
                        ) {
                            Column {
                                Text(
                                    if (option == PaceRace.HALF) "Half" else option.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = PulseColors.Text,
                                )
                                Text(
                                    predicted?.let {
                                        tr("%s · predicted", PacePlanCalculator.formatClock(it))
                                    } ?: tr("No recent run"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PulseColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(PulseShapes.medium)
                    .background(PulseColors.Surface)
                    .border(
                        1.dp,
                        if (customMode) PulseColors.Accent else PulseColors.BorderStrong,
                        PulseShapes.medium,
                    )
                    .clickable(role = Role.Button, onClick = onCustom)
                    .padding(PulseSpacing.lgPlus),
            ) {
                Column {
                    Text("Custom distance", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        if (distanceMeters > 0.0 && customMode) {
                            "%.2f km · any race distance".format(distanceMeters / 1_000.0)
                        } else {
                            "Enter any distance for a custom goal"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            if (customMode) {
                OutlinedTextField(
                    value = customDistanceKm,
                    onValueChange = onCustomDistance,
                    label = { Text("Distance (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xlPlus),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Goal finish time", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    GoalStepButton("−", "Subtract 15 seconds", onGoalMinus)
                    Text(
                        if (goalSeconds > 0) PacePlanCalculator.formatClock(goalSeconds) else "—:—",
                        style = MaterialTheme.typography.displaySmall,
                        color = PulseColors.Text,
                    )
                    GoalStepButton("+", "Add 15 seconds", onGoalPlus)
                }
                Text(
                    when {
                        distanceMeters <= 0.0 -> "Enter a distance to build splits."
                        goalSeconds <= 0 -> "No recent run to estimate from. Set a goal time to continue."
                        customMode && estimatedGoal == goalSeconds ->
                            tr(
                                "From your race predictor, scaled to %s km",
                                "%.2f".format(distanceMeters / 1_000.0),
                            )
                        !customMode && predictions[race] == goalSeconds ->
                            tr("From your %s race predictor", race.label)
                        customMode -> tr("Custom distance goal")
                        else -> tr("Custom goal")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun GoalStepButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(PulseShapes.medium)
            .background(PulseColors.SurfaceElevated)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = PulseColors.Text)
    }
}

@Composable
private fun CourseStep(
    course: PaceCourse?,
    activityCourseId: Long?,
    recentRuns: List<ActivityEntity>,
    loading: Boolean,
    onFlat: () -> Unit,
    onActivity: (ActivityEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
        SectionLabel("Course")
        CourseRow(
            name = "Flat, no course",
            sub = "Assumes flat ground",
            selected = activityCourseId == null,
            onClick = onFlat,
        )
        recentRuns.forEach { activity ->
            val distance = "%.1f".format(activity.distanceMeters / 1000.0)
            val climb = activity.ascentMeters?.let { tr(" · %s m climb", it) } ?: ""
            CourseRow(
                name = tr("Run · %s", formatDate(activity.startTime)),
                sub = tr("%s km%s", distance, climb),
                selected = activityCourseId == activity.id,
                onClick = { onActivity(activity) },
            )
        }
        if (loading) {
            Text(
                "Reading elevation from the activity…",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }
        if (activityCourseId != null && course == null && !loading) {
            Text(
                "That activity has no GPS/elevation track. Choose another run or use the flat course.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Coral,
            )
        }
        Text(
            "Elevation from the course sets each split's target. Without a course the plan assumes flat ground.",
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextMuted,
        )
    }
}

@Composable
private fun CourseRow(name: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseShapes.medium)
            .background(PulseColors.Surface)
            .border(
                1.dp,
                if (selected) PulseColors.Accent else PulseColors.BorderStrong,
                PulseShapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PulseSpacing.lgPlus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) PulseColors.Accent else Color.Transparent)
                .border(2.dp, if (selected) PulseColors.Accent else PulseColors.BorderStrong, CircleShape),
        )
    }
}

@Composable
private fun StrategyStep(
    splitSlider: Int,
    hillSlider: Int,
    alert: PaceAlert,
    onSplit: (Int) -> Unit,
    onHill: (Int) -> Unit,
    onAlert: (PaceAlert) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Split strategy", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Text(
                        PacePlanCalculator.splitSliderLabel(splitSlider),
                        style = MaterialTheme.typography.labelLarge,
                        color = PulseColors.Text,
                    )
                }
                Slider(
                    value = splitSlider.toFloat(),
                    onValueChange = { onSplit((it / 5f).roundToInt() * 5) },
                    valueRange = 0f..100f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = PulseColors.Accent,
                        activeTrackColor = PulseColors.Accent,
                        inactiveTrackColor = PulseColors.RingTrack,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Split strategy, negative to positive"
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Negative split", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    Text("Even", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    Text("Positive split", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                }
            }
        }
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Hill effort", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                    Text(
                        PacePlanCalculator.hillSliderLabel(hillSlider),
                        style = MaterialTheme.typography.labelLarge,
                        color = PulseColors.Text,
                    )
                }
                Slider(
                    value = hillSlider.toFloat(),
                    onValueChange = { onHill((it / 5f).roundToInt() * 5) },
                    valueRange = 0f..100f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = PulseColors.Accent,
                        activeTrackColor = PulseColors.Accent,
                        inactiveTrackColor = PulseColors.RingTrack,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Hill effort, easier to harder"
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Easier", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                    Text("Harder", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
                }
            }
        }
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text("Pacing alerts", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                SegmentedControl(
                    options = PaceAlert.entries.map { it.label },
                    selectedIndex = PaceAlert.entries.indexOf(alert),
                    onSelected = { onAlert(PaceAlert.entries[it]) },
                )
            }
        }
    }
}

@Composable
private fun PreviewStep(
    raceLabel: String,
    distanceMeters: Double,
    course: PaceCourse?,
    goalSeconds: Int,
    splits: List<PacePlanSplit>,
    alert: PaceAlert,
    splitSlider: Int,
    hillSlider: Int,
) {
    val avgPace = if (splits.isNotEmpty()) {
        (goalSeconds / (splits.sumOf { it.distanceMeters } / 1000.0)).roundToInt()
    } else {
        0
    }
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xlPlus),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        tr(
                            "%s · %s km · %s",
                            raceLabel,
                            "%.2f".format(distanceMeters / 1_000.0),
                            course?.name ?: tr("Flat, no course"),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr("Climb %s m", splits.sumOf { it.gainMeters }.roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    tr(
                        "%s finish · avg %s /km",
                        PacePlanCalculator.formatClock(goalSeconds),
                        PacePlanCalculator.formatPace(avgPace),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = PulseColors.Text,
                )
                ElevationBars(splits = splits)
                Text(
                    "Elevation gain per km. Taller bars are climbs.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = PulseSpacing.xl, end = PulseSpacing.xl, top = PulseSpacing.md, bottom = PulseSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    TableHeader("Split", 0.7f)
                    TableHeader("Target pace", 1f)
                    TableHeader("Elapsed", 1f)
                    TableHeader("Climb", 0.8f, TextAlign.End)
                }
                splits.forEach { split ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                    ) {
                        Text(
                            "${split.index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.weight(0.7f),
                        )
                        Text(
                            "${PacePlanCalculator.formatPace(split.paceSecondsPerKm())} /km",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = PulseColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            PacePlanCalculator.formatClock(split.cumulativeSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${split.gainMeters.roundToInt()} m",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.TextMuted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                }
            }
        }

        Text(
            tr(
                "Alerts: %s. %s · hill effort %s. Targets assume you run this course; a different route will throw off guidance on the watch.",
                tr(alert.label).lowercase(),
                PacePlanCalculator.splitSliderLabel(splitSlider),
                PacePlanCalculator.hillSliderLabel(hillSlider).lowercase(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextMuted,
        )
    }
}

@Composable
private fun ElevationBars(splits: List<PacePlanSplit>) {
    val maxGain = (splits.maxOfOrNull { it.gainMeters } ?: 0.0).coerceAtLeast(1.0)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .semantics {
                contentDescription = "Elevation gain per split, " +
                    splits.sumOf { it.gainMeters }.roundToInt() + " metres total"
            },
    ) {
        if (splits.isEmpty()) return@Canvas
        val slot = size.width / splits.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        splits.forEachIndexed { index, split ->
            val fraction = (split.gainMeters / maxGain).toFloat().coerceIn(0f, 1f)
            val barHeight = (size.height * fraction).coerceAtLeast(3f)
            drawRoundRect(
                color = if (split.gainMeters >= 20.0) PulseColors.Zone4 else PulseColors.Teal,
                topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

@Composable
private fun RowScope.TableHeader(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = PulseColors.TextMuted,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun SendStatusCard(status: PacePlanSendStatus) {
    val color = when {
        status.sending -> PulseColors.Yellow
        status.success -> PulseColors.Teal
        else -> PulseColors.Coral
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseShapes.medium)
            .background(PulseColors.Surface)
            .border(1.dp, color.copy(alpha = 0.5f), PulseShapes.medium)
            .padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.mdPlus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(PulseSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(status.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = PulseColors.Text)
            Text(
                status.message ?: if (status.sending) "Sending to watch…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }
    }
}

@Composable
private fun PaceSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(CircleShape)
            .border(1.dp, PulseColors.BorderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = PulseColors.Text)
    }
}

private fun formatDate(timestamp: Long): String =
    java.time.Instant.ofEpochSecond(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
