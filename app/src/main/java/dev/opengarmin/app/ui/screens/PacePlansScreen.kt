package dev.opengarmin.app.ui.screens

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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.i18n.Text
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.launch

/** Overview of every locally saved PacePro strategy. */
@Composable
fun PacePlansScreen(
    plans: List<PacePlanEntity>,
    sendStatus: PacePlanSendStatus?,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (PacePlanEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: suspend (PacePlanEntity) -> PacePlanEntity?,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<PacePlanEntity?>(null) }
    var renaming by remember { mutableStateOf<PacePlanEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val renameFailureMessage = tr("Could not rename the strategy.")

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
                Text("PacePro", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
                Text(
                    "Saved split-by-split strategies",
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

        PrimaryButton(
            text = "New strategy",
            onClick = onNew,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Your strategies")
        if (plans.isEmpty()) {
            PulseCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PulseSpacing.xlPlus),
                    verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    Text("No strategies yet", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                    Text(
                        "Create a race goal, choose the elevation source and tune the splits before sending it to the watch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        } else {
            plans.forEach { plan ->
                PacePlanRow(
                    plan = plan,
                    onOpen = { onOpen(plan) },
                    onRename = {
                        renaming = plan
                        renameText = plan.name
                        renameError = null
                    },
                    onDelete = { deleting = plan },
                )
            }
        }
        Spacer(Modifier.height(PulseSpacing.lg))
    }

    deleting?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleting = null },
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
                    deleting = null
                }) { Text("Delete", color = PulseColors.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }

    renaming?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!renameBusy) renaming = null },
            title = { Text("Rename strategy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            renameText = it
                            renameError = null
                        },
                        label = { Text("Strategy name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    renameError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = PulseColors.Coral)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && !renameBusy,
                    onClick = {
                        if (renameBusy) return@TextButton
                        renameBusy = true
                        renameError = null
                        scope.launch {
                            val updated = onRename(plan.copy(name = renameText.trim()))
                            renameBusy = false
                            if (updated != null) {
                                renaming = null
                            } else {
                                renameError = renameFailureMessage
                            }
                        }
                    },
                ) { Text(tr("Save"), color = PulseColors.Accent) }
            },
            dismissButton = {
                TextButton(
                    enabled = !renameBusy,
                    onClick = { renaming = null },
                ) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun PacePlanRow(
    plan: PacePlanEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    PulseCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.padding(
                start = PulseSpacing.xl,
                end = PulseSpacing.md,
                top = PulseSpacing.lg,
                bottom = PulseSpacing.lg,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PulseColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = PulseColors.Accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(PulseSpacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = PulseColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        translate = false,
                    )
                    Spacer(Modifier.width(PulseSpacing.sm))
                    SentPill(plan.lastSentAt != null)
                }
                Text(
                    plan.summary(),
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    plan.courseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.Edit,
                contentDescription = tr("Rename %s", plan.name),
                tint = PulseColors.TextMuted,
                modifier = Modifier
                    .size(PulseSpacing.touchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRename)
                    .padding(PulseSpacing.md),
            )
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = tr("Delete %s", plan.name),
                tint = PulseColors.TextMuted,
                modifier = Modifier
                    .size(PulseSpacing.touchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDelete)
                    .padding(PulseSpacing.md),
            )
        }
    }
}

@Composable
private fun SentPill(sent: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PulseColors.SurfaceElevated)
            .padding(horizontal = PulseSpacing.sm, vertical = 3.dp),
    ) {
        Text(
            tr(if (sent) "Sent" else "Draft"),
            style = MaterialTheme.typography.labelSmall,
            color = if (sent) PulseColors.Teal else PulseColors.TextMuted,
        )
    }
}


