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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opengarmin.app.ble.GarminBleClient
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Handshake
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.ServiceId

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GarminBleClient? = null

    private val devices = mutableStateListOf<DeviceEntry>()
    private val logs = mutableStateListOf<String>()
    private var state by mutableStateOf("idle")

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
            onStateChanged = { s -> main.post { state = s } },
        )
        client = c
        c.session.onGfdiMessage = { frame ->
            main.post {
                val payload = Hex.encode(frame.payload)
                val shown = if (payload.length <= 120) payload else payload.take(120) + "…"
                log("GFDI type=${frame.type} seq=${frame.seq} len=${frame.payload.size} $shown")
            }
        }

        val gdi = GdiClient(c.session) { log(it) }
        var capabilitiesReceived = false
        var listReceived = false
        var capabilityAttempts = 0
        var listAttempts = 0
        var requestedPull = false

        fun handleItemList(response: ByteArray) {
            val fields = Gdi.smartFields(response)
            val fa = fields[Gdi.SMART_FILE_ACCESS]
            if (fa == null) {
                log("GDI: response fields=${fields.keys} (no FileAccess)")
                return
            }
            val svc = Gdi.parseFileAccessService(fa)
            val list = svc.itemListResponse
            if (list == null) {
                log("GDI: FileAccess had no item_list_response")
                return
            }
            log("FILE LIST status=${list.status} session=${list.sessionId} nextTx=${list.nextTransactionId} items=${list.items.size}")
            list.items.forEach { item ->
                val dt = item.dataType
                log(
                    "  item uid=${item.uid?.hex()} fmt=${dt?.format} id=${dt?.gdxmlFileIdentifier} " +
                        "str=${dt?.gdxmlString} size=${item.dataSize} flags=${item.requestedFlags} tx=${item.transactionId}",
                )
            }

            if (!requestedPull && list.items.isNotEmpty()) {
                requestedPull = true
                val item = list.items.minByOrNull { it.dataSize } ?: list.items.first()
                log("PULL request uid=${item.uid?.hex()} size=${item.dataSize}")
                gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { resp ->
                    val fa = Gdi.smartFields(resp)[Gdi.SMART_FILE_ACCESS]
                    val pr = if (fa != null) Gdi.parseFileAccessService(fa).pullItemResponse else null
                    main.post {
                        if (pr != null) {
                            log(
                                "PULL response result=${pr.result} transport=${pr.transport} " +
                                    "handle=${pr.transferHandle} path=${pr.filePath} err=${pr.errorCode} window=${pr.compressionWindow}",
                            )
                        } else {
                            log("PULL response had no pull_item_response, fields=${Gdi.smartFields(resp).keys}")
                        }
                    }
                }
            }
        }

        fun requestList() {
            if (listReceived || listAttempts >= 5) return
            listAttempts++
            log("GDI → ItemListRequest (attempt $listAttempts)")
            gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.itemListRequest(maxItemCount = 100))) { response ->
                main.post { handleItemList(response) }
            }
            main.postDelayed({ if (!listReceived) requestList() }, 5000)
        }

        fun requestCapabilities() {
            if (capabilitiesReceived || capabilityAttempts >= 5) return
            capabilityAttempts++
            log("GDI → FeatureCapabilitiesRequest (attempt $capabilityAttempts)")
            gdi.sendSmart(Gdi.featureCapabilitiesRequest()) { response ->
                val caps = Gdi.coreFeatureCapabilitiesResponse(response)
                main.post {
                    if (caps != null) {
                        capabilitiesReceived = true
                        log("GDI: capabilities response ${caps.size} bytes")
                        requestList()
                    }
                }
            }
            main.postDelayed({ if (!capabilitiesReceived) requestCapabilities() }, 5000)
        }

        gdi.onSmart = { smart ->
            val fields = Gdi.smartFields(smart)
            main.post {
                log("GDI smart fields=${fields.keys} sizes=${fields.mapValues { it.value.size }}")
                if (Gdi.coreFeatureCapabilitiesResponse(smart) != null) {
                    capabilitiesReceived = true
                    requestList()
                }
                if (fields.containsKey(Gdi.SMART_FILE_ACCESS)) {
                    handleItemList(smart)
                }
            }
        }

        c.session.onReady = {
            main.postDelayed({ requestCapabilities() }, 500)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen(
                        state = state,
                        devices = devices,
                        logs = logs,
                        onScan = { startScan() },
                        onStop = { client?.stopScan() },
                        onConnect = { client?.connect(it) },
                        onDisconnect = { client?.disconnect() },
                        deviceInfo = {
                            val s = c.session
                            listOf(
                                "GFDI handle" to if (s.gfdiHandle >= 0) "${s.gfdiHandle}" else "—",
                                "product" to if (s.productNumber != 0) "${s.productNumber}" else "—",
                                "firmware" to if (s.firmwareVersion != 0) "${s.firmwareVersion}" else "—",
                                "unit id" to if (s.unitId != 0L) "${s.unitId}" else "—",
                                "services mask" to "0x%06x".format(s.supportedServicesMask),
                                "GFDI/REG/KEEP_ALIVE" to listOf(
                                    ServiceId.GFDI, ServiceId.REGISTRATION, ServiceId.KEEP_ALIVE,
                                ).joinToString(","),
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
                    log("auto-connecting to bonded ${watch.name}")
                    c.connect(watch)
                } else {
                    log("no bonded Garmin watch found")
                }
            }
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
        client?.disconnect()
        super.onDestroy()
    }
}

@Composable
private fun Screen(
    state: String,
    devices: List<MainActivity.DeviceEntry>,
    logs: List<String>,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    deviceInfo: () -> List<Pair<String, String>>,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OpenGarmin", style = MaterialTheme.typography.headlineSmall)
        Text("state: $state", style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) { Text("Scan") }
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
