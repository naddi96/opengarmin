package dev.opengarmin.app.ui.screens

import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.coach.PaceCoachContext
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.PaceAlert
import dev.opengarmin.app.data.PaceCourse
import dev.opengarmin.app.data.PaceCourseSource
import dev.opengarmin.app.data.PacePlanCalculator
import dev.opengarmin.app.data.PacePlanCodec
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.PacePlanSplit
import dev.opengarmin.app.data.PaceRace
import dev.opengarmin.app.data.PaceSplitEditMode
import dev.opengarmin.app.data.PaceTrackParser
import dev.opengarmin.app.i18n.AppStrings
import dev.opengarmin.app.i18n.LocalAppLanguage
import dev.opengarmin.app.i18n.Text
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val STEP_LABELS = listOf("1. Goal", "2. Course", "3. Strategy", "4. Preview")

@Composable
fun PaceStrategyScreen(
    plans: List<PacePlanEntity>,
    activities: List<ActivityEntity>,
    sendStatus: PacePlanSendStatus?,
    onBack: () -> Unit,
    onSend: (PacePlanEntity) -> Unit,
    onSave: suspend (PacePlanEntity) -> PacePlanEntity?,
    loadCourse: suspend (Long) -> PaceCourse?,
    onOpenRun: (PacePlanEntity) -> Unit,
    providerStore: ProviderStore,
    initialPlanId: Long? = null,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(0) }
    var race by remember { mutableStateOf(PaceRace.TEN_K) }
    var customMode by remember { mutableStateOf(false) }
    var customDistanceKm by remember { mutableStateOf("") }
    var goalSeconds by remember { mutableStateOf(0) }
    var activityCourseId by remember { mutableStateOf<Long?>(null) }
    var course by remember { mutableStateOf<PaceCourse?>(null) }
    var courseSource by remember { mutableStateOf(PaceCourseSource.FLAT) }
    var courseLoading by remember { mutableStateOf(false) }
    var courseError by remember { mutableStateOf<String?>(null) }
    var splitSlider by remember { mutableStateOf(50) }
    var hillSlider by remember { mutableStateOf(50) }
    var alert by remember { mutableStateOf(PaceAlert.KILOMETRE) }
    var acceptedPlan by remember { mutableStateOf<PacePlanEntity?>(null) }
    var goalTouched by remember { mutableStateOf(false) }
    var manualOffsets by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var editMode by remember { mutableStateOf(PaceSplitEditMode.FREE) }
    var loadedPlanSplits by remember { mutableStateOf<List<PacePlanSplit>?>(null) }
    var initialPlanConsumed by remember { mutableStateOf(false) }
    var showMapEditor by remember { mutableStateOf(false) }
    var showCoach by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf<PacePlanSendStatus?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val savingMessage = tr("Saving on this phone…")
    val savedMessage = tr("Saved on this phone.")
    val saveFailureMessage = tr("Could not save the plan.")

    val predictions = PacePlanCalculator.predictions(activities)
    val recentRuns = activities.filter { it.sport == 1 && it.distanceMeters >= 1_000.0 }
        .sortedByDescending { it.startTime }
        .take(5)
    val customDistanceMeters = customDistanceKm.replace(',', '.').toDoubleOrNull()?.times(1_000.0)
    val distanceMeters = if (customMode) customDistanceMeters ?: 0.0 else race.distanceMeters
    val raceLabel = if (customMode) "Custom" else race.label
    val estimatedGoal = PacePlanCalculator.estimateGoal(distanceMeters, predictions)

    val coursePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        courseLoading = true
        courseError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "track"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(
                            AppStrings.translate("Could not read the selected file.", language),
                        )
                    PaceTrackParser.parse(fileName, bytes)
                }
            }
            courseLoading = false
            result.onSuccess { track ->
                course = PaceTrackParser.courseFrom(
                    track = track,
                    source = PaceCourseSource.IMPORTED,
                    id = "imported-${System.currentTimeMillis()}",
                )
                activityCourseId = null
                courseSource = PaceCourseSource.IMPORTED
                courseError = null
            }.onFailure { failure ->
                courseError = failure.message
                    ?: AppStrings.translate("The track could not be read.", language)
            }
        }
    }

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
        manualOffsets = emptyMap()
        courseError = null
        if (plan.courseActivityId == null) {
            course = if (plan.courseName == "Flat, no course") {
                null
            } else {
                PacePlanCalculator.courseFromPlan(plan)
            }
            courseSource = if (course == null) PaceCourseSource.FLAT else PaceCourseSource.PLAN
            loadedPlanSplits = plan.splits
            step = 3
        } else {
            courseLoading = true
            scope.launch {
                course = loadCourse(plan.courseActivityId)
                courseSource = if (course == null) PaceCourseSource.FLAT else PaceCourseSource.ACTIVITY
                courseLoading = false
                loadedPlanSplits = plan.splits
                step = 3
            }
        }
    }

    fun pickActivityCourse(activity: ActivityEntity) {
        activityCourseId = activity.id
        courseLoading = true
        courseError = null
        scope.launch {
            course = loadCourse(activity.id)
            courseSource = if (course == null) PaceCourseSource.FLAT else PaceCourseSource.ACTIVITY
            courseLoading = false
        }
    }

    LaunchedEffect(initialPlanId, plans) {
        val id = initialPlanId ?: return@LaunchedEffect
        if (initialPlanConsumed) return@LaunchedEffect
        val plan = plans.firstOrNull { it.id == id } ?: return@LaunchedEffect
        initialPlanConsumed = true
        loadPlan(plan)
    }

    val baseSplits = remember(distanceMeters, goalSeconds, splitSlider, hillSlider, course, alert) {
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
    val splits = remember(baseSplits, manualOffsets) {
        PacePlanCalculator.applyOffsets(baseSplits, manualOffsets)
    }

    LaunchedEffect(distanceMeters, goalSeconds, splitSlider, hillSlider, course?.id, alert) {
        manualOffsets = emptyMap()
    }

    // Restore the edits saved in a strategy after its course finishes loading.
    LaunchedEffect(loadedPlanSplits, baseSplits) {
        val saved = loadedPlanSplits ?: return@LaunchedEffect
        if (saved.size == baseSplits.size) {
            manualOffsets = PacePlanCalculator.offsetsFrom(baseSplits, saved)
        }
        loadedPlanSplits = null
    }

    fun buildPlan(): PacePlanEntity {
        val now = System.currentTimeMillis() / 1000
        val existing = acceptedPlan
        return PacePlanEntity(
            id = existing?.id ?: 0L,
            name = "$raceLabel PacePro",
            race = if (customMode) "Custom" else race.id,
            distanceMeters = distanceMeters,
            goalSeconds = splits.sumOf { it.targetSeconds }.takeIf { it > 0 } ?: goalSeconds,
            courseName = course?.name ?: "Flat, no course",
            courseActivityId = course?.activityId ?: activityCourseId,
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

    fun save() {
        if (saving || goalSeconds <= 0 || splits.isEmpty()) return
        saving = true
        val plan = buildPlan()
        val planId = acceptedPlan?.id ?: 0L
        saveStatus = PacePlanSendStatus(
            planId = planId,
            name = plan.name,
            sending = true,
            message = savingMessage,
        )
        scope.launch {
            val saved = onSave(plan)
            saving = false
            if (saved != null) {
                acceptedPlan = saved
                saveStatus = PacePlanSendStatus(
                    planId = saved.id,
                    name = saved.name,
                    success = true,
                    message = savedMessage,
                )
            } else {
                saveStatus = PacePlanSendStatus(
                    planId = planId,
                    name = plan.name,
                    success = false,
                    message = saveFailureMessage,
                )
            }
        }
    }

    val statusForPlan = sendStatus?.takeIf { status ->
        acceptedPlan != null &&
            (status.planId == acceptedPlan?.id || status.name == acceptedPlan?.name)
    }
    LaunchedEffect(statusForPlan?.planId) {
        val savedId = statusForPlan?.planId ?: return@LaunchedEffect
        if (savedId != 0L) acceptedPlan = acceptedPlan?.copy(id = savedId)
    }
    val totalSeconds = splits.sumOf { it.targetSeconds }.takeIf { it > 0 } ?: goalSeconds
    val hasUnsavedChanges = acceptedPlan?.let { saved ->
        saved.id == 0L || !saved.sameContentAs(buildPlan())
    } ?: true

    LaunchedEffect(splits, distanceMeters, goalSeconds, splitSlider, hillSlider, course?.id, alert) {
        saveStatus = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showCoach) {
            val coachContext = PaceCoachContext(
                raceLabel = raceLabel,
                distanceMeters = distanceMeters,
                goalSeconds = totalSeconds,
                splits = splits,
                predictions = predictions.mapKeys { it.key.label }.mapValues { it.value },
                recentClimbMeters = recentRuns.firstOrNull()?.ascentMeters,
            )
            PaceCoachScreen(
                context = coachContext,
                baseSplits = baseSplits,
                currentSplits = splits,
                providerStore = providerStore,
                onApply = { offsets ->
                    manualOffsets = offsets
                    showCoach = false
                },
                onDiscard = { showCoach = false },
            )
        } else if (showMapEditor) {
            PaceMapEditorScreen(
                initialPoints = course?.points.orEmpty(),
                raceDistanceMeters = distanceMeters,
                onCancel = { showMapEditor = false },
                onConfirm = { drawn ->
                    course = drawn
                    courseSource = PaceCourseSource.DRAWN
                    activityCourseId = null
                    courseError = null
                    showMapEditor = false
                },
            )
        } else {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonRound(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    tr("Back to PacePro plans"),
                    onBack,
                )
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
                )
                1 -> CourseStep(
                    course = course,
                    courseSource = courseSource,
                    activityCourseId = activityCourseId,
                    recentRuns = recentRuns,
                    loading = courseLoading,
                    error = courseError,
                    raceDistanceMeters = distanceMeters,
                    onFlat = {
                        activityCourseId = null
                        course = null
                        courseSource = PaceCourseSource.FLAT
                        courseError = null
                    },
                    onLoadTrack = {
                        coursePicker.launch(
                            arrayOf(
                                "application/gpx+xml",
                                "application/vnd.garmin.tcx+xml",
                                "application/xml",
                                "text/xml",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    onDraw = { showMapEditor = true },
                    onReplaceTrack = {
                        if (courseSource == PaceCourseSource.DRAWN) {
                            showMapEditor = true
                        } else {
                            coursePicker.launch(arrayOf("*/*"))
                        }
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
                    baseSplits = baseSplits,
                    splits = splits,
                    alert = alert,
                    splitSlider = splitSlider,
                    hillSlider = hillSlider,
                    manualEdits = manualOffsets.isNotEmpty(),
                    editMode = editMode,
                    onMode = { editMode = it },
                    onAdjust = { index, delta ->
                        val edited = PacePlanCalculator.adjustSplit(
                            splits = splits,
                            index = index,
                            deltaSeconds = delta,
                            mode = editMode,
                            baseSplits = baseSplits,
                        )
                        manualOffsets = PacePlanCalculator.offsetsFrom(baseSplits, edited)
                    },
                    onReset = { manualOffsets = emptyMap() },
                    onOpenCoach = { showCoach = true },
                )
            }

            saveStatus?.let { status ->
                SendStatusCard(status = status)
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
                else -> {
                    PaceSecondaryButton(
                        label = if (saving) tr("Saving…") else tr("Save"),
                        onClick = { save() },
                        enabled = hasUnsavedChanges && !saving &&
                            goalSeconds > 0 && splits.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = if (statusForPlan?.sending == true) {
                            tr("Sending…")
                        } else {
                            tr("Send to watch")
                        },
                        onClick = {
                            if (!saving && statusForPlan?.sending != true) accept()
                        },
                        enabled = goalSeconds > 0 && splits.isNotEmpty() &&
                            !saving && statusForPlan?.sending != true,
                        modifier = Modifier.weight(1.5f),
                    )
                }
            }
        }
    }
        }
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg)) {
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
    courseSource: PaceCourseSource,
    activityCourseId: Long?,
    recentRuns: List<ActivityEntity>,
    loading: Boolean,
    error: String?,
    raceDistanceMeters: Double,
    onFlat: () -> Unit,
    onLoadTrack: () -> Unit,
    onDraw: () -> Unit,
    onReplaceTrack: () -> Unit,
    onActivity: (ActivityEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
        SectionLabel("Where should the elevation come from?")
        CourseRow(
            name = "Flat, no course",
            sub = "Assumes flat ground",
            selected = courseSource == PaceCourseSource.FLAT,
            onClick = onFlat,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            CourseSourceCard(
                title = "Load track",
                sub = "GPX, TCX or FIT",
                icon = Icons.Outlined.UploadFile,
                selected = courseSource == PaceCourseSource.IMPORTED,
                onClick = onLoadTrack,
                modifier = Modifier.weight(1f),
            )
            CourseSourceCard(
                title = "Draw on the map",
                sub = "Draw the loop by hand",
                icon = Icons.Outlined.Route,
                selected = courseSource == PaceCourseSource.DRAWN,
                onClick = onDraw,
                modifier = Modifier.weight(1f),
            )
        }

        if (loading) {
            Text(
                if (courseSource == PaceCourseSource.ACTIVITY) {
                    tr("Reading elevation from the activity…")
                } else {
                    tr("Reading the track…")
                },
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = PulseColors.Coral)
        }
        if (course != null &&
            (courseSource == PaceCourseSource.IMPORTED ||
                courseSource == PaceCourseSource.DRAWN ||
                courseSource == PaceCourseSource.PLAN)
        ) {
            TrackCard(course = course, onReplace = onReplaceTrack)
        }
        if (course != null && courseSource != PaceCourseSource.FLAT &&
            raceDistanceMeters > 0.0 && course.distanceMeters > 0.0 &&
            abs(course.distanceMeters - raceDistanceMeters) / raceDistanceMeters > 0.02
        ) {
            val missingKm = (raceDistanceMeters - course.distanceMeters) / 1_000.0
            Text(
                if (missingKm > 0.0) {
                    if (courseSource == PaceCourseSource.DRAWN) {
                        tr(
                            "The loop is %s km short of the race. The profile is resampled to the race distance.",
                            "%.2f".format(missingKm),
                        )
                    } else {
                        tr(
                            "The track is %s km short of the race. The profile is resampled to the race distance.",
                            "%.2f".format(missingKm),
                        )
                    }
                } else {
                    tr(
                        "Track is %s km, race is %s km. The elevation profile is resampled to the race distance.",
                        "%.2f".format(course.distanceMeters / 1_000.0),
                        "%.2f".format(raceDistanceMeters / 1_000.0),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Coral,
            )
        }

        SectionLabel("From your runs")
        recentRuns.forEach { activity ->
            val distance = "%.1f".format(activity.distanceMeters / 1000.0)
            val climb = activity.ascentMeters?.let { tr(" · %s m climb", it) } ?: ""
            CourseRow(
                name = tr("Run · %s", formatDate(activity.startTime)),
                sub = tr("%s km%s", distance, climb),
                selected = courseSource == PaceCourseSource.ACTIVITY && activityCourseId == activity.id,
                onClick = { onActivity(activity) },
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
private fun CourseSourceCard(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 128.dp)
            .clip(PulseShapes.medium)
            .background(PulseColors.Surface)
            .border(
                1.dp,
                if (selected) PulseColors.Accent else PulseColors.BorderStrong,
                PulseShapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PulseSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PulseColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PulseColors.Accent.copy(alpha = 0.16f))
                    .padding(horizontal = PulseSpacing.sm, vertical = 3.dp),
            ) {
                Text(
                    tr("New"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PulseColors.Accent,
                )
            }
        }
        Text(
            tr(title),
            style = MaterialTheme.typography.titleSmall,
            color = PulseColors.Text,
        )
        Text(
            tr(sub),
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextMuted,
        )
    }
}

@Composable
private fun TrackCard(course: PaceCourse, onReplace: () -> Unit) {
    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        course.fileName ?: course.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (course.displayDescentMeters > 0.0) {
                            tr(
                                "%s km · +%s m / −%s m",
                                "%.2f".format(course.distanceMeters / 1_000.0),
                                course.displayAscentMeters.roundToInt(),
                                course.displayDescentMeters.roundToInt(),
                            )
                        } else {
                            tr(
                                "%s km · +%s m",
                                "%.2f".format(course.distanceMeters / 1_000.0),
                                course.displayAscentMeters.roundToInt(),
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    tr("Replace"),
                    style = MaterialTheme.typography.labelLarge,
                    color = PulseColors.Accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onReplace)
                        .padding(horizontal = PulseSpacing.md, vertical = PulseSpacing.sm),
                )
            }
            if (course.gainsPerKm.isNotEmpty()) {
                CourseElevationBars(gains = course.gainsPerKm)
            }
        }
    }
}

@Composable
private fun CourseElevationBars(gains: List<Double>) {
    val maxGain = (gains.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val description = tr(
        "Elevation profile, %s metres of climbing",
        gains.sum().roundToInt(),
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .semantics { contentDescription = description },
    ) {
        if (gains.isEmpty()) return@Canvas
        val slot = size.width / gains.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        gains.forEachIndexed { index, gain ->
            val fraction = (gain / maxGain).toFloat().coerceIn(0f, 1f)
            val barHeight = (size.height * fraction).coerceAtLeast(3f)
            drawRoundRect(
                color = if (gain >= 20.0) PulseColors.Zone4 else PulseColors.Teal,
                topLeft = Offset(slot * index + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
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
    baseSplits: List<PacePlanSplit>,
    splits: List<PacePlanSplit>,
    alert: PaceAlert,
    splitSlider: Int,
    hillSlider: Int,
    manualEdits: Boolean,
    editMode: PaceSplitEditMode,
    onMode: (PaceSplitEditMode) -> Unit,
    onAdjust: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onOpenCoach: () -> Unit,
) {
    val totalSeconds = splits.sumOf { it.targetSeconds }.takeIf { it > 0 } ?: goalSeconds
    val avgPace = if (splits.isNotEmpty()) {
        (totalSeconds / (splits.sumOf { it.distanceMeters } / 1000.0)).roundToInt()
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
                        PacePlanCalculator.formatClock(totalSeconds),
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
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("When you change a kilometre"),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr(editMode.label()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = PulseColors.Accent,
                    )
                }
                SplitModeSlider(selected = editMode, onSelect = onMode)
                Text(
                    tr(editMode.help()),
                    style = MaterialTheme.typography.labelMedium,
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Splits"),
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    if (manualEdits) {
                        TextButton(onClick = onReset) {
                            Text(tr("Reset"), color = PulseColors.Accent)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = PulseSpacing.xl, end = PulseSpacing.xl, bottom = PulseSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
                ) {
                    TableHeader("Km", 0.4f)
                    TableHeader("Target pace", 1.45f)
                    TableHeader("Elapsed", 0.9f)
                    TableHeader("Climb", 0.6f, TextAlign.End)
                }
                splits.forEach { split ->
                    val base = baseSplits.getOrNull(split.index)
                    val delta = base?.let { split.targetSeconds - it.targetSeconds } ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${split.index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.TextMuted,
                            modifier = Modifier.weight(0.4f),
                        )
                        Row(
                            modifier = Modifier.weight(1.45f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The minus starts under the "P" of the column header.
                            SplitAdjustButton(
                                label = "−",
                                description = tr("One second faster on split %s", split.index + 1),
                                onClick = { onAdjust(split.index, -1) },
                            )
                            Text(
                                PacePlanCalculator.formatPace(split.paceSecondsPerKm()),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PulseColors.Text,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(40.dp),
                            )
                            SplitAdjustButton(
                                label = "+",
                                description = tr("One second slower on split %s", split.index + 1),
                                onClick = { onAdjust(split.index, 1) },
                            )
                        }
                        Column(modifier = Modifier.weight(0.9f)) {
                            Text(
                                PacePlanCalculator.formatClock(split.cumulativeSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PulseColors.Text,
                            )
                            if (delta != 0) {
                                Text(
                                    PacePlanCalculator.formatSignedSeconds(delta),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (delta < 0) PulseColors.Teal else PulseColors.Coral,
                                )
                            }
                        }
                        Text(
                            "${split.gainMeters.roundToInt()} m",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PulseColors.TextMuted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
                Text(
                    tr("Tap − or + to change a kilometre by one second. The arrival time updates immediately."),
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                    modifier = Modifier.padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.md),
                )
            }
        }

        PaceSecondaryButton(
            label = tr("Optimise with AI coach ›"),
            onClick = onOpenCoach,
            modifier = Modifier.fillMaxWidth(),
        )

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
private fun SplitModeSlider(
    selected: PaceSplitEditMode,
    onSelect: (PaceSplitEditMode) -> Unit,
) {
    val modes = PaceSplitEditMode.entries
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / modes.size
                val centerY = size.height / 2f
                drawLine(
                    color = PulseColors.BorderStrong,
                    start = Offset(step / 2f, centerY),
                    end = Offset(size.width - step / 2f, centerY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                modes.forEachIndexed { index, mode ->
                    val center = Offset(step * (index + 0.5f), centerY)
                    if (mode == selected) {
                        drawCircle(PulseColors.Accent, 8.dp.toPx(), center)
                    } else {
                        drawCircle(PulseColors.BorderStrong, 5.dp.toPx(), center)
                    }
                }
            }
            Row(Modifier.fillMaxSize()) {
                modes.forEach { mode ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = mode == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            modes.forEach { mode ->
                Text(
                    tr(mode.label()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    color = if (mode == selected) PulseColors.Accent else PulseColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SplitAdjustButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PulseColors.SurfaceElevated)
            .border(1.dp, PulseColors.BorderStrong, CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PulseColors.Text,
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
private fun PaceSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(CircleShape)
            .border(
                1.dp,
                if (enabled) PulseColors.BorderStrong else PulseColors.Border,
                CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) PulseColors.Text else PulseColors.TextMuted,
        )
    }
}

private fun PacePlanEntity.sameContentAs(other: PacePlanEntity): Boolean =
    name == other.name &&
        race == other.race &&
        distanceMeters == other.distanceMeters &&
        goalSeconds == other.goalSeconds &&
        courseName == other.courseName &&
        courseActivityId == other.courseActivityId &&
        splitSlider == other.splitSlider &&
        hillSlider == other.hillSlider &&
        alert == other.alert &&
        splitsJson == other.splitsJson

private fun PaceSplitEditMode.label(): String = when (this) {
    PaceSplitEditMode.FREE -> "Free"
    PaceSplitEditMode.FIXED_TOTAL -> "Fixed total"
    PaceSplitEditMode.LOCK_PREVIOUS -> "Lock previous"
    PaceSplitEditMode.LOCK_SUCCESSIVE -> "Lock following"
}

private fun PaceSplitEditMode.help(): String = when (this) {
    PaceSplitEditMode.FREE ->
        "You change a kilometre and the finish time goes up or down."
    PaceSplitEditMode.FIXED_TOTAL ->
        "The finish time stays fixed; the difference is spread over the other kilometres, ignoring elevation."
    PaceSplitEditMode.LOCK_PREVIOUS ->
        "Earlier kilometres stay locked; the difference is spread proportionally over the following kilometres."
    PaceSplitEditMode.LOCK_SUCCESSIVE ->
        "Later kilometres stay locked; the difference is spread proportionally over the preceding kilometres."
}

private fun formatDate(timestamp: Long): String =
    java.time.Instant.ofEpochSecond(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault()))
