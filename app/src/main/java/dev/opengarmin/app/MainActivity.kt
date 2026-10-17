package dev.opengarmin.app

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.coach.ChatStore
import dev.opengarmin.app.coach.CoachController
import dev.opengarmin.app.coach.GpsSummary
import dev.opengarmin.app.coach.RepositoryHealthDataSource
import dev.opengarmin.app.coach.RunningDynamicsSummary
import dev.opengarmin.app.coach.ToolScope
import dev.opengarmin.app.coach.tools.ToolRegistry
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
import dev.opengarmin.app.data.FitMessageEntity
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.data.WellnessDayEntity
import dev.opengarmin.app.data.WellnessSampleEntity
import dev.opengarmin.app.llm.ProviderStore
import dev.opengarmin.app.settings.PulseSettings
import dev.opengarmin.app.sync.ActivitySyncProgress
import dev.opengarmin.app.sync.SyncCoordinator
import dev.opengarmin.app.ui.PulseApp
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseTheme
import dev.opengarmin.protocol.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null
    private var coordinator: SyncCoordinator? = null
    private var syncAttempts = 0
    private var watchdog: Runnable? = null
    private lateinit var repository: ActivityRepository
    private lateinit var settings: PulseSettings
    private lateinit var providerStore: ProviderStore
    private lateinit var coachController: CoachController
    private lateinit var healthDataSource: RepositoryHealthDataSource

    private val devices = mutableStateListOf<DeviceEntry>()
    private val logs = mutableStateListOf<String>()
    private val activities = mutableStateListOf<ActivityEntity>()
    private val wellnessDays = mutableStateListOf<WellnessDayEntity>()
    private var selectedActivity by mutableStateOf<ActivityEntity?>(null)
    private var selectedRecords by mutableStateOf<List<RecordEntity>>(emptyList())
    private var selectedLaps by mutableStateOf<List<LapEntity>>(emptyList())
    private var selectedDynamics by mutableStateOf<RunningDynamicsSummary?>(null)
    private var selectedGps by mutableStateOf<GpsSummary?>(null)
    private var selectedWellnessDay by mutableStateOf<WellnessDayEntity?>(null)
    private var wellnessSamples by mutableStateOf<List<WellnessSampleEntity>>(emptyList())
    private var latestHrSamples by mutableStateOf<List<WellnessSampleEntity>>(emptyList())
    private var dataActivity by mutableStateOf<ActivityEntity?>(null)
    private var dataMessages by mutableStateOf<List<FitMessageEntity>>(emptyList())
    private var dataLoading by mutableStateOf(false)
    private var state by mutableStateOf("idle")
    private var lastFile by mutableStateOf("—")
    private var importing by mutableStateOf(false)
    private var activityProgress by mutableStateOf<ActivitySyncProgress?>(null)
    private var lastImportedLabel by mutableStateOf<String?>(null)

    data class DeviceEntry(val device: BluetoothDevice, val rssi: Int) {
        val label: String get() = "${device.name ?: "(unnamed)"}  ${device.address}  ${rssi} dBm"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) client?.startScan() else log("permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        repository = ActivityRepository(AppDatabase.get(this).activityDao())
        settings = PulseSettings(this)
        providerStore = ProviderStore(this)
        healthDataSource = RepositoryHealthDataSource(repository)
        coachController = CoachController(
            scope = lifecycleScope,
            providerStore = providerStore,
            tools = ToolRegistry(healthDataSource),
            chatStore = ChatStore(this),
            settings = settings,
        )
        lifecycleScope.launch {
            repository.activities.collect { list ->
                activities.clear()
                activities.addAll(list)
            }
        }
        lifecycleScope.launch {
            repository.wellnessDays.collect { list ->
                wellnessDays.clear()
                wellnessDays.addAll(list)
                val latest = list.firstOrNull()
                if (latest != null) {
                    val samples = withContext(Dispatchers.IO) { repository.wellnessSamples(latest.id) }
                    latestHrSamples = samples
                } else {
                    latestHrSamples = emptyList()
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
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
                            activityProgress = null
                            lastImportedLabel = null
                            settings.recordSyncStarted()
                            client?.findBondedWatch()?.name?.let { settings.updateWatchName(it) }
                            armWatchdog()
                        }
                        "failed" -> {
                            settings.recordSyncError("Sync failed")
                            cancelWatchdog()
                        }
                        "disconnected", "reconnecting" -> cancelWatchdog()
                    }
                }
            },
        )
        client = c

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
            onFinished = { raw, decoded, itemUid ->
                cancelWatchdog()
                lastFile = "${decoded.name} (${decoded.length()} bytes)"
                log("SYNC complete: raw=${raw.length()} decoded=${decoded.length()} (${decoded.name})")
                importFit(decoded, itemUid)
            },
            onWellnessFile = { decoded, type ->
                log("WELLNESS file $type: ${decoded.name} (${decoded.length()} bytes)")
                importWellness(decoded, type)
            },
            onUpToDate = {
                cancelWatchdog()
                activityProgress = null
                state = "up to date"
                settings.recordSyncSuccess()
                log("SYNC up to date: all activity files already imported")
            },
            onActivityProgress = { progress ->
                activityProgress = progress.takeIf { it.total > 0 && it.done < it.total }
                if (progress.total > 0 && progress.done < progress.total) state = "syncing"
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
                    selectedActivity = selectedActivity,
                    selectedRecords = selectedRecords,
                    selectedLaps = selectedLaps,
                    selectedDynamics = selectedDynamics,
                    selectedGps = selectedGps,
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
                    onSync = { syncNow() },
                    onScan = { startScan() },
                    onStop = { client?.stopScan() },
                    onDisconnect = {
                        cancelWatchdog()
                        client?.disconnect()
                    },
                    onSelectActivity = { selectActivity(it) },
                    onClearSelection = {
                        selectedActivity = null
                        selectedRecords = emptyList()
                        selectedLaps = emptyList()
                        selectedDynamics = null
                        selectedGps = null
                    },
                    onExploreData = { openDataExplorer(it) },
                    onCloseData = { closeDataExplorer() },
                )
            }
        }

        if (c.hasPermissions()) {
            main.post {
                val watch = c.findBondedWatch()
                if (watch != null) {
                    syncAttempts = 0
                    settings.updateWatchName(watch.name ?: "")
                    log("auto-connecting to bonded ${watch.name}")
                    c.connect(watch)
                } else {
                    log("no bonded Garmin watch found")
                }
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
                settings.recordSyncSuccess()
                if (entity == null) {
                    log("FIT has no session or was already imported")
                } else {
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
                settings.recordSyncError("FIT import failed")
                log("FIT import failed: ${e.message}")
            }
        }
    }

    private fun importWellness(file: File, type: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importWellness(file.readBytes()) }
            }
            result.onSuccess { day ->
                if (day == null) {
                    log("WELLNESS $type contained no daily samples")
                } else {
                    log(
                        "WELLNESS imported ${day.date}: ${day.steps} steps, " +
                            "%.2f km, resting HR ${day.restingHeartRate ?: "—"}".format(
                                day.distanceMeters / 1000.0,
                            ),
                    )
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
        selectedDynamics = null
        selectedGps = null
        lifecycleScope.launch {
            val (records, laps, detail) = withContext(Dispatchers.IO) {
                Triple(
                    repository.records(activity.id),
                    repository.laps(activity.id),
                    healthDataSource.workout(ToolScope(), activity.id),
                )
            }
            if (selectedActivity?.id == activity.id) {
                selectedRecords = records
                selectedLaps = laps
                selectedDynamics = detail?.dynamics
                selectedGps = detail?.gps
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
            if (syncAttempts < 5) {
                syncAttempts++
                log("sync stalled; reconnecting ($syncAttempts/5)")
                state = "reconnecting"
                client?.reconnect()
            } else {
                log("sync failed after $syncAttempts attempts")
                state = "failed"
            }
        }
        watchdog = r
        main.postDelayed(r, timeoutMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    private fun syncNow() {
        val c = client ?: return
        val watch = c.findBondedWatch()
        if (watch == null) {
            log("no bonded Garmin watch")
            return
        }
        syncAttempts = 0
        cancelWatchdog()
        activityProgress = null
        lastImportedLabel = null
        settings.updateWatchName(watch.name ?: "")
        coordinator?.reset()
        c.connect(watch)
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
        client?.disconnect()
        super.onDestroy()
    }
}

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
                val today = wellnessDays.first()
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

@Composable
internal fun RouteMap(records: List<RecordEntity>, modifier: Modifier = Modifier) {
    val points = remember(records) {
        records.mapNotNull { record ->
            val lat = record.latitude ?: return@mapNotNull null
            val lon = record.longitude ?: return@mapNotNull null
            GeoPoint(lat, lon)
        }
    }
    if (points.size < 2) {
        Text(
            "No GPS track in this activity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val context = LocalContext.current
    val mapView = remember(records) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val line = Polyline().apply {
                setPoints(points)
                outlinePaint.color = 0xFF2E7D32.toInt()
                outlinePaint.strokeWidth = 10f
            }
            overlays.add(line)
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
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().weight(1f))
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
    var selectedType by remember { mutableStateOf("record") }
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
                    Text("All data", style = MaterialTheme.typography.titleLarge)
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
                    Text("Loading FIT messages…")
                }
            }
        } else if (messages.isEmpty()) {
            item {
                Text(
                    "No stored FIT messages for this activity. Re-import it to populate the data explorer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            item { Text("Message types", style = MaterialTheme.typography.titleMedium) }
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
                    Text("Raw messages", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun MessagePlotSection(messages: List<FitMessageEntity>) {
    val rows = remember(messages) { messages.map { parseMessageFields(it) } }
    val fieldNames = remember(rows) {
        val names = LinkedHashSet<String>()
        rows.take(500).forEach { names.addAll(it.keys) }
        names.sorted()
    }
    if (fieldNames.size < 2) {
        Text("Not enough numeric fields to plot.", style = MaterialTheme.typography.bodySmall)
        return
    }

    val preferred = listOf(
        "heart_rate", "enhanced_speed", "speed", "enhanced_altitude", "altitude",
        "cadence", "power", "temperature", "distance",
    )
    val availableSeries = preferred.filter { it in fieldNames }
    var selectedSeries by remember(rows) { mutableStateOf(availableSeries.take(4)) }
    var xField by remember(rows) {
        mutableStateOf(if ("distance" in fieldNames) "distance" else fieldNames.first())
    }
    var yField by remember(rows) {
        mutableStateOf(if ("heart_rate" in fieldNames) "heart_rate" else fieldNames.getOrElse(1) { fieldNames.first() })
    }

    Text("Cross-plot", style = MaterialTheme.typography.titleMedium)
    if (availableSeries.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(availableSeries) { name ->
                FilterChip(
                    selected = name in selectedSeries,
                    onClick = {
                        selectedSeries = if (name in selectedSeries) {
                            selectedSeries - name
                        } else {
                            selectedSeries + name
                        }
                    },
                    label = { Text(name) },
                )
            }
        }
        val series = selectedSeries.mapNotNull { name ->
            val values = rows.mapNotNull { it[name] }
            if (values.size >= 2) name to values else null
        }
        MultiSeriesChart(series)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldDropdown("X", fieldNames, xField) { xField = it }
        FieldDropdown("Y", fieldNames, yField) { yField = it }
    }

    val points = rows.mapNotNull { row ->
        val x = row[xField] ?: return@mapNotNull null
        val y = row[yField] ?: return@mapNotNull null
        x to y
    }
    ScatterChart(points)
    val r = correlation(points)
    Text(
        "$xField vs $yField · n=${points.size} · r=" +
            if (r.isNaN()) "n/a" else "%.3f".format(r),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MultiSeriesChart(series: List<Pair<String, List<Double>>>) {
    if (series.isEmpty()) {
        Text("Select at least one series.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val colors = listOf(
        Color(0xFF1E88E5),
        Color(0xFFE53935),
        Color(0xFF43A047),
        Color(0xFFFB8C00),
        Color(0xFF8E24AA),
        Color(0xFF00897B),
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        series.forEachIndexed { index, (_, values) ->
            if (values.size < 2) return@forEachIndexed
            val min = values.min()
            val max = values.max()
            val range = (max - min).coerceAtLeast(1e-9)
            val path = Path()
            values.forEachIndexed { i, value ->
                val x = size.width * i / (values.size - 1).toFloat()
                val y = size.height - ((value - min) / range * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = colors[index % colors.size], style = Stroke(width = 3f))
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(series.size) { index ->
            val (name, values) = series[index]
            val text = if (values.isNotEmpty()) {
                "● $name ${formatCompact(values.min())}–${formatCompact(values.max())}"
            } else {
                "● $name"
            }
            Text(
                text,
                color = colors[index % colors.size],
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ScatterChart(points: List<Pair<Double, Double>>) {
    if (points.size < 2) {
        Text("Not enough points for a scatter plot.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val xs = points.map { it.first }
    val ys = points.map { it.second }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val rangeX = (maxX - minX).coerceAtLeast(1e-9)
    val rangeY = (maxY - minY).coerceAtLeast(1e-9)
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        drawLine(Color.Gray, Offset(0f, 0f), Offset(0f, size.height), 1f)
        points.forEach { (x, y) ->
            val px = ((x - minX) / rangeX * size.width).toFloat()
            val py = size.height - ((y - minY) / rangeY * size.height).toFloat()
            drawCircle(color = color, radius = 3.5f, center = Offset(px, py))
        }
    }
    Row {
        Text("%.2f".format(minX), style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.weight(1f))
        Text("%.2f".format(maxX), style = MaterialTheme.typography.labelSmall)
    }
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
                Text(message.messageName, style = MaterialTheme.typography.titleSmall)
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
            Text(text, style = MaterialTheme.typography.bodySmall)
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
