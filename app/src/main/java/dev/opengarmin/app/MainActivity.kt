package dev.opengarmin.app

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.app.sync.SyncCoordinator
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.ServiceId

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null
    private var coordinator: SyncCoordinator? = null
    private var syncAttempts = 0
    private var watchdog: Runnable? = null

    private val devices = mutableStateListOf<DeviceEntry>()
    private val logs = mutableStateListOf<String>()
    private var state by mutableStateOf("idle")
    private var lastFile by mutableStateOf("—")

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
            onFinished = { raw, decoded ->
                cancelWatchdog()
                state = "synced"
                lastFile = "${decoded.name} (${decoded.length()} bytes)"
                log("SYNC complete: raw=${raw.length()} decoded=${decoded.length()} (${decoded.name})")
            },
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen(
                        state = state,
                        lastFile = lastFile,
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

    private fun armWatchdog() {
        cancelWatchdog()
        val r = Runnable {
            val co = coordinator ?: return@Runnable
            if (co.isDone()) return@Runnable
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
        main.postDelayed(r, 90_000)
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
    devices: List<MainActivity.DeviceEntry>,
    logs: List<String>,
    onScan: () -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    deviceInfo: () -> List<Pair<String, String>>,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OpenGarmin", style = MaterialTheme.typography.headlineSmall)
        Text("state: $state", style = MaterialTheme.typography.bodyMedium)
        Text("last file: $lastFile", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSync) { Text("Sync") }
            OutlinedButton(onClick = onScan) { Text("Scan") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Device", style = MaterialTheme.typography.titleMedium)
                deviceInfo().forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            }
        }

        Text("Devices", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(devices.toList()) { entry ->
                Text(
                    text = entry.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
                OutlinedButton(onClick = { onConnect(entry.device) }) { Text("Connect") }
            }
        }

        Text("Log", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(logs) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
