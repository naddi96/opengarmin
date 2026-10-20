package dev.opengarmin.app.sync

import android.os.Handler
import android.os.SystemClock
import dev.opengarmin.protocol.FitPaceBand
import dev.opengarmin.protocol.FitWorkout
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Crc16
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Gfdi
import dev.opengarmin.protocol.GfdiFileUpload
import dev.opengarmin.protocol.Handshake
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.TransportPipe
import dev.opengarmin.app.data.WellnessFileTypes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
    /** True when a non-activity wellness file (sleep/HRV/monitoring) is known. */
    private val isWellnessItemKnown: (String) -> Boolean = { false },
    private val onFinished: (raw: File, decoded: File, itemUid: String) -> Unit,
    private val onWellnessFile: (decoded: File, type: String, itemUid: String) -> Unit,
    private val onUpToDate: () -> Unit,
    private val onActivityProgress: (ActivitySyncProgress) -> Unit = {},
    private val onWorkoutSent: (id: Long, name: String, bytes: Int) -> Unit = { _, _, _ -> },
    private val onWorkoutFailed: (id: Long, name: String, message: String) -> Unit = { _, _, _ -> },
) {
    /** A user-requested FIT upload, queued until the GDI session is ready. */
    data class PendingWorkout(
        val id: Long,
        val name: String,
        val bytes: ByteArray,
        val fitType: Int = FitWorkout.FILE_WORKOUT,
        var attempts: Int = 0,
        var triedGdi: Boolean = false,
    ) {
        /** Log/status label for this upload kind. */
        val kind: String get() = if (fitType == FitPaceBand.FILE_PACE_BAND) "PACE" else "WORKOUT"
    }

    private val gdi = GdiClient(session) { log(it) }
    private val gdxmlLookup = Gdi.GdxmlLookup()
    private val workoutQueue = ArrayDeque<PendingWorkout>()
    private var pendingWorkout: PendingWorkout? = null
    private var workoutRequested = false
    private var workoutPipe: TransportPipe? = null
    private var legacyUpload: LegacyUpload? = null
    private var legacyGeneration = 0

    private class LegacyUpload(val pending: PendingWorkout) {
        var fileIndex: Int = 0
        var offset: Int = 0
        var crc: Int = 0
        var stage: Stage = Stage.CREATE
    }

    private enum class Stage { CREATE, UPLOAD, DATA }

    /** Which kind of pull is currently in flight; routes pull responses. */
    private enum class PullKind { Activity, Wellness }

    private val wellnessQueue = ArrayDeque<Gdi.ItemReference>()
    private var wellnessTransfer = false
    private var activePull: PullKind? = null
    /** Wellness type label for the active pull; used for logging only. */
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
    private var listRequestQueued = false
    private var listReceived = false
    private var listPagePending = false
    private var listPageCount = 0
    private var listCursor = 0
    private val listItems = ArrayList<Gdi.ItemReference>()
    private var activityListProbeRequested = false
    private var activityListProbePending = false
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
            if (svc == PIPE_SERVICE) {
                pipe?.onRegistered(h)
                workoutPipe?.onRegistered(h)
            }
        }
        session.onServiceClosed = { svc ->
            if (svc == PIPE_SERVICE) {
                pipe?.finish()
                workoutPipe?.finish()
            }
        }
        session.onMlrFrame = { frame ->
            pipe?.onFrame(frame)
            workoutPipe?.onFrame(frame)
        }
        val previousGfdiHandler = session.onGfdiMessage
        session.onGfdiMessage = { frame ->
            previousGfdiHandler?.invoke(frame)
            handler.post { handleLegacyUploadResponse(frame) }
        }
        session.onReady = {
            postIfCurrent(300) {
                log("GDI: DEVICE_SETTINGS + SYNC_READY")
                session.sendGfdi(Handshake.deviceSettings())
                session.sendGfdi(Handshake.systemEventSyncReady())
            }
            postIfCurrent(1_500) {
                if (!capabilitiesReceived) sendCapabilities()
            }
            // The watch reopens the GDI session after a transport pipe closes.
            // Give it a moment, then prefer a queued activity over more wellness.
            if (pendingActivityItem != null && !done) {
                // If a wellness transfer was interrupted by the session
                // restart, put it back in the queue but let the activity go
                // first once the session is ready.
                inFlightWellness?.let { retry ->
                    inFlightWellness = null
                    wellnessTransfer = false
                    activePull = null
                    pulledWellnessType = ""
                    wellnessQueue.addFirst(retry)
                }
                activityPullNotBefore = SystemClock.uptimeMillis() + READY_GRACE_MS
                postIfCurrent(READY_GRACE_MS) { tryStartActivityPull() }
            } else {
                handler.post {
                    val retry = inFlightWellness
                    if (retry != null) {
                        inFlightWellness = null
                        wellnessTransfer = false
                        activePull = null
                        pulledWellnessType = ""
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
        workoutPipe = null
        legacyUpload = null
        legacyGeneration++
        workoutRequested = false
        capabilitiesRequested = false
        capabilitiesReceived = false
        listRequested = false
        listRequestQueued = false
        listReceived = false
        listPagePending = false
        listPageCount = 0
        listCursor = 0
        listItems.clear()
        activityListProbeRequested = false
        activityListProbePending = false
        requestedPull = false
        started = false
        pulledItemUid = ""
        cutResponseReceived = false
        gdxmlLookup.clear()
        wellnessQueue.clear()
        wellnessTransfer = false
        activePull = null
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

    /**
     * The watch accepted the BLE connection and sent GDI traffic, but never
     * opened the GDI session (capability exchange). On a watch with a single
     * session slot this usually means Garmin Connect is holding it.
     */
    fun stalledBeforeCapabilities(): Boolean =
        started && !capabilitiesReceived && !listReceived && !done

    /** True while a file is being pulled through the transport pipe. */
    fun isTransferring(): Boolean =
        pipe != null || wellnessTransfer || workoutPipe != null || legacyUpload != null

    /**
     * Queues a prepared FIT workout for upload. The upload starts as soon as
     * the GDI capability exchange is complete and the transfer slot is free.
     */
    fun enqueueWorkout(id: Long, name: String, bytes: ByteArray) {
        enqueueUpload(id, name, bytes, FitWorkout.FILE_WORKOUT)
    }

    /** Queues a PacePro plan encoded as a `FIT_TYPE_56` pace-band file. */
    fun enqueuePacePlan(id: Long, name: String, bytes: ByteArray) {
        enqueueUpload(id, name, bytes, FitPaceBand.FILE_PACE_BAND)
    }

    private fun enqueueUpload(id: Long, name: String, bytes: ByteArray, fitType: Int) {
        val pending = PendingWorkout(id, name, bytes, fitType)
        val kind = pending.kind
        if (bytes.isEmpty()) {
            onWorkoutFailed(id, name, "The $kind file is empty.")
            return
        }
        if ((pendingWorkout?.id == id && pendingWorkout?.fitType == fitType) ||
            workoutQueue.any { it.id == id && it.fitType == fitType }
        ) {
            log("$kind already queued id=$id")
            return
        }
        workoutQueue.addLast(pending)
        log("$kind queued id=$id name=\"$name\" bytes=${bytes.size}")
        if (capabilitiesReceived && pipe == null && !wellnessTransfer) sendNextWorkout()
    }

    /** Drops queued/uploads after a connection failure so a later sync does not send them. */
    fun clearWorkouts() {
        workoutQueue.clear()
        pendingWorkout = null
        workoutRequested = false
        legacyUpload = null
        legacyGeneration++
    }

    private fun reportActivityProgress() {
        onActivityProgress(
            ActivitySyncProgress(
                done = activityDone,
                total = activityTotal,
                currentSize = pendingActivityItem?.dataSize,
            ),
        )
    }

    /** Runs [action] after [delayMs] unless the coordinator was reset meanwhile. */
    private fun postIfCurrent(delayMs: Long, action: () -> Unit) {
        val expected = generation
        handler.postDelayed({ if (expected == generation) action() }, delayMs)
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
            core != null && Gdi.field(core, 17) != null -> {
                log("GDI: time sync request → phone time")
                Gdi.timeSyncResponse(System.currentTimeMillis() / 1000)
            }
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
            postIfCurrent(2_500) {
                if (!listRequested && !listReceived) requestList()
            }
        }

        val fa = fields[Gdi.SMART_FILE_ACCESS] ?: return
        val svc = Gdi.parseFileAccessService(fa)
        val list = svc.itemListResponse
        val pull = svc.pullItemResponse
        val push = svc.pushItemResponse
        svc.itemAdded?.let { handleItemAdded(it) }
        if (list != null) handleItemList(list)
        if (pull != null) handlePullResponse(pull)
        if (push != null) handlePushResponse(push)
    }

    private fun handleItemAdded(notification: Gdi.ItemAddedNotification) {
        if (notification.items.isEmpty()) return
        gdxmlLookup.learn(notification.items)
        log(
            "GDI: items added " + notification.items.joinToString {
                "${gdxmlLookup.typeOf(it) ?: "unknown"}(${it.dataSize})"
            },
        )
        val queuedUids = wellnessQueue.mapNotNull { it.uid?.hex() }.toHashSet()
        val newItems = notification.items.filter { item ->
            val uid = item.uid?.hex().orEmpty()
            val type = gdxmlLookup.typeOf(item)
            val immutable = WellnessFileTypes.isImmutable(type)
            // SETTINGS_CHANGE carries no health samples and costs the watch a
            // full transfer slot; skipping it makes the subsequent directory
            // request much faster. Workout files (FIT_TYPE_5) are written by
            // us and are not health data either.
            type != "FIT_TYPE_41" &&
                type != "FIT_TYPE_5" &&
                type != "FIT_TYPE_56" &&
                uid.isNotEmpty() && uid !in queuedUids &&
                !(immutable && isWellnessItemKnown(uid))
        }
        wellnessQueue.addAll(newItems)
        if (pipe == null && !wellnessTransfer) pullNextWellness()
    }

    private fun pullNextWellness() {
        if (wellnessTransfer || pipe != null) return
        if (pendingActivityItem != null && !done && listReceived) {
            tryStartActivityPull()
            return
        }
        val item = wellnessQueue.removeFirstOrNull()
        if (item != null) {
            startWellnessPull(item)
            return
        }
        finishWellnessPhase()
    }

    private fun startWellnessPull(item: Gdi.ItemReference) {
        wellnessTransfer = true
        activePull = PullKind.Wellness
        pulledWellnessType = gdxmlLookup.typeOf(item) ?: "unknown"
        inFlightWellness = item
        log("WELLNESS PULL request type=$pulledWellnessType size=${item.dataSize} left=${wellnessQueue.size}")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
    }

    private fun finishWellnessPhase() {
        if (pendingActivityItem == null && activityQueue.isNotEmpty() && !requestedPull && pipe == null) {
            startNextActivity()
            return
        }
        when {
            activitiesUpToDate && !activityListProbePending -> markActivitiesUpToDate()
            !listReceived && !done -> requestList()
            // The activity pull may have been deferred while wellness was
            // using the watch's single transfer slot.
            else -> tryStartActivityPull()
        }
    }

    private fun startNextActivity() {
        val next = activityQueue.removeFirstOrNull() ?: return
        pendingActivityItem = next
        pulledItemUid = next.uid?.hex().orEmpty()
        activityTotal = activityDone + activityQueue.size + 1
        reportActivityProgress()
        activitiesUpToDate = false
        tryStartActivityPull()
    }

    private fun markActivitiesUpToDate() {
        if (done) return
        done = true
        log("SYNC up to date: activities and wellness synced")
        onUpToDate()
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
        if (pendingWorkout != null || workoutQueue.isNotEmpty()) {
            // A user asked to send a workout. Use the transfer slot for that
            // first; the normal activity/wellness sync resumes afterwards.
            sendNextWorkout()
        } else {
            sendCutWellnessThenList()
        }
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
                    postIfCurrent(2_500) {
                        if (!done && wellnessQueue.isEmpty()) {
                            listRequested = false
                            listReceived = false
                            requestList()
                        }
                    }
                } else {
                    requestList()
                }
            }
        }
        // Fallback in case the watch does not answer the cut request.
        postIfCurrent(2_000) {
            if (!cutResponseReceived && !listRequested) requestList()
        }
    }

    private fun requestList() {
        if (listReceived || listRequested || done) return
        // The watch has one transfer slot. Asking for the directory while a
        // wellness file is still streaming makes the list request disappear.
        if (wellnessTransfer || pipe != null || wellnessQueue.isNotEmpty()) {
            if (!listRequestQueued) {
                listRequestQueued = true
                log("FILE LIST deferred until wellness transfers finish")
                handler.postDelayed({
                    listRequestQueued = false
                    requestList()
                }, 1_000)
            }
            return
        }
        listRequested = true
        listItems.clear()
        listCursor = 0
        listPageCount = 0
        requestListPage(0)
    }

    /**
     * The watch caps each ItemListResponse at 100 entries and sets
     * next_transaction_id to 0, so the only reliable way to reach the rest of
     * the directory is to use the highest transaction id from the page as the
     * cursor for the next request.
     */
    private fun requestListPage(transactionId: Int) {
        if (listPageCount >= 20) {
            log("FILE LIST stopped after $listPageCount pages")
            listReceived = true
            processListItems(listItems.toList())
            return
        }
        listPageCount++
        listPagePending = true
        log("GDI → ItemListRequest page $listPageCount tx=$transactionId")
        gdi.sendSmart(
            Gdi.wrapSmart(
                Gdi.SMART_FILE_ACCESS,
                Gdi.itemListRequest(transactionId = transactionId, maxItemCount = 100),
            ),
        ) { response ->
            handler.post { handleSmart(response) }
        }
    }

    /**
     * The watch returns at most 100 directory entries per list. When the
     * unfiltered list is exactly full, activities can be hidden behind the
     * workout/settings entries. Ask for a FIT_TYPE_4-only list so every
     * activity the watch has is considered.
     */
    private fun requestActivityListProbe() {
        if (activityListProbeRequested || done) return
        activityListProbeRequested = true
        activityListProbePending = true
        val type = Gdi.DataTypeInfo(
            format = 0,
            gdxmlString = "FIT_TYPE_4",
            gdxmlStringKey = 0,
            gdxmlFileIdentifier = null,
            softwareUpdatePath = null,
            specialCase = 0,
            updatePartNumber = null,
        )
        log("GDI → ItemListRequest (activities only)")
        gdi.sendSmart(
            Gdi.wrapSmart(
                Gdi.SMART_FILE_ACCESS,
                Gdi.itemListRequest(maxItemCount = 1000, includedDataTypes = listOf(type)),
            ),
        ) { response ->
            handler.post { handleSmart(response) }
        }
        postIfCurrent(6_000) {
            if (activityListProbePending) {
                activityListProbePending = false
                log("ACTIVITY LIST probe timeout")
                maybeFinish()
            }
        }
    }

    private fun handleActivityProbe(items: List<Gdi.ItemReference>) {
        val queued = activityQueue.mapNotNull { it.uid?.hex() }.toHashSet()
        val pendingUid = pendingActivityItem?.uid?.hex()
        val newItems = items.filter { item ->
            gdxmlLookup.typeOf(item) == "FIT_TYPE_4" &&
                item.uid?.hex()?.let { uid ->
                    uid != pendingUid &&
                        uid !in completedActivityUids &&
                        uid !in queued &&
                        !isItemKnown(uid)
                } == true
        }
        if (newItems.isEmpty()) {
            log("ACTIVITY LIST probe: no additional activity files")
            maybeFinish()
            return
        }
        log("ACTIVITY LIST probe: ${newItems.size} additional activity file(s)")
        activitiesUpToDate = false
        activityQueue.addAll(newItems)
        if (pendingActivityItem == null && !requestedPull && pipe == null && !wellnessTransfer) {
            pendingActivityItem = activityQueue.removeFirstOrNull()
            val item = pendingActivityItem
            if (item != null) {
                pulledItemUid = item.uid?.hex().orEmpty()
                activityTotal = activityDone + activityQueue.size + 1
                reportActivityProgress()
                tryStartActivityPull()
            } else {
                maybeFinish()
            }
        } else {
            activityTotal = activityDone + activityQueue.size + (if (pendingActivityItem != null) 1 else 0)
            reportActivityProgress()
        }
    }

    private fun maybeFinish() {
        if (done || !activitiesUpToDate) return
        if (pendingActivityItem != null || requestedPull || pipe != null ||
            wellnessTransfer || activityQueue.isNotEmpty()
        ) {
            return
        }
        if (wellnessQueue.isNotEmpty()) {
            pullNextWellness()
            return
        }
        done = true
        log("SYNC up to date: activities and wellness synced")
        onUpToDate()
    }

    private fun handleItemList(list: Gdi.ItemListResponse) {
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

        if (listPagePending) {
            handleListPage(list)
            return
        }

        if (activityListProbePending) {
            activityListProbePending = false
            handleActivityProbe(list.items)
            return
        }
        listReceived = true
        processListItems(list.items)
    }

    /** Accumulates one paged directory response and requests the next page. */
    private fun handleListPage(list: Gdi.ItemListResponse) {
        listPagePending = false
        val seen = listItems.mapNotNull { it.uid?.hex() }.toHashSet()
        var added = 0
        for (item in list.items) {
            val uid = item.uid?.hex() ?: continue
            if (uid !in seen) {
                listItems.add(item)
                seen.add(uid)
                added++
            }
        }
        val previousCursor = listCursor
        val pageMax = list.items.maxOfOrNull { it.transactionId } ?: previousCursor
        listCursor = maxOf(listCursor, pageMax)
        log("FILE LIST page tx=$previousCursor..$listCursor added=$added total=${listItems.size}")
        if (list.items.size >= 100 && listCursor > previousCursor) {
            requestListPage(listCursor)
            return
        }
        listReceived = true
        processListItems(listItems.toList())
    }

    private fun processListItems(items: List<Gdi.ItemReference>) {
        if (items.size <= 1) return

        // Pull sleep/HRV/biometric/monitoring files that are present in the
        // directory but were not pushed by CutWellnessFilesRequest. Skip files
        // this app already imported so a normal sync stays incremental.
        val queuedUids = wellnessQueue.mapNotNull { it.uid?.hex() }.toHashSet()
        val healthItems = items.filter { item ->
            val type = gdxmlLookup.typeOf(item) ?: return@filter false
            val uid = item.uid?.hex() ?: return@filter false
            val immutable = WellnessFileTypes.isImmutable(type)
            type in WellnessFileTypes.immutable &&
                uid !in queuedUids &&
                !(immutable && isWellnessItemKnown(uid)) &&
                item.uid?.hex() != pendingActivityItem?.uid?.hex()
        }
        if (healthItems.isNotEmpty()) {
            log("SYNC: queued ${healthItems.size} health file(s) from the directory")
            wellnessQueue.addAll(healthItems)
        }

        if (requestedPull || (pipe != null && !wellnessTransfer)) {
            log("SYNC: activity transfer already active; keeping the queued activity")
            return
        }

        val activityItems = items.filter { gdxmlLookup.typeOf(it) == "FIT_TYPE_4" }
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
            if (activityListProbePending) return
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
        activePull = PullKind.Activity
        pulledWellnessType = ""
        reportActivityProgress()
        log(
            "PULL request uid=${item.uid?.hex()} type=${gdxmlLookup.typeOf(item)} " +
                "size=${item.dataSize} attempt=${activityPullAttempts + 1}",
        )
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pullItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
        // If the watch does not answer (e.g. the request raced with a session
        // restart), retry rather than waiting for the outer watchdog.
        postIfCurrent(PULL_TIMEOUT_MS) {
            if (!done && requestedPull && pipe == null && !wellnessTransfer) {
                log("PULL timeout waiting for the watch; retrying activity")
                requestedPull = false
                activityPullNotBefore = SystemClock.uptimeMillis() + READY_GRACE_MS
                tryStartActivityPull()
            }
        }
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
            if (activePull == PullKind.Wellness) {
                val retry = inFlightWellness
                activePull = null
                pulledWellnessType = ""
                wellnessTransfer = false
                inFlightWellness = null
                if (pr.result == RESULT_NO_RESOURCES && retry != null && wellnessPullAttempts < MAX_PULL_RETRIES) {
                    wellnessPullAttempts++
                    wellnessQueue.addFirst(retry)
                    handler.postDelayed({ pullNextWellness() }, RETRY_DELAY_MS)
                }
            } else {
                activePull = null
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
                    if (activePull == PullKind.Wellness) {
                        completeWellnessTransfer(raw, decoded, decFile)
                    } else {
                        completeActivityTransfer(raw, decoded, decFile, pulledItemUid)
                    }
                }
            },
            onError = { msg -> handler.post { log("PIPE error: $msg") } },
            log = { msg -> log(msg) },
        )
        pipe = p
        p.start()
    }

    /**
     * Finishes a wellness transfer: clears the transfer slot, hands the decoded
     * file to the importer and continues the wellness queue.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun completeWellnessTransfer(raw: File, decoded: ByteArray, decFile: File) {
        val type = pulledWellnessType
        val wellnessUid = inFlightWellness?.uid?.hex().orEmpty()
        activePull = null
        pulledWellnessType = ""
        wellnessTransfer = false
        inFlightWellness = null
        log("WELLNESS complete type=$type decoded=${decoded.size}")
        onWellnessFile(decFile, type, wellnessUid)
        if (pendingActivityItem != null && !done) {
            activityPullNotBefore = SystemClock.uptimeMillis() + ACTIVITY_GRACE_MS
        }
        pullNextWellness()
    }

    /**
     * Finishes an activity transfer: records the completed UID, reports it to
     * the importer and schedules the next queued activity, if any.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun completeActivityTransfer(raw: File, decoded: ByteArray, decFile: File, pulledUid: String) {
        activePull = null
        pendingActivityItem = null
        if (pulledUid.isNotEmpty()) completedActivityUids += pulledUid
        activityDone++
        onFinished(raw, decFile, pulledUid)
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

    private fun handlePushResponse(pr: Gdi.PushItemResponse) {
        log(
            "WORKOUT push response result=${pr.result} transport=${pr.transport} " +
                "handle=${pr.transferHandle} offset=${pr.offset} window=${pr.compressionWindow}",
        )
        val pending = pendingWorkout
        if (pr.result != 0 || pr.transferHandle == 0) {
            workoutRequested = false
            val message = "Watch rejected the workout: ${pullResultName(pr.result)} (${pr.result})"
            if (pending != null && pr.result == RESULT_NO_RESOURCES && pending.attempts < MAX_PULL_RETRIES) {
                log("WORKOUT retry after NO_AVAILABLE_RESOURCES")
                pending.attempts++
                handler.postDelayed({ startGdiPushWorkout(pending) }, RETRY_DELAY_MS)
            } else {
                failWorkout(message)
            }
            return
        }
        if (pending == null) {
            log("WORKOUT push response without a pending workout")
            return
        }
        workoutRequested = false
        val p = TransportPipe(
            session = session,
            serviceId = PIPE_SERVICE,
            transferHandle = pr.transferHandle,
            expectedSize = pending.bytes.size.toLong(),
            onData = {},
            onComplete = { handler.post { completeWorkout() } },
            onError = { msg -> handler.post { failWorkout(msg) } },
            log = { log(it) },
            writePayload = pending.bytes,
            writeCompressionWindow = pr.compressionWindow,
        )
        workoutPipe = p
        p.start()
    }

    private fun sendNextWorkout() {
        if (workoutRequested || pipe != null || wellnessTransfer || workoutPipe != null || legacyUpload != null) return
        if (!capabilitiesReceived || done) return
        val pending = pendingWorkout ?: workoutQueue.removeFirstOrNull() ?: return
        pendingWorkout = pending
        pending.attempts++
        workoutRequested = true
        log(
            "WORKOUT upload id=${pending.id} \"${pending.name}\" " +
                "bytes=${pending.bytes.size} attempt=${pending.attempts}",
        )
        startLegacyWorkout(pending)
    }

    /**
     * Legacy GFDI upload (`CREATE_FILE` / `UPLOAD_REQUEST` /
     * `FILE_TRANSFER_DATA`). The watch's GDI push path rejects `FIT_TYPE_5`
     * with INVALID_PARAMS, but this path is what Garmin firmware uses for FIT
     * workout files.
     */
    private fun startLegacyWorkout(pending: PendingWorkout) {
        val upload = LegacyUpload(pending)
        legacyUpload = upload
        val kind = pending.kind
        log("$kind legacy CREATE_FILE size=${pending.bytes.size} type=FIT/${pending.fitType}")
        session.sendGfdi(
            GfdiFileUpload.createFile(pending.bytes.size, subType = pending.fitType),
        )
        armLegacyTimeout(upload, "The watch did not answer CREATE_FILE.")
    }

    /** Fallback for watches that expose the newer GDI FileAccess push path. */
    private fun startGdiPushWorkout(pending: PendingWorkout) {
        val uuid = UUID.randomUUID()
        val item = Gdi.ItemReference(
            uid = Gdi.Uuid(uuid.mostSignificantBits, uuid.leastSignificantBits),
            dataType = Gdi.DataTypeInfo(
                format = 0, // GDXML_DATA_TYPE
                gdxmlString = "FIT_TYPE_${pending.fitType}",
                gdxmlStringKey = 0,
                gdxmlFileIdentifier = null,
                softwareUpdatePath = null,
                specialCase = 0,
                updatePartNumber = null,
            ),
            dataSize = pending.bytes.size.toLong(),
            requestedFlags = 0,
            transactionId = 0,
            urgency = 0,
        )
        workoutRequested = true
        val kind = pending.kind
        log("$kind GDI push request id=${pending.id} bytes=${pending.bytes.size}")
        gdi.sendSmart(Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, Gdi.pushItemRequest(item))) { response ->
            handler.post { handleSmart(response) }
        }
        handler.postDelayed({
            if (workoutRequested && pendingWorkout === pending && workoutPipe == null) {
                workoutRequested = false
                failWorkout("The watch did not answer the $kind upload.")
            }
        }, PULL_TIMEOUT_MS)
    }

    private fun handleLegacyUploadResponse(frame: dev.opengarmin.protocol.Gfdi.Frame) {
        if (frame.type != dev.opengarmin.protocol.Gfdi.MessageType.RESPONSE.id) return
        val response = dev.opengarmin.protocol.Gfdi.parseResponse(frame) ?: return
        val upload = legacyUpload ?: return
        when (response.originalType) {
            dev.opengarmin.protocol.Gfdi.MessageType.CREATE_FILE.id -> {
                if (upload.stage != Stage.CREATE) return
                val data = response.data
                if (response.status != 0 || data.size < 7) {
                    failLegacy("CREATE_FILE rejected (status=${response.status})")
                    return
                }
                val createStatus = data[0].toInt() and 0xFF
                if (createStatus != 0) {
                    failLegacy("Watch refused the workout file (code $createStatus)")
                    return
                }
                upload.fileIndex = dev.opengarmin.protocol.Gfdi.le16(data, 1)
                upload.stage = Stage.UPLOAD
                log("WORKOUT legacy fileIndex=${upload.fileIndex}; UPLOAD_REQUEST")
                session.sendGfdi(
                    GfdiFileUpload.uploadRequest(
                        fileIndex = upload.fileIndex,
                        fileSize = upload.pending.bytes.size,
                    ),
                )
                armLegacyTimeout(upload, "The watch did not answer UPLOAD_REQUEST.")
            }

            dev.opengarmin.protocol.Gfdi.MessageType.UPLOAD_REQUEST.id -> {
                if (upload.stage != Stage.UPLOAD) return
                val data = response.data
                if (response.status != 0 || data.size < 11) {
                    failLegacy("UPLOAD_REQUEST rejected (status=${response.status})")
                    return
                }
                val uploadStatus = data[0].toInt() and 0xFF
                if (uploadStatus != 0) {
                    failLegacy("Watch refused the upload (code $uploadStatus)")
                    return
                }
                upload.offset = GfdiFileUpload.le32(data, 1)
                upload.stage = Stage.DATA
                log(
                    "WORKOUT legacy upload accepted offset=${upload.offset} " +
                        "max=${GfdiFileUpload.le32(data, 5)} seed=${dev.opengarmin.protocol.Gfdi.le16(data, 9)}",
                )
                sendLegacyChunk(upload)
            }

            dev.opengarmin.protocol.Gfdi.MessageType.FILE_TRANSFER_DATA.id -> {
                if (upload.stage != Stage.DATA) return
                val data = response.data
                if (response.status != 0 || data.size < 5) {
                    failLegacy("FILE_TRANSFER_DATA rejected (status=${response.status})")
                    return
                }
                val transferStatus = data[0].toInt() and 0xFF
                if (transferStatus != 0) {
                    failLegacy("Watch aborted the transfer (code $transferStatus)")
                    return
                }
                upload.offset = GfdiFileUpload.le32(data, 1)
                if (upload.offset >= upload.pending.bytes.size) {
                    log("WORKOUT legacy upload complete (${upload.offset} bytes)")
                    session.sendGfdi(GfdiFileUpload.syncComplete())
                    legacyUpload = null
                    legacyGeneration++
                    pendingWorkout = null
                    workoutRequested = false
                    onWorkoutSent(
                        upload.pending.id,
                        upload.pending.name,
                        upload.pending.bytes.size,
                    )
                    handler.postDelayed({
                        if (!done) sendCutWellnessThenList()
                    }, 800)
                } else {
                    sendLegacyChunk(upload)
                }
            }
        }
    }

    private fun sendLegacyChunk(upload: LegacyUpload) {
        val data = upload.pending.bytes
        if (upload.offset >= data.size) return
        val end = minOf(upload.offset + GfdiFileUpload.DEFAULT_CHUNK_SIZE, data.size)
        val chunk = data.copyOfRange(upload.offset, end)
        upload.crc = dev.opengarmin.protocol.Crc16.continueFrom(upload.crc, chunk)
        log(
            "WORKOUT legacy chunk offset=${upload.offset} size=${chunk.size} " +
                "crc=0x%04x".format(upload.crc),
        )
        session.sendGfdi(
            GfdiFileUpload.fileTransferData(chunk, upload.offset, upload.crc),
        )
        armLegacyTimeout(upload, "The watch did not acknowledge offset ${upload.offset}.")
    }

    private fun armLegacyTimeout(upload: LegacyUpload, message: String) {
        val generation = ++legacyGeneration
        handler.postDelayed({
            if (legacyUpload === upload && generation == legacyGeneration) {
                failLegacy(message)
            }
        }, LEGACY_UPLOAD_TIMEOUT_MS)
    }

    private fun failLegacy(message: String) {
        val upload = legacyUpload ?: return
        legacyUpload = null
        legacyGeneration++
        workoutRequested = false
        log("WORKOUT legacy failed: $message")
        if (!upload.pending.triedGdi) {
            upload.pending.triedGdi = true
            log("WORKOUT falling back to GDI push")
            startGdiPushWorkout(upload.pending)
            return
        }
        pendingWorkout = null
        onWorkoutFailed(upload.pending.id, upload.pending.name, message)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else if (!done) {
            sendCutWellnessThenList()
        }
    }

    private fun completeWorkout() {
        workoutPipe = null
        workoutRequested = false
        val pending = pendingWorkout ?: return
        pendingWorkout = null
        log("WORKOUT sent id=${pending.id} \"${pending.name}\" bytes=${pending.bytes.size}")
        onWorkoutSent(pending.id, pending.name, pending.bytes.size)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else {
            sendCutWellnessThenList()
        }
    }

    private fun failWorkout(message: String) {
        val pending = pendingWorkout ?: return
        workoutPipe = null
        workoutRequested = false
        log("WORKOUT failed id=${pending.id}: $message")
        pendingWorkout = null
        onWorkoutFailed(pending.id, pending.name, message)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else {
            sendCutWellnessThenList()
        }
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
        private const val LEGACY_UPLOAD_TIMEOUT_MS = 10_000L

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
