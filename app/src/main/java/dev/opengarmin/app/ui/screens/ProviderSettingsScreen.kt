package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import dev.opengarmin.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.llm.LlmException
import dev.opengarmin.app.llm.ModelConfig
import dev.opengarmin.app.llm.OpenAiClient
import dev.opengarmin.app.llm.ProviderConfig
import dev.opengarmin.app.llm.ProviderPresets
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.CompactCard
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.components.SectionLabel
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.launch

@Composable
fun ProviderSettingsScreen(
    store: ProviderStore,
    initialConfig: ProviderConfig,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetId by remember { mutableStateOf(initialConfig.presetId) }
    var baseUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var keyInput by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(initialConfig.models) }
    var defaultModelId by remember { mutableStateOf(initialConfig.defaultModelId ?: initialConfig.models.firstOrNull()?.id) }
    var hasStoredKey by remember { mutableStateOf(initialConfig.hasStoredKey) }
    var keyHint by remember { mutableStateOf(initialConfig.keyHint) }
    var fromBuildConfig by remember { mutableStateOf(initialConfig.fromBuildConfig) }
    var editingModel by remember { mutableStateOf<ModelConfig?>(null) }
    var addingModel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    val scope = rememberCoroutineScope()

    fun nextModelId() = "model-${System.currentTimeMillis()}-${models.size}"

    fun saveConfig() {
        messageIsError = true
        val trimmed = baseUrl.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            message = "Base URL must start with http:// or https://."
            return
        }
        if (models.isEmpty()) {
            message = "Add at least one model."
            return
        }
        val default = defaultModelId?.takeIf { id -> models.any { it.id == id } } ?: models.first().id
        val next = ProviderConfig(
            presetId = presetId,
            baseUrl = trimmed,
            models = models,
            defaultModelId = default,
            keyHint = keyHint,
            hasStoredKey = hasStoredKey || keyInput.isNotBlank(),
            fromBuildConfig = false,
        )
        val saved = store.save(next, keyInput.takeIf { it.isNotBlank() })
        baseUrl = saved.baseUrl
        keyInput = ""
        keyHint = saved.keyHint
        hasStoredKey = saved.hasStoredKey
        fromBuildConfig = false
        defaultModelId = saved.defaultModelId
        messageIsError = false
        message = "Provider saved."
        onChanged()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = PulseSpacing.xxl, end = PulseSpacing.xxl, top = PulseSpacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(PulseSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            IconButtonRound(Icons.Outlined.ArrowBack, "Back to settings", onBack)
            Text("Provider", style = MaterialTheme.typography.headlineLarge, color = PulseColors.Text)
        }

        if (fromBuildConfig) {
            Text(
                "Using the deployment configuration from BuildConfig. Saving here overrides it for this device.",
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.Orange,
            )
        }

        SectionLabel("Preset")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            ProviderPresets.all.forEach { preset ->
                PillChip(
                    label = preset.label,
                    selected = presetId == preset.id,
                    onClick = {
                        presetId = preset.id
                        if (preset.baseUrl.isNotBlank()) baseUrl = preset.baseUrl
                        if (models.isEmpty() && preset.suggestedModels.isNotEmpty()) {
                            val stamp = System.currentTimeMillis()
                            models = preset.suggestedModels.mapIndexed { index, name ->
                                ModelConfig(id = "model-$stamp-$index", name = name)
                            }
                            defaultModelId = models.firstOrNull()?.id
                        }
                    },
                )
            }
        }

        val selectedPreset = ProviderPresets.byId(presetId)
        if (selectedPreset.isLocal) {
            Text(
                "Local preset: the endpoint is on your device, LAN, or an adb reverse tunnel. Android emulators reach the host at 10.0.2.2.",
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.Teal,
            )
        }
        if (baseUrl.startsWith("http://")) {
            Text(
                "This uses plain HTTP. Health data will be sent unencrypted; prefer HTTPS or a trusted local network.",
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.Orange,
            )
        }

        SectionLabel("Endpoint")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PulseSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; presetId = "custom" },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API key") },
                    placeholder = {
                        Text(
                            if (hasStoredKey) tr("Saved (%s)", keyHint) else tr("Optional for local endpoints"),
                        )
                    },
                    supportingText = {
                        Text("Stored encrypted with Android Keystore. Never shown again or logged.")
                    },
                    visualTransformation = if (keyInput.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasStoredKey) {
                    Text(tr("Saved key: %s", keyHint), style = MaterialTheme.typography.labelMedium, color = PulseColors.Teal)
                    PrimaryButton("Delete stored key", onClick = {
                        val next = store.deleteKey()
                        hasStoredKey = next.hasStoredKey
                        keyHint = next.keyHint
                        messageIsError = false
                        message = "Stored key deleted."
                        onChanged()
                    })
                }
                if (selectedPreset.suggestedModels.isNotEmpty()) {
                    Text("Suggested models", style = MaterialTheme.typography.labelMedium, color = PulseColors.TextMuted)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                    ) {
                        selectedPreset.suggestedModels.forEach { name ->
                            if (models.none { it.name == name }) {
                                PillChip(label = "+ $name", selected = false, onClick = {
                                    models = models + ModelConfig(id = nextModelId(), name = name)
                                    if (defaultModelId == null) defaultModelId = models.last().id
                                })
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Models")
        models.forEach { model ->
            CompactCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(PulseSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
                ) {
                    RadioButton(
                        selected = model.id == defaultModelId,
                        onClick = { defaultModelId = model.id },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            model.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = PulseColors.Text,
                            translate = false,
                        )
                        Text(
                            if (model.id == defaultModelId) {
                                tr(
                                    "temperature %s · max tokens %s · default",
                                    model.temperature?.toString() ?: tr("default"),
                                    model.maxTokens?.toString() ?: tr("default"),
                                )
                            } else {
                                tr(
                                    "temperature %s · max tokens %s",
                                    model.temperature?.toString() ?: tr("default"),
                                    model.maxTokens?.toString() ?: tr("default"),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseColors.TextMuted,
                        )
                    }
                    IconButtonRound(Icons.Outlined.Edit, tr("Edit %s", model.name), {
                        editingModel = model
                    }, background = PulseColors.Surface, size = 40.dp)
                    IconButtonRound(Icons.Outlined.Delete, tr("Delete %s", model.name), {
                        models = models.filterNot { it.id == model.id }
                        if (defaultModelId == model.id) defaultModelId = models.firstOrNull()?.id
                    }, background = PulseColors.Surface, size = 40.dp, tint = PulseColors.Coral)
                }
            }
        }
        if (models.isEmpty()) {
            Text("No models yet. Add one or pick a preset with suggestions.", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        }
        PrimaryButton("Add model", onClick = { addingModel = true }, leading = Icons.Outlined.Add)

        Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
            PrimaryButton("Save provider", onClick = { saveConfig() })
            PrimaryButton("Test connection", onClick = {
                val model = models.firstOrNull { it.id == defaultModelId } ?: models.firstOrNull()
                if (model == null || baseUrl.isBlank()) {
                    messageIsError = true
                    message = "Add a base URL and model first."
                    return@PrimaryButton
                }
                val key = keyInput.takeIf { it.isNotBlank() } ?: store.apiKeyFor(
                    ProviderConfig(hasStoredKey = hasStoredKey, fromBuildConfig = fromBuildConfig),
                )
                testState = TestState.Loading
                scope.launch {
                    testState = try {
                        val result = OpenAiClient(
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            apiKey = key,
                            model = model.name,
                            connectTimeoutSeconds = 10,
                            readTimeoutSeconds = 20,
                        ).testConnection()
                        TestState.Success(result)
                    } catch (e: LlmException) {
                        TestState.Failure(e.userMessage())
                    } catch (e: Exception) {
                        TestState.Failure("Connection failed. Check the base URL and network.")
                    }
                }
            }, enabled = testState != TestState.Loading)
        }
        when (val state = testState) {
            TestState.Idle -> Unit
            TestState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), color = PulseColors.Accent, strokeWidth = 2.dp)
                Text("Testing…", style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
            }
            is TestState.Success -> Text(state.message, style = MaterialTheme.typography.labelMedium, color = PulseColors.Teal)
            is TestState.Failure -> Text(state.message, style = MaterialTheme.typography.labelMedium, color = PulseColors.Coral)
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (messageIsError) PulseColors.Coral else PulseColors.Teal,
            )
        }

        SectionLabel("Privacy")
        PulseCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PulseSpacing.xl), verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                Text(
                    "The coach sends the results of the tools it calls (and, for models without tool calling, a compact summary) to the endpoint above. Health data is never sent anywhere else. A local model keeps it on your network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
                Text(
                    "The API key is encrypted at rest, never returned to the screen after saving, and never written to logs.",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulseColors.TextMuted,
                )
            }
        }
        PrimaryButton("Remove provider configuration", onClick = { confirmDelete = true })
        Spacer(Modifier.padding(bottom = PulseSpacing.xl))
    }

    if (addingModel || editingModel != null) {
        ModelDialog(
            initial = editingModel,
            onDismiss = { addingModel = false; editingModel = null },
            onSave = { model ->
                val existing = editingModel
                if (existing == null) {
                    models = models + model.copy(id = nextModelId())
                    if (defaultModelId == null) defaultModelId = models.last().id
                } else {
                    models = models.map { if (it.id == existing.id) model.copy(id = existing.id) else it }
                }
                addingModel = false
                editingModel = null
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove provider?") },
            text = { Text("This deletes the endpoint, models and stored API key from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    baseUrl = ""
                    keyInput = ""
                    models = emptyList()
                    defaultModelId = null
                    hasStoredKey = false
                    keyHint = ""
                    fromBuildConfig = false
                    confirmDelete = false
                    onChanged()
                }) { Text("Remove", color = PulseColors.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Loading : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

@Composable
private fun ModelDialog(
    initial: ModelConfig?,
    onDismiss: () -> Unit,
    onSave: (ModelConfig) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var temperature by remember(initial) { mutableStateOf(initial?.temperature?.toString() ?: "") }
    var maxTokens by remember(initial) { mutableStateOf(initial?.maxTokens?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add model" else "Edit model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Model name") },
                    placeholder = { Text("gpt-4o-mini") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Temperature (optional)") },
                    supportingText = { Text("0.0–2.0; blank uses the provider default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) },
                    label = { Text("Max tokens (optional)") },
                    supportingText = { Text("1–8192; blank uses the provider default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PulseColors.Coral) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    error = "Model name is required."
                    return@TextButton
                }
                val temp = temperature.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                if (temperature.isNotBlank() && (temp == null || temp !in 0.0..2.0)) {
                    error = "Temperature must be between 0 and 2."
                    return@TextButton
                }
                val max = maxTokens.takeIf { it.isNotBlank() }?.toIntOrNull()
                if (maxTokens.isNotBlank() && (max == null || max !in 1..8192)) {
                    error = "Max tokens must be between 1 and 8192."
                    return@TextButton
                }
                onSave(
                    ModelConfig(
                        id = initial?.id ?: "pending",
                        name = name.trim(),
                        temperature = temp,
                        maxTokens = max,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
