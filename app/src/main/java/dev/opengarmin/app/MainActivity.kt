package dev.opengarmin.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.opengarmin.app.analysis.ActivityAnalysisNotifications
import dev.opengarmin.app.analysis.ActivityAnalysisScheduler
import dev.opengarmin.app.coach.ChatStore
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RepositoryHealthDataSource
import dev.opengarmin.app.coach.RepositoryWorkoutWriter
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.WorkoutDetail
import dev.opengarmin.app.coach.tools.ToolRegistry
import dev.opengarmin.app.data.ActivityAnalysisEntity
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
import dev.opengarmin.app.diagnostics.AppEvent
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.diagnostics.SyncLogExport
import dev.opengarmin.app.garmin.garminActivityUid
import dev.opengarmin.app.i18n.LocalAppLanguage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivityImporter
import dev.opengarmin.app.sync.FitImportResult
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
    private lateinit var analysisScheduler: ActivityAnalysisScheduler
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
    private var sleepStageRows by mutableStateOf<List<dev.opengarmin.app.data.SleepStageEntity>>(emptyList())
    private var trainingSummary by mutableStateOf(dev.opengarmin.app.data.TrainingSummary())
    private var dataActivity by mutableStateOf<ActivityEntity?>(null)
    private var dataMessages by mutableStateOf<List<FitMessageEntity>>(emptyList())
    private var dataLoading by mutableStateOf(false)
    private var backupStatus by mutableStateOf<String?>(null)
    private var backupBusy by mutableStateOf(false)
    private var exportStatus by mutableStateOf<String?>(null)
    private var exportBusy by mutableStateOf(false)
    private var analyses by mutableStateOf<Map<Long, ActivityAnalysisEntity>>(emptyMap())
    private var pendingOpenActivityId by mutableStateOf<Long?>(null)
    private val diagnosticEvents = mutableStateListOf<AppEvent>()
    private var diagnosticsLoading by mutableStateOf(false)
    private var diagnosticsStatus by mutableStateOf<String?>(null)
    private var syncLogExportStatus by mutableStateOf<String?>(null)

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
        pendingOpenActivityId = intent
            .getLongExtra(ActivityAnalysisNotifications.EXTRA_ACTIVITY_ID, -1L)
            .takeIf { it > 0L }

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
        analysisScheduler = ActivityAnalysisScheduler(this, repository, providerStore, settings)
        importer = ActivityImporter(
            context = this,
            repository = repository,
            scope = lifecycleScope,
            ui = uiState,
        )
        importer.onActivityImported = { activity ->
            lifecycleScope.launch {
                runCatching { analysisScheduler.enqueue(activity.id) }
                    .onFailure {
                        Diagnostics.record(
                            area = Diagnostics.AREA_ANALYSIS,
                            code = "QUEUE_FAILED",
                            message = it.message ?: "Could not queue the activity analysis.",
                            throwable = it,
                            context = mapOf("activityId" to activity.id.toString()),
                        )
                        log("ANALYSIS queue failed: ${it.message}")
                    }
            }
        }
        syncController = SyncSessionController(
            context = this,
            scope = lifecycleScope,
            settings = settings,
            repository = repository,
            ui = uiState,
            importer = importer,
            isActivityKnown = { uid ->
                importer.isKnownActivityUid(uid) || activities.any { it.itemUid == uid }
            },
            requestNotificationPermission = { ensureNotificationPermission() },
        )

        observeDatabase()
        lifecycleScope.launch(Dispatchers.IO) {
            var known = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(emptySet())
            importer.setKnownWellnessUids(known)
            runCatching { importer.reloadKnownActivityUids() }
            runCatching { repository.backfillHrvFromSamples() }
            runCatching { repository.backfillWellnessFiles(filesDir) }
            known = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(known)
            importer.setKnownWellnessUids(known)
            runCatching { repository.backfillDerived(filesDir) }
            // A process death can leave an analysis queued/reading even though
            // WorkManager no longer has the job. Re-queue it, or surface an
            // actionable error when automatic analysis is no longer configured.
            runCatching { repository.unfinishedAnalyses() }.getOrDefault(emptyList()).forEach { row ->
                if (providerStore.load().isConfigured) {
                    runCatching { analysisScheduler.enqueue(row.activityId, force = true) }
                } else {
                    runCatching {
                        repository.markAnalysisError(
                            row.activityId,
                            "Analysis was interrupted. Tap Generate to try again.",
                        )
                    }
                }
            }
        }

        setContent {
            CompositionLocalProvider(LocalAppLanguage provides settings.appLanguage) {
                PulseTheme {
                    val progress = syncController.activityProgress
                    val syncLabel = syncStatusLabel(syncController.phase, progress, settings)
                    val syncColor = syncStatusColor(syncController.phase, progress, settings)
                    PulseApp(
                        activities = activities,
                        wellnessDays = wellnessDays,
                        hrSamples = latestHrSamples,
                        sleepStages = sleepStageRows,
                        loadWellnessSamples = { date -> repository.wellnessSamplesForDate(date) },
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
                        selectedAnalysis = selectedActivity?.let { analyses[it.id] },
                        openActivityId = pendingOpenActivityId,
                        onOpenActivityHandled = { pendingOpenActivityId = null },
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
                        syncLogExportStatus = syncLogExportStatus,
                        onExportSyncLog = { uri -> exportSyncLog(uri) },
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
                        onGenerateAnalysis = { activity ->
                            ensureNotificationPermission()
                            lifecycleScope.launch {
                                val queued = runCatching {
                                    analysisScheduler.enqueue(activity.id, force = true)
                                }.getOrDefault(false)
                                if (!queued) log("ANALYSIS not queued: configure an AI provider first")
                            }
                        },
                        onImportFit = { uri -> importer.importFitFromUri(uri) },
                        onImportGarminFit = { name, bytes, itemUid ->
                            if (syncController.isSyncingOrImporting()) {
                                FitImportResult.Failed(
                                    settings.localized("Wait for the current watch sync to finish and try again."),
                                )
                            } else {
                                importer.importFitBytes(name, bytes, itemUid)
                            }
                        },
                        isGarminActivityKnown = { id ->
                            importer.isKnownActivityUid(garminActivityUid(id))
                        },
                        onAuditWatchFiles = { syncController.auditWatchDirectory() },
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
                        diagnosticEvents = diagnosticEvents,
                        diagnosticsLoading = diagnosticsLoading,
                        diagnosticsStatus = diagnosticsStatus,
                        onLoadDiagnostics = { loadDiagnostics() },
                        onClearDiagnostics = { clearDiagnostics() },
                        onExportDiagnostics = { uri -> exportDiagnostics(uri) },
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenActivityId = intent
            .getLongExtra(ActivityAnalysisNotifications.EXTRA_ACTIVITY_ID, -1L)
            .takeIf { it > 0L }
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
            repository.activityAnalyses.collect { list ->
                analyses = list.associateBy { it.activityId }
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
                        repository.wellnessSamples(latest.id) to repository.allSleepStages()
                    }
                    latestHrSamples = samples
                    sleepStageRows = stages
                } else {
                    latestHrSamples = emptyList()
                    sleepStageRows = emptyList()
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
        exportStatus = settings.localized("Exporting %s…", format.name)
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
                onSuccess = { name -> settings.localized("Exported %s. Nothing was uploaded.", name) },
                onFailure = { error ->
                    Diagnostics.record(
                        area = Diagnostics.AREA_EXPORT,
                        code = "ACTIVITY_EXPORT_FAILED",
                        message = error.message ?: "Activity export failed.",
                        throwable = error,
                        context = mapOf(
                            "activityId" to activity.id.toString(),
                            "format" to format.name,
                        ),
                    )
                    // The picker has already created the destination document;
                    // remove the empty/partial file so a failed export does not
                    // leave a misleading artifact behind.
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                        .recoverCatching { contentResolver.delete(uri, null, null) }
                    settings.localized("Could not export: %s", error.message ?: "unknown error")
                },
            )
        }
    }

    private fun exportBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = settings.localized("Creating backup…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.export(this@MainActivity, uri, chatStore) }
            }
            backupBusy = false
            backupStatus = result.fold(
                onSuccess = { settings.localized("Backup saved. It contains the database and coach conversations.") },
                onFailure = { it.message ?: settings.localized("Could not create the backup.") },
            )
            result.onFailure { error ->
                Diagnostics.record(
                    area = Diagnostics.AREA_BACKUP,
                    code = "BACKUP_EXPORT_FAILED",
                    message = error.message ?: "Could not create the backup.",
                    throwable = error,
                )
            }
            log("BACKUP export: ${result.fold({ "ok" }, { it.message ?: "failed" })}")
        }
    }

    private fun importBackup(uri: Uri) {
        if (backupBusy) return
        if (syncController.isSyncingOrImporting()) {
            backupStatus = settings.localized("Wait for the current sync to finish before restoring.")
            return
        }
        backupBusy = true
        backupStatus = settings.localized("Restoring backup…")
        dataJobs.forEach { it.cancel() }
        dataJobs.clear()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.import(this@MainActivity, uri, chatStore) }
            }
            backupBusy = false
            result.onSuccess { imported ->
                // In-flight analyses belong to the replaced database.
                runCatching { analysisScheduler.cancelAll() }
                selectedActivity = null
                selectedRecords = emptyList()
                selectedLaps = emptyList()
                selectedInsights = null
                selectedDynamics = null
                selectedGps = null
                closeDataExplorer()
                latestHrSamples = emptyList()
                sleepStageRows = emptyList()
                importer.clearImportedLabel()
                activities.clear()
                wellnessDays.clear()
                withContext(Dispatchers.IO) {
                    importer.reloadKnownWellnessUids()
                    importer.reloadKnownActivityUids()
                }
                observeDatabase()
                coachController.reloadAfterRestore()
                backupStatus = if (imported.chatRestored) {
                    settings.localized("Backup restored. Activities, wellness data and conversations were replaced.")
                } else {
                    settings.localized("Database restored. Conversations were left unchanged (the backup has none).")
                }
                log("BACKUP restore: ok")
            }.onFailure { error ->
                observeDatabase()
                backupStatus = error.message ?: settings.localized("Could not restore the backup.")
                Diagnostics.record(
                    area = Diagnostics.AREA_BACKUP,
                    code = "BACKUP_IMPORT_FAILED",
                    message = error.message ?: "Could not restore the backup.",
                    throwable = error,
                )
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

    private fun loadDiagnostics() {
        diagnosticsLoading = true
        diagnosticsStatus = null
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { Diagnostics.events(limit = 500) }
            diagnosticEvents.clear()
            diagnosticEvents.addAll(rows)
            diagnosticsLoading = false
        }
    }

    private fun clearDiagnostics() {
        diagnosticsStatus = settings.localized("Clearing…")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Diagnostics.clear() }
            loadDiagnostics()
            diagnosticsStatus = settings.localized("Diagnostics cleared.")
        }
    }

    private fun exportDiagnostics(uri: Uri) {
        diagnosticsStatus = settings.localized("Exporting…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = Diagnostics.exportJson()
                        ?: error("The diagnostics store is unavailable.")
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected location for writing.")
                }
            }
            diagnosticsStatus = result.fold(
                onSuccess = { settings.localized("Diagnostics exported. Nothing was uploaded.") },
                onFailure = { error ->
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                        .recoverCatching { contentResolver.delete(uri, null, null) }
                    Diagnostics.record(
                        area = Diagnostics.AREA_BACKUP,
                        code = "DIAGNOSTICS_EXPORT_FAILED",
                        message = error.message ?: "Diagnostics export failed.",
                        throwable = error,
                    )
                    settings.localized("Could not export: %s", error.message ?: "unknown error")
                },
            )
        }
    }

    private fun exportSyncLog(uri: Uri) {
        syncLogExportStatus = settings.localized("Exporting…")
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val payload = SyncLogExport.text(
            deviceInfo = syncController.deviceInfo(),
            logs = syncController.logs,
            exportedAtLabel = label,
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected location for writing.")
                }
            }
            syncLogExportStatus = result.fold(
                onSuccess = { settings.localized("Sync log exported. MAC addresses were redacted.") },
                onFailure = { error ->
                    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                        .recoverCatching { contentResolver.delete(uri, null, null) }
                    Diagnostics.record(
                        area = Diagnostics.AREA_EXPORT,
                        code = "SYNC_LOG_EXPORT_FAILED",
                        message = error.message ?: "Sync log export failed.",
                        throwable = error,
                    )
                    settings.localized("Could not export: %s", error.message ?: "unknown error")
                },
            )
        }
    }

    private fun log(message: String) {
        uiState.log(message)
    }

    override fun onDestroy() {
        if (::syncController.isInitialized) syncController.destroy()
        super.onDestroy()
    }
}


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
