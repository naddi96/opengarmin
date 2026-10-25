package dev.opengarmin.app.sync

import android.os.Handler
import android.os.SystemClock
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Handshake
import dev.opengarmin.protocol.Hex
import dev.opengarmin.protocol.TransportPipe
import dev.opengarmin.app.data.WellnessFileTypes
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
    /** True when a non-activity wellness file (sleep/HRV/monitoring) is known. */
    private val isWellnessItemKnown: (String) -> Boolean = { false },
    private val onFinished: (raw: File, decoded: File, itemUid: String) -> Unit,
    private val onWellnessFile: (decoded: File, type: String, itemUid: String) -> Unit,
    private val onUpToDate: () -> Unit,
    private val onActivityProgress: (ActivitySyncProgress) -> Unit = {},
    private val onWorkoutSent: (id: Long, name: String, bytes: Int) -> Unit = { _, _, _ -> },
    private val onWorkoutFailed: (id: Long, name: String, message: String) -> Unit = { _, _, _ -> },
) {
    private val gdi = GdiClient(session) { log(it) }
    private val gdxmlLookup = Gdi.GdxmlLookup()
    private val workouts = WorkoutUploader(
        session = session,
        gdi = gdi,
        handler = handler,
        log = log,
        isDone = { done },
        isSessionReady = { capabilitiesReceived },
        isSlotBusy = { pipe != null || wellnessTransfer },
        dispatchSmart = { handleSmart(it) },
        onUploadFinished = { sendCutWellnessThenList() },
        onUploadSent = onWorkoutSent,
        onUploadFailed = onWorkoutFailed,
    )

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
    private val healthProbeQueue = ArrayDeque<String>()
    private var healthProbePending = false
    private var healthProbeType = ""
    private var healthProbeRequested = false
    private var upToDateReported = false
    private var thresholdRetries = 0
    private var thresholdCutScheduled = false
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
                workouts.onServiceRegistered(h)
            }
        }
        session.onServiceClosed = { svc ->
            if (svc == PIPE_SERVICE) {
                pipe?.finish()
                workouts.onServiceClosed()
            }
        }
        session.onMlrFrame = { frame ->
            pipe?.onFrame(frame)
            workouts.onFrame(frame)
        }
        val previousGfdiHandler = session.onGfdiMessage
        session.onGfdiMessage = { frame ->
            previousGfdiHandler?.invoke(frame)
            handler.post { workouts.handleLegacyResponse(frame) }
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
        workouts.reset()
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
        healthProbeQueue.clear()
        healthProbePending = false
        healthProbeType = ""
        healthProbeRequested = false
        upToDateReported = false
        thresholdRetries = 0
        thresholdCutScheduled = false
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
        pipe != null || wellnessTransfer || workouts.isTransferring()

    /**
     * Queues a prepared FIT workout for upload. The upload starts as soon as
     * the GDI capability exchange is complete and the transfer slot is free.
     */
    fun enqueueWorkout(id: Long, name: String, bytes: ByteArray) {
        workouts.enqueueWorkout(id, name, bytes)
    }

    /** Queues a PacePro plan encoded as a `FIT_TYPE_56` pace-band file. */
    fun enqueuePacePlan(id: Long, name: String, bytes: ByteArray) {
        workouts.enqueuePacePlan(id, name, bytes)
    }

    /** Drops queued/uploads after a connection failure so a later sync does not send them. */
    fun clearWorkouts() {
        workouts.clearWorkouts()
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
            // The watch uses this notification to say that a new wellness
            // file (often SLEEP_DATA after wake-up) is ready. Garmin Connect's
            // ConnectTransferService answers it with a fresh cut. Only asking
            // for the directory here is not enough: the sleep file may not
            // have been cut yet, so it never appears in that snapshot.
            log("GDI: wellness threshold met → CutWellnessFilesRequest")
            onWellnessThreshold()
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
        if (push != null) workouts.handlePushResponse(push)
    }

    private fun handleItemAdded(notification: Gdi.ItemAddedNotification) {
        if (notification.items.isEmpty()) return
        gdxmlLookup.learn(notification.items)
        log(
            "GDI: items added " + notification.items.joinToString {
                "${gdxmlLookup.typeOf(it) ?: "unknown"}(${it.dataSize})"
            },
        )
        val queuedKeys = wellnessQueue.mapNotNull { item ->
            val uid = item.uid?.hex() ?: return@mapNotNull null
            WellnessFileTypes.cacheKey(gdxmlLookup.typeOf(item), uid, item.transactionId)
        }.toHashSet()
        val newItems = notification.items.filter { item ->
            val uid = item.uid?.hex().orEmpty()
            val type = gdxmlLookup.typeOf(item)
            val key = WellnessFileTypes.cacheKey(type, uid, item.transactionId)
            val immutable = WellnessFileTypes.isImmutable(type)
            // SETTINGS_CHANGE carries no health samples and costs the watch a
            // full transfer slot; skipping it makes the subsequent directory
            // request much faster. Workout files (FIT_TYPE_5/FIT_TYPE_56) are
            // written by us and are not health data either. Activities
            // (FIT_TYPE_4) are handled by the directory path, which knows the
            // imported UIDs; queueing one here would pull it twice and route
            // the FIT through the wellness importer.
            type != "FIT_TYPE_4" &&
                type != "FIT_TYPE_41" &&
                type != "FIT_TYPE_5" &&
                type != "FIT_TYPE_56" &&
                uid.isNotEmpty() && key !in queuedKeys &&
                !(immutable && isWellnessItemKnown(key))
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
        reportUpToDate("SYNC up to date: activities and wellness synced")
    }

    /**
     * Reports completion once per sync attempt. A late wellness-threshold
     * notification can re-open the sync to pull a sleep file; that must not
     * record a second successful sync or fire the UI callback twice.
     */
    private fun reportUpToDate(message: String) {
        done = true
        log(message)
        if (upToDateReported) return
        upToDateReported = true
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
        if (workouts.hasPending) {
            // A user asked to send a workout. Use the transfer slot for that
            // first; the normal activity/wellness sync resumes afterwards.
            workouts.onSessionReady()
        } else {
            sendCutWellnessThenList()
        }
    }

    private fun onWellnessThreshold() {
        if (wellnessTransfer || pipe != null || requestedPull || listPagePending || healthProbePending) {
            // A cut/list during a transfer or directory read is ignored by the
            // watch. Retry once the single transfer slot is free.
            if (thresholdRetries < 5) {
                thresholdRetries++
                postIfCurrent(2_000) { onWellnessThreshold() }
            }
            return
        }
        thresholdRetries = 0
        if (thresholdCutScheduled) return
        // A threshold can arrive after the directory snapshot has already
        // completed. Re-open the sync so the cut that follows can add the
        // newly created file to the wellness queue.
        if (done) {
            done = false
            activitiesUpToDate = true
        }
        listRequested = false
        listReceived = false
        healthProbeRequested = false
        healthProbeQueue.clear()
        healthProbePending = false
        thresholdCutScheduled = true
        handler.postDelayed({
            thresholdCutScheduled = false
            if (done) {
                done = false
                activitiesUpToDate = true
            }
            sendCutWellnessThenList(priority = 0)
        }, 500)
    }

    private fun sendCutWellnessThenList(priority: Int = 1) {
        cutResponseReceived = false
        log("GDI → CutWellnessFilesRequest priority=$priority")
        gdi.sendSmart(Gdi.cutWellnessFilesRequest(priority)) { response ->
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
        healthProbeQueue.clear()
        healthProbePending = false
        healthProbeType = ""
        healthProbeRequested = false
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
            finishMainListAndProbe()
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
        reportUpToDate("SYNC up to date: activities and wellness synced")
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

        if (healthProbePending) {
            handleHealthProbe(list.items)
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
        finishMainListAndProbe()
    }

    /**
     * A full directory response is capped at 100 entries, so a sleep/HRV file
     * can be hidden behind activities and settings. Ask the watch for a
     * type-filtered list as a fallback, exactly the way the activity probe
     * already does for FIT_TYPE_4. This also gives the first item of each
     * health type its gdxml string, which resolves follow-up key-only items.
     */
    private fun finishMainListAndProbe() {
        if (healthProbeRequested) {
            processListItems(listItems.toList())
            return
        }
        val healthTypes = listOf("FIT_TYPE_49", "FIT_TYPE_44", "FIT_TYPE_72")
        val present = healthTypes.associateWith { type ->
            listItems.any { gdxmlLookup.typeOf(it) == type }
        }
        // A full response may be truncated before an older health item, so
        // re-query every type even when one of them is present.
        val probeTypes = if (listItems.size >= 100) {
            healthTypes
        } else {
            healthTypes.filter { present[it] != true }
        }
        if (probeTypes.isEmpty()) {
            processListItems(listItems.toList())
            return
        }
        healthProbeRequested = true
        healthProbeQueue.addAll(probeTypes)
        log(
            "HEALTH LIST probe queued (main list ${listItems.size} item(s), " +
                present.entries.joinToString { "${it.key}=${it.value}" } + ")",
        )
        startNextHealthProbe()
    }

    private fun startNextHealthProbe() {
        if (done) {
            processListItems(listItems.toList())
            return
        }
        val type = healthProbeQueue.removeFirstOrNull()
        if (type == null) {
            processListItems(listItems.toList())
            return
        }
        healthProbePending = true
        healthProbeType = type
        log("GDI → ItemListRequest ($type only)")
        gdi.sendSmart(
            Gdi.wrapSmart(
                Gdi.SMART_FILE_ACCESS,
                Gdi.itemListRequest(maxItemCount = 1000, includedDataTypes = listOf(dataType(type))),
            ),
        ) { response ->
            handler.post { handleSmart(response) }
        }
        postIfCurrent(5_000) {
            if (healthProbePending && healthProbeType == type) {
                healthProbePending = false
                log("HEALTH LIST probe $type timeout")
                startNextHealthProbe()
            }
        }
    }

    private fun handleHealthProbe(items: List<Gdi.ItemReference>) {
        val type = healthProbeType
        healthProbePending = false
        gdxmlLookup.learn(items)
        // A watch that ignores included_data_types can return the whole
        // directory. Only apply the probe type to key-only items when the
        // response is small and contains no other known type, so a sleep
        // probe can never relabel activities as SLEEP_DATA.
        val resolvedTypes = items.mapNotNull { gdxmlLookup.typeOf(it) }.toHashSet()
        val trustFilter = items.size <= 20 && resolvedTypes.all { it == type }
        val seen = listItems.mapNotNull { it.uid?.hex() }.toHashSet()
        var added = 0
        for (item in items) {
            val uid = item.uid?.hex() ?: continue
            if (uid in seen) continue
            // Some watches omit the gdxml string on a key-only item even in a
            // filtered response. The filter tells us the type, so use it as a
            // fallback instead of dropping a real sleep file.
            val resolved = if (gdxmlLookup.typeOf(item) == null && trustFilter) {
                item.copy(
                    dataType = (item.dataType ?: dataType(type)).copy(gdxmlString = type),
                )
            } else {
                item
            }
            listItems.add(resolved)
            seen.add(uid)
            added++
        }
        log(
            "HEALTH LIST probe $type: items=${items.size} new=$added " +
                "filterTrusted=$trustFilter",
        )
        startNextHealthProbe()
    }

    private fun dataType(type: String): Gdi.DataTypeInfo = Gdi.DataTypeInfo(
        format = 0,
        gdxmlString = type,
        gdxmlStringKey = 0,
        gdxmlFileIdentifier = null,
        softwareUpdatePath = null,
        specialCase = 0,
        updatePartNumber = null,
    )

    private fun processListItems(items: List<Gdi.ItemReference>) {
        if (items.isEmpty()) {
            reportUpToDate("SYNC up to date: the watch directory is empty")
            return
        }

        // Pull sleep/HRV/biometric/monitoring files that are present in the
        // directory but were not pushed by CutWellnessFilesRequest. Skip files
        // this app already imported so a normal sync stays incremental.
        val queuedKeys = wellnessQueue.mapNotNull { item ->
            val uid = item.uid?.hex() ?: return@mapNotNull null
            WellnessFileTypes.cacheKey(gdxmlLookup.typeOf(item), uid, item.transactionId)
        }.toHashSet()
        val healthItems = items.filter { item ->
            val type = gdxmlLookup.typeOf(item) ?: return@filter false
            val uid = item.uid?.hex() ?: return@filter false
            val key = WellnessFileTypes.cacheKey(type, uid, item.transactionId)
            val immutable = WellnessFileTypes.isImmutable(type)
            type in WellnessFileTypes.pullable &&
                key !in queuedKeys &&
                !(immutable && isWellnessItemKnown(key)) &&
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
            reportUpToDate(
                if (activityItems.isEmpty()) {
                    "SYNC up to date: no activity files on the watch"
                } else {
                    "SYNC up to date: ${activityItems.size} activity item(s) already imported"
                },
            )
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
        val item = inFlightWellness
        val uid = item?.uid?.hex().orEmpty()
        val wellnessKey = WellnessFileTypes.cacheKey(type, uid, item?.transactionId ?: 0)
        activePull = null
        pulledWellnessType = ""
        wellnessTransfer = false
        inFlightWellness = null
        log("WELLNESS complete type=$type decoded=${decoded.size}")
        onWellnessFile(decFile, type, wellnessKey)
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

    companion object {
        const val PIPE_SERVICE = 0x2018
        private const val RESULT_NO_RESOURCES = 3
        private const val MAX_PULL_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val ACTIVITY_GRACE_MS = 2_500L
        private const val READY_GRACE_MS = 800L
        private const val LIST_GRACE_MS = 400L
        private const val PULL_TIMEOUT_MS = 8_000L

        internal fun pullResultName(result: Int): String = when (result) {
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
