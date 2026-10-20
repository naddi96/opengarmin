package dev.opengarmin.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.opengarmin.app.coach.ChatStore
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RepositoryHealthDataSource
import dev.opengarmin.app.coach.RepositoryWorkoutWriter
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.WorkoutDetail
import dev.opengarmin.app.coach.tools.ToolRegistry
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityExport
import dev.opengarmin.app.data.ActivityExportException
import dev.opengarmin.app.data.ActivityExportFormat
import dev.opengarmin.app.data.ActivityInsights
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
import dev.opengarmin.app.data.BackupManager
import dev.opengarmin.app.data.FitMessageEntity
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.PacePlanEntity
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivityImporter
import dev.opengarmin.app.sync.SyncScheduler
import dev.opengarmin.app.sync.SyncSessionController
import dev.opengarmin.app.sync.SyncUiState
import dev.opengarmin.app.ui.PulseApp
import dev.opengarmin.app.ui.syncStatusColor
import dev.opengarmin.app.ui.syncStatusLabel
import dev.opengarmin.app.ui.theme.PulseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private data class ActivityDetailLoad(
    val records: List<RecordEntity>,
    val laps: List<LapEntity>,
    val workout: WorkoutDetail?,
    val insights: ActivityInsights?,
)

class MainActivity : ComponentActivity() {

    private lateinit var repository: ActivityRepository
    private lateinit var settings: PulseSettings
    private lateinit var providerStore: ProviderStore
    private lateinit var coachController: CoachController
    private lateinit var chatStore: ChatStore
    private lateinit var healthDataSource: RepositoryHealthDataSource
    private lateinit var uiState: SyncUiState
    private lateinit var importer: ActivityImporter
    private lateinit var syncController: SyncSessionController
    private val dataJobs = mutableListOf<Job>()

    private val activities = mutableStateListOf<ActivityEntity>()
    private val wellnessDays = mutableStateListOf<WellnessDayEntity>()
    private val plannedWorkouts = mutableStateListOf<PlannedWorkoutEntity>()
    private val pacePlans = mutableStateListOf<PacePlanEntity>()
    private var selectedActivity by mutableStateOf<ActivityEntity?>(null)
    private var selectedRecords by mutableStateOf<List<RecordEntity>>(emptyList())
    private var selectedLaps by mutableStateOf<List<LapEntity>>(emptyList())
    private var selectedInsights by mutableStateOf<ActivityInsights?>(null)
    private var selectedDynamics by mutableStateOf<RunningDynamicsSummary?>(null)
    private var selectedGps by mutableStateOf<GpsSummary?>(null)
    private var latestHrSamples by mutableStateOf<List<WellnessSampleEntity>>(emptyList())
    private var latestSleepStages by mutableStateOf<List<dev.opengarmin.app.data.SleepStageEntity>>(emptyList())
    private var trainingSummary by mutableStateOf(dev.opengarmin.app.data.TrainingSummary())
    private var dataActivity by mutableStateOf<ActivityEntity?>(null)
    private var dataMessages by mutableStateOf<List<FitMessageEntity>>(emptyList())
    private var dataLoading by mutableStateOf(false)
    private var backupStatus by mutableStateOf<String?>(null)
    private var backupBusy by mutableStateOf(false)
    private var exportStatus by mutableStateOf<String?>(null)
    private var exportBusy by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) syncController.startScan() else log("permissions denied")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) log("notification permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        repository = ActivityRepository { AppDatabase.get(this) }
        settings = PulseSettings(this)
        runCatching { SyncScheduler.apply(this, settings) }
        providerStore = ProviderStore(this)
        chatStore = ChatStore(this)
        healthDataSource = RepositoryHealthDataSource(repository)
        coachController = CoachController(
            scope = lifecycleScope,
            providerStore = providerStore,
            tools = ToolRegistry(healthDataSource, RepositoryWorkoutWriter(repository)),
            chatStore = chatStore,
            settings = settings,
        )
        uiState = SyncUiState()
        importer = ActivityImporter(
            context = this,
            repository = repository,
            scope = lifecycleScope,
            ui = uiState,
        )
        syncController = SyncSessionController(
            context = this,
            scope = lifecycleScope,
            settings = settings,
            repository = repository,
            ui = uiState,
            importer = importer,
            isActivityKnown = { uid -> uid.isNotEmpty() && activities.any { it.itemUid == uid } },
            requestNotificationPermission = { ensureNotificationPermission() },
        )

        observeDatabase()
        lifecycleScope.launch(Dispatchers.IO) {
            var known = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(emptySet())
            importer.setKnownWellnessUids(known)
            runCatching { repository.backfillHrvFromSamples() }
            runCatching { repository.backfillWellnessFiles(filesDir) }
            known = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(known)
            importer.setKnownWellnessUids(known)
            runCatching { repository.backfillDerived(filesDir) }
        }

        setContent {
            PulseTheme {
                val progress = syncController.activityProgress
                val syncLabel = syncStatusLabel(syncController.phase, progress, settings)
                val syncColor = syncStatusColor(syncController.phase, progress, settings)
                PulseApp(
                    activities = activities,
                    wellnessDays = wellnessDays,
                    hrSamples = latestHrSamples,
                    sleepStages = latestSleepStages,
                    trainingSummary = trainingSummary,
                    plannedWorkouts = plannedWorkouts,
                    workoutSendStatus = syncController.workoutSendStatus,
                    pacePlans = pacePlans,
                    pacePlanSendStatus = syncController.pacePlanSendStatus,
                    selectedActivity = selectedActivity,
                    selectedRecords = selectedRecords,
                    selectedLaps = selectedLaps,
                    selectedInsights = selectedInsights,
                    selectedDynamics = selectedDynamics,
                    selectedGps = selectedGps,
                    selectedFitExportAvailable = selectedActivity?.let {
                        File(filesDir, it.fileName).isFile
                    } == true,
                    exportStatus = exportStatus,
                    exportBusy = exportBusy,
                    dataActivity = dataActivity,
                    dataMessages = dataMessages,
                    dataLoading = dataLoading,
                    settings = settings,
                    providerStore = providerStore,
                    coachController = coachController,
                    syncLabel = syncLabel,
                    syncColor = syncColor,
                    activitySyncProgress = progress,
                    lastImportedLabel = syncController.lastImportedLabel,
                    logs = syncController.logs,
                    deviceInfo = { syncController.deviceInfo() },
                    onSync = { syncController.syncNow(trigger = "manual") },
                    onScan = { startScan() },
                    onStop = { syncController.stopScan() },
                    onDisconnect = { syncController.disconnectWatch() },
                    onSelectActivity = { selectActivity(it) },
                    onClearSelection = {
                        selectedActivity = null
                        selectedRecords = emptyList()
                        selectedLaps = emptyList()
                        selectedInsights = null
                        selectedDynamics = null
                        selectedGps = null
                        exportStatus = null
                    },
                    onExploreData = { openDataExplorer(it) },
                    onCloseData = { closeDataExplorer() },
                    onExportActivity = { activity, format, uri -> exportActivity(activity, format, uri) },
                    onImportFit = { uri -> importer.importFitFromUri(uri) },
                    onSaveWorkout = { workout -> saveWorkout(workout) },
                    onDeleteWorkout = { id -> deleteWorkout(id) },
                    onSendWorkout = { workout -> syncController.sendWorkout(workout) },
                    onSendPacePlan = { plan -> syncController.sendPacePlan(plan) },
                    onDeletePacePlan = { id -> deletePacePlan(id) },
                    loadPaceCourse = { id ->
                        withContext(Dispatchers.IO) { repository.paceCourse(id) }
                    },
                    backupStatus = backupStatus,
                    backupBusy = backupBusy,
                    onExportBackup = { uri -> exportBackup(uri) },
                    onImportBackup = { uri -> importBackup(uri) },
                    onBackgroundSyncChanged = {
                        runCatching {
                            SyncScheduler.apply(this, settings, replaceSchedule = true)
                        }
                    },
                    onRunBackgroundSyncNow = {
                        // Drop the visible-app BLE lease so the worker can own
                        // the watch, then run the exact same background path.
                        // A short pause lets the watch close the old GATT
                        // session before the worker reconnects.
                        lifecycleScope.launch {
                            syncController.disconnectWatch()
                            delay(1_500)
                            runCatching { SyncScheduler.runOnce(this@MainActivity) }
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncController.onStart()
    }

    override fun onStop() {
        syncController.onStop()
        super.onStop()
    }

    private fun observeDatabase() {
        dataJobs.forEach { it.cancel() }
        dataJobs.clear()
        dataJobs += lifecycleScope.launch {
            repository.activities.collect { list ->
                activities.clear()
                activities.addAll(list)
                val rows = withContext(Dispatchers.IO) {
                    runCatching { repository.trainingRows() }.getOrDefault(emptyList())
                }
                trainingSummary = dev.opengarmin.app.data.TrainingMetrics.build(list, rows)
            }
        }
        dataJobs += lifecycleScope.launch {
            repository.plannedWorkouts.collect { list ->
                plannedWorkouts.clear()
                plannedWorkouts.addAll(list)
            }
        }
        dataJobs += lifecycleScope.launch {
            repository.pacePlans.collect { list ->
                pacePlans.clear()
                pacePlans.addAll(list)
            }
        }
        dataJobs += lifecycleScope.launch {
            repository.wellnessDays.collect { list ->
                wellnessDays.clear()
                wellnessDays.addAll(list)
                settings.updateImportedRestingHeartRate(
                    dev.opengarmin.app.data.WellnessMetrics.latestRestingHeartRate(list),
                )
                val latest = dev.opengarmin.app.data.WellnessMetrics.latestDay(list)
                if (latest != null) {
                    val (samples, stages) = withContext(Dispatchers.IO) {
                        repository.wellnessSamples(latest.id) to repository.sleepStages(latest.date)
                    }
                    latestHrSamples = samples
                    latestSleepStages = stages
                } else {
                    latestHrSamples = emptyList()
                    latestSleepStages = emptyList()
                }
            }
        }
    }

    private fun exportActivity(
        activity: ActivityEntity,
        format: ActivityExportFormat,
        uri: Uri,
    ) {
        if (exportBusy) return
        exportBusy = true
        exportStatus = "Exporting ${format.name}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val records = repository.records(activity.id)
                    val laps = repository.laps(activity.id)
                    val payload = when (format) {
                        ActivityExportFormat.FIT -> ActivityExport.readFitFile(File(filesDir, activity.fileName))
                        ActivityExportFormat.GPX -> ActivityExport.gpx(activity, records)
                            .toByteArray(Charsets.UTF_8)
                        ActivityExportFormat.TCX -> ActivityExport.tcx(activity, records, laps)
                            .toByteArray(Charsets.UTF_8)
                        ActivityExportFormat.CSV -> ActivityExport.csv(activity, records, laps)
                            .toByteArray(Charsets.UTF_8)
                    }
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(payload)
                    } ?: throw ActivityExportException("Could not open the selected location for writing.")
                    ActivityExport.fileName(activity, format)
                }
            }
            exportBusy = false
            exportStatus = result.fold(
                onSuccess = { name -> "Exported $name. Nothing was uploaded." },
                onFailure = { error ->
                    // The picker has already created the destination document;
                    // remove the empty/partial file so a failed export does not
                    // leave a misleading artifact behind.
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                        .recoverCatching { contentResolver.delete(uri, null, null) }
                    "Could not export: ${error.message ?: "unknown error"}"
                },
            )
        }
    }

    private fun exportBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "Creating backup…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.export(this@MainActivity, uri, chatStore) }
            }
            backupBusy = false
            backupStatus = result.fold(
                onSuccess = { "Backup saved. It contains the database and coach conversations." },
                onFailure = { it.message ?: "Could not create the backup." },
            )
            log("BACKUP export: ${result.fold({ "ok" }, { it.message ?: "failed" })}")
        }
    }

    private fun importBackup(uri: Uri) {
        if (backupBusy) return
        if (syncController.isSyncingOrImporting()) {
            backupStatus = "Wait for the current sync to finish before restoring."
            return
        }
        backupBusy = true
        backupStatus = "Restoring backup…"
        dataJobs.forEach { it.cancel() }
        dataJobs.clear()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.import(this@MainActivity, uri, chatStore) }
            }
            backupBusy = false
            result.onSuccess { imported ->
                selectedActivity = null
                selectedRecords = emptyList()
                selectedLaps = emptyList()
                selectedInsights = null
                selectedDynamics = null
                selectedGps = null
                closeDataExplorer()
                latestHrSamples = emptyList()
                latestSleepStages = emptyList()
                importer.clearImportedLabel()
                activities.clear()
                wellnessDays.clear()
                withContext(Dispatchers.IO) { importer.reloadKnownWellnessUids() }
                observeDatabase()
                coachController.reloadAfterRestore()
                backupStatus = if (imported.chatRestored) {
                    "Backup restored. Activities, wellness data and conversations were replaced."
                } else {
                    "Database restored. Conversations were left unchanged (the backup has none)."
                }
                log("BACKUP restore: ok")
            }.onFailure { error ->
                observeDatabase()
                backupStatus = error.message ?: "Could not restore the backup."
                log("BACKUP restore failed: ${error.message}")
            }
        }
    }

    private fun selectActivity(activity: ActivityEntity) {
        selectedActivity = activity
        selectedRecords = emptyList()
        selectedLaps = emptyList()
        selectedInsights = null
        selectedDynamics = null
        selectedGps = null
        exportStatus = null
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                ActivityDetailLoad(
                    records = repository.records(activity.id),
                    laps = repository.laps(activity.id),
                    workout = healthDataSource.workout(ToolScope(), activity.id),
                    insights = repository.activityInsights(activity.id),
                )
            }
            if (selectedActivity?.id == activity.id) {
                selectedRecords = loaded.records
                selectedLaps = loaded.laps
                selectedInsights = loaded.insights
                selectedDynamics = loaded.workout?.dynamics
                selectedGps = loaded.workout?.gps
            }
        }
    }

    private fun openDataExplorer(activity: ActivityEntity) {
        dataActivity = activity
        dataLoading = true
        dataMessages = emptyList()
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) { repository.allMessages(activity.id) }
            if (dataActivity?.id == activity.id) {
                dataMessages = messages
                dataLoading = false
            }
        }
    }

    private fun closeDataExplorer() {
        dataActivity = null
        dataMessages = emptyList()
        dataLoading = false
    }

    private fun saveWorkout(workout: PlannedWorkoutEntity) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.saveWorkout(workout) }
            }
            result.onSuccess { saved ->
                log("PLAN saved \"${saved.name}\" (${saved.steps.size} steps)")
            }.onFailure {
                log("PLAN save failed: ${it.message}")
            }
        }
    }

    private fun deleteWorkout(id: Long) {
        if (id == 0L) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.deleteWorkout(id) } }
            log("PLAN deleted workout id=$id")
        }
    }

    private fun deletePacePlan(id: Long) {
        if (id == 0L) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.deletePacePlan(id) } }
            log("PACE deleted plan id=$id")
        }
    }

    private fun startScan() {
        if (!syncController.startScan()) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun log(message: String) {
        uiState.log(message)
    }

    override fun onDestroy() {
        if (::syncController.isInitialized) syncController.destroy()
        super.onDestroy()
    }
}


internal fun formatWellnessDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}.getOrDefault(date)

internal fun sportName(sport: Int): String = when (sport) {
    0 -> "Generic"
    1 -> "Running"
    2 -> "Cycling"
    3 -> "Transition"
    4 -> "Fitness equipment"
    5 -> "Swimming"
    6 -> "Basketball"
    7 -> "Soccer"
    8 -> "Tennis"
    10 -> "Training"
    11 -> "Walking"
    12 -> "Cross-country skiing"
    13 -> "Alpine skiing"
    14 -> "Snowboarding"
    15 -> "Rowing"
    16 -> "Mountaineering"
    17 -> "Hiking"
    18 -> "Multisport"
    19 -> "Paddling"
    21 -> "E-biking"
    25 -> "Golf"
    30 -> "Inline skating"
    31 -> "Rock climbing"
    32 -> "Sailing"
    35 -> "Snowshoeing"
    37 -> "Stand-up paddleboarding"
    38 -> "Surfing"
    41 -> "Kayaking"
    43 -> "Windsurfing"
    44 -> "Kitesurfing"
    53 -> "Diving"
    62 -> "HIIT"
    71 -> "Disc golf"
    79 -> "Mixed martial arts"
    else -> "Sport $sport"
}

internal fun formatDate(unixSeconds: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

internal fun formatDistance(meters: Double): String = "%.2f km".format(meters / 1000.0)


internal fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

internal fun formatPace(durationSeconds: Double, distanceMeters: Double): String {
    if (distanceMeters <= 0.0) return "—"
    val secondsPerKm = durationSeconds / (distanceMeters / 1000.0)
    val total = secondsPerKm.toLong()
    return "%d:%02d /km".format(total / 60, total % 60)
}
