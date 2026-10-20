package dev.opengarmin.app.sync

import android.os.Handler
import dev.opengarmin.protocol.Crc16
import dev.opengarmin.protocol.FitPaceBand
import dev.opengarmin.protocol.FitWorkout
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Gdi
import dev.opengarmin.protocol.GdiClient
import dev.opengarmin.protocol.Gfdi
import dev.opengarmin.protocol.GfdiFileUpload
import dev.opengarmin.protocol.TransportPipe
import java.util.UUID

/**
 * Owns the user-requested workout/pace-plan upload queue and the two upload
 * protocols: the legacy GFDI file upload and the GDI FileAccess push fallback.
 *
 * The coordinator drives this collaborator: it tells the uploader when the GDI
 * session is ready and when the shared transfer slot is free, and resumes the
 * normal wellness/activity sync through [onUploadFinished] once the queue is
 * drained.
 */
internal class WorkoutUploader(
    private val session: GarminSession,
    private val gdi: GdiClient,
    private val handler: Handler,
    private val log: (String) -> Unit,
    /** True when the coordinator has finished (up to date or otherwise done). */
    private val isDone: () -> Boolean,
    /** True once the GDI capability exchange is complete. */
    private val isSessionReady: () -> Boolean,
    /** True while a wellness transfer or activity pipe owns the transfer slot. */
    private val isSlotBusy: () -> Boolean,
    /** Routes GDI smart responses back through the coordinator's dispatcher. */
    private val dispatchSmart: (ByteArray) -> Unit,
    /** Called when the queue is drained so the coordinator resumes its sync. */
    private val onUploadFinished: () -> Unit,
    private val onUploadSent: (id: Long, name: String, bytes: Int) -> Unit,
    private val onUploadFailed: (id: Long, name: String, message: String) -> Unit,
) {
    /** A user-requested FIT upload, queued until the GDI session is ready. */
    private data class PendingWorkout(
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

    private class LegacyUpload(val pending: PendingWorkout) {
        var fileIndex: Int = 0
        var offset: Int = 0
        var crc: Int = 0
        var stage: Stage = Stage.CREATE
    }

    private enum class Stage { CREATE, UPLOAD, DATA }

    private val workoutQueue = ArrayDeque<PendingWorkout>()
    private var pendingWorkout: PendingWorkout? = null
    private var workoutRequested = false
    private var workoutPipe: TransportPipe? = null
    private var legacyUpload: LegacyUpload? = null
    private var legacyGeneration = 0

    /** True when a workout upload is queued or in flight. */
    val hasPending: Boolean get() = pendingWorkout != null || workoutQueue.isNotEmpty()

    /** True while an upload owns the transport pipe or a legacy upload is open. */
    fun isTransferring(): Boolean = workoutPipe != null || legacyUpload != null

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
            onUploadFailed(id, name, "The $kind file is empty.")
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
        if (isSessionReady() && !isSlotBusy()) sendNextWorkout()
    }

    /** Drops queued/uploads after a connection failure so a later sync does not send them. */
    fun clearWorkouts() {
        workoutQueue.clear()
        pendingWorkout = null
        workoutRequested = false
        legacyUpload = null
        legacyGeneration++
    }

    /** Clears in-flight upload state on a coordinator reset; the queue is kept. */
    fun reset() {
        workoutPipe = null
        legacyUpload = null
        legacyGeneration++
        workoutRequested = false
    }

    /** Called by the coordinator once capabilities arrive; starts a queued upload. */
    fun onSessionReady() {
        sendNextWorkout()
    }

    /** A service registration for an MLR transfer handle (workout pipe). */
    fun onServiceRegistered(handle: Int) {
        workoutPipe?.onRegistered(handle)
    }

    fun onServiceClosed() {
        workoutPipe?.finish()
    }

    fun onFrame(frame: ByteArray) {
        workoutPipe?.onFrame(frame)
    }

    private fun sendNextWorkout() {
        if (workoutRequested || isSlotBusy() || workoutPipe != null || legacyUpload != null) return
        if (!isSessionReady() || isDone()) return
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
            handler.post { dispatchSmart(response) }
        }
        handler.postDelayed({
            if (workoutRequested && pendingWorkout === pending && workoutPipe == null) {
                workoutRequested = false
                failWorkout("The watch did not answer the $kind upload.")
            }
        }, PULL_TIMEOUT_MS)
    }

    fun handleLegacyResponse(frame: Gfdi.Frame) {
        if (frame.type != Gfdi.MessageType.RESPONSE.id) return
        val response = Gfdi.parseResponse(frame) ?: return
        val upload = legacyUpload ?: return
        when (response.originalType) {
            Gfdi.MessageType.CREATE_FILE.id -> {
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
                upload.fileIndex = Gfdi.le16(data, 1)
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

            Gfdi.MessageType.UPLOAD_REQUEST.id -> {
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
                        "max=${GfdiFileUpload.le32(data, 5)} seed=${Gfdi.le16(data, 9)}",
                )
                sendLegacyChunk(upload)
            }

            Gfdi.MessageType.FILE_TRANSFER_DATA.id -> {
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
                    onUploadSent(
                        upload.pending.id,
                        upload.pending.name,
                        upload.pending.bytes.size,
                    )
                    handler.postDelayed({
                        if (!isDone()) onUploadFinished()
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
        upload.crc = Crc16.continueFrom(upload.crc, chunk)
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
        onUploadFailed(upload.pending.id, upload.pending.name, message)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else if (!isDone()) {
            onUploadFinished()
        }
    }

    fun handlePushResponse(pr: Gdi.PushItemResponse) {
        log(
            "WORKOUT push response result=${pr.result} transport=${pr.transport} " +
                "handle=${pr.transferHandle} offset=${pr.offset} window=${pr.compressionWindow}",
        )
        val pending = pendingWorkout
        if (pr.result != 0 || pr.transferHandle == 0) {
            workoutRequested = false
            val message =
                "Watch rejected the workout: ${SyncCoordinator.pullResultName(pr.result)} (${pr.result})"
            if (pending != null && pr.result == RESULT_NO_RESOURCES && pending.attempts < MAX_UPLOAD_RETRIES) {
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
            serviceId = SyncCoordinator.PIPE_SERVICE,
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

    private fun completeWorkout() {
        workoutPipe = null
        workoutRequested = false
        val pending = pendingWorkout ?: return
        pendingWorkout = null
        log("WORKOUT sent id=${pending.id} \"${pending.name}\" bytes=${pending.bytes.size}")
        onUploadSent(pending.id, pending.name, pending.bytes.size)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else {
            onUploadFinished()
        }
    }

    private fun failWorkout(message: String) {
        val pending = pendingWorkout ?: return
        workoutPipe = null
        workoutRequested = false
        log("WORKOUT failed id=${pending.id}: $message")
        pendingWorkout = null
        onUploadFailed(pending.id, pending.name, message)
        if (workoutQueue.isNotEmpty()) {
            sendNextWorkout()
        } else {
            onUploadFinished()
        }
    }

    companion object {
        private const val RESULT_NO_RESOURCES = 3
        private const val MAX_UPLOAD_RETRIES = 5
        private const val RETRY_DELAY_MS = 2_000L
        private const val PULL_TIMEOUT_MS = 8_000L
        private const val LEGACY_UPLOAD_TIMEOUT_MS = 10_000L
    }
}
