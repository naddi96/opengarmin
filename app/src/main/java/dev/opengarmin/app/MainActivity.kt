package dev.opengarmin.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.coach.ChartPoint
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
import dev.opengarmin.app.data.PacePlanSendStatus
import dev.opengarmin.app.data.PlannedWorkoutEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.data.WorkoutSendStatus
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.sync.SyncCoordinator
import dev.opengarmin.app.sync.SyncGate
import dev.opengarmin.app.sync.SyncNotifications
import dev.opengarmin.app.sync.SyncPolicy
import dev.opengarmin.app.sync.SyncScheduler
import dev.opengarmin.app.ui.PulseApp
import dev.opengarmin.app.ui.components.InteractiveChart
import dev.opengarmin.app.ui.components.InteractiveChartStyle
import dev.opengarmin.app.ui.components.InteractiveSeries
import dev.opengarmin.app.ui.components.PulseCard
import dev.opengarmin.app.ui.screens.formatFileSize
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseSpacing
import dev.opengarmin.app.ui.theme.PulseTheme
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.FitPaceBand
import dev.opengarmin.protocol.FitWorkout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private data class ActivityDetailLoad(
    val records: List<RecordEntity>,
    val laps: List<LapEntity>,
    val details: WorkoutDetail?,
    val insights: ActivityInsights?,
)

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null
    private var coordinator: SyncCoordinator? = null
    private var syncAttempts = 0
    private val maxSyncAttempts = 8
    private var watchdog: Runnable? = null
    private lateinit var repository: ActivityRepository
    private lateinit var settings: PulseSettings
    private lateinit var providerStore: ProviderStore
    private lateinit var coachController: CoachController
    private lateinit var chatStore: ChatStore
    private lateinit var healthDataSource: RepositoryHealthDataSource
    private val dataJobs = mutableListOf<Job>()

    private val devices = mutableStateListOf<DeviceEntry>()
    private val logs = mutableStateListOf<String>()
    private val activities = mutableStateListOf<ActivityEntity>()
    private val wellnessDays = mutableStateListOf<WellnessDayEntity>()
    private val plannedWorkouts = mutableStateListOf<PlannedWorkoutEntity>()
    private val pacePlans = mutableStateListOf<PacePlanEntity>()
    private var workoutSendStatus by mutableStateOf<WorkoutSendStatus?>(null)
    private var pacePlanSendStatus by mutableStateOf<PacePlanSendStatus?>(null)
    private var pendingWorkoutSend by mutableStateOf<PendingWorkoutSend?>(null)
    private var selectedActivity by mutableStateOf<ActivityEntity?>(null)
    private var selectedRecords by mutableStateOf<List<RecordEntity>>(emptyList())
    private var selectedLaps by mutableStateOf<List<LapEntity>>(emptyList())
    private var selectedInsights by mutableStateOf<ActivityInsights?>(null)
    private var selectedDynamics by mutableStateOf<RunningDynamicsSummary?>(null)
    private var selectedGps by mutableStateOf<GpsSummary?>(null)
    private var selectedWellnessDay by mutableStateOf<WellnessDayEntity?>(null)
    private var wellnessSamples by mutableStateOf<List<WellnessSampleEntity>>(emptyList())
    private var latestHrSamples by mutableStateOf<List<WellnessSampleEntity>>(emptyList())
    private var latestSleepStages by mutableStateOf<List<dev.opengarmin.app.data.SleepStageEntity>>(emptyList())
    private var trainingSummary by mutableStateOf(dev.opengarmin.app.data.TrainingSummary())
    private var dataActivity by mutableStateOf<ActivityEntity?>(null)
    private var dataMessages by mutableStateOf<List<FitMessageEntity>>(emptyList())
    private var dataLoading by mutableStateOf(false)
    private var state by mutableStateOf("idle")
    private var lastFile by mutableStateOf("—")
    private var importing by mutableStateOf(false)
    private var activityProgress by mutableStateOf<ActivitySyncProgress?>(null)
    private var lastImportedLabel by mutableStateOf<String?>(null)
    private var backupStatus by mutableStateOf<String?>(null)
    private var backupBusy by mutableStateOf(false)
    private var exportStatus by mutableStateOf<String?>(null)
    private var exportBusy by mutableStateOf(false)
    private var importedThisSync = 0
    private var syncNotificationActive = false
    private var knownWellnessUids: Set<String> = emptySet()
    private var syncInProgress = false
    private var syncTrigger = "manual"
    private var syncStartedAt = 0L
    private var appVisible = false
    private var disconnectWhenSyncFinishes = false
    private var importErrorThisSync: String? = null
    private var aclReceiverRegistered = false

    private val watchReconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            main.post {
                if (!appVisible || syncInProgress) return@post
                if (SyncGate.owner() == SyncGate.OWNER_BACKGROUND) return@post
                val watch = client?.findBondedWatch() ?: return@post
                if (watch.address != device.address) return@post
                log("watch reconnected; starting sync")
                syncNow(trigger = "reconnect")
            }
        }
    }

    data class DeviceEntry(val device: BluetoothDevice, val rssi: Int) {
        val label: String get() = "${device.name ?: "(unnamed)"}  ${device.address}  ${rssi} dBm"
    }

    private enum class UploadKind { WORKOUT, PACE_PLAN }

    private data class PendingWorkoutSend(
        val id: Long,
        val name: String,
        val bytes: ByteArray,
        val kind: UploadKind = UploadKind.WORKOUT,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) client?.startScan() else log("permissions denied")
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
        observeDatabase()
        lifecycleScope.launch(Dispatchers.IO) {
            knownWellnessUids = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(emptySet())
            runCatching { repository.backfillHrvFromSamples() }
            runCatching { repository.backfillWellnessFiles(filesDir) }
            knownWellnessUids = runCatching { repository.importedWellnessFileIds() }
                .getOrDefault(knownWellnessUids)
            runCatching { repository.backfillDerived(filesDir) }
        }

        val c = GarminBleClient(
            context = this,
            onLog = { log(it) },
            onDeviceFound = { device, rssi ->
                main.post {
                    if (devices.none { it.device.address == device.address }) {
                        devices.add(DeviceEntry(device, rssi))
                    }
                }
            },
            onStateChanged = { s ->
                main.post {
                    state = s
                    when (s) {
                        "connected" -> {
                            coordinator?.reset()
                            pendingWorkoutSend?.let { pending ->
                                when (pending.kind) {
                                    UploadKind.WORKOUT -> coordinator?.enqueueWorkout(
                                        pending.id,
                                        pending.name,
                                        pending.bytes,
                                    )
                                    UploadKind.PACE_PLAN -> coordinator?.enqueuePacePlan(
                                        pending.id,
                                        pending.name,
                                        pending.bytes,
                                    )
                                }
                            }
                            activityProgress = null
                            ensureNotificationPermission()
                            syncNotificationActive = true
                            SyncNotifications.start(this)
                            updateSyncNotification(
                                status = "Connected · reading activity list…",
                                progress = null,
                            )
                            client?.findBondedWatch()?.name?.let { settings.updateWatchName(it) }
                            armWatchdog()
                        }
                        "failed" -> {
                            val reason = syncFailureReason()
                            coordinator?.clearWorkouts()
                            pendingWorkoutSend?.let { pending ->
                                pendingWorkoutSend = null
                                when (pending.kind) {
                                    UploadKind.WORKOUT -> workoutSendStatus = WorkoutSendStatus(
                                        workoutId = pending.id,
                                        name = pending.name,
                                        sending = false,
                                        success = false,
                                        message = reason,
                                    )
                                    UploadKind.PACE_PLAN -> pacePlanSendStatus = PacePlanSendStatus(
                                        planId = pending.id,
                                        name = pending.name,
                                        sending = false,
                                        success = false,
                                        message = reason,
                                    )
                                }
                            }
                            finishSync(success = false, error = reason)
                        }
                        "reconnecting" -> {
                            cancelWatchdog()
                            if (activityProgress != null) {
                                updateSyncNotification(
                                    status = "Reconnecting to watch…",
                                    progress = null,
                                )
                            }
                        }
                        "disconnected" -> {
                            cancelWatchdog()
                            if (syncInProgress) {
                                finishSync(
                                    success = false,
                                    error = "Watch disconnected before the sync finished.",
                                )
                            } else {
                                activityProgress = null
                                SyncGate.release(SyncGate.OWNER_UI)
                            }
                        }
                    }
                }
            },
        )
        client = c
        runCatching {
            ContextCompat.registerReceiver(
                this,
                watchReconnectReceiver,
                IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            aclReceiverRegistered = true
        }

        c.session.onGfdiMessage = { frame ->
            main.post {
                val payload = Hex.encode(frame.payload)
                val shown = if (payload.length <= 120) payload else payload.take(120) + "…"
                log("GFDI type=${frame.type} seq=${frame.seq} len=${frame.payload.size} $shown")
            }
        }

        coordinator = SyncCoordinator(
            session = c.session,
            filesDir = filesDir,
            handler = main,
            log = { log(it) },
            isItemKnown = { uid -> uid.isNotEmpty() && activities.any { it.itemUid == uid } },
            isWellnessItemKnown = { uid -> uid.isNotEmpty() && uid in knownWellnessUids },
            onFinished = { raw, decoded, itemUid ->
                cancelWatchdog()
                lastFile = "${decoded.name} (${decoded.length()} bytes)"
                log("SYNC complete: raw=${raw.length()} decoded=${decoded.length()} (${decoded.name})")
                importFit(decoded, itemUid)
            },
            onWellnessFile = { decoded, type, itemUid ->
                log("WELLNESS file $type: ${decoded.name} (${decoded.length()} bytes)")
                importWellness(decoded, type, itemUid)
            },
            onUpToDate = {
                cancelWatchdog()
                activityProgress = null
                state = "up to date"
                log("SYNC up to date: all activity files already imported")
                finishSync(success = true)
            },
            onActivityProgress = { progress ->
                activityProgress = progress.takeIf { it.total > 0 && it.done < it.total }
                if (progress.total > 0 && progress.done < progress.total) {
                    state = "syncing"
                    updateSyncNotification(progress = activityProgress)
                }
            },
            onWorkoutSent = { id, name, bytes ->
                val kind = pendingWorkoutSend?.kind ?: UploadKind.WORKOUT
                pendingWorkoutSend = null
                when (kind) {
                    UploadKind.WORKOUT -> {
                        workoutSendStatus = WorkoutSendStatus(
                            workoutId = id,
                            name = name,
                            sending = false,
                            success = true,
                            message = "Sent to watch · ${formatFileSize(bytes.toLong())}",
                        )
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { repository.markWorkoutSent(id) }
                        }
                        log("WORKOUT sent: \"$name\" ($bytes bytes)")
                    }
                    UploadKind.PACE_PLAN -> {
                        pacePlanSendStatus = PacePlanSendStatus(
                            planId = id,
                            name = name,
                            sending = false,
                            success = true,
                            message = "Sent to watch · ${formatFileSize(bytes.toLong())}",
                        )
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { repository.markPacePlanSent(id) }
                        }
                        log("PACE sent: \"$name\" ($bytes bytes)")
                    }
                }
            },
            onWorkoutFailed = { id, name, message ->
                val kind = pendingWorkoutSend?.kind ?: UploadKind.WORKOUT
                pendingWorkoutSend = null
                when (kind) {
                    UploadKind.WORKOUT -> {
                        workoutSendStatus = WorkoutSendStatus(
                            workoutId = id,
                            name = name,
                            sending = false,
                            success = false,
                            message = message,
                        )
                        log("WORKOUT send failed: \"$name\" — $message")
                    }
                    UploadKind.PACE_PLAN -> {
                        pacePlanSendStatus = PacePlanSendStatus(
                            planId = id,
                            name = name,
                            sending = false,
                            success = false,
                            message = message,
                        )
                        log("PACE send failed: \"$name\" — $message")
                    }
                }
            },
        )

        setContent {
            PulseTheme {
                val progress = activityProgress
                val progressNumber = progress?.let { (it.done + 1).coerceAtMost(it.total) }
                val syncLabel = when {
                    progress != null && progressNumber != null -> "Syncing $progressNumber/${progress.total}"
                    state == "importing" || state == "syncing" || settings.lastSyncStatus == "syncing" -> "Syncing…"
                    state == "failed" || settings.lastSyncStatus == "failed" -> "Sync failed"
                    settings.lastSyncAt != null -> "Synced ${relativeSync(settings.lastSyncAt!!)}"
                    else -> "Not synced"
                }
                val syncColor = when {
                    progress != null -> PulseColors.Orange
                    state == "failed" || settings.lastSyncStatus == "failed" -> PulseColors.Coral
                    state == "importing" || state == "syncing" || settings.lastSyncStatus == "syncing" -> PulseColors.Orange
                    settings.lastSyncStatus == "synced" -> PulseColors.Teal
                    else -> PulseColors.TextMuted
                }
                PulseApp(
                    activities = activities,
                    wellnessDays = wellnessDays,
                    hrSamples = latestHrSamples,
                    sleepStages = latestSleepStages,
                    trainingSummary = trainingSummary,
                    plannedWorkouts = plannedWorkouts,
                    workoutSendStatus = workoutSendStatus,
                    pacePlans = pacePlans,
                    pacePlanSendStatus = pacePlanSendStatus,
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
                    lastImportedLabel = lastImportedLabel,
                    logs = logs,
                    deviceInfo = {
                        val session = c.session
                        listOf(
                            "GFDI handle" to if (session.gfdiHandle >= 0) "${session.gfdiHandle}" else "—",
                            "product" to if (session.productNumber != 0) "${session.productNumber}" else "—",
                            "firmware" to if (session.firmwareVersion != 0) "${session.firmwareVersion}" else "—",
                            "unit id" to if (session.unitId != 0L) "${session.unitId}" else "—",
                            "services mask" to "0x%06x".format(session.supportedServicesMask),
                            "sync attempts" to "$syncAttempts",
                        )
                    },
                    onSync = { syncNow(trigger = "manual") },
                    onScan = { startScan() },
                    onStop = { client?.stopScan() },
                    onDisconnect = { disconnectWatch() },
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
                    onImportFit = { uri -> importFitFromUri(uri) },
                    onSaveWorkout = { workout -> saveWorkout(workout) },
                    onDeleteWorkout = { id -> deleteWorkout(id) },
                    onSendWorkout = { workout -> sendWorkout(workout) },
                    onSendPacePlan = { plan -> sendPacePlan(plan) },
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
                            disconnectWatch()
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
        appVisible = true
        log("APP onStart syncInProgress=$syncInProgress lease=${SyncGate.owner()}")
        if (syncInProgress) {
            disconnectWhenSyncFinishes = false
            return
        }
        val c = client ?: return
        if (!c.hasPermissions()) return
        val backgroundRunning = SyncGate.owner() == SyncGate.OWNER_BACKGROUND
        if (!backgroundRunning && settings.lastSyncStatus == "syncing") {
            // A process death or force-stop can leave the persisted status at
            // "syncing" even though no worker survived. Do not show a fake
            // in-progress state indefinitely.
            settings.recordSyncError(
                "The previous sync was interrupted before it finished.",
                trigger = settings.lastSyncTrigger.ifBlank { "manual" },
                kind = "cancelled",
            )
        }
        if (backgroundRunning) {
            state = "background sync"
            log("background sync is already running")
            return
        }
        val lastSync = settings.lastSyncAt
        val due = lastSync == null ||
            System.currentTimeMillis() - lastSync >= AUTO_SYNC_MIN_INTERVAL_MS
        if (due) syncNow(trigger = "launch")
    }

    override fun onStop() {
        appVisible = false
        log("APP onStop syncInProgress=$syncInProgress")
        if (syncInProgress) {
            // Let the transfer finish, then drop the BLE lease so the periodic
            // worker can take over while the app is in the background.
            disconnectWhenSyncFinishes = true
        } else {
            disconnectWatch()
        }
        super.onStop()
    }

    private fun disconnectWatch() {
        cancelWatchdog()
        if (syncInProgress) {
            val durationMs = (SystemClock.elapsedRealtime() - syncStartedAt).coerceAtLeast(0L)
            settings.recordSyncError(
                "Sync stopped before it finished.",
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = "cancelled",
            )
        }
        syncInProgress = false
        activityProgress = null
        client?.disconnect()
        SyncGate.release(SyncGate.OWNER_UI)
        log("BLE lease released (idle)")
        if (syncNotificationActive) {
            syncNotificationActive = false
            SyncNotifications.cancel(this)
        }
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
        if (state == "syncing" || state == "importing") {
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
                selectedWellnessDay = null
                wellnessSamples = emptyList()
                latestHrSamples = emptyList()
                latestSleepStages = emptyList()
                lastImportedLabel = null
                activities.clear()
                wellnessDays.clear()
                knownWellnessUids = withContext(Dispatchers.IO) {
                    runCatching { repository.importedWellnessFileIds() }.getOrDefault(emptySet())
                }
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

    private fun importFit(file: File, itemUid: String) {
        importing = true
        state = "importing"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importFit(file.name, file.readBytes(), itemUid) }
            }
            importing = false
            state = if (activityProgress != null) "syncing" else "synced"
            result.onSuccess { entity ->
                if (entity == null) {
                    log("FIT has no session or was already imported")
                } else {
                    importedThisSync++
                    lastImportedLabel = buildString {
                        append(formatDate(entity.startTime))
                        append(" · ")
                        append(sportName(entity.sport))
                        if (entity.distanceMeters > 0) {
                            append(" · ")
                            append("%.2f km".format(entity.distanceMeters / 1000.0))
                        }
                    }
                    log(
                        "IMPORTED ${formatDate(entity.startTime)} ${sportName(entity.sport)} " +
                            "%.2f km".format(entity.distanceMeters / 1000.0),
                    )
                }
            }.onFailure { e ->
                importErrorThisSync = e.message ?: "FIT import failed"
                log("FIT import failed: ${e.message}")
            }
            if (activityProgress != null) {
                updateSyncNotification(progress = activityProgress)
            }
        }
    }

    private fun importFitFromUri(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                log("FIT import failed: could not read the selected file")
                return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "imported.fit"
            // Keep the selected FIT file in app storage so Export → FIT can
            // return the original watch file later. The timestamp prevents
            // two imports with the same display name from colliding.
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    File(filesDir, "import-${System.currentTimeMillis()}-${safeFitName(name)}").also {
                        it.writeBytes(bytes)
                    }
                }.getOrNull()
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.importFit(stored?.name ?: name, bytes, itemUid = "")
                }
            }
            result.onSuccess { entity ->
                if (entity == null) {
                    stored?.delete()
                    log("FIT import: no session found or activity already exists")
                } else {
                    importedThisSync++
                    lastImportedLabel = buildString {
                        append(formatDate(entity.startTime))
                        append(" · ")
                        append(sportName(entity.sport))
                        if (entity.distanceMeters > 0) {
                            append(" · ")
                            append("%.2f km".format(entity.distanceMeters / 1000.0))
                        }
                    }
                    log("IMPORTED from file: ${entity.fileName}")
                }
            }.onFailure { e ->
                stored?.delete()
                log("FIT import failed: ${e.message}")
            }
        }
    }

    private fun safeFitName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { character ->
            if (character.isLetterOrDigit() || character in "._-") character else '_'
        }.joinToString("").take(100)
        val safe = cleaned.ifBlank { "imported.fit" }
        return if (safe.endsWith(".fit", ignoreCase = true)) safe else "$safe.fit"
    }

    private fun importWellness(file: File, type: String, itemUid: String) {
        lifecycleScope.launch {
            val immutable = type == "FIT_TYPE_44" || type == "FIT_TYPE_49"
            val result = withContext(Dispatchers.IO) {
                val days = runCatching { repository.importWellness(file.readBytes()) }
                if (days.isSuccess && immutable) {
                    runCatching { repository.markWellnessFileImported(itemUid, type) }
                }
                days
            }
            result.onSuccess { days ->
                if (days.isEmpty()) {
                    log("WELLNESS $type contained no health samples")
                } else {
                    if (immutable) knownWellnessUids = knownWellnessUids + itemUid
                    days.forEach { day ->
                        val distance = "%.2f".format(day.distanceMeters / 1000.0)
                        log(
                            "WELLNESS imported ${day.date}: ${day.steps} steps, " +
                                "$distance km, resting HR ${day.restingHeartRate ?: "—"}, " +
                                "sleep score ${day.sleepScore ?: "—"}, " +
                                "Body Battery ${day.bodyBatteryLatest ?: "—"}",
                        )
                    }
                }
            }.onFailure { e ->
                log("WELLNESS import failed: ${e.message}")
            }
        }
    }

    private fun selectWellness(day: WellnessDayEntity) {
        selectedWellnessDay = day
        wellnessSamples = emptyList()
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) { repository.wellnessSamples(day.id) }
            if (selectedWellnessDay?.id == day.id) wellnessSamples = samples
        }
    }

    private fun closeWellness() {
        selectedWellnessDay = null
        wellnessSamples = emptyList()
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
                    details = healthDataSource.workout(ToolScope(), activity.id),
                    insights = repository.activityInsights(activity.id),
                )
            }
            if (selectedActivity?.id == activity.id) {
                selectedRecords = loaded.records
                selectedLaps = loaded.laps
                selectedInsights = loaded.insights
                selectedDynamics = loaded.details?.dynamics
                selectedGps = loaded.details?.gps
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

    private fun armWatchdog(timeoutMs: Long = 15_000) {
        cancelWatchdog()
        val r = Runnable {
            val co = coordinator ?: return@Runnable
            if (co.isDone()) return@Runnable
            if (co.isTransferring()) {
                armWatchdog(60_000)
                return@Runnable
            }
            if (!co.hasStarted() && syncAttempts == 0) {
                log("awaiting watch-side sync (link stays open)")
                state = "awaiting watch sync"
                armWatchdog(60_000)
                return@Runnable
            }
            if (syncAttempts < maxSyncAttempts) {
                syncAttempts++
                log("sync stalled; reconnecting ($syncAttempts/$maxSyncAttempts)")
                state = "reconnecting"
                updateSyncNotification(status = "Reconnecting to watch…", progress = null)
                client?.reconnect()
            } else {
                val reason = syncFailureReason()
                log("sync failed after $syncAttempts attempts: $reason")
                state = "failed"
                finishSync(success = false, error = reason)
            }
        }
        watchdog = r
        main.postDelayed(r, timeoutMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    private fun syncFailureReason(): String =
        if (coordinator?.stalledBeforeCapabilities() == true) {
            "The watch accepted the connection but did not open its GDI session. " +
                "Garmin Connect can hold that single session — close/force-stop it and try again."
        } else {
            "Could not finish syncing. Keep the watch nearby and try again."
        }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun importedSummary(): String =
        if (importedThisSync > 0) {
            "Imported $importedThisSync " +
                (if (importedThisSync == 1) "activity" else "activities")
        } else {
            "Everything up to date"
        }

    private fun syncProgressText(progress: ActivitySyncProgress): String {
        val current = (progress.done + 1).coerceAtMost(progress.total)
        return progress.currentSize?.let {
            "Activity $current of ${progress.total} · ${formatFileSize(it)}"
        } ?: "${progress.done} of ${progress.total} imported"
    }

    private fun updateSyncNotification(
        status: String? = null,
        progress: ActivitySyncProgress? = activityProgress,
    ) {
        SyncNotifications.show(
            context = this,
            title = "Syncing watch",
            status = status ?: progress?.let { syncProgressText(it) } ?: "Waiting for the watch…",
            detail = lastImportedLabel?.let { "Last imported: $it" },
            progress = progress,
            ongoing = true,
        )
    }

    private fun finishSyncNotification(title: String, status: String) {
        log("SYNC notification: $title — $status")
        SyncNotifications.finish(
            context = this,
            title = title,
            status = status,
            detail = lastImportedLabel?.let { "Last imported: $it" },
        )
        importedThisSync = 0
        syncNotificationActive = false
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

    private fun sendWorkout(workout: PlannedWorkoutEntity) {
        if (workoutSendStatus?.sending == true || pacePlanSendStatus?.sending == true) return
        val watch = client?.findBondedWatch()
        if (watch == null) {
            workoutSendStatus = WorkoutSendStatus(
                workoutId = workout.id,
                name = workout.name,
                sending = false,
                success = false,
                message = "No bonded Garmin watch found. Pair the watch first.",
            )
            return
        }
        workoutSendStatus = WorkoutSendStatus(
            workoutId = workout.id,
            name = workout.name,
            sending = true,
            message = "Preparing workout…",
        )
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { repository.saveWorkout(workout) }
            }.getOrElse { error ->
                workoutSendStatus = WorkoutSendStatus(
                    workoutId = workout.id,
                    name = workout.name,
                    sending = false,
                    success = false,
                    message = error.message ?: "Could not save the workout.",
                )
                return@launch
            }
            val bytes = runCatching { FitWorkout.encode(saved.toFitWorkout()) }
                .getOrElse { error ->
                    workoutSendStatus = WorkoutSendStatus(
                        workoutId = saved.id,
                        name = saved.name,
                        sending = false,
                        success = false,
                        message = error.message ?: "Could not build the FIT workout.",
                    )
                    return@launch
                }
            val pending = PendingWorkoutSend(saved.id, saved.name, bytes)
            pendingWorkoutSend = pending
            workoutSendStatus = WorkoutSendStatus(
                workoutId = saved.id,
                name = saved.name,
                sending = true,
                message = "Connecting to ${watch.name ?: "watch"}…",
            )
            log("WORKOUT preparing \"${saved.name}\": ${bytes.size} FIT bytes")
            syncNow(pending, trigger = "upload")
        }
    }

    private fun deletePacePlan(id: Long) {
        if (id == 0L) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.deletePacePlan(id) } }
            log("PACE deleted plan id=$id")
        }
    }

    private fun sendPacePlan(plan: PacePlanEntity) {
        if (pacePlanSendStatus?.sending == true || workoutSendStatus?.sending == true) return
        val watch = client?.findBondedWatch()
        pacePlanSendStatus = PacePlanSendStatus(
            planId = plan.id,
            name = plan.name,
            sending = true,
            message = "Preparing pace plan…",
        )
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { repository.savePacePlan(plan) }
            }.getOrElse { error ->
                pacePlanSendStatus = PacePlanSendStatus(
                    planId = plan.id,
                    name = plan.name,
                    sending = false,
                    success = false,
                    message = error.message ?: "Could not save the pace plan.",
                )
                return@launch
            }
            log("PACE saved \"${saved.name}\" (${saved.splits.size} splits)")
            if (watch == null) {
                pacePlanSendStatus = PacePlanSendStatus(
                    planId = saved.id,
                    name = saved.name,
                    sending = false,
                    success = false,
                    message = "Saved on this phone. No bonded Garmin watch found.",
                )
                return@launch
            }
            val bytes = runCatching { FitPaceBand.encode(saved.toFitPaceBand()) }
                .getOrElse { error ->
                    pacePlanSendStatus = PacePlanSendStatus(
                        planId = saved.id,
                        name = saved.name,
                        sending = false,
                        success = false,
                        message = error.message ?: "Could not build the FIT pace band.",
                    )
                    return@launch
                }
            val pending = PendingWorkoutSend(saved.id, saved.name, bytes, UploadKind.PACE_PLAN)
            pendingWorkoutSend = pending
            pacePlanSendStatus = PacePlanSendStatus(
                planId = saved.id,
                name = saved.name,
                sending = true,
                message = "Connecting to ${watch.name ?: "watch"}…",
            )
            log("PACE preparing \"${saved.name}\": ${bytes.size} FIT bytes")
            syncNow(pending, trigger = "upload")
        }
    }

    private fun syncNow(
        pendingWorkout: PendingWorkoutSend? = null,
        trigger: String = "manual",
    ) {
        if (pendingWorkout != null) pendingWorkoutSend = pendingWorkout
        val c = client ?: return
        if (syncInProgress) {
            pendingWorkout?.let { pending ->
                markPendingUploadFailed(pending, "A sync is already running. Try again when it finishes.")
            }
            return
        }
        if (!SyncGate.tryAcquire(SyncGate.OWNER_UI)) {
            val message = "A background sync is running. Try again when it finishes."
            log(message)
            pendingWorkout?.let { markPendingUploadFailed(it, message) }
            return
        }
        if (!c.hasBluetoothAdapter()) {
            SyncGate.release(SyncGate.OWNER_UI)
            recordPermanentSetupError("Bluetooth LE is not available on this phone.", trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, "Bluetooth LE is not available.") }
            return
        }
        if (!c.isBluetoothEnabled()) {
            SyncGate.release(SyncGate.OWNER_UI)
            recordPermanentSetupError("Bluetooth is off. Turn Bluetooth on, then sync again.", trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, "Bluetooth is off.") }
            return
        }
        val watch = c.findBondedWatch()
        if (watch == null) {
            SyncGate.release(SyncGate.OWNER_UI)
            val message = "No bonded Garmin watch found. Pair the watch in Android Bluetooth settings."
            recordPermanentSetupError(message, trigger)
            pendingWorkout?.let { markPendingUploadFailed(it, message) }
            return
        }
        syncAttempts = 0
        cancelWatchdog()
        activityProgress = null
        lastImportedLabel = null
        importedThisSync = 0
        importErrorThisSync = null
        syncInProgress = true
        syncTrigger = trigger
        syncStartedAt = SystemClock.elapsedRealtime()
        disconnectWhenSyncFinishes = false
        settings.recordSyncStarted(trigger)
        ensureNotificationPermission()
        syncNotificationActive = true
        SyncNotifications.start(this)
        updateSyncNotification(
            status = when (pendingWorkoutSend?.kind) {
                UploadKind.PACE_PLAN -> "Connecting to ${watch.name} to send a pace plan…"
                UploadKind.WORKOUT -> "Connecting to ${watch.name} to send a workout…"
                null -> "Connecting to ${watch.name}…"
            },
            progress = null,
        )
        settings.updateWatchName(watch.name ?: "")
        coordinator?.reset()
        c.connect(watch)
    }

    private fun markPendingUploadFailed(pending: PendingWorkoutSend, message: String) {
        pendingWorkoutSend = null
        when (pending.kind) {
            UploadKind.WORKOUT -> workoutSendStatus = WorkoutSendStatus(
                workoutId = pending.id,
                name = pending.name,
                sending = false,
                success = false,
                message = message,
            )
            UploadKind.PACE_PLAN -> pacePlanSendStatus = PacePlanSendStatus(
                planId = pending.id,
                name = pending.name,
                sending = false,
                success = false,
                message = message,
            )
        }
    }

    private fun recordPermanentSetupError(message: String, trigger: String) {
        log(message)
        settings.recordSyncStarted(trigger)
        settings.recordSyncError(
            message,
            durationMs = 0,
            trigger = trigger,
            kind = "permanent",
        )
    }

    private fun finishSync(success: Boolean, error: String? = null) {
        if (!syncInProgress) {
            SyncGate.release(SyncGate.OWNER_UI)
            return
        }
        syncInProgress = false
        cancelWatchdog()
        val durationMs = (SystemClock.elapsedRealtime() - syncStartedAt).coerceAtLeast(0L)
        val importError = importErrorThisSync
        if (success && importError != null) {
            val reason = "Sync finished, but importing an activity failed: $importError"
            val kind = SyncPolicy.FailureKind.TRANSIENT
            settings.recordSyncError(
                reason,
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = when (kind) {
                    SyncPolicy.FailureKind.PERMANENT -> "permanent"
                    SyncPolicy.FailureKind.BUSY -> "busy"
                    SyncPolicy.FailureKind.TRANSIENT -> "transient"
                },
            )
            state = "failed"
            finishSyncNotification("Sync failed", reason)
        } else if (success) {
            settings.recordSyncSuccess(durationMs, syncTrigger)
            state = "up to date"
            finishSyncNotification("Sync complete", importedSummary())
        } else {
            val reason = error ?: "Could not finish syncing."
            val kind = SyncPolicy.classifyFailure(
                reason,
                stalledBeforeCapabilities = coordinator?.stalledBeforeCapabilities() == true,
            )
            settings.recordSyncError(
                reason,
                durationMs = durationMs,
                trigger = syncTrigger,
                kind = when (kind) {
                    SyncPolicy.FailureKind.PERMANENT -> "permanent"
                    SyncPolicy.FailureKind.BUSY -> "busy"
                    SyncPolicy.FailureKind.TRANSIENT -> "transient"
                },
            )
            state = "failed"
            finishSyncNotification("Sync failed", reason)
        }
        activityProgress = null
        if (!success || importError != null || !appVisible || disconnectWhenSyncFinishes) {
            disconnectWhenSyncFinishes = false
            client?.disconnect()
            SyncGate.release(SyncGate.OWNER_UI)
        }
    }

    private fun startScan() {
        val c = client ?: return
        devices.clear()
        if (c.hasPermissions()) c.startScan() else permissionLauncher.launch(requiredPermissions())
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
        android.util.Log.d("OpenGarmin", message)
        main.post {
            logs.add(0, message)
            if (logs.size > 300) logs.removeAt(logs.lastIndex)
        }
    }

    override fun onDestroy() {
        cancelWatchdog()
        syncInProgress = false
        client?.disconnect()
        SyncGate.release(SyncGate.OWNER_UI)
        if (aclReceiverRegistered) {
            runCatching { unregisterReceiver(watchReconnectReceiver) }
            aclReceiverRegistered = false
        }
        if (syncNotificationActive) {
            syncNotificationActive = false
            SyncNotifications.cancel(this)
        }
        super.onDestroy()
    }
}

private const val AUTO_SYNC_MIN_INTERVAL_MS = 5 * 60 * 1000L

@Composable
private fun Screen(
    state: String,
    lastFile: String,
    importing: Boolean,
    activities: List<ActivityEntity>,
    selectedActivity: ActivityEntity?,
    selectedRecords: List<RecordEntity>,
    selectedLaps: List<LapEntity>,
    wellnessDays: List<WellnessDayEntity>,
    selectedWellnessDay: WellnessDayEntity?,
    wellnessSamples: List<WellnessSampleEntity>,
    dataActivity: ActivityEntity?,
    dataMessages: List<FitMessageEntity>,
    dataLoading: Boolean,
    devices: List<MainActivity.DeviceEntry>,
    logs: List<String>,
    onScan: () -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSelect: (ActivityEntity) -> Unit,
    onClearSelection: () -> Unit,
    onExploreData: (ActivityEntity) -> Unit,
    onCloseData: () -> Unit,
    onSelectWellness: (WellnessDayEntity) -> Unit,
    onCloseWellness: () -> Unit,
    deviceInfo: () -> List<Pair<String, String>>,
) {
    var showLog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OpenGarmin", style = MaterialTheme.typography.headlineSmall)
                Text("state: $state", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (importing) "importing FIT…" else "last: $lastFile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSync) { Text("Sync") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onScan) { Text("Scan") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            OutlinedButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide log" else "Log")
            }
        }

        if (dataActivity != null) {
            DataExplorer(
                activity = dataActivity,
                messages = dataMessages,
                loading = dataLoading,
                onBack = onCloseData,
                modifier = Modifier.weight(1f),
            )
        } else if (selectedActivity != null) {
            ActivityDetail(
                activity = selectedActivity,
                records = selectedRecords,
                laps = selectedLaps,
                onBack = onClearSelection,
                onExploreData = { onExploreData(selectedActivity) },
                modifier = Modifier.weight(1f),
            )
        } else if (selectedWellnessDay != null) {
            HealthDetail(
                day = selectedWellnessDay,
                samples = wellnessSamples,
                days = wellnessDays,
                onBack = onCloseWellness,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (wellnessDays.isNotEmpty()) {
                val today = dev.opengarmin.app.data.WellnessMetrics.latestDay(wellnessDays) ?: wellnessDays.first()
                HealthCard(today) { onSelectWellness(today) }
            }
            Text(
                "Activities (${activities.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            if (activities.isEmpty()) {
                Text(
                    "No activities yet. Sync the watch to import one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(activities, key = { it.id }) { activity ->
                    ActivityCard(activity) { onSelect(activity) }
                }
                if (devices.isNotEmpty() && state != "connected") {
                    item {
                        Text("Devices", style = MaterialTheme.typography.titleMedium)
                    }
                    items(devices) { entry ->
                        OutlinedButton(onClick = { onConnect(entry.device) }) { Text(entry.label) }
                    }
                }
            }
        }

        if (showLog) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleSmall)
                    deviceInfo().forEach { (k, v) ->
                        Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(logs) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(day: WellnessDayEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (day.date == LocalDate.now().toString()) {
                        "Today"
                    } else {
                        formatWellnessDate(day.date)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatWellnessDate(day.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%,d".format(day.steps),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(" steps", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(formatDistance(day.distanceMeters), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${day.activeCalories} kcal", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatDuration(day.activeSeconds.toDouble())} active",
                    style = MaterialTheme.typography.bodySmall,
                )
                day.restingHeartRate?.let {
                    Text("Resting HR $it bpm", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                day.avgHeartRate?.let { Text("Avg HR $it", style = MaterialTheme.typography.bodySmall) }
                day.maxHeartRate?.let { Text("Max HR $it", style = MaterialTheme.typography.bodySmall) }
                day.stressAvg?.let { Text("Stress $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun HealthDetail(
    day: WellnessDayEntity,
    samples: List<WellnessSampleEntity>,
    days: List<WellnessDayEntity>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heartRate = remember(samples) {
        samples.filter { it.kind == "hr" }.sortedBy { it.timestamp }
    }
    val stress = remember(samples) {
        samples.filter { it.kind == "stress" }.sortedBy { it.timestamp }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.weight(1f))
            Text(formatWellnessDate(day.date), style = MaterialTheme.typography.bodySmall)
        }

        Text("Daily health", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%,d".format(day.steps),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(" steps", style = MaterialTheme.typography.bodyLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    Stat("Distance", formatDistance(day.distanceMeters))
                    Stat("Active time", formatDuration(day.activeSeconds.toDouble()))
                }
                Row {
                    Stat("Calories", "${day.activeCalories} kcal")
                    Stat("Resting HR", day.restingHeartRate?.let { "$it bpm" } ?: "—")
                }
                Row {
                    Stat("Avg HR", day.avgHeartRate?.let { "$it bpm" } ?: "—")
                    Stat("Max HR", day.maxHeartRate?.let { "$it bpm" } ?: "—")
                }
                Row {
                    Stat("Stress", day.stressAvg?.let { "$it" } ?: "—")
                    Stat("SpO2", day.spo2Avg?.let { "$it%" } ?: "—")
                }
                Row {
                    Stat(
                        "Respiration",
                        day.respirationAvg?.let { "%.1f br/min".format(it) } ?: "—",
                    )
                    Stat("Updated", formatDate(day.updatedAt))
                }
            }
        }

        if (heartRate.size >= 2) {
            Text("Heart rate", style = MaterialTheme.typography.titleMedium)
            SampleLineChart(heartRate.map { it.value }, MaterialTheme.colorScheme.primary)
            Row {
                Text(
                    "${heartRate.minOf { it.value }.toInt()} bpm",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${heartRate.maxOf { it.value }.toInt()} bpm",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (stress.size >= 2) {
            Text("Stress", style = MaterialTheme.typography.titleMedium)
            SampleLineChart(stress.map { it.value }, MaterialTheme.colorScheme.tertiary)
        }

        if (days.size >= 2) {
            Text("Steps history", style = MaterialTheme.typography.titleMedium)
            DailyStepsChart(days.take(7).reversed())
        }
    }
}

@Composable
private fun SampleLineChart(values: List<Double>, color: Color) {
    if (values.size < 2) {
        Text(
            "Not enough samples.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val min = values.min()
    val max = values.max()
    val range = (max - min).coerceAtLeast(1e-9)
    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height - ((value - min) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))
    }
}

@Composable
private fun DailyStepsChart(days: List<WellnessDayEntity>) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.steps }.coerceAtLeast(1)
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        val barWidth = size.width / (days.size * 1.6f)
        days.forEachIndexed { index, day ->
            val height = day.steps.toFloat() / max * size.height
            val center = size.width * (index + 0.5f) / days.size
            drawRect(
                color = color,
                topLeft = Offset(center - barWidth / 2, size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
    Row {
        days.forEach { day ->
            Text(
                "${day.steps}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun formatWellnessDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}.getOrDefault(date)

@Composable
private fun ActivityCard(activity: ActivityEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sportName(activity.sport),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatDate(activity.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${formatDistance(activity.distanceMeters)}  ·  ${formatDuration(activity.durationSeconds)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val hr = activity.avgHeartRate?.let { "$it bpm avg" } ?: "no HR"
                Text(hr, style = MaterialTheme.typography.bodySmall)
                activity.maxHeartRate?.let {
                    Text("$it max", style = MaterialTheme.typography.bodySmall)
                }
                activity.calories?.let {
                    Text("$it kcal", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ActivityDetail(
    activity: ActivityEntity,
    records: List<RecordEntity>,
    laps: List<LapEntity>,
    onBack: () -> Unit,
    onExploreData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onExploreData) { Text("All data") }
            Spacer(modifier = Modifier.weight(1f))
            Text(formatDate(activity.startTime), style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "${sportName(activity.sport)} · ${formatDistance(activity.distanceMeters)}",
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    Stat("Time", formatDuration(activity.durationSeconds))
                    Stat("Avg pace", formatPace(activity.durationSeconds, activity.distanceMeters))
                }
                Row {
                    Stat("Avg HR", activity.avgHeartRate?.let { "$it bpm" } ?: "—")
                    Stat("Max HR", activity.maxHeartRate?.let { "$it bpm" } ?: "—")
                }
                Row {
                    Stat("Calories", activity.calories?.let { "$it kcal" } ?: "—")
                    Stat("Ascent", activity.ascentMeters?.let { "$it m" } ?: "—")
                }
                Row {
                    Stat("Elapsed", formatDuration(activity.elapsedSeconds))
                    Stat("Descent", activity.descentMeters?.let { "$it m" } ?: "—")
                }
            }
        }

        Text("Track", style = MaterialTheme.typography.titleMedium)
        RouteMap(records, Modifier.fillMaxWidth().height(280.dp))

        Text("Heart rate", style = MaterialTheme.typography.titleMedium)
        HeartRateChart(records)

        Text("Elevation", style = MaterialTheme.typography.titleMedium)
        ElevationChart(records)

        if (laps.isNotEmpty()) {
            Text("Laps", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("#", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.4f))
                        Text("Distance", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("Time", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("Pace", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("HR", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f))
                    }
                    laps.forEach { lap ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${lap.lapIndex + 1}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
                            Text(formatDistance(lap.distanceMeters), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(formatDuration(lap.durationSeconds), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(formatPace(lap.durationSeconds, lap.distanceMeters), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                lap.avgHeartRate?.let { "$it" } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private data class RouteSample(val point: GeoPoint, val speedMps: Double?)

/**
 * Draws the GPS trace as colour-coded line segments: blue for slower samples,
 * red for faster ones. Using a custom overlay instead of one Polyline keeps
 * the speed gradient without adding thousands of map overlays.
 */
private class SpeedRouteOverlay(
    private val route: List<RouteSample>,
) : Overlay() {
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val slowColor = PulseColors.RouteSlow.toArgb()
    private val fastColor = PulseColors.RouteFast.toArgb()
    private val lowSpeed: Double
    private val highSpeed: Double

    init {
        val speeds = route
            .mapNotNull { it.speedMps }
            .filter { it.isFinite() && it > 0.3 }
            .sorted()
        if (speeds.size < 2 || speeds.last() - speeds.first() < 0.15) {
            lowSpeed = speeds.firstOrNull() ?: 0.0
            highSpeed = lowSpeed
        } else {
            val lowIndex = (speeds.size * 0.05).toInt().coerceIn(0, speeds.lastIndex)
            val highIndex = (speeds.size * 0.95).toInt().coerceIn(0, speeds.lastIndex)
            lowSpeed = speeds[lowIndex]
            highSpeed = speeds[highIndex].takeIf { it > lowSpeed } ?: (lowSpeed + 0.5)
        }
    }

    override fun draw(canvas: AndroidCanvas, mapView: MapView, shadow: Boolean) {
        if (shadow || route.size < 2) return
        val projection = mapView.projection
        val first = Point()
        val second = Point()
        val step = if (route.size > 1_800) 2 else 1
        var index = 0
        while (index < route.lastIndex) {
            val next = minOf(index + step, route.lastIndex)
            projection.toPixels(route[index].point, first)
            projection.toPixels(route[next].point, second)
            paint.color = colorForSpeed(averageSpeed(index, next))
            canvas.drawLine(
                first.x.toFloat(),
                first.y.toFloat(),
                second.x.toFloat(),
                second.y.toFloat(),
                paint,
            )
            index = next
        }
    }

    private fun averageSpeed(index: Int, next: Int): Double {
        val first = route[index].speedMps
        val second = route[next].speedMps
        return when {
            first != null && second != null -> (first + second) / 2.0
            first != null -> first
            second != null -> second
            else -> 0.0
        }
    }

    private fun colorForSpeed(speed: Double): Int {
        val fraction = if (highSpeed > lowSpeed + 0.001) {
            ((speed - lowSpeed) / (highSpeed - lowSpeed)).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return lerpColor(slowColor, fastColor, fraction)
    }

    private fun lerpColor(start: Int, end: Int, fraction: Double): Int {
        val red = (android.graphics.Color.red(start) +
            (android.graphics.Color.red(end) - android.graphics.Color.red(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        val green = (android.graphics.Color.green(start) +
            (android.graphics.Color.green(end) - android.graphics.Color.green(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        val blue = (android.graphics.Color.blue(start) +
            (android.graphics.Color.blue(end) - android.graphics.Color.blue(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(red, green, blue)
    }
}

@Composable
internal fun RouteMap(records: List<RecordEntity>, modifier: Modifier = Modifier) {
    val route = remember(records) {
        records.mapNotNull { record ->
            val lat = record.latitude ?: return@mapNotNull null
            val lon = record.longitude ?: return@mapNotNull null
            RouteSample(GeoPoint(lat, lon), record.speedMps)
        }
    }
    val points = remember(route) { route.map { it.point } }
    if (points.size < 2) {
        Text(
            "No GPS track in this activity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val context = LocalContext.current
    val mapView = remember(route) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            overlays.add(SpeedRouteOverlay(route))
            overlays.add(Marker(this).apply { position = points.first(); title = "Start" })
            overlays.add(Marker(this).apply { position = points.last(); title = "Finish" })
            val box = BoundingBox.fromGeoPoints(points)
            controller.setCenter(
                GeoPoint((box.latNorth + box.latSouth) / 2.0, (box.lonEast + box.lonWest) / 2.0),
            )
            controller.setZoom(16.0)
            post { zoomToBoundingBox(box, true, 64) }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable
internal fun HeartRateChart(records: List<RecordEntity>) {
    val points = remember(records) { records.mapNotNull { it.heartRate } }
    if (points.size < 2) {
        Text(
            "No heart-rate samples in this activity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val minHr = points.min()
    val maxHr = points.max()
    val range = (maxHr - minHr).coerceAtLeast(1)
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
        val path = Path()
        points.forEachIndexed { index, hr ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = size.height - (hr - minHr).toFloat() / range * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
    }
    Row {
        Text("$minHr bpm", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.weight(1f))
        Text("$maxHr bpm", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ElevationChart(records: List<RecordEntity>) {
    val points = remember(records) { records.mapNotNull { it.altitudeMeters } }
    if (points.size < 2) {
        Text(
            "No altitude samples in this activity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val minAlt = points.min()
    val maxAlt = points.max()
    val range = (maxAlt - minAlt).coerceAtLeast(0.1).toFloat()
    val lineColor = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val path = Path()
        points.forEachIndexed { index, altitude ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = size.height - (altitude.toFloat() - minAlt.toFloat()) / range * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
    }
    Row {
        Text("%.0f m".format(minAlt), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.weight(1f))
        Text("%.0f m".format(maxAlt), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DataExplorer(
    activity: ActivityEntity,
    messages: List<FitMessageEntity>,
    loading: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(messages) {
        val preferred = listOf(
            "record", "session", "lap", "activity", "event", "device_info",
            "gps_metadata", "time_in_zone", "split",
        )
        messages.groupBy { it.messageName }.toList().sortedWith(
            compareBy(
                { (name, _) -> preferred.indexOf(name).let { if (it < 0) preferred.size else it } },
                { (_, list) -> -list.size },
            ),
        )
    }
    var selectedType by rememberSaveable { mutableStateOf("record") }
    LaunchedEffect(groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        if (groups.none { it.first == selectedType }) {
            selectedType = if (groups.any { it.first == "record" }) {
                "record"
            } else {
                groups.first().first
            }
        }
    }
    val selected = groups.firstOrNull { it.first == selectedType }?.second.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("All data", style = MaterialTheme.typography.titleLarge, color = PulseColors.Text)
                    Text(
                        "${sportName(activity.sport)} · ${formatDate(activity.startTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading FIT messages…", color = PulseColors.TextMuted)
                }
            }
        } else if (messages.isEmpty()) {
            item {
                Text(
                    "No stored FIT messages for this activity. Re-import it to populate the data explorer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.TextMuted,
                )
            }
        } else {
            item {
                Text(
                    "Message types",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.Text,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(groups) { (name, list) ->
                        FilterChip(
                            selected = name == selectedType,
                            onClick = { selectedType = name },
                            label = { Text("$name (${list.size})") },
                        )
                    }
                }
            }
            if (selected.size >= 2) {
                item { MessagePlotSection(selected) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw messages", style = MaterialTheme.typography.titleMedium, color = PulseColors.Text)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "showing ${minOf(selected.size, 200)} of ${selected.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(selected.take(200), key = { it.id }) { message ->
                MessageCard(message)
            }
        }
    }
}

private data class PlotSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
    val minValue: Double,
    val maxValue: Double,
    val count: Int,
    val correlation: Double,
)

private val MessagePlotColors = listOf(
    PulseColors.Accent,
    PulseColors.Teal,
    PulseColors.Coral,
    PulseColors.Blue,
    PulseColors.Orange,
    PulseColors.Yellow,
    PulseColors.Magenta,
    PulseColors.Violet,
)

@Composable
private fun MessagePlotSection(messages: List<FitMessageEntity>) {
    val rows = remember(messages) { messages.map { parseMessageFields(it) } }
    val fieldNames = remember(rows) {
        val names = LinkedHashSet<String>()
        rows.take(500).forEach { names.addAll(it.keys) }
        names.sorted()
    }
    if (fieldNames.size < 2) {
        Text(
            "Not enough numeric fields to plot.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextMuted,
        )
        return
    }
    val orderedFields = remember(fieldNames) {
        val preferred = listOf(
            "heart_rate", "enhanced_speed", "speed", "enhanced_altitude", "altitude",
            "cadence", "power", "distance", "timestamp", "temperature",
        )
        preferred.filter { it in fieldNames } + fieldNames.filter { it !in preferred }
    }

    val defaultY = remember(fieldNames) {
        val speed = if ("enhanced_speed" in fieldNames) "enhanced_speed" else "speed"
        val altitude = if ("enhanced_altitude" in fieldNames) "enhanced_altitude" else "altitude"
        listOf("heart_rate", speed, altitude, "cadence", "power")
            .filter { it in fieldNames }
            .take(3)
            .ifEmpty { fieldNames.take(2) }
    }
    var selectedY by remember(rows) { mutableStateOf(defaultY) }
    var xField by remember(rows) {
        mutableStateOf(
            when {
                "distance" in fieldNames -> "distance"
                "timestamp" in fieldNames -> "timestamp"
                else -> fieldNames.first()
            },
        )
    }
    var normalize by remember(rows) { mutableStateOf(true) }

    val useNormalized = selectedY.size > 1 && normalize
    val series = remember(rows, xField, selectedY, useNormalized) {
        val xBase = if (xField == "timestamp") {
            rows.mapNotNull { it[xField] }.minOrNull() ?: 0.0
        } else {
            0.0
        }
        val xScale = if (xField == "distance") 0.001 else 1.0
        selectedY.mapIndexedNotNull { index, name ->
            val pairs = rows.mapNotNull { row ->
                val x = row[xField] ?: return@mapNotNull null
                val y = row[name] ?: return@mapNotNull null
                x to y
            }.sortedBy { it.first }
            if (pairs.size < 2) return@mapIndexedNotNull null
            val min = pairs.minOf { it.second }
            val max = pairs.maxOf { it.second }
            val span = (max - min).coerceAtLeast(1e-9)
            val points = pairs.map { (x, y) ->
                val plotY = when {
                    !useNormalized -> y
                    max - min > 1e-9 -> ((y - min) / span).coerceIn(0.0, 1.0)
                    else -> 0.5
                }
                ChartPoint(
                    x = ((x - xBase) * xScale).toFloat(),
                    y = plotY.toFloat(),
                )
            }
            PlotSeries(
                name = fieldLabel(name),
                color = MessagePlotColors[index % MessagePlotColors.size],
                points = points,
                minValue = min,
                maxValue = max,
                count = pairs.size,
                correlation = correlation(pairs),
            )
        }
    }

    PulseCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PulseSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cross-plot", style = MaterialTheme.typography.titleSmall, color = PulseColors.Text)
            Text(
                "Choose one X axis and one or more Y metrics. Only this plot is shown.",
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            FieldDropdown("X", orderedFields, xField) { xField = it }
            Text("Y metrics", style = MaterialTheme.typography.labelSmall, color = PulseColors.TextMuted)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(orderedFields) { name ->
                    FilterChip(
                        selected = name in selectedY,
                        onClick = {
                            selectedY = if (name in selectedY) {
                                selectedY - name
                            } else {
                                selectedY + name
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }
            if (selectedY.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = normalize,
                        onClick = { normalize = !normalize },
                        label = { Text("Normalise") },
                    )
                    Text(
                        if (normalize) {
                            "Each metric is scaled to its own range."
                        } else {
                            "Raw values share one Y scale."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
            if (series.isEmpty()) {
                Text(
                    "Select at least one Y metric with two or more samples.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            } else {
                val chartSeries = series.map { InteractiveSeries(it.name, it.color, it.points) }
                InteractiveChart(
                    series = chartSeries,
                    style = InteractiveChartStyle.Line,
                    xTitle = fieldLabel(xField),
                    xValueFormatter = { value -> formatPlotX(value, xField) },
                    yValueFormatter = { value ->
                        if (useNormalized) {
                            "${(value * 100f).roundToInt()}%"
                        } else {
                            formatCompact(value.toDouble())
                        }
                    },
                    yRange = if (useNormalized) 0f..1f else null,
                    fill = chartSeries.size == 1,
                    title = "Cross-plot",
                    accessibility = "Cross-plot. X ${fieldLabel(xField)}. " +
                        series.joinToString("; ") { "${it.name}: ${it.count} samples" },
                    fullscreenLegend = if (chartSeries.size > 1) {
                        series.map {
                            "${it.name} ${formatCompact(it.minValue)}–${formatCompact(it.maxValue)}" to it.color
                        }
                    } else {
                        emptyList()
                    },
                )
                MessagePlotLegend(series)
                if (useNormalized) {
                    Text(
                        "Multiple metrics are normalised to 0–100% of their own range so they can share one graph. The legend shows each metric's real range.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseColors.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagePlotLegend(series: List<PlotSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(item.color),
                )
                Text(
                    item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.Text,
                    maxLines = 1,
                )
                Text(
                    "${formatCompact(item.minValue)}–${formatCompact(item.maxValue)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
                Text(
                    if (item.correlation.isNaN()) "r=n/a" else "r=%.2f".format(item.correlation),
                    style = MaterialTheme.typography.labelSmall,
                    color = PulseColors.TextMuted,
                )
            }
        }
    }
}

private fun fieldLabel(name: String): String = when (name) {
    "heart_rate" -> "heart rate (bpm)"
    "speed", "enhanced_speed" -> "speed (m/s)"
    "altitude", "enhanced_altitude" -> "altitude (m)"
    "cadence" -> "cadence (rpm)"
    "power" -> "power (W)"
    "temperature" -> "temperature (°C)"
    "distance" -> "distance (km)"
    "timestamp" -> "elapsed"
    else -> name.replace('_', ' ')
}

private fun formatPlotX(value: Float, xField: String): String =
    if (xField == "timestamp") {
        formatDuration(value.toDouble())
    } else {
        formatCompact(value.toDouble())
    }

@Composable
private fun FieldDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $selected", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: FitMessageEntity) {
    val fields = remember(message.fieldsJson) { JSONObject(message.fieldsJson) }
    val units = remember(message.unitsJson) { JSONObject(message.unitsJson) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row {
                Text(
                    message.messageName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PulseColors.Text,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    message.timestamp?.let { formatDate(it) } ?: "#${message.orderIndex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val text = fields.keys().asSequence().sorted().joinToString(" · ") { key ->
                val value = formatValue(fields.opt(key))
                val unit = units.optString(key).takeIf { it.isNotEmpty() }
                "$key=$value" + (unit?.let { " $it" } ?: "")
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = PulseColors.TextMuted)
        }
    }
}

private fun parseMessageFields(message: FitMessageEntity): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    val obj = JSONObject(message.fieldsJson)
    obj.keys().forEach { key ->
        val value = obj.opt(key)
        if (value is Number) out[key] = value.toDouble()
    }
    return out
}

private fun correlation(points: List<Pair<Double, Double>>): Double {
    if (points.size < 2) return Double.NaN
    val n = points.size
    val meanX = points.sumOf { it.first } / n
    val meanY = points.sumOf { it.second } / n
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    for ((x, y) in points) {
        val dx = x - meanX
        val dy = y - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    val denominator = sqrt(varianceX * varianceY)
    return if (denominator == 0.0) Double.NaN else covariance / denominator
}

internal fun relativeSync(timestamp: Long): String {
    val deltaSeconds = ((System.currentTimeMillis() - timestamp) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 60 -> "just now"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} min ago"
        deltaSeconds < 86_400 -> "${deltaSeconds / 3600} h ago"
        else -> "${deltaSeconds / 86_400} d ago"
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

internal fun formatDate(unixSeconds: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

internal fun formatDistance(meters: Double): String = "%.2f km".format(meters / 1000.0)

private fun formatCompact(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }

private fun formatValue(value: Any?): String = when (value) {
    null -> "—"
    is Double -> if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.3f".format(value)
    }
    is Float -> formatValue(value.toDouble())
    else -> value.toString()
}

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
