package dev.opengarmin.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import dev.opengarmin.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.diagnostics.AppEvent
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.Badge
import dev.opengarmin.app.ui.components.EmptyState
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.LoadingState
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hidden diagnostics browser. Reads the dedicated error database only; it is
 * never linked from normal navigation unless the version row was tapped seven
 * times in Settings.
 */
@Composable
fun DiagnosticsScreen(
    events: List<AppEvent>,
    loading: Boolean,
    status: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExport: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    var area by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExport(uri) }

    val areas = events.map { it.area }.distinct().sorted()
    val visible = if (area == null) events else events.filter { it.area == area }
    val totalOccurrences = events.sumOf { it.occurrences }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButtonRound(Icons.Outlined.ArrowBack, "Back to settings", onBack)
                Column(modifier = Modifier.padding(start = PulseSpacing.md)) {
                    Text(
                        "Diagnostics",
                        style = MaterialTheme.typography.headlineLarge,
                        color = PulseColors.Text,
                    )
                    Text(
                        tr("%s grouped · %s total", events.size, totalOccurrences),
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }

        item {
            Text(
                "Errors are stored only on this device, capped at 30 days / 5,000 rows. " +
                    "Keys, tokens and MAC addresses are redacted before writing. Export is manual; nothing is uploaded.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }

        if (areas.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                    item {
                        PillChip(
                            label = "All",
                            selected = area == null,
                            onClick = { area = null },
                        )
                    }
                    items(areas) { name ->
                        val count = events.count { it.area == name }
                        PillChip(
                            label = tr("%s (%s)", name, count),
                            selected = area == name,
                            onClick = { area = if (area == name) null else name },
                        )
                    }
                }
            }
        }

        if (loading) {
            item { LoadingState("Reading the local error log…") }
        } else if (visible.isEmpty()) {
            item {
                EmptyState(
                    title = "No errors recorded",
                    message = "Sync, import, coach, analysis and crash failures will appear here.",
                )
            }
        } else {
            items(visible, key = { it.id }) { event ->
                EventRow(
                    event = event,
                    expanded = expandedId == event.id,
                    onToggle = { expandedId = if (expandedId == event.id) null else event.id },
                )
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = PulseSpacing.md),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    PrimaryButton(
                        "Export JSON",
                        onClick = { exporter.launch("opengarmin-diagnostics-$stamp.json") },
                        enabled = events.isNotEmpty(),
                    )
                    PrimaryButton("Refresh", onRefresh)
                }
                PrimaryButton("Clear", onClick = { confirmClear = true }, enabled = events.isNotEmpty())
            }
        }

        status?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.startsWith("Could not") || it.startsWith("Impossibile")) {
                        PulseColors.Coral
                    } else {
                        PulseColors.Teal
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear diagnostics?") },
            text = { Text("This deletes the local error history. It cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text("Clear", color = PulseColors.Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EventRow(
    event: AppEvent,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val time = remember(event.lastSeenAt) {
        SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(Date(event.lastSeenAt))
    }
    PulseCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
            ) {
                Badge(event.level.uppercase(), levelColor(event.level))
                Text(
                    event.area + " · " + event.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                if (event.occurrences > 1) {
                    Text(
                        "×${event.occurrences}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.Accent,
                    )
                }
            }
            Text(
                event.message,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Text,
                translate = false,
            )
            Text(
                tr("%s · session %s", time, event.sessionId),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            if (expanded) {
                event.stackTrace?.let { trace ->
                    Text(
                        trace,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = PulseColors.TextMuted,
                        translate = false,
                    )
                }
                if (event.contextJson.isNotBlank() && event.contextJson != "{}") {
                    Text(
                        event.contextJson,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = PulseColors.Blue,
                    )
                }
            }
        }
    }
}

private fun levelColor(level: String) = when (level) {
    Diagnostics.LEVEL_FATAL -> PulseColors.Coral
    Diagnostics.LEVEL_ERROR -> PulseColors.Orange
    Diagnostics.LEVEL_WARN -> PulseColors.Yellow
    else -> PulseColors.TextMuted
}
