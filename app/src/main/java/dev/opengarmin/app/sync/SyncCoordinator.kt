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
    /** True when a non-activity wellness file (sleep/HRV/monitoring) is known. */
    private val isWellnessItemKnown: (String) -> Boolean = { false },
    private val onFinished: (raw: File, decoded: File, itemUid: String) -> Unit,
    private val onWellnessFile: (decoded: File, type: String, itemUid: String) -> Unit,
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
        val queuedUids = wellnessQueue.mapNotNull { it.uid?.hex() }.toHashSet()
        val newItems = notification.items.filter { item ->
            val uid = item.uid?.hex().orEmpty()
            val type = gdxmlLookup.typeOf(item)
            val immutable = type in IMMUTABLE_HEALTH_FILE_TYPES
            // SETTINGS_CHANGE carries no health samples and costs the watch a
            // full transfer slot; skipping it makes the subsequent directory
            // request much faster.
            type != "FIT_TYPE_41" &&
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
        if (item == null) {
            if (pendingActivityItem == null && activityQueue.isNotEmpty() &&
                !requestedPull && pipe == null
            ) {
                pendingActivityItem = activityQueue.removeFirstOrNull()
                pendingActivityItem?.let { next ->
                    pulledItemUid = next.uid?.hex().orEmpty()
                    activityTotal = activityDone + activityQueue.size + 1
                    reportActivityProgress()
                    activitiesUpToDate = false
                    tryStartActivityPull()
                    return
                }
            }
            if (activitiesUpToDate && !activityListProbePending) {
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
        val g = generation
        handler.postDelayed({
            if (g == generation && activityListProbePending) {
                activityListProbePending = false
                log("ACTIVITY LIST probe timeout")
                maybeFinish()
            }
        }, 6_000)
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

    private fun processListItems(items: List<Gdi.ItemReference>) {
        if (items.size <= 1) return

        // Pull sleep/HRV/biometric/monitoring files that are present in the
        // directory but were not pushed by CutWellnessFilesRequest. Skip files
        // this app already imported so a normal sync stays incremental.
        val queuedUids = wellnessQueue.mapNotNull { it.uid?.hex() }.toHashSet()
        val healthItems = items.filter { item ->
            val type = gdxmlLookup.typeOf(item) ?: return@filter false
            val uid = item.uid?.hex() ?: return@filter false
            val immutable = type in IMMUTABLE_HEALTH_FILE_TYPES
            type in HEALTH_FILE_TYPES &&
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
                        val wellnessUid = inFlightWellness?.uid?.hex().orEmpty()
                        pulledWellnessType = ""
                        wellnessTransfer = false
                        inFlightWellness = null
                        log("WELLNESS complete type=$type decoded=${decoded.size}")
                        onWellnessFile(decFile, type, wellnessUid)
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

        /**
         * Immutable health history files we pull from the directory. The
         * current monitoring (FIT_TYPE_32) delta is pushed by
         * CutWellnessFilesRequest, so it is intentionally excluded here to
         * avoid downloading dozens of historical monitoring files on every
         * activity sync.
         */
        private val HEALTH_FILE_TYPES = setOf(
            "FIT_TYPE_44",
            "FIT_TYPE_49",
        )

        /**
         * Only sleep/biometric files are immutable. Monitoring files are
         * rewritten by every wellness cut, so their UID must not be cached as
         * "already imported" or future deltas would be skipped.
         */
        private val IMMUTABLE_HEALTH_FILE_TYPES = setOf(
            "FIT_TYPE_44",
            "FIT_TYPE_49",
        )

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
