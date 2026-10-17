package dev.opengarmin.app.sync

import android.os.Handler
import android.os.SystemClock
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
    private val onActivityProgress: (ActivitySyncProgress) -> Unit = {},
) {
    private val gdi = GdiClient(session) { log(it) }
    private val gdxmlLookup = Gdi.GdxmlLookup()
    private val wellnessQueue = ArrayDeque<Gdi.ItemReference>()
    private var wellnessTransfer = false
    private var pulledWellnessType = ""
    private var inFlightWellness: Gdi.ItemReference? = null
    private val activityQueue = ArrayDeque<Gdi.ItemReference>()
    private val completedActivityUids = mutableSetOf<String>()
    private var pendingActivityItem: Gdi.ItemReference? = null
    private var activityPullAttempts = 0
    private var wellnessPullAttempts = 0
    private var activityPullNotBefore = 0L
    private var activitiesUpToDate = false
    private var activityDone = 0
    private var activityTotal = 0

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
            // The watch reopens the GDI session after a transport pipe closes.
            // Give it a moment, then prefer a queued activity over more wellness.
            if (pendingActivityItem != null && !done) {
                // If a wellness transfer was interrupted by the session
                // restart, put it back in the queue but let the activity go
                // first once the session is ready.
                inFlightWellness?.let { retry ->
                    inFlightWellness = null
                    wellnessTransfer = false
                    wellnessQueue.addFirst(retry)
                }
                activityPullNotBefore = SystemClock.uptimeMillis() + READY_GRACE_MS
                handler.postDelayed({
                    if (g == generation) tryStartActivityPull()
                }, READY_GRACE_MS)
            } else {
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
        gdxmlLookup.clear()
        wellnessQueue.clear()
        wellnessTransfer = false
        pulledWellnessType = ""
        inFlightWellness = null
        activityQueue.clear()
        completedActivityUids.clear()
        pendingActivityItem = null
        activityPullAttempts = 0
        wellnessPullAttempts = 0
        activityPullNotBefore = 0L
        activitiesUpToDate = false
        activityDone = 0
        activityTotal = 0
        done = false
        reportActivityProgress()
    }

    fun isDone(): Boolean = done

    /** True once the watch has sent any GDI message (i.e. a sync is underway). */
    fun hasStarted(): Boolean = started

    /** True while a file is being pulled through the transport pipe. */
    fun isTransferring(): Boolean = pipe != null || wellnessTransfer

    private fun reportActivityProgress() {
        onActivityProgress(
            ActivitySyncProgress(
                done = activityDone,
                total = activityTotal,
                currentSize = pendingActivityItem?.dataSize,
            ),
        )
    }

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
        gdxmlLookup.learn(notification.items)
        log(
            "GDI: items added " + notification.items.joinToString {
                "${gdxmlLookup.typeOf(it) ?: "unknown"}(${it.dataSize})"
            },
        )
        wellnessQueue.addAll(notification.items)
        if (pipe == null && !wellnessTransfer) pullNextWellness()
    }

    private fun pullNextWellness() {
        if (wellnessTransfer || pipe != null) return
        if (pendingActivityItem != null && !done && listReceived) {
            tryStartActivityPull()
            return
        }
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
            } else {
                // The activity pull may have been deferred while wellness was
                // using the watch's single transfer slot.
                tryStartActivityPull()
            }
            return
        }
        wellnessTransfer = true
        pulledWellnessType = gdxmlLookup.typeOf(item) ?: "unknown"
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
        // The watch omits the gdxml string on repeat items of the same type and
        // sends only its numeric key, so learn the pairs before classifying.
        gdxmlLookup.learn(list.items)
        log(
            "FILE LIST status=${list.status} session=${list.sessionId} " +
                "nextTx=${list.nextTransactionId} items=${list.items.size}",
        )
        if (list.items.size > 1) {
            list.items.forEach { item ->
                val dt = item.dataType
                log(
                    "  item uid=${item.uid?.hex()} fmt=${dt?.format} key=${dt?.gdxmlStringKey} " +
                        "str=${dt?.gdxmlString} resolved=${gdxmlLookup.typeOf(item)} " +
                        "size=${item.dataSize} tx=${item.transactionId}",
                )
            }
        }
        if (list.items.size <= 1) return
        if (requestedPull || (pipe != null && !wellnessTransfer)) {
            log("SYNC: activity transfer already active; keeping the queued activity")
            return
        }

        val activityItems = list.items.filter { gdxmlLookup.typeOf(it) == "FIT_TYPE_4" }
        val newActivityItems = activityItems.filter { item ->
            val uid = item.uid?.hex() ?: return@filter true
            uid !in completedActivityUids && !isItemKnown(uid)
        }
        if (activityItems.isEmpty() || newActivityItems.isEmpty()) {
            activitiesUpToDate = true
            activityQueue.clear()
            pendingActivityItem = null
            activityTotal = activityDone
            reportActivityProgress()
            if (wellnessQueue.isNotEmpty() || wellnessTransfer) {
                log("SYNC: activities up to date; finishing wellness transfers")
                pullNextWellness()
                return
            }
            done = true
            log(
                if (activityItems.isEmpty()) {
                    "SYNC up to date: no activity files on the watch"
                } else {
                    "SYNC up to date: ${activityItems.size} activity item(s) already imported"
                },
            )
            onUpToDate()
            return
        }

        activitiesUpToDate = false
        // Keep any activity that is already queued/pending; add unseen items
        // from this list. This prevents re-downloading the activity currently
        // being imported when the list is refreshed.
        val inFlightUid = pendingActivityItem?.uid?.hex()
        newActivityItems.forEach { candidate ->
            val uid = candidate.uid?.hex() ?: return@forEach
            if (uid != inFlightUid && activityQueue.none { it.uid?.hex() == uid }) {
                activityQueue.add(candidate)
            }
        }
        if (pendingActivityItem == null) {
            pendingActivityItem = activityQueue.removeFirstOrNull()
        }
        val item = pendingActivityItem ?: return
        pulledItemUid = item.uid?.hex().orEmpty()
        activityTotal = activityDone + activityQueue.size + 1
        reportActivityProgress()
        log(
            "PULL queued uid=${item.uid?.hex()} type=${gdxmlLookup.typeOf(item)} " +
                "size=${item.dataSize} new=${newActivityItems.size}/${activityItems.size} " +
                "queued=${activityQueue.size}",
        )
        if (wellnessTransfer || pipe != null) {
            log("SYNC: activity pull deferred until the wellness transfer finishes")
            return
        }
        activityPullNotBefore = SystemClock.uptimeMillis() + LIST_GRACE_MS
        tryStartActivityPull()
    }

    /**
     * Pull the queued activity only when no wellness transfer or transport pipe
     * is active. The watch has one transfer slot and answers an overlapping
     * request with NO_AVAILABLE_RESOURCES (3).
     */
    private fun tryStartActivityPull() {
        if (done || requestedPull || wellnessTransfer || pipe != null) return
        val item = pendingActivityItem ?: return
        val now = SystemClock.uptimeMillis()
        if (now < activityPullNotBefore) {
            handler.postDelayed({ tryStartActivityPull() }, activityPullNotBefore - now)
            return
        }
        requestedPull = true
        pulledWellnessType = ""
        reportActivityProgress()
        val sentGeneration = generation
        log(
            "PULL request uid=${item.uid?.hex()} type=${gdxmlLookup.typeOf(item)} " +
                "size=${item.dataSize} attempt=${activityPullAttempts + 1}",
        )
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
        // If the watch does not answer (e.g. the request raced with a session
        // restart), retry rather than waiting for the outer watchdog.
        handler.postDelayed({
            if (sentGeneration == generation && !done && requestedPull && pipe == null && !wellnessTransfer) {
                log("PULL timeout waiting for the watch; retrying activity")
                requestedPull = false
                activityPullNotBefore = SystemClock.uptimeMillis() + READY_GRACE_MS
                tryStartActivityPull()
            }
        }, PULL_TIMEOUT_MS)
    }

    private fun handlePullResponse(pr: Gdi.PullItemResponse) {
        log(
            "PULL response result=${pr.result} transport=${pr.transport} " +
                "handle=${pr.transferHandle} window=${pr.compressionWindow}",
        )
        if (pr.result != 0 || pr.transferHandle == 0 || pipe != null) {
            if (pr.result != 0) {
                log("PULL failed: ${pullResultName(pr.result)} (${pr.result})")
            }
            if (pulledWellnessType.isNotEmpty()) {
                val retry = inFlightWellness
                pulledWellnessType = ""
                wellnessTransfer = false
                inFlightWellness = null
                if (pr.result == RESULT_NO_RESOURCES && retry != null && wellnessPullAttempts < MAX_PULL_RETRIES) {
                    wellnessPullAttempts++
                    wellnessQueue.addFirst(retry)
                    handler.postDelayed({ pullNextWellness() }, RETRY_DELAY_MS)
                }
            } else {
                requestedPull = false
                if (pr.result == RESULT_NO_RESOURCES && activityPullAttempts < MAX_PULL_RETRIES) {
                    activityPullAttempts++
                    handler.postDelayed({ tryStartActivityPull() }, RETRY_DELAY_MS)
                }
            }
            return
        }
        activityPullAttempts = 0
        wellnessPullAttempts = 0

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
                        if (pendingActivityItem != null && !done) {
                            activityPullNotBefore = SystemClock.uptimeMillis() + ACTIVITY_GRACE_MS
                        }
                        pullNextWellness()
                    } else {
                        pendingActivityItem = null
                        if (pulledItemUid.isNotEmpty()) completedActivityUids += pulledItemUid
                        activityDone++
                        onFinished(raw, decFile, pulledItemUid)
                        val next = activityQueue.removeFirstOrNull()
                        if (next != null) {
                            pendingActivityItem = next
                            pulledItemUid = next.uid?.hex().orEmpty()
                            requestedPull = false
                            activitiesUpToDate = false
                            done = false
                            activityTotal = activityDone + activityQueue.size + 1
                            activityPullNotBefore = SystemClock.uptimeMillis() + ACTIVITY_GRACE_MS
                            log(
                                "SYNC: next activity queued uid=${next.uid?.hex()} " +
                                    "type=${gdxmlLookup.typeOf(next)} remaining=${activityQueue.size}",
                            )
                            reportActivityProgress()
                            tryStartActivityPull()
                        } else {
                            activityTotal = activityDone
                            reportActivityProgress()
                            done = true
                            if (wellnessQueue.isNotEmpty()) pullNextWellness()
                        }
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
        private const val RESULT_NO_RESOURCES = 3
        private const val MAX_PULL_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val ACTIVITY_GRACE_MS = 2_500L
        private const val READY_GRACE_MS = 800L
        private const val LIST_GRACE_MS = 400L
        private const val PULL_TIMEOUT_MS = 8_000L

        private fun pullResultName(result: Int): String = when (result) {
            0 -> "SUCCESS"
            1 -> "ITEM_TYPE_NOT_SUPPORTED"
            2 -> "NO_SUPPORTED_TRANSPORT"
            3 -> "NO_AVAILABLE_RESOURCES"
            4 -> "INTERNAL_ERROR"
            5 -> "INVALID_PARAMS"
            6 -> "INVALID_OFFSET"
            7 -> "AMBIGUOUS_DATA_TYPE"
            8 -> "UNKNOWN_ITEM"
            9 -> "NO_SPACE"
            10 -> "DATA_TYPE_ERROR"
            11 -> "CONFLICTING_PUSH_REQUEST"
            else -> "UNKNOWN"
        }

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
