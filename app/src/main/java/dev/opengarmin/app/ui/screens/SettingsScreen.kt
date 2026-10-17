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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.BuildConfig
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderPresets
import dev.opengarmin.app.settings.PulseSettings
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
    onBack: () -> Unit,
    onManageProvider: () -> Unit,
    onSync: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onShowLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var goalText by remember(settings.stepGoal) { mutableStateOf(settings.stepGoal.toString()) }
    var maxHrText by remember(settings.maxHeartRate) { mutableStateOf(settings.maxHeartRate?.toString() ?: "") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

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
}
