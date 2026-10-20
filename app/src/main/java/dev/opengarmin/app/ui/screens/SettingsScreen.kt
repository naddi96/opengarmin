package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.net.Uri
import dev.opengarmin.app.BuildConfig
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderPresets
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.sync.SyncPhase
import dev.opengarmin.app.sync.SyncPolicy
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.util.formatFileSize
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.components.SegmentedControl
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: PulseSettings,
    provider: ProviderConfig,
    syncLabel: String,
    activitySyncProgress: ActivitySyncProgress?,
    lastImportedLabel: String?,
    onBack: () -> Unit,
    onManageProvider: () -> Unit,
    onSync: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onShowLog: () -> Unit,
    onImportFit: (Uri) -> Unit,
    backupStatus: String?,
    backupBusy: Boolean,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onBackgroundSyncChanged: () -> Unit,
    onRunBackgroundSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var goalText by remember(settings.stepGoal) { mutableStateOf(settings.stepGoal.toString()) }
    var maxHrText by remember(settings.maxHeartRate) { mutableStateOf(settings.maxHeartRate?.toString() ?: "") }
    var heightText by remember(settings.heightCm) { mutableStateOf(settings.heightCm?.toString() ?: "") }
    var weightText by remember(settings.weightKg) {
        mutableStateOf(
            settings.weightKg?.let { value ->
                if (value == value.toInt().toDouble()) value.toInt().toString()
                else "%.1f".format(value)
            } ?: "",
        )
    }
    var birthYearText by remember(settings.birthYear) {
        mutableStateOf(settings.birthYear?.toString() ?: "")
    }
    var biologicalSex by remember(settings.biologicalSex) {
        mutableStateOf(settings.biologicalSex ?: "prefer_not_to_say")
    }
    var limitationsText by remember(settings.trainingLimitations) {
        mutableStateOf(settings.trainingLimitations ?: "")
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val focusManager = LocalFocusManager.current
    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportFit(uri)
    }
    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) onExportBackup(uri)
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }
    val timestamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            IconButtonRound(Icons.Outlined.ArrowBack, "Back", onBack)
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
        }

        SectionLabel("Profile")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Daily step goal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxHrText,
                    onValueChange = { maxHrText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Max heart rate (bpm, optional)") },
                    supportingText = { Text("Used to calculate heart-rate zones. No value is estimated.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Height (cm, optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = {
                            weightText = it
                                .filter { char -> char.isDigit() || char == '.' || char == ',' }
                                .take(6)
                        },
                        label = { Text("Weight (kg, optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = birthYearText,
                    onValueChange = { birthYearText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year of birth (optional)") },
                    supportingText = { Text("Used for age context. No full date of birth is stored.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Biological sex",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        listOf(
                            "female" to "Female",
                            "male" to "Male",
                            "intersex" to "Intersex",
                        ).forEach { (value, label) ->
                            PillChip(
                                label = label,
                                selected = biologicalSex == value,
                                onClick = { biologicalSex = value },
                            )
                        }
                    }
                    PillChip(
                        label = "Prefer not to say",
                        selected = biologicalSex == "prefer_not_to_say",
                        onClick = { biologicalSex = "prefer_not_to_say" },
                    )
                }
                Text(
                    "Optional; used for metabolic and training-load context.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                OutlinedTextField(
                    value = limitationsText,
                    onValueChange = { limitationsText = it.take(500) },
                    label = { Text("Training limitations or injuries (optional)") },
                    supportingText = {
                        Text("Stored locally. Shared with the coach only while you chat, to avoid unsafe suggestions.")
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Resting heart rate",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                        Text(
                            settings.importedRestingHeartRate?.let { "$it bpm" } ?: "Not imported yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = PulseColors.Text,
                        )
                    }
                    Text(
                        "From watch",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    "Imported automatically from the latest watch wellness data. Used for recovery and heart-rate-reserve context.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                Text(
                    "Height, weight, year of birth, sex, training limitations and the imported resting heart rate are stored only on this device. This profile is sent to the coach only when you chat with your configured provider.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    PrimaryButton("Save profile", onClick = {
                        val goal = goalText.toIntOrNull()
                        val maxHr = maxHrText.toIntOrNull()
                        val height = heightText.toIntOrNull()
                        val weight = weightText.replace(',', '.').toDoubleOrNull()
                        val birthYear = birthYearText.toIntOrNull()
                        val maxBirthYear = java.time.Year.now().value - 10
                        when {
                            goal == null || goal !in 1_000..100_000 ->
                                savedMessage = "Step goal must be between 1,000 and 100,000."
                            maxHrText.isNotBlank() && (maxHr == null || maxHr !in 100..250) ->
                                savedMessage = "Max heart rate must be between 100 and 250."
                            heightText.isNotBlank() && (height == null || height !in 100..250) ->
                                savedMessage = "Height must be between 100 and 250 cm."
                            weightText.isNotBlank() && (weight == null || weight !in 30.0..300.0) ->
                                savedMessage = "Weight must be between 30 and 300 kg."
                            birthYearText.isNotBlank() &&
                                (birthYear == null || birthYear !in 1900..maxBirthYear) ->
                                savedMessage = "Year of birth must be between 1900 and $maxBirthYear."
                            else -> {
                                settings.updateStepGoal(goal)
                                settings.updateMaxHeartRate(maxHr)
                                settings.updateHeightCm(height)
                                settings.updateWeightKg(weight)
                                settings.updateBirthYear(birthYear)
                                settings.updateBiologicalSex(
                                    biologicalSex.takeIf { it != "prefer_not_to_say" },
                                )
                                settings.updateTrainingLimitations(limitationsText)
                                focusManager.clearFocus()
                                savedMessage = "Profile saved."
                            }
                        }
                    })
                    PrimaryButton("Clear max HR", onClick = {
                        maxHrText = ""
                        settings.updateMaxHeartRate(null)
                        savedMessage = "Max heart rate cleared."
                    }, enabled = settings.maxHeartRate != null)
                }
                savedMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it.contains("saved", ignoreCase = true) || it.contains("cleared", ignoreCase = true)) {
                            PulseColors.Teal
                        } else {
                            PulseColors.Coral
                        },
                    )
                }
            }
        }

        SectionLabel("AI provider")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                val preset = ProviderPresets.byId(provider.presetId)
                Text(
                    if (provider.isConfigured) preset.label else "Not configured",
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                if (provider.isConfigured) {
                    Text(
                        provider.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        "Default model: ${provider.defaultModel?.name ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        if (provider.hasStoredKey) "API key: ${provider.keyHint}" else if (provider.fromBuildConfig) "API key: from build configuration" else "API key: not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Text(
                        "Configure any OpenAI-compatible endpoint. The coach is disabled until a base URL and default model exist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                PrimaryButton("Manage provider", onManageProvider)
                Text(
                    "Health data is sent to the endpoint you configure. Use a local model (Ollama, LM Studio, vLLM) if you want it to stay on your network.",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
            }
        }

        SectionLabel("Device & sync")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text(
                    settings.watchName.ifBlank { "No watch name recorded" },
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                Text(syncLabel, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
                activitySyncProgress?.let { progress ->
                    val current = (progress.done + 1).coerceAtMost(progress.total)
                    Text(
                        buildString {
                            append("Downloading activity $current of ${progress.total}")
                            progress.currentSize?.let { append(" · ${formatFileSize(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.Accent,
                    )
                }
                lastImportedLabel?.let {
                    Text(
                        "Last imported: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                settings.lastSyncAt?.let {
                    Text(
                        "Last success: ${SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                settings.lastSyncError.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = PulseColors.Coral)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    PrimaryButton("Sync now", onSync)
                    PrimaryButton("Scan", onScan)
                    PrimaryButton("Disconnect", onDisconnect, enabled = true)
                }
                PrimaryButton("Show device log", onShowLog)
                Text(
                    "Recovery: import a FIT file that is no longer exposed by the watch. The file stays on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                PrimaryButton(
                    "Import FIT file",
                    onClick = { fitPicker.launch(arrayOf("*/*")) },
                )
            }
        }

        SectionLabel("Background sync")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Periodic sync", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                        Text(
                            "Default off. When enabled, Android syncs your bonded watch even when the app is closed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = settings.periodicSyncEnabled,
                        onCheckedChange = { enabled ->
                            settings.updatePeriodicSyncEnabled(enabled)
                            onBackgroundSyncChanged()
                        },
                    )
                }
                if (settings.periodicSyncEnabled) {
                    Text("Interval", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                    SegmentedControl(
                        options = SyncPolicy.INTERVAL_LABELS,
                        selectedIndex = SyncPolicy.optionIndex(settings.periodicSyncIntervalMinutes),
                        onSelected = { index ->
                            settings.updatePeriodicSyncInterval(SyncPolicy.INTERVAL_OPTIONS[index])
                            onBackgroundSyncChanged()
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Only while charging", style = MaterialTheme.typography.bodySmall, color = PulseColors.Text)
                            Text(
                                "Battery-not-low is always required. Android may delay a run to save power.",
                                style = MaterialTheme.typography.labelSmall,
                                color = PulseColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = settings.periodicSyncChargingOnly,
                            onCheckedChange = { only ->
                                settings.updatePeriodicSyncChargingOnly(only)
                                onBackgroundSyncChanged()
                            },
                        )
                    }
                    Text(
                        "Scheduled by Android: every ${SyncPolicy.intervalLabel(settings.periodicSyncIntervalMinutes)}, " +
                            "battery not low" +
                            if (settings.periodicSyncChargingOnly) ", charging required." else ", charging optional.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                } else {
                    Text(
                        "Manual sync and sync-on-launch still run. Periodic sync does not schedule anything while this is off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
                PrimaryButton(
                    "Run background sync now",
                    onClick = onRunBackgroundSyncNow,
                    enabled = settings.periodicSyncEnabled &&
                        SyncPhase.fromStored(settings.lastSyncStatus) != SyncPhase.Syncing,
                )
                settings.lastSyncAttemptAt?.let {
                    Text(
                        "Last attempt: ${SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                settings.lastSyncAt?.let {
                    Text(
                        "Last success: ${SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
                Text(
                    "Duration: ${SyncPolicy.formatDuration(settings.lastSyncDurationMs)} · " +
                        "Trigger: ${SyncPolicy.triggerLabel(settings.lastSyncTrigger)} · " +
                        "Result: ${syncResultLabel(settings.lastSyncKind, settings.lastSyncStatus)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                settings.lastSyncError.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = PulseColors.Coral)
                }
            }
        }

        SectionLabel("Data & backup")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                Text(
                    "Export every activity, wellness sample and coach conversation to a single backup file. The file stays wherever you save it; nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    PrimaryButton(
                        if (backupBusy) "Working…" else "Export backup",
                        onClick = { backupExporter.launch("opengarmin-backup-$timestamp.zip") },
                        enabled = !backupBusy,
                    )
                    PrimaryButton(
                        "Import backup",
                        onClick = {
                            backupImporter.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "application/x-sqlite3",
                                    "*/*",
                                ),
                            )
                        },
                        enabled = !backupBusy,
                    )
                }
                backupStatus?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = PulseColors.Teal)
                }
                Text(
                    "Restoring replaces all data currently on this device. Importing a database-only backup leaves the current conversations untouched.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }

        SectionLabel("About")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.xs),
            ) {
                Text("Pulse / OpenGarmin ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
                Text(
                    "Local-first fitness companion. The coach is informational, not medical advice, and cannot diagnose or treat conditions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                Text(
                    "Fonts: DM Sans and Space Grotesk (SIL Open Font License). Map tiles © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.padding(bottom = PulseSpacing.xl))
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "This replaces every activity, wellness sample and coach conversation on this device. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    onImportBackup(uri)
                }) {
                    Text("Restore", color = PulseColors.Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
    }
}

private fun syncResultLabel(kind: String, status: String): String {
    val phase = SyncPhase.fromStored(status)
    return when {
        phase == SyncPhase.Syncing -> "Syncing"
        kind == "success" -> "Success"
        kind == "permanent" -> "Needs setup"
        kind == "busy" -> "Busy"
        kind == "cancelled" -> "Stopped"
        kind == "transient" -> "Temporary failure"
        phase == SyncPhase.UpToDate -> "Success"
        phase == SyncPhase.Failed -> "Failed"
        else -> "Not recorded"
    }
}
