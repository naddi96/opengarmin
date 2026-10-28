package dev.opengarmin.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.garmin.GarminActivity
import dev.opengarmin.app.garmin.GarminConnectClient
import dev.opengarmin.app.garmin.GarminCredentialStore
import dev.opengarmin.app.garmin.GarminDailySummary
import dev.opengarmin.app.garmin.GarminException
import dev.opengarmin.app.garmin.GarminLoginResult
import dev.opengarmin.app.garmin.garminActivityUid
import dev.opengarmin.app.i18n.AppStrings
import dev.opengarmin.app.i18n.LocalAppLanguage
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.sync.FitImportResult
import dev.opengarmin.app.ui.components.PillChip
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Garmin Connect cloud recovery for watches whose pending-upload queue no
 * longer offers an archived activity. The original FIT is downloaded from the
 * same unofficial mobile API that python-garminconnect uses.
 */
@Composable
fun GarminConnectCard(
    onImportFit: suspend (String, ByteArray, String) -> FitImportResult,
    isActivityKnown: (String) -> Boolean,
    onImportWellness: suspend (List<GarminDailySummary>) -> Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    fun l(text: String, vararg args: Any?): String = AppStrings.format(text, language, *args)
    val store = remember(context) { GarminCredentialStore(context) }
    val client = remember(store) { GarminConnectClient(store) }
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf(store.username()) }
    var password by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(store.isSignedIn()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var mfaMethod by remember { mutableStateOf<String?>(null) }
    var mfaCode by remember { mutableStateOf("") }
    var activities by remember { mutableStateOf<List<GarminActivity>>(emptyList()) }
    var activitiesLoaded by remember { mutableStateOf(false) }
    var importingId by remember { mutableStateOf<String?>(null) }
    var importedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkRunning by remember { mutableStateOf(false) }
    var bulkJob by remember { mutableStateOf<Job?>(null) }
    var confirmBulkImport by remember { mutableStateOf(false) }
    var showWellnessImport by remember { mutableStateOf(false) }
    var wellnessDays by remember { mutableStateOf(30) }
    var wellnessBusy by remember { mutableStateOf(false) }
    var wellnessProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var wellnessJob by remember { mutableStateOf<Job?>(null) }

    fun fail(error: Throwable) {
        val garminError = error as? GarminException
        if (garminError?.kind == GarminException.Kind.NotSignedIn) {
            signedIn = false
            activities = emptyList()
            activitiesLoaded = false
            store.clear()
        }
        status = garminError?.message
            ?: error.message
            ?: l("The Garmin request failed.")
        statusIsError = true
    }

    suspend fun loadActivities() {
        val list = client.recentActivities(limit = 20)
        activities = list
        activitiesLoaded = true
        signedIn = true
    }

    fun signIn() {
        if (busy) return
        busy = true
        status = null
        statusIsError = false
        scope.launch {
            try {
                when (val result = client.login(username, password)) {
                    is GarminLoginResult.NeedsMfa -> {
                        mfaMethod = result.method
                        mfaCode = ""
                        status = l(
                            "Garmin sent a verification code via %s. Enter it below to finish signing in.",
                            l(mfaLabel(result.method)),
                        )
                        statusIsError = false
                    }
                    is GarminLoginResult.Success -> {
                        store.saveUsername(username)
                        password = ""
                        mfaMethod = null
                        mfaCode = ""
                        signedIn = true
                        loadActivities()
                        status = if (activities.isEmpty()) {
                            l("Signed in. Garmin returned no activities.")
                        } else {
                            l("Signed in. Choose an activity to import.")
                        }
                        statusIsError = false
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                busy = false
            }
        }
    }

    fun verifyMfa() {
        if (busy) return
        busy = true
        status = null
        statusIsError = false
        scope.launch {
            try {
                client.completeMfa(mfaCode)
                store.saveUsername(username)
                password = ""
                mfaMethod = null
                mfaCode = ""
                signedIn = true
                loadActivities()
                status = "Signed in. Choose an activity to import."
                statusIsError = false
            } catch (error: Throwable) {
                fail(error)
            } finally {
                busy = false
            }
        }
    }

    fun importActivity(activity: GarminActivity) {
        if (importingId != null) return
        importingId = activity.id
        status = l("Downloading %s from Garmin Connect…", activity.displayTitle)
        statusIsError = false
        scope.launch {
            try {
                val bytes = client.downloadOriginalFit(activity.id)
                when (val result = onImportFit(
                    "garmin-${activity.id}.fit",
                    bytes,
                    garminActivityUid(activity.id),
                )) {
                    is FitImportResult.Imported -> {
                        importedIds = importedIds + activity.id
                        status = l("Imported %s.", activity.displayTitle)
                        statusIsError = false
                    }
                    FitImportResult.AlreadyImported -> {
                        importedIds = importedIds + activity.id
                        status = l("%s is already in Pulse.", activity.displayTitle)
                        statusIsError = false
                    }
                    is FitImportResult.Failed -> {
                        status = l(
                            "Downloaded the file, but the import failed: %s",
                            result.message,
                        )
                        statusIsError = true
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                importingId = null
            }
        }
    }

    fun importWellness(days: Int) {
        if (wellnessBusy) return
        showWellnessImport = false
        wellnessBusy = true
        wellnessProgress = 0 to days
        status = l("Importing daily data from Garmin Connect…")
        statusIsError = false
        val today = LocalDate.now()
        val dates = (0 until days).map { offset -> today.minusDays(offset.toLong()).toString() }
        wellnessJob = scope.launch {
            try {
                val summaries = client.dailySummaries(dates) { done, total ->
                    wellnessProgress = done to total
                }
                val updated = onImportWellness(summaries)
                status = if (updated > 0) {
                    l("Recovered %s day(s) from Garmin Connect.", updated)
                } else {
                    l("Garmin Connect returned no daily data for this range.")
                }
                statusIsError = false
            } catch (cancelled: CancellationException) {
                status = l("Import cancelled.")
                statusIsError = false
                throw cancelled
            } catch (error: Throwable) {
                fail(error)
            } finally {
                wellnessBusy = false
                wellnessProgress = null
                wellnessJob = null
            }
        }
    }

    fun startImportAll() {
        if (bulkRunning) return
        confirmBulkImport = false
        bulkRunning = true
        status = l("Checking the Garmin activity count…")
        statusIsError = false
        var imported = 0
        var skipped = 0
        var failed = 0
        bulkJob = scope.launch {
            try {
                val total = client.activityCount()
                if (total <= 0) {
                    status = l("Garmin returned no activities to import.")
                    statusIsError = false
                    return@launch
                }
                val pageSize = 50
                var start = 0
                while (start < total) {
                    currentCoroutineContext().ensureActive()
                    val page = client.activities(start = start, limit = pageSize)
                    if (page.isEmpty()) break
                    for ((index, activity) in page.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val ordinal = (start + index + 1).coerceAtMost(total)
                        status = l("Importing %s of %s: %s", ordinal, total, activity.displayTitle)
                        val uid = garminActivityUid(activity.id)
                        if (activity.id in importedIds || isActivityKnown(activity.id)) {
                            skipped++
                            importedIds = importedIds + activity.id
                            continue
                        }
                        try {
                            val bytes = client.downloadOriginalFit(activity.id)
                            when (onImportFit(
                                "garmin-${activity.id}.fit",
                                bytes,
                                uid,
                            )) {
                                is FitImportResult.Imported -> {
                                    imported++
                                    importedIds = importedIds + activity.id
                                }
                                FitImportResult.AlreadyImported -> {
                                    skipped++
                                    importedIds = importedIds + activity.id
                                }
                                is FitImportResult.Failed -> failed++
                            }
                        } catch (error: GarminException) {
                            // A malformed file is specific to one activity; keep
                            // going. Auth, network, server and rate limits stop
                            // the whole batch.
                            if (error.kind != GarminException.Kind.Malformed) throw error
                            failed++
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            failed++
                        }
                    }
                    start += page.size
                }
                status = buildString {
                    append(l("Import finished: %s imported", imported))
                    if (skipped > 0) append(l(", %s already present", skipped))
                    if (failed > 0) append(l(", %s failed", failed))
                    append(".")
                }
                statusIsError = failed > 0
            } catch (cancelled: CancellationException) {
                status = l("Import stopped. %s imported so far.", imported)
                statusIsError = false
                throw cancelled
            } catch (error: Throwable) {
                fail(error)
            } finally {
                bulkRunning = false
                bulkJob = null
            }
        }
    }

    PulseCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.md),
        ) {
            Text(
                "Some watches, including the Forerunner 245, only offer activities " +
                    "that are still waiting to upload. A run that Garmin Connect " +
                    "already archived can be downloaded here as the original FIT.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextMuted,
            )

            if (!signedIn) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.take(320)
                        mfaMethod = null
                    },
                    label = { Text("Garmin email or username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(200) },
                    label = { Text("Garmin password") },
                    supportingText = {
                        Text("Used once for sign-in. The password is not saved.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mfaMethod != null) {
                    OutlinedTextField(
                        value = mfaCode,
                        onValueChange = { mfaCode = it.filter(Char::isLetterOrDigit).take(12) },
                        label = { Text("Garmin verification code") },
                        supportingText = {
                            Text(tr("Sent via %s. It expires quickly.", tr(mfaLabel(mfaMethod.orEmpty()))))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        if (busy) "Verifying…" else "Verify code",
                        onClick = ::verifyMfa,
                        enabled = !busy && mfaCode.isNotBlank(),
                    )
                    PrimaryButton(
                        "Start over",
                        onClick = {
                            mfaMethod = null
                            mfaCode = ""
                            status = "Enter the Garmin password again to request a new code."
                            statusIsError = false
                        },
                        enabled = !busy,
                    )
                } else {
                    PrimaryButton(
                        if (busy) "Signing in…" else "Sign in",
                        onClick = ::signIn,
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    )
                }
            } else {
                Text(
                    tr(
                        "Signed in as %s",
                        store.username().ifBlank { username.ifBlank { tr("Garmin account") } },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        if (busy) "Loading…" else "Load recent activities",
                        onClick = {
                            if (!busy && !bulkRunning) {
                                busy = true
                                status = null
                                statusIsError = false
                                scope.launch {
                                    try {
                                        loadActivities()
                                        status = if (activities.isEmpty()) {
                                            "Garmin returned no activities."
                                        } else {
                                            "Choose an activity to import."
                                        }
                                        statusIsError = false
                                    } catch (error: Throwable) {
                                        fail(error)
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy && !bulkRunning,
                    )
                    PrimaryButton(
                        "Sign out",
                        onClick = {
                            wellnessJob?.cancel()
                            wellnessJob = null
                            wellnessBusy = false
                            store.signOut()
                            signedIn = false
                            activities = emptyList()
                            activitiesLoaded = false
                            importedIds = emptySet()
                            mfaMethod = null
                            status = "Signed out of Garmin Connect."
                            statusIsError = false
                        },
                        enabled = !busy && !bulkRunning && importingId == null,
                    )
                }
                PrimaryButton(
                    if (wellnessBusy) {
                        l("Importing daily data…")
                    } else {
                        l("Recover daily data")
                    },
                    onClick = { showWellnessImport = true },
                    enabled = !busy && !bulkRunning &&
                        importingId == null && !wellnessBusy,
                )
                Text(
                    "Recover steps, distance, calories, active minutes, resting heart rate, stress and Body Battery for recent days. Watch data already stored locally is never lowered.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                if (wellnessBusy) {
                    val progress = wellnessProgress
                    val total = progress?.second ?: wellnessDays
                    val done = progress?.first ?: 0
                    Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        Text(
                            tr("Importing %s of %s days…", done, total),
                            style = MaterialTheme.typography.labelMedium,
                            color = PulseColors.Accent,
                        )
                        LinearProgressIndicator(
                            progress = if (total > 0) done.toFloat() / total else 0f,
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = PulseColors.Accent,
                            trackColor = PulseColors.Border,
                        )
                        TextButton(onClick = {
                            wellnessJob?.cancel()
                            wellnessJob = null
                        }) {
                            Text(tr("Cancel import"), color = PulseColors.Coral)
                        }
                    }
                }
                PrimaryButton(
                    if (bulkRunning) "Stop import" else "Import all activities",
                    onClick = {
                        when {
                            bulkRunning -> {
                                status = "Stopping after the current download…"
                                statusIsError = false
                                bulkJob?.cancel()
                            }
                            !busy && importingId == null -> confirmBulkImport = true
                        }
                    },
                    enabled = bulkRunning || (!busy && importingId == null),
                )
                Text(
                    "Bulk import downloads every activity in your Garmin account, " +
                        "newest first. Existing activities are skipped.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )

                if (activities.isNotEmpty()) {
                    activities.forEach { activity ->
                        GarminActivityRow(
                            activity = activity,
                            busy = importingId != null || bulkRunning,
                            importing = importingId == activity.id,
                            imported = activity.id in importedIds ||
                                isActivityKnown(activity.id),
                            onImport = { importActivity(activity) },
                        )
                    }
                } else if (activitiesLoaded) {
                    Text(
                        "No activities found. Record a run, sync it with Garmin Connect, then load the list again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (statusIsError) PulseColors.Coral else PulseColors.Teal,
                )
            }

            Text(
                "Uses Garmin's unofficial mobile API, not the partner developer API. " +
                    "Credentials go only to Garmin over HTTPS; only OAuth tokens are kept " +
                    "on this device, encrypted with the Android Keystore. Repeated sign-ins " +
                    "can be rate-limited by Garmin.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
    }

    if (showWellnessImport) {
        AlertDialog(
            onDismissRequest = { showWellnessImport = false },
            title = { Text("Recover daily data?") },
            text = {
                val end = LocalDate.now()
                val start = end.minusDays((wellnessDays - 1).coerceAtLeast(0).toLong())
                val pattern = if (start.year == end.year) "d MMM" else "d MMM yyyy"
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                Column(verticalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                    Text(
                        "Choose how many recent days to read from Garmin Connect. " +
                            "Values already imported from the watch are kept when they are higher.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                        listOf(7, 30, 90).forEach { option ->
                            PillChip(
                                label = tr("%s days", option),
                                selected = wellnessDays == option,
                                onClick = { wellnessDays = option },
                            )
                        }
                    }
                    Text(
                        tr("Days to recover: %s", wellnessDays),
                        style = MaterialTheme.typography.labelLarge,
                        color = PulseColors.Text,
                    )
                    Slider(
                        value = wellnessDays.toFloat(),
                        onValueChange = { wellnessDays = it.roundToInt().coerceIn(1, 365) },
                        valueRange = 1f..365f,
                    )
                    Text(
                        tr(
                            "From %s to %s",
                            start.format(formatter),
                            end.format(formatter),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                    Text(
                        "Drag to choose how far back to read, up to one year.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importWellness(wellnessDays) }) {
                    Text(tr("Import %s days", wellnessDays), color = PulseColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWellnessImport = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmBulkImport) {
        AlertDialog(
            onDismissRequest = { confirmBulkImport = false },
            title = { Text("Import all Garmin activities?") },
            text = {
                Text(
                    "Pulse downloads every original FIT from Garmin Connect, newest first, " +
                        "with a randomized wait between files to avoid rate limits. A large " +
                        "history can take a long time. Activities already in Pulse are skipped. " +
                        "If automatic analysis is on, recent activities may be queued for analysis.",
                )
            },
            confirmButton = {
                TextButton(onClick = ::startImportAll) { Text("Start import") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkImport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GarminActivityRow(
    activity: GarminActivity,
    busy: Boolean,
    importing: Boolean,
    imported: Boolean,
    onImport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                activity.displayTitle,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Text,
                translate = false,
            )
            Text(
                listOf(activity.displayDate, activity.displayDistance, activity.displayDuration)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
        }
        PrimaryButton(
            when {
                importing -> "Importing…"
                imported -> "Imported"
                else -> "Import"
            },
            onClick = onImport,
            enabled = !busy && !imported,
        )
    }
}

private fun mfaLabel(method: String): String = when {
    method.contains("sms", ignoreCase = true) -> "SMS"
    method.contains("email", ignoreCase = true) -> "email"
    method.isBlank() -> "email"
    else -> method
}
