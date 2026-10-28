package dev.opengarmin.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import dev.opengarmin.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.PlannedDurationType
import dev.opengarmin.app.data.PlannedStep
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.StepRole
import dev.opengarmin.app.data.WorkoutPlanCodec
import dev.opengarmin.app.data.WorkoutSendStatus
import dev.opengarmin.app.data.WorkoutSport
import dev.opengarmin.app.data.newStepId
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PlanScreen(
    workouts: List<PlannedWorkoutEntity>,
    sendStatus: WorkoutSendStatus?,
    onBack: () -> Unit,
    onSave: (PlannedWorkoutEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onSend: (PlannedWorkoutEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<PlannedWorkoutEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PlannedWorkoutEntity?>(null) }

    BackHandler(enabled = creating || editing != null) {
        creating = false
        editing = null
    }

    if (creating || editing != null) {
        val existing = editing
        WorkoutEditor(
            initial = existing,
            sendStatus = sendStatus,
            onCancel = {
                creating = false
                editing = null
            },
            onSave = { workout ->
                onSave(workout)
                creating = false
                editing = null
            },
            onSend = { workout ->
                onSend(workout)
                creating = false
                editing = null
            },
            modifier = modifier,
        )
    } else {
        PlanOverview(
            workouts = workouts,
            sendStatus = sendStatus,
            onBack = onBack,
            onCreate = { creating = true },
            onEdit = { editing = it },
            onDelete = { deleting = it },
            modifier = modifier,
        )
    }

    deleting?.let { workout ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete workout?") },
            text = {
                Text(
                    tr(
                        "%s will be removed from this phone. A copy already on the watch is not changed.",
                        workout.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(workout.id)
                    deleting = null
                }) { Text("Delete", color = PulseColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlanOverview(
    workouts: List<PlannedWorkoutEntity>,
    sendStatus: WorkoutSendStatus?,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (PlannedWorkoutEntity) -> Unit,
    onDelete: (PlannedWorkoutEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheduled = workouts.filter { isThisWeek(it.scheduledDate) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, "Back to training", onBack)
            Spacer(Modifier.width(PulseSpacing.sm))
            Column {
                Text("Plan", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                Text(
                    "Build workouts and send them to your watch",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
        }

        sendStatus?.let { status ->
            val color = when {
                status.sending -> PulseColors.Yellow
                status.success -> PulseColors.Teal
                else -> PulseColors.Coral
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PulseColors.Surface)
                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.mdPlus),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(PulseSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = PulseColors.Text,
                    )
                    Text(
                        status.message ?: if (status.sending) "Sending to watch…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        PrimaryButton(text = "New workout", onClick = onCreate, modifier = Modifier.fillMaxWidth())

        if (scheduled.isNotEmpty()) {
            SectionLabel("Scheduled this week")
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    scheduled.forEachIndexed { index, workout ->
                        ScheduledRow(workout = workout, showDivider = index != scheduled.lastIndex) {
                            onEdit(workout)
                        }
                    }
                }
            }
        }

        SectionLabel("Your workouts")
        if (workouts.isEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Text("No workouts yet", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        "Create a session with timed or distance steps, repeat it as an interval block, then send it to the watch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        } else {
            workouts.forEach { workout ->
                WorkoutRow(workout = workout, onClick = { onEdit(workout) }, onDelete = { onDelete(workout) })
            }
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }
}

@Composable
private fun ScheduledRow(workout: PlannedWorkoutEntity, showDivider: Boolean, onClick: () -> Unit) {
    val date = workout.scheduledDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PulseSpacing.xl, vertical = PulseSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(48.dp)) {
            Text(
                date?.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            Text(
                date?.dayOfMonth?.toString() ?: "—",
                style = MaterialTheme.typography.titleSmall,
                color = PulseColors.Text,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                workout.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = PulseColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                translate = false,
            )
            Text(
                workout.summary(),
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.TextMuted,
            )
        }
        SentPill(workout.lastSentAt != null)
        if (showDivider) {
            // The divider is drawn through the parent card's bottom border in
            // the mockup; keep rows compact without extra chrome.
        }
    }
    if (showDivider) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 68.dp, end = PulseSpacing.xl)
                .height(1.dp)
                .background(PulseColors.Border.copy(alpha = 0.6f)),
        )
    }
}

@Composable
private fun WorkoutRow(workout: PlannedWorkoutEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(start = PulseSpacing.xl, end = PulseSpacing.md, top = PulseSpacing.lg, bottom = PulseSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workout.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        translate = false,
                    )
                    Spacer(Modifier.width(PulseSpacing.sm))
                    SentPill(workout.lastSentAt != null)
                }
                Text(
                    tr(
                        "%s · %s",
                        tr(WorkoutSport.from(workout.sport, workout.subSport).label),
                        workout.summary(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
                workout.scheduledDate?.let { date ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = PulseColors.TextMuted,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            formatScheduled(date),
                            style = MaterialTheme.typography.labelMedium,
                            color = PulseColors.TextMuted,
                        )
                    }
                }
            }
            IconButtonRound(Icons.Outlined.DeleteOutline, tr("Delete %s", workout.name), onDelete)
        }
    }
}

@Composable
private fun SentPill(sent: Boolean) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (sent) PulseColors.Accent else Color.Transparent)
            .border(1.dp, if (sent) PulseColors.Accent else PulseColors.BorderStrong, CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            if (sent) "Sent" else "Draft",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (sent) PulseColors.TextOnAccent else PulseColors.TextMuted,
        )
    }
}

private data class StepEditRequest(
    val parentId: String?,
    val step: PlannedStep.Single,
    val existing: Boolean,
)

@Composable
private fun WorkoutEditor(
    initial: PlannedWorkoutEntity?,
    sendStatus: WorkoutSendStatus?,
    onCancel: () -> Unit,
    onSave: (PlannedWorkoutEntity) -> Unit,
    onSend: (PlannedWorkoutEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "Interval session") }
    var sport by remember(initial?.id) {
        mutableStateOf(WorkoutSport.from(initial?.sport ?: 1, initial?.subSport ?: 0))
    }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var scheduledDate by remember(initial?.id) { mutableStateOf(initial?.scheduledDate) }
    var steps by remember(initial?.id) { mutableStateOf(initial?.steps ?: emptyList()) }
    var editRequest by remember { mutableStateOf<StepEditRequest?>(null) }

    fun updateStep(request: StepEditRequest, transform: (PlannedStep.Single) -> PlannedStep.Single) {
        steps = steps.updateStep(request.parentId, request.step.id, transform)
    }

    fun addStep(request: StepEditRequest) {
        steps = steps.addStep(request.parentId, request.step)
    }

    fun removeStep(request: StepEditRequest) {
        steps = steps.removeStep(request.parentId, request.step.id)
    }

    fun moveTop(step: PlannedStep.Single, direction: Int) {
        steps = steps.moveStep(step.id, direction)
    }

    fun moveChild(repeatId: String, child: PlannedStep.Single, direction: Int) {
        steps = steps.updateRepeat(repeatId) { repeat ->
            val index = repeat.children.indexOfFirst { it.id == child.id }
            repeat.copy(children = repeat.children.moveBy(index, direction))
        }
    }

    fun moveRepeat(repeat: PlannedStep.Repeat, direction: Int) {
        steps = steps.moveStep(repeat.id, direction)
    }

    fun buildEntity(): PlannedWorkoutEntity {
        val now = System.currentTimeMillis() / 1000
        return PlannedWorkoutEntity(
            id = initial?.id ?: 0L,
            name = name.trim().ifBlank { "Workout" },
            sport = sport.sport,
            subSport = sport.subSport,
            notes = notes.trim(),
            scheduledDate = scheduledDate?.takeIf { it.isNotBlank() },
            stepsJson = WorkoutPlanCodec.encode(steps),
            createdAt = initial?.createdAt ?: now,
            updatedAt = now,
            lastSentAt = initial?.lastSentAt,
        )
    }

    val status = sendStatus?.takeIf { status ->
        initial != null && status.workoutId == initial.id
    }
    val hasContent = steps.any { step ->
        step is PlannedStep.Single || (step is PlannedStep.Repeat && step.children.isNotEmpty())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, "Back to plan", onCancel)
            Spacer(Modifier.width(PulseSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    if (initial == null) "New workout" else "Edit workout",
                    style = MaterialTheme.typography.headlineLarge,
                    color = PulseColors.Text,
                )
                Text(
                    "Build the steps, then save or send",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
            TextButton(onClick = { onSave(buildEntity()) }) {
                Text("Save", color = PulseColors.Accent, fontWeight = FontWeight.Bold)
            }
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Workout name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Sport")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    WorkoutSport.entries.forEach { option ->
                        SelectChip(
                            label = option.label,
                            selected = sport == option,
                            onClick = { sport = option },
                        )
                    }
                }
                SectionLabel("Schedule")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    SelectChip(
                        label = "None",
                        selected = scheduledDate == null,
                        onClick = { scheduledDate = null },
                    )
                    SelectChip(
                        label = "Today",
                        selected = scheduledDate == LocalDate.now().toString(),
                        onClick = { scheduledDate = LocalDate.now().toString() },
                    )
                    SelectChip(
                        label = "Tomorrow",
                        selected = scheduledDate == LocalDate.now().plusDays(1).toString(),
                        onClick = { scheduledDate = LocalDate.now().plusDays(1).toString() },
                    )
                }
                OutlinedTextField(
                    value = scheduledDate.orEmpty(),
                    onValueChange = {
                        scheduledDate = it.trim().takeIf { value -> value.isNotEmpty() }
                    },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionLabel("Steps")
        steps.forEach { item ->
            when (item) {
                is PlannedStep.Single -> StepRow(
                    step = item,
                    onUp = { moveTop(item, -1) },
                    onDown = { moveTop(item, 1) },
                    onDelete = {
                        removeStep(StepEditRequest(parentId = null, step = item, existing = true))
                    },
                    onClick = {
                        editRequest = StepEditRequest(parentId = null, step = item, existing = true)
                    },
                )

                is PlannedStep.Repeat -> RepeatCard(
                    repeat = item,
                    onCount = { delta ->
                        steps = steps.map {
                            if (it is PlannedStep.Repeat && it.id == item.id) {
                                it.copy(count = (it.count + delta).coerceIn(1, 99))
                            } else {
                                it
                            }
                        }
                    },
                    onMoveUp = { moveRepeat(item, -1) },
                    onMoveDown = { moveRepeat(item, 1) },
                    onDelete = { steps = steps.filterNot { it is PlannedStep.Repeat && it.id == item.id } },
                    onAddChild = {
                        editRequest = StepEditRequest(
                            parentId = item.id,
                            step = PlannedStep.Single(role = StepRole.REST, seconds = 60),
                            existing = false,
                        )
                    },
                    onChildClick = { child ->
                        editRequest = StepEditRequest(parentId = item.id, step = child, existing = true)
                    },
                    onChildUp = { child -> moveChild(item.id, child, -1) },
                    onChildDown = { child -> moveChild(item.id, child, 1) },
                    onChildDelete = { child ->
                        removeStep(StepEditRequest(parentId = item.id, step = child, existing = true))
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            PrimaryButton(
                text = "Add step",
                onClick = {
                    editRequest = StepEditRequest(
                        parentId = null,
                        step = PlannedStep.Single(role = StepRole.ACTIVE, seconds = 300),
                        existing = false,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                label = "Add repeat",
                onClick = {
                    steps = steps + PlannedStep.Repeat(id = newStepId())
                },
                modifier = Modifier.weight(1f),
            )
        }

        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Estimated", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                    Text(
                        estimateLabel(steps),
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                    )
                }
                Text(
                    status?.let { it.message ?: if (it.sending) tr("Sending…") else "" }
                        ?: if (initial?.lastSentAt != null) {
                            tr("Last sent %s", formatSent(initial.lastSentAt))
                        } else {
                            tr("Not sent yet")
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        status?.sending == true -> PulseColors.Yellow
                        status?.success == true -> PulseColors.Teal
                        status?.message != null -> PulseColors.Coral
                        initial?.lastSentAt != null -> PulseColors.Teal
                        else -> PulseColors.TextMuted
                    },
                    modifier = Modifier.width(150.dp),
                )
            }
        }

        PrimaryButton(
            text = if (status?.sending == true) "Sending…" else "Send to watch",
            onClick = { if (status?.sending != true) onSend(buildEntity()) },
            enabled = hasContent && status?.sending != true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The workout is written to the watch as a FIT workout file. It appears in the watch's Workouts menu and can be started from a matching activity profile.",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.TextMuted,
        )
        Spacer(Modifier.height(PulseSpacing.lg))
    }

    editRequest?.let { request ->
        StepEditorDialog(
            initial = request.step,
            allowDelete = request.existing,
            onDismiss = { editRequest = null },
            onApply = { updated ->
                if (request.existing) updateStep(request, { updated }) else addStep(request.copy(step = updated))
                editRequest = null
            },
            onDelete = {
                removeStep(request)
                editRequest = null
            },
        )
    }
}

/** Applies [transform] to the repeat with [repeatId], leaving the other steps untouched. */
private fun List<PlannedStep>.updateRepeat(
    repeatId: String,
    transform: (PlannedStep.Repeat) -> PlannedStep.Repeat,
): List<PlannedStep> = map { item ->
    if (item is PlannedStep.Repeat && item.id == repeatId) transform(item) else item
}

/** Applies [transform] to a single step at the top level or inside the repeat with [parentId]. */
private fun List<PlannedStep>.updateStep(
    parentId: String?,
    stepId: String,
    transform: (PlannedStep.Single) -> PlannedStep.Single,
): List<PlannedStep> = if (parentId == null) {
    map { item -> if (item is PlannedStep.Single && item.id == stepId) transform(item) else item }
} else {
    updateRepeat(parentId) { repeat ->
        repeat.copy(
            children = repeat.children.map { child -> if (child.id == stepId) transform(child) else child },
        )
    }
}

/** Appends [step] at the top level or to the repeat with [parentId]. */
private fun List<PlannedStep>.addStep(parentId: String?, step: PlannedStep.Single): List<PlannedStep> =
    if (parentId == null) {
        this + step
    } else {
        updateRepeat(parentId) { repeat -> repeat.copy(children = repeat.children + step) }
    }

/** Removes the step with [stepId] from the top level or from the repeat with [parentId]. */
private fun List<PlannedStep>.removeStep(parentId: String?, stepId: String): List<PlannedStep> =
    if (parentId == null) {
        filterNot { it is PlannedStep.Single && it.id == stepId }
    } else {
        updateRepeat(parentId) { repeat ->
            repeat.copy(children = repeat.children.filterNot { child -> child.id == stepId })
        }
    }

/** Moves the top-level step with [stepId] one slot in [direction] (-1 up, 1 down). */
private fun List<PlannedStep>.moveStep(stepId: String, direction: Int): List<PlannedStep> =
    moveBy(indexOfFirst { it.id == stepId }, direction)

/** Moves the item at [index] one slot in [direction], or returns the list unchanged when out of bounds. */
private fun <T> List<T>.moveBy(index: Int, direction: Int): List<T> {
    val target = index + direction
    if (index !in indices || target !in indices) return this
    val copy = toMutableList()
    copy.add(target, copy.removeAt(index))
    return copy
}

@Composable
private fun StepRow(
    step: PlannedStep.Single,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(end = PulseSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(58.dp)
                .background(roleColor(step.role), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = PulseSpacing.lg, top = PulseSpacing.md, bottom = PulseSpacing.md),
        ) {
            Text(
                step.role.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = PulseColors.Text,
            )
            Text(
                step.summary(),
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.TextMuted,
            )
        }
        ReorderButtons(onUp, onDown, onDelete)
    }
}

@Composable
private fun RepeatCard(
    repeat: PlannedStep.Repeat,
    onCount: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onAddChild: () -> Unit,
    onChildClick: (PlannedStep.Single) -> Unit,
    onChildUp: (PlannedStep.Single) -> Unit,
    onChildDown: (PlannedStep.Single) -> Unit,
    onChildDelete: (PlannedStep.Single) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PulseColors.Background)
            .border(1.dp, PulseColors.BorderElevated, RoundedCornerShape(16.dp))
            .padding(PulseSpacing.mdPlus),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallRoundButton("−", onClick = { onCount(-1) })
            Text(
                tr(" %s Times ", repeat.count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = PulseColors.Text,
            )
            SmallRoundButton("+", onClick = { onCount(1) })
            Spacer(Modifier.weight(1f))
            ReorderButtons(onMoveUp, onMoveDown, onDelete)
        }
        repeat.children.forEach { child ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PulseColors.Surface)
                    .border(1.dp, PulseColors.Border, RoundedCornerShape(12.dp))
                    .clickable { onChildClick(child) }
                    .padding(end = PulseSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(52.dp)
                        .background(roleColor(child.role), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = PulseSpacing.md, top = PulseSpacing.sm, bottom = PulseSpacing.sm),
                ) {
                    Text(
                        child.role.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = PulseColors.Text,
                    )
                    Text(child.summary(), style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                }
                ReorderButtons(
                    onUp = { onChildUp(child) },
                    onDown = { onChildDown(child) },
                    onDelete = { onChildDelete(child) },
                )
            }
        }
        SecondaryButton(label = "+ Add step to repeat", onClick = onAddChild, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReorderButtons(onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallRoundButton("↑", onUp)
        SmallRoundButton("↓", onDown)
        SmallRoundButton("✕", onDelete, danger = true)
    }
}

@Composable
private fun SmallRoundButton(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (danger) Color.Transparent else PulseColors.SurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) PulseColors.TextMuted else PulseColors.Text,
        )
    }
}

@Composable
private fun StepEditorDialog(
    initial: PlannedStep.Single,
    allowDelete: Boolean,
    onDismiss: () -> Unit,
    onApply: (PlannedStep.Single) -> Unit,
    onDelete: () -> Unit,
) {
    var role by remember(initial.id) { mutableStateOf(initial.role) }
    var durationType by remember(initial.id) { mutableStateOf(initial.durationType) }
    var minutes by remember(initial.id) { mutableStateOf((initial.seconds / 60).toString()) }
    var seconds by remember(initial.id) { mutableStateOf((initial.seconds % 60).toString()) }
    var kilometers by remember(initial.id) { mutableStateOf("%.2f".format(initial.meters / 1000.0)) }

    val timeTotal = (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
    val distanceMeters = ((kilometers.toDoubleOrNull() ?: 0.0) * 1000).roundToInt()
    val valid = when (durationType) {
        PlannedDurationType.TIME -> timeTotal > 0
        PlannedDurationType.DISTANCE -> distanceMeters > 0
        PlannedDurationType.OPEN -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allowDelete) "Edit step" else "Add step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                Text("Role", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    StepRole.entries.forEach { option ->
                        SelectChip(
                            label = option.label,
                            selected = role == option,
                            onClick = { role = option },
                        )
                    }
                }
                Text("Duration", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    PlannedDurationType.entries.forEach { option ->
                        SelectChip(
                            label = if (option == PlannedDurationType.OPEN) {
                                tr("Open-ended")
                            } else {
                                tr(option.label)
                            },
                            selected = durationType == option,
                            onClick = { durationType = option },
                        )
                    }
                }
                when (durationType) {
                    PlannedDurationType.TIME -> Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                            label = { Text("Seconds") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    PlannedDurationType.DISTANCE -> OutlinedTextField(
                        value = kilometers,
                        onValueChange = { value ->
                            kilometers = value.filter { it.isDigit() || it == '.' || it == ',' }
                                .replace(',', '.')
                        },
                        label = { Text("Kilometres") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PlannedDurationType.OPEN -> Text(
                        "The step lasts until you press the lap button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onApply(
                        initial.copy(
                            role = role,
                            durationType = durationType,
                            seconds = timeTotal.coerceAtLeast(1),
                            meters = distanceMeters.coerceAtLeast(1),
                        ),
                    )
                },
            ) { Text(if (allowDelete) "Apply" else "Add", color = PulseColors.Accent) }
        },
        dismissButton = {
            Row {
                if (allowDelete) {
                    TextButton(onClick = onDelete) { Text("Delete", color = PulseColors.Coral) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) PulseColors.Accent else PulseColors.SurfaceElevated)
            .border(1.dp, if (selected) PulseColors.Accent else PulseColors.BorderStrong, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = PulseSpacing.lg, vertical = PulseSpacing.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (selected) PulseColors.TextOnAccent else PulseColors.Text,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, PulseColors.Accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = PulseColors.Accent)
    }
}

private fun roleColor(role: StepRole): Color = when (role) {
    StepRole.WARM_UP -> PulseColors.Coral
    StepRole.ACTIVE -> PulseColors.Blue
    StepRole.INTERVAL -> PulseColors.Orange
    StepRole.REST -> PulseColors.TextMuted
    StepRole.RECOVERY -> PulseColors.Teal
    StepRole.COOL_DOWN -> PulseColors.Accent
    StepRole.OTHER -> PulseColors.Yellow
}

private fun estimateLabel(steps: List<PlannedStep>): String {
    val seconds = WorkoutPlanCodec.totalSeconds(steps)
    val meters = WorkoutPlanCodec.totalMeters(steps)
    return buildString {
        append(if (seconds > 0) WorkoutPlanCodec.formatDuration(seconds) else "Open")
        if (meters > 0) append(" · %.1f km".format(meters / 1000.0))
    }
}

private fun isThisWeek(date: String?): Boolean {
    if (date == null) return false
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return !parsed.isBefore(monday) && parsed.isBefore(monday.plusWeeks(1))
}

private fun formatScheduled(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return parsed.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}

private fun formatSent(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
