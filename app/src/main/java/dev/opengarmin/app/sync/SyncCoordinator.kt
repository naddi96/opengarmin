package dev.opengarmin.app.sync

import android.os.Handler
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Handshake
import dev.opengarmin.protocol.TransportPipe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Drives one sync attempt: capabilities -> item list -> pull one file -> pipe.
 *
 * A new instance is created per connection, or [reset] is called before each
 * attempt. The owning activity runs a watchdog and reconnects if [isDone] is
 * still false after a timeout.
 */
class SyncCoordinator(
    private val session: GarminSession,
    private val filesDir: File,
    private val handler: Handler,
    private val log: (String) -> Unit,
    private val onFinished: (raw: File, decoded: File) -> Unit,
) {
    private val gdi = GdiClient(session) { log(it) }

    private var capabilitiesReceived = false
    private var listRequested = false
    private var listReceived = false
    private var requestedPull = false
    private var pipe: TransportPipe? = null
    private var output: FileOutputStream? = null
    private var generation = 0
    private var done = false

    init {
        gdi.onSmart = { smart -> handler.post { handleSmart(smart) } }
        session.onServiceRegistered = { svc, h, _ ->
            if (svc == PIPE_SERVICE) pipe?.onRegistered(h)
        }
        session.onServiceClosed = { svc ->
            if (svc == PIPE_SERVICE) pipe?.finish()
        }
        session.onMlrFrame = { frame -> pipe?.onFrame(frame) }
        session.onReady = {
            val g = generation
            handler.postDelayed({
                if (g == generation) {
                    log("GDI: DEVICE_SETTINGS + SYNC_READY")
                    session.sendGfdi(Handshake.deviceSettings())
                    session.sendGfdi(Handshake.systemEventSyncReady())
                }
            }, 300)
            handler.postDelayed({
                if (g == generation && !capabilitiesReceived) sendCapabilities()
            }, 1500)
        }
    }

    fun reset() {
        generation++
        closeOutput()
        pipe = null
        capabilitiesReceived = false
        listRequested = false
        listReceived = false
        requestedPull = false
        done = false
    }

    fun isDone(): Boolean = done

    private fun closeOutput() {
        try {
            output?.flush()
            output?.close()
        } catch (_: Exception) {
        }
        output = null
    }

    private fun handleSmart(smart: ByteArray) {
        val fields = Gdi.smartFields(smart)
        log("GDI smart fields=${fields.keys} sizes=${fields.mapValues { it.value.size }}")

        if (Gdi.coreFeatureCapabilitiesResponse(smart) != null) onCapabilities()

        val fa = fields[Gdi.SMART_FILE_ACCESS] ?: return
        try {
            val svc = Gdi.parseFileAccessService(fa)
            val list = svc.itemListResponse
            val pull = svc.pullItemResponse
            if (list != null) handleItemList(list)
            if (pull != null) handlePullResponse(pull)
        } catch (e: Exception) {
            log("GDI parse error: ${e.message}")
        }
    }

    private fun sendCapabilities() {
        if (capabilitiesReceived || done) return
        log("GDI → FeatureCapabilitiesRequest")
        gdi.sendSmart(Gdi.featureCapabilitiesRequest()) { response ->
            if (Gdi.coreFeatureCapabilitiesResponse(response) != null) {
                handler.post { onCapabilities() }
            }
        }
    }

    private fun onCapabilities() {
        if (capabilitiesReceived) return
        capabilitiesReceived = true
        log("GDI: capabilities received")
        requestList()
    }

    private fun requestList() {
        if (listReceived || listRequested || done) return
        listRequested = true
        log("GDI → ItemListRequest")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.itemListRequest(maxItemCount = 100))) { response ->
            handler.post { handleSmart(response) }
        }
    }

    private fun handleItemList(list: Gdi.ItemListResponse) {
        listReceived = true
        log(
            "FILE LIST status=${list.status} session=${list.sessionId} " +
                "nextTx=${list.nextTransactionId} items=${list.items.size}",
        )
        if (list.items.size > 1) {
            list.items.forEach { item ->
                val dt = item.dataType
                log(
                    "  item uid=${item.uid?.hex()} fmt=${dt?.format} str=${dt?.gdxmlString} " +
                        "size=${item.dataSize} tx=${item.transactionId}",
                )
            }
        }
        if (requestedPull || list.items.size <= 1) return
        requestedPull = true

        val item = list.items.firstOrNull { it.dataType?.gdxmlString == "FIT_TYPE_4" }
            ?: list.items.firstOrNull { it.dataType?.gdxmlString?.startsWith("FIT_TYPE") == true }
            ?: list.items.minByOrNull { it.dataSize }
            ?: list.items.first()

        log("PULL request uid=${item.uid?.hex()} type=${item.dataType?.gdxmlString} size=${item.dataSize}")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
    }

    private fun handlePullResponse(pr: Gdi.PullItemResponse) {
        log(
            "PULL response result=${pr.result} transport=${pr.transport} " +
                "handle=${pr.transferHandle} window=${pr.compressionWindow}",
        )
        if (pr.result != 0 || pr.transferHandle == 0 || pipe != null || done) return

        val raw = File(filesDir, "download_${pr.transferHandle}.bin")
        val out = FileOutputStream(raw)
        output = out

        val p = TransportPipe(
            session = session,
            serviceId = PIPE_SERVICE,
            transferHandle = pr.transferHandle,
            expectedSize = 0, // read until the device closes the pipe
            onData = { data -> out.write(data) },
            onComplete = {
                closeOutput()
                val compressed = raw.readBytes()
                val decoded = inflateOrRaw(compressed)
                val decFile = File(filesDir, "download_${pr.transferHandle}.dec")
                decFile.writeBytes(decoded)
                done = true
                handler.post {
                    log("DOWNLOAD raw=${compressed.size} decoded=${decoded.size} -> ${decFile.absolutePath}")
                    onFinished(raw, decFile)
                }
            },
            onError = { msg -> handler.post { log("PIPE error: $msg") } },
            log = { msg -> log(msg) },
        )
        pipe = p
        p.start()
    }

    companion object {
        const val PIPE_SERVICE = 0x2018

        fun inflateOrRaw(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            try {
                return InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            } catch (_: Exception) {
            }
            try {
                return InflaterInputStream(ByteArrayInputStream(data), Inflater(true)).use { it.readBytes() }
            } catch (_: Exception) {
            }
            return data
        }
    }
}
