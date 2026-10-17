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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.AppDatabase
import dev.opengarmin.app.data.LapEntity
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.sync.SyncCoordinator
import dev.opengarmin.protocol.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null
    private var coordinator: SyncCoordinator? = null
    private var syncAttempts = 0
    private var watchdog: Runnable? = null
    private lateinit var repository: ActivityRepository

    private val devices = mutableStateListOf<DeviceEntry>()
    private val logs = mutableStateListOf<String>()
    private val activities = mutableStateListOf<ActivityEntity>()
    private var selectedActivity by mutableStateOf<ActivityEntity?>(null)
    private var selectedRecords by mutableStateOf<List<RecordEntity>>(emptyList())
    private var selectedLaps by mutableStateOf<List<LapEntity>>(emptyList())
    private var state by mutableStateOf("idle")
    private var lastFile by mutableStateOf("—")
    private var importing by mutableStateOf(false)

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

        repository = ActivityRepository(AppDatabase.get(this).activityDao())
        lifecycleScope.launch {
            repository.activities.collect { list ->
                activities.clear()
                activities.addAll(list)
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.backfillLaps(filesDir) }
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
                            armWatchdog()
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
            onUpToDate = {
                cancelWatchdog()
                state = "up to date"
                log("SYNC up to date: all activity files already imported")
            },
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen(
                        state = state,
                        lastFile = lastFile,
                        importing = importing,
                        activities = activities,
                        selectedActivity = selectedActivity,
                        selectedRecords = selectedRecords,
                        selectedLaps = selectedLaps,
                        devices = devices,
                        logs = logs,
                        onScan = { startScan() },
                        onSync = { syncNow() },
                        onStop = { client?.stopScan() },
                        onConnect = {
                            syncAttempts = 0
                            client?.connect(it)
                        },
                        onDisconnect = {
                            cancelWatchdog()
                            client?.disconnect()
                        },
                        onSelect = { selectActivity(it) },
                        onClearSelection = {
                            selectedActivity = null
                            selectedRecords = emptyList()
                            selectedLaps = emptyList()
                        },
                        deviceInfo = {
                            val s = c.session
                            listOf(
                                "GFDI handle" to if (s.gfdiHandle >= 0) "${s.gfdiHandle}" else "—",
                                "product" to if (s.productNumber != 0) "${s.productNumber}" else "—",
                                "firmware" to if (s.firmwareVersion != 0) "${s.firmwareVersion}" else "—",
                                "unit id" to if (s.unitId != 0L) "${s.unitId}" else "—",
                                "services mask" to "0x%06x".format(s.supportedServicesMask),
                                "sync attempts" to "$syncAttempts",
                            )
                        },
                    )
                }
            }
        }

        if (c.hasPermissions()) {
            main.post {
                val watch = c.findBondedWatch()
                if (watch != null) {
                    syncAttempts = 0
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
            state = "synced"
            result.onSuccess { entity ->
                if (entity == null) {
                    log("FIT has no session or was already imported")
                } else {
                    log(
                        "IMPORTED ${formatDate(entity.startTime)} ${sportName(entity.sport)} " +
                            "%.2f km".format(entity.distanceMeters / 1000.0),
                    )
                }
            }.onFailure { e ->
                log("FIT import failed: ${e.message}")
            }
        }
    }

    private fun selectActivity(activity: ActivityEntity) {
        selectedActivity = activity
        selectedRecords = emptyList()
        selectedLaps = emptyList()
        lifecycleScope.launch {
            val (records, laps) = withContext(Dispatchers.IO) {
                repository.records(activity.id) to repository.laps(activity.id)
            }
            if (selectedActivity?.id == activity.id) {
                selectedRecords = records
                selectedLaps = laps
            }
        }
    }

    private fun armWatchdog(timeoutMs: Long = 15_000) {
        cancelWatchdog()
        val r = Runnable {
            val co = coordinator ?: return@Runnable
            if (co.isDone()) return@Runnable
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
    devices: List<MainActivity.DeviceEntry>,
    logs: List<String>,
    onScan: () -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSelect: (ActivityEntity) -> Unit,
    onClearSelection: () -> Unit,
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

        if (selectedActivity != null) {
            ActivityDetail(
                activity = selectedActivity,
                records = selectedRecords,
                laps = selectedLaps,
                onBack = onClearSelection,
                modifier = Modifier.weight(1f),
            )
        } else {
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
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

        Text("Heart rate", style = MaterialTheme.typography.titleMedium)
        HeartRateChart(records)

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
private fun HeartRateChart(records: List<RecordEntity>) {
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
