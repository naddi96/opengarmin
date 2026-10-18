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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.net.Uri
import dev.opengarmin.app.BuildConfig
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderPresets
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
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
    modifier: Modifier = Modifier,
) {
    var goalText by remember(settings.stepGoal) { mutableStateOf(settings.stepGoal.toString()) }
    var maxHrText by remember(settings.maxHeartRate) { mutableStateOf(settings.maxHeartRate?.toString() ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
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
                    supportingText = { Text("Used only to calculate heart-rate zones. No value is estimated.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    PrimaryButton("Save profile", onClick = {
                        val goal = goalText.toIntOrNull()
                        if (goal == null || goal !in 1_000..100_000) {
                            savedMessage = "Step goal must be between 1,000 and 100,000."
                        } else {
                            settings.updateStepGoal(goal)
                            val maxHr = maxHrText.toIntOrNull()
                            if (maxHrText.isNotBlank() && (maxHr == null || maxHr !in 100..250)) {
                                savedMessage = "Max heart rate must be between 100 and 250."
                            } else {
                                settings.updateMaxHeartRate(maxHr)
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
                    Text(it, style = MaterialTheme.typography.labelMedium, color = PulseColors.Teal)
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
