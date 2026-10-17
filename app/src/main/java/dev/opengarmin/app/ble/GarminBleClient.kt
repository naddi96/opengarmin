package dev.opengarmin.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.Uuids

/**
 * Bridges Android BluetoothGatt to the pure-JVM [GarminSession].
 *
 * Discovery is minimal: find the Garmin Multi-Link service (6A4E2800), pick the
 * first read/write characteristic pair, enable notifications, and hand the raw
 * frames to the protocol session.
 */
class GarminBleClient(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onDeviceFound: (BluetoothDevice, Int) -> Unit,
    private val onStateChanged: (String) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val handler = Handler(Looper.getMainLooper())
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var writeGeneration = 0

    val session = GarminSession(clientId = 1L, sink = { bytes -> write(bytes) }, log = onLog)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDeviceFound(result.device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("scan failed: $errorCode")
        }
    }

    fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Returns the OS-bonded Garmin watch, if any (requires BLUETOOTH_CONNECT). */
    fun findBondedWatch(): BluetoothDevice? {
        val bonded = try {
            adapter?.bondedDevices
        } catch (e: SecurityException) {
            onLog("missing BLUETOOTH_CONNECT")
            null
        } ?: return null
        return bonded.firstOrNull { device ->
            val name = try { device.name } catch (e: SecurityException) { null } ?: return@firstOrNull false
            GARMIN_NAMES.any { name.contains(it, ignoreCase = true) }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onLog("no BLE scanner")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        onStateChanged("scanning")
        scanner.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        onStateChanged("idle")
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        onStateChanged("connecting")
        onLog("connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        onStateChanged("disconnected")
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeQueue) { writeQueue.addLast(bytes) }
        drainWrites()
    }

    private fun drainWrites() {
        val g = gatt ?: return
        val c = writeChar ?: return
        val next: ByteArray
        synchronized(writeQueue) {
            if (writeInFlight) return
            next = writeQueue.pollFirst() ?: return
            writeInFlight = true
        }
        val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val rc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, next, writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                c.value = next
                c.writeType = writeType
                g.writeCharacteristic(c)
            }
        }
        onLog("TX($rc) ${Hex.encode(next)}")
        if (rc != 0) {
            // Controller busy: put it back and retry shortly.
            synchronized(writeQueue) {
                writeQueue.addFirst(next)
                writeInFlight = false
            }
            handler.postDelayed({ drainWrites() }, 25)
            return
        }
        // Some stacks do not deliver onCharacteristicWrite for no-response
        // writes, so pace the queue even without a callback.
        val gen = ++writeGeneration
        handler.postDelayed({
            if (gen == writeGeneration) {
                synchronized(writeQueue) { writeInFlight = false }
                drainWrites()
            }
        }, 40)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChanged("connected")
                    onLog("connected, requesting MTU")
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStateChanged("disconnected")
                    onLog("disconnected")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onLog("MTU=$mtu")
            session.maxWriteSize = (mtu - 3).coerceAtLeast(20)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val mlService = g.getService(java.util.UUID.fromString(Uuids.MULTI_LINK))
            if (mlService == null) {
                onLog("Multi-Link service not found on this device")
                return
            }
            var pair: Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? = null
            for (i in Uuids.ML_READ.indices) {
                val recv = mlService.getCharacteristic(java.util.UUID.fromString(Uuids.ML_READ[i]))
                val send = mlService.getCharacteristic(java.util.UUID.fromString(Uuids.ML_WRITE[i]))
                if (recv != null && send != null) {
                    pair = recv to send
                    onLog("using pair ${Uuids.ML_READ[i]} / ${Uuids.ML_WRITE[i]}")
                    break
                }
            }
            val (recv, send) = pair ?: run {
                onLog("no ML characteristic pair found")
                return
            }
            writeChar = send
            g.setCharacteristicNotification(recv, true)
            val cccd = recv.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }
            if (cccd == null) {
                onLog("no CCCD descriptor; starting session anyway")
                session.begin()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onLog("notifications enabled (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("starting GFDI session")
                session.begin()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeGeneration++
            synchronized(writeQueue) { writeInFlight = false }
            if (status != BluetoothGatt.GATT_SUCCESS) onLog("write failed status=$status")
            drainWrites()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onLog("RX ${Hex.encode(value)}")
            session.onNotification(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                onLog("RX ${Hex.encode(value)}")
                session.onNotification(value)
            }
        }
    }

    companion object {
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
        private val GARMIN_NAMES = listOf(
            "Forerunner", "Garmin", "Fenix", "Venu", "Instinct", "Epix", "Enduro", "Vivoactive", "Vivomove",
        )
    }
}
