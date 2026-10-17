package dev.opengarmin.app.sync

import android.os.Handler
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Handshake
import dev.opengarmin.protocol.Hex
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
    /** True when the given GDI item UID has already been imported. */
    private val isItemKnown: (String) -> Boolean,
    private val onFinished: (raw: File, decoded: File, itemUid: String) -> Unit,
    private val onWellnessFile: (decoded: File, type: String) -> Unit,
    private val onUpToDate: () -> Unit,
) {
    private val gdi = GdiClient(session) { log(it) }
    private val wellnessQueue = ArrayDeque<Gdi.ItemReference>()
    private var wellnessTransfer = false
    private var pulledWellnessType = ""
    private var inFlightWellness: Gdi.ItemReference? = null
    private var activitiesUpToDate = false

    private var capabilitiesRequested = false
    private var capabilitiesReceived = false
    private var listRequested = false
    private var listReceived = false
    private var requestedPull = false
    private var started = false
    private var pulledItemUid = ""
    private var cutResponseReceived = false
    private var pipe: TransportPipe? = null
    private var output: FileOutputStream? = null
    private var generation = 0
    private var done = false

    init {
        gdi.onSmart = { smart -> handler.post { handleSmart(smart) } }
        gdi.onRequest = { id, smart -> handler.post { handleDeviceRequest(id, smart) } }
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
            handler.post {
                val retry = inFlightWellness
                if (retry != null) {
                    inFlightWellness = null
                    wellnessTransfer = false
                    wellnessQueue.addFirst(retry)
                    handler.postDelayed({ pullNextWellness() }, 800)
                } else if (wellnessQueue.isNotEmpty() && !wellnessTransfer) {
                    handler.postDelayed({ pullNextWellness() }, 800)
                }
            }
        }
    }

    fun reset() {
        generation++
        closeOutput()
        pipe = null
        capabilitiesRequested = false
        capabilitiesReceived = false
        listRequested = false
        listReceived = false
        requestedPull = false
        started = false
        pulledItemUid = ""
        cutResponseReceived = false
        wellnessQueue.clear()
        wellnessTransfer = false
        pulledWellnessType = ""
        inFlightWellness = null
        activitiesUpToDate = false
        done = false
    }

    fun isDone(): Boolean = done

    /** True once the watch has sent any GDI message (i.e. a sync is underway). */
    fun hasStarted(): Boolean = started

    /** True while a file is being pulled through the transport pipe. */
    fun isTransferring(): Boolean = pipe != null || wellnessTransfer

    private fun closeOutput() {
        try {
            output?.flush()
            output?.close()
        } catch (_: Exception) {
        }
        output = null
    }

    private fun handleSmart(smart: ByteArray) {
        try {
            handleSmartInner(smart)
        } catch (e: Exception) {
            log("GDI parse error: ${e.message} (${Hex.encode(smart.take(32).toByteArray())})")
        }
    }

    /**
     * Answers the service requests the watch pushes when it opens a GDI
     * session. Ignoring them makes the watch retransmit its whole burst every
     * five seconds and refuse to stream the file list.
     */
    private fun handleDeviceRequest(id: Int, smart: ByteArray) {
        val fields = Gdi.smartFields(smart)
        val core = fields[Gdi.SMART_CORE]
        val sms = fields[Gdi.SMART_SMS_NOTIFICATION]
        val deviceMessages = fields[Gdi.SMART_DEVICE_MESSAGES]
        val audio = fields[Gdi.SMART_AUDIO_PROMPTS]
        val http = fields[Gdi.SMART_CONNECT_IQ_HTTP]

        val response = when {
            http != null && Gdi.field(http, 1) != null ->
                Gdi.connectIqHttpResponse()
            core != null && Gdi.field(core, 3) != null ->
                Gdi.getLocationResponse()
            core != null && Gdi.field(core, 5) != null ->
                Gdi.locationUpdatedSetEnabledResponse(Gdi.field(core, 5)!!)
            sms != null && Gdi.field(sms, 4) != null ->
                Gdi.smsCannedListResponse(Gdi.field(sms, 4)!!)
            deviceMessages != null && Gdi.field(deviceMessages, 3) != null ->
                Gdi.ciqMessageResponse(Gdi.field(deviceMessages, 3)!!)
            audio != null && Gdi.field(audio, 12) != null ->
                Gdi.audioSetLanguageResponse()
            else -> {
                log("GDI: unanswered device request id=0x%04x fields=${fields.keys}".format(id))
                null
            }
        }
        if (response != null) {
            log("GDI → device response id=0x%04x fields=${fields.keys}".format(id))
            gdi.sendResponse(id, response)
        }
    }

    private fun handleSmartInner(smart: ByteArray) {
        started = true
        val fields = Gdi.smartFields(smart)
        log("GDI smart fields=${fields.keys} sizes=${fields.mapValues { it.value.size }}")

        if (Gdi.coreFeatureCapabilitiesResponse(smart) != null) onCapabilities()
        if (Gdi.isWellnessThresholdMet(smart)) {
            log("GDI: wellness threshold met")
            listRequested = false
            listReceived = false
            handler.postDelayed({ requestList() }, 500)
        }
        if (Gdi.isConnectionReady(smart) && !capabilitiesReceived && !done) {
            // The watch opens a GDI session and waits for the capability
            // exchange before it will stream a full ItemListResponse. If it
            // never answers, fall back to asking for the list directly.
            log("GDI: connection ready")
            sendCapabilities()
            val g = generation
            handler.postDelayed({
                if (g == generation && !listRequested && !listReceived) requestList()
            }, 2500)
        }

        val fa = fields[Gdi.SMART_FILE_ACCESS] ?: return
        val svc = Gdi.parseFileAccessService(fa)
        val list = svc.itemListResponse
        val pull = svc.pullItemResponse
        svc.itemAdded?.let { handleItemAdded(it) }
        if (list != null) handleItemList(list)
        if (pull != null) handlePullResponse(pull)
    }

    private fun handleItemAdded(notification: Gdi.ItemAddedNotification) {
        if (notification.items.isEmpty()) return
        log(
            "GDI: items added " + notification.items.joinToString {
                "${it.dataType?.gdxmlString ?: "unknown"}(${it.dataSize})"
            },
        )
        wellnessQueue.addAll(notification.items)
        if (pipe == null && !wellnessTransfer) pullNextWellness()
    }

    private fun pullNextWellness() {
        if (wellnessTransfer || pipe != null) return
        val item = wellnessQueue.removeFirstOrNull()
        if (item == null) {
            if (activitiesUpToDate) {
                if (!done) {
                    done = true
                    log("SYNC up to date: activities and wellness synced")
                    onUpToDate()
                }
            } else if (!listReceived && !done) {
                requestList()
            }
            return
        }
        wellnessTransfer = true
        pulledWellnessType = item.dataType?.gdxmlString ?: "unknown"
        inFlightWellness = item
        log("WELLNESS PULL request type=$pulledWellnessType size=${item.dataSize} left=${wellnessQueue.size}")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
    }

    private fun sendCapabilities() {
        if (capabilitiesRequested || capabilitiesReceived || listReceived || done) return
        capabilitiesRequested = true
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
        sendCutWellnessThenList()
    }

    private fun sendCutWellnessThenList() {
        cutResponseReceived = false
        log("GDI → CutWellnessFilesRequest")
        gdi.sendSmart(Gdi.cutWellnessFilesRequest()) { response ->
            cutResponseReceived = true
            val status = Gdi.cutWellnessFilesStatus(response)
            handler.post {
                log("GDI: cut wellness status=$status")
                if (status == 0) {
                    // Give the watch time to finalize the wellness file, then
                    // ask for the directory again.
                    val g = generation
                    handler.postDelayed({
                        if (g == generation && !done && wellnessQueue.isEmpty()) {
                            listRequested = false
                            listReceived = false
                            requestList()
                        }
                    }, 2500)
                } else {
                    requestList()
                }
            }
        }
        // Fallback in case the watch does not answer the cut request.
        val g = generation
        handler.postDelayed({
            if (g == generation && !cutResponseReceived && !listRequested) requestList()
        }, 2000)
    }

    private fun requestList() {
        if (listReceived || listRequested || done) return
        listRequested = true
        log("GDI → ItemListRequest")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.itemListRequest(maxItemCount = 1000))) { response ->
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

        val activityItems = list.items.filter { it.dataType?.gdxmlString == "FIT_TYPE_4" }
        val newActivityItems = activityItems.filter { item ->
            val uid = item.uid?.hex() ?: return@filter true
            !isItemKnown(uid)
        }
        if (activityItems.isNotEmpty() && newActivityItems.isEmpty()) {
            activitiesUpToDate = true
            if (wellnessQueue.isNotEmpty() || wellnessTransfer) {
                log("SYNC: activities up to date; finishing wellness transfers")
                pullNextWellness()
                return
            }
            done = true
            log("SYNC up to date: ${activityItems.size} activity item(s) already imported")
            onUpToDate()
            return
        }

        activitiesUpToDate = false
        requestedPull = true
        pulledWellnessType = ""
        val item = newActivityItems.firstOrNull()
            ?: list.items.firstOrNull { it.dataType?.gdxmlString == "FIT_TYPE_4" }
            ?: list.items.firstOrNull { it.dataType?.gdxmlString?.startsWith("FIT_TYPE") == true }
            ?: list.items.minByOrNull { it.dataSize }
            ?: list.items.first()

        pulledItemUid = item.uid?.hex().orEmpty()
        log(
            "PULL request uid=${item.uid?.hex()} type=${item.dataType?.gdxmlString} " +
                "size=${item.dataSize} new=${newActivityItems.size}/${activityItems.size}",
        )
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
    }

    private fun handlePullResponse(pr: Gdi.PullItemResponse) {
        log(
            "PULL response result=${pr.result} transport=${pr.transport} " +
                "handle=${pr.transferHandle} window=${pr.compressionWindow}",
        )
        if (pr.result != 0 || pr.transferHandle == 0 || pipe != null) return

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
                pipe = null
                handler.post {
                    log("DOWNLOAD raw=${compressed.size} decoded=${decoded.size} -> ${decFile.absolutePath}")
                    if (pulledWellnessType.isNotEmpty()) {
                        val type = pulledWellnessType
                        pulledWellnessType = ""
                        wellnessTransfer = false
                        inFlightWellness = null
                        log("WELLNESS complete type=$type decoded=${decoded.size}")
                        onWellnessFile(decFile, type)
                        pullNextWellness()
                    } else {
                        done = true
                        onFinished(raw, decFile, pulledItemUid)
                        if (wellnessQueue.isNotEmpty()) pullNextWellness()
                    }
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
