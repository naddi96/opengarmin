package dev.opengarmin.app.sync

import android.os.Handler
import dev.opengarmin.protocol.Crc16
import dev.opengarmin.protocol.GarminSession
import dev.opengarmin.protocol.Gfdi
import dev.opengarmin.protocol.Hex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Garmin's pre-GDI ("legacy" / V1) GFDI file-transfer sync.
 *
 * Watches such as the Forerunner 245 do not implement the protobuf FileAccess
 * service. Their activity list comes from a directory file requested with
 * `DOWNLOAD_REQUEST (5002)`, and file bytes stream back as
 * `FILE_TRANSFER_DATA (5004)` chunks. The watch asks the phone to select new
 * files with `SYNCHRONIZATION (5037)` / `DIRECTORY_FILTER (5007)`.
 *
 * The flow is single-threaded: request the directory, receive and parse the
 * 16-byte entries, then download one activity/wellness file at a time. Each
 * successful file is archived on the watch with `SET_FILE_FLAGS (5008)` so it
 * is not listed again on the next sync.
 */
internal class LegacySync(
    private val session: GarminSession,
    private val filesDir: File,
    private val handler: Handler,
    private val log: (String) -> Unit,
    /** Sends the GDI Core.SyncRequest that makes the watch emit 5037. */
    private val requestSyncSession: () -> Unit,
    private val isActivityKnown: (String) -> Boolean,
    private val isWellnessKnown: (String) -> Boolean,
    private val onActivityFile: (
        raw: File,
        decoded: File,
        itemUid: String,
        onResult: (Boolean) -> Unit,
    ) -> Unit,
    private val onWellnessFile: (
        decoded: File,
        type: String,
        itemUid: String,
        onResult: (Boolean) -> Unit,
    ) -> Unit,
    private val onProgress: (ActivitySyncProgress) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
    /** Directory filter value; 3 = pending uploads, 0/1 = full/default. */
    private val directoryFilter: Int = FILTER_ONLY_NEW,
    /** When true, log the directory only and never download or archive. */
    private val fullScan: Boolean = false,
) {
    private enum class Phase {
        IDLE,
        WAIT_SYNC_SESSION,
        WAIT_FILTER,
        WAIT_SUPPORTED_TYPES,
        DIRECTORY_REQUESTED,
        DIRECTORY_RECEIVING,
        FILE_REQUESTED,
        FILE_RECEIVING,
        DONE,
    }

    internal data class Entry(
        val fileIndex: Int,
        val dataType: Int,
        val subType: Int,
        val fileNumber: Int,
        val specificFlags: Int,
        val fileFlags: Int,
        val size: Long,
        val timestamp: Long,
    ) {
        val typeName: String get() = "FIT_TYPE_$subType"
        val isActivity: Boolean get() = dataType == FIT_DATA_TYPE && subType == FIT_ACTIVITY_SUBTYPE
        val isWellness: Boolean get() = typeName in WELLNESS_TYPES

        /**
         * Stable synthetic UID for the imported-file caches. Legacy entries do
         * not carry a UUID; the file number, size and wire timestamp identify
         * the same file even if the watch re-indexes the directory.
         */
        val uid: String
            get() = "legacy:$dataType:$subType:$fileNumber:$size:$timestamp"
    }

    private var phase = Phase.IDLE
    private var generation = 0
    private var timeout: Runnable? = null
    private var done = false

    private val entries = ArrayDeque<Entry>()
    private var current: Entry? = null
    private var buffer: ByteArray? = null
    private var received = 0
    private var runningCrc = 0
    private var doneCount = 0
    private var total = 0
    private var fileListStarted = false

    /** Entry whose bytes are written but whose import has not reported back. */
    private var pendingImportEntry: Entry? = null
    private var importToken = 0
    private var importTimeout: Runnable? = null

    fun reset() {
        generation++
        cancelTimeout()
        phase = Phase.IDLE
        entries.clear()
        current = null
        buffer = null
        received = 0
        runningCrc = 0
        doneCount = 0
        total = 0
        fileListStarted = false
        pendingImportEntry = null
        importToken++
        cancelImportTimeout()
        done = false
    }

    fun start() {
        reset()
        log("LEGACY: starting V1 file transfer")
        // Garmin Connect's legacy SyncManager sends a Core.SyncRequest and
        // waits for the watch's SYNCHRONIZATION (5037) before touching the
        // file manager. Fall back to the directory handshake if the watch
        // does not answer.
        phase = Phase.WAIT_SYNC_SESSION
        requestSyncSession()
        val expected = generation
        handler.postDelayed({
            if (expected == generation && !done && !fileListStarted &&
                phase == Phase.WAIT_SYNC_SESSION
            ) {
                log("LEGACY: no 5037 sync notification; starting file list anyway")
                startFileList()
            }
        }, SYNC_SESSION_TIMEOUT_MS)
    }

    fun isDone(): Boolean = done

    fun isTransferring(): Boolean = !done

    /** Feeds a decoded GFDI frame; called on the coordinator's main handler. */
    fun onFrame(frame: Gfdi.Frame) {
        if (done) return
        when (frame.type) {
            Gfdi.MessageType.RESPONSE.id -> handleResponse(frame.payload)
            MT_FILE_TRANSFER_DATA -> handleTransferData(frame.payload)
            MT_FILE_AVAILABLE -> handleFileAvailable(frame.payload)
            MT_SYNCHRONIZATION -> handleSynchronization(frame.payload)
        }
    }

    // ---- Directory ---------------------------------------------------------

    /**
     * Mirrors Garmin Connect's `FileManager.listFiles`:
     * `DIRECTORY_FILTER (5007)` → `SUPPORTED_FILE_TYPES (5031)` →
     * `DOWNLOAD_REQUEST (5002)` for file index 0.
     */
    private fun startFileList() {
        if (fileListStarted || done) return
        fileListStarted = true
        cancelTimeout()
        phase = Phase.WAIT_FILTER
        log("LEGACY → DirectoryFilter ${filterName(directoryFilter)}")
        session.sendGfdi(Gfdi.build(MT_DIRECTORY_FILTER, byteArrayOf(directoryFilter.toByte())))
        // Some firmware does not answer 5007 but still serves the directory.
        val expected = generation
        handler.postDelayed({
            if (expected == generation && !done && phase == Phase.WAIT_FILTER) {
                log("LEGACY: no filter response; requesting supported types")
                requestSupportedFileTypes()
            }
        }, STEP_TIMEOUT_MS)
    }

    private fun requestSupportedFileTypes() {
        if (done) return
        cancelTimeout()
        phase = Phase.WAIT_SUPPORTED_TYPES
        log("LEGACY → SupportedFileTypesRequest")
        session.sendGfdi(Gfdi.build(MT_SUPPORTED_FILE_TYPES))
        val expected = generation
        handler.postDelayed({
            if (expected == generation && !done && phase == Phase.WAIT_SUPPORTED_TYPES) {
                log("LEGACY: no supported-file-types response; requesting directory")
                requestDirectory()
            }
        }, STEP_TIMEOUT_MS)
    }

    private fun requestDirectory() {
        if (done) return
        cancelTimeout()
        phase = Phase.DIRECTORY_REQUESTED
        log("LEGACY → DownloadRequest directory")
        sendDownloadRequest(fileIndex = 0)
        armTimeout(15_000, "directory status")
    }

    private fun startNext() {
        if (done || pendingImportEntry != null) return
        val next = entries.removeFirstOrNull()
        if (next == null) {
            finish()
            return
        }
        current = next
        phase = Phase.FILE_REQUESTED
        log(
            "LEGACY → DownloadRequest file=${next.fileIndex} type=${next.typeName} " +
                "size=${next.size} remaining=${entries.size}",
        )
        sendDownloadRequest(next.fileIndex)
        reportProgress()
        armTimeout(20_000, "file ${next.fileIndex}")
    }

    private fun parseDirectory(bytes: ByteArray) {
        if (bytes.size % DIRECTORY_ENTRY_SIZE != 0) {
            log("LEGACY: directory length ${bytes.size} is not a multiple of $DIRECTORY_ENTRY_SIZE")
        }

        val parsed = parseEntries(
            bytes,
            directoryStart(bytes),
            includeArchived = fullScan,
            includeAllTypes = fullScan,
        )
        if (fullScan) {
            log(
                "LEGACY: full scan filter=${filterName(directoryFilter)} " +
                    "entries=${parsed.size} (read-only; nothing downloaded or archived)",
            )
            parsed.forEach { entry ->
                log(
                    "LEGACY: entry index=${entry.fileIndex} type=${entry.typeName} " +
                        "dataType=${entry.dataType} fileNumber=${entry.fileNumber} " +
                        "flags=0x%02x size=${entry.size} timestamp=${entry.timestamp}"
                            .format(entry.fileFlags),
                )
            }
            finish()
            return
        }

        val found = parsed.filter { entry ->
            if (entry.isActivity && isActivityKnown(entry.uid)) {
                log("LEGACY: activity ${entry.fileIndex} already imported")
                false
            } else if (entry.isWellness && isWellnessKnown(entry.uid)) {
                log("LEGACY: wellness ${entry.typeName} ${entry.fileIndex} already imported")
                false
            } else {
                true
            }
        }

        val seen = entries.map { it.uid }.toHashSet()
        found.forEach { entry ->
            if (seen.add(entry.uid)) entries.addLast(entry)
        }
        total = doneCount + entries.size + (if (current != null) 1 else 0)
        log(
            "LEGACY: directory files=${found.size} queued=${entries.size} " +
                "activities=${found.count { it.isActivity }} wellness=${found.count { it.isWellness }}",
        )
        reportProgress()
    }

    // ---- Transfers ---------------------------------------------------------

    private fun beginReceive(next: Phase, size: Long) {
        if (size <= 0 || size > MAX_FILE_SIZE) {
            fail("Invalid legacy file size: $size")
            return
        }
        buffer = ByteArray(size.toInt())
        received = 0
        runningCrc = 0
        phase = next
        armTimeout(60_000, "file data")
    }

    private fun handleTransferData(payload: ByteArray) {
        val chunk = fileData(payload) ?: return
        val crc = chunk.crc
        val offset = chunk.offset
        val data = chunk.data
        val target = buffer ?: run {
            log("LEGACY: ignoring ${data.size}-byte data with no active download")
            return
        }
        if (offset != received.toLong()) {
            log("LEGACY: offset mismatch expected=$received got=$offset")
            sendDataStatus(offset.toInt(), TRANSFER_OFFSET_MISMATCH)
            fail("The watch lost sync while sending a file.")
            return
        }
        if (offset + data.size > target.size.toLong()) {
            log("LEGACY: chunk overflow offset=$offset len=${data.size} size=${target.size}")
            sendDataStatus(offset.toInt(), TRANSFER_ABORT)
            fail("The watch sent more data than expected.")
            return
        }
        val computed = Crc16.continueFrom(runningCrc, data)
        if (computed != crc) {
            log(
                "LEGACY: CRC mismatch offset=$offset expected=%04x got=%04x"
                    .format(crc, computed),
            )
            sendDataStatus(offset.toInt(), TRANSFER_CRC_MISMATCH)
            fail("The watch's file data was corrupted in transit.")
            return
        }
        System.arraycopy(data, 0, target, offset.toInt(), data.size)
        received += data.size
        runningCrc = computed
        sendDataStatus(received, TRANSFER_OK)
        if (received >= target.size) {
            completeTransfer()
        } else {
            // Inactivity timeout, not a total-transfer limit: reset it for
            // every chunk so large FIT files can take as long as they need.
            armTimeout(60_000, "file data")
        }
    }

    private fun completeTransfer() {
        val bytes = buffer ?: return
        buffer = null
        when (phase) {
            Phase.DIRECTORY_RECEIVING -> {
                phase = Phase.IDLE
                cancelTimeout()
                log(
                    "LEGACY: directory received ${bytes.size} bytes, header=" +
                        Hex.encode(bytes.copyOfRange(0, minOf(32, bytes.size))),
                )
                parseDirectory(bytes)
                if (entries.isEmpty()) {
                    finish()
                } else {
                    total = doneCount + entries.size + (if (current != null) 1 else 0)
                    reportProgress()
                    startNext()
                }
            }
            Phase.FILE_RECEIVING -> {
                val entry = current ?: run {
                    fail("The watch finished a file it never announced.")
                    return
                }
                current = null
                phase = Phase.IDLE
                cancelTimeout()
                if (!handleCompletedFile(entry, bytes)) {
                    scheduleNext()
                }
            }
            else -> Unit
        }
    }

    private fun handleCompletedFile(entry: Entry, bytes: ByteArray): Boolean {
        val file = File(filesDir, "legacy_${entry.fileIndex}_${entry.fileNumber}_${entry.timestamp}.fit")
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            log("LEGACY: could not write ${file.name}: ${e.message}")
            return false
        }
        if (entry.isActivity) {
            log("LEGACY: activity ${entry.typeName} index=${entry.fileIndex} bytes=${bytes.size}")
            awaitImport(entry) { onResult ->
                onActivityFile(file, file, entry.uid, onResult)
            }
        } else {
            log("LEGACY: wellness ${entry.typeName} index=${entry.fileIndex} bytes=${bytes.size}")
            awaitImport(entry) { onResult ->
                onWellnessFile(file, entry.typeName, entry.uid, onResult)
            }
        }
        return true
    }

    /**
     * Waits for the importer before archiving. Garmin archives a file after its
     * cloud upload succeeds; a local client must archive only after the local
     * import succeeds, otherwise a failed import loses the watch copy forever.
     */
    private fun awaitImport(entry: Entry, start: ((Boolean) -> Unit) -> Unit) {
        pendingImportEntry = entry
        val token = ++importToken
        val expected = generation
        armImportTimeout(entry, token, expected)
        runCatching {
            start { success -> onImportResult(entry, token, expected, success) }
        }.onFailure { e ->
            log(
                "LEGACY: could not start import for ${entry.typeName} " +
                    "index=${entry.fileIndex}: ${e.message}",
            )
            finishImportedFile(entry, success = false)
        }
    }

    private fun onImportResult(entry: Entry, token: Int, expected: Int, success: Boolean) {
        handler.post {
            if (done || expected != generation || token != importToken ||
                pendingImportEntry?.fileIndex != entry.fileIndex
            ) {
                return@post
            }
            finishImportedFile(entry, success)
        }
    }

    private fun finishImportedFile(entry: Entry, success: Boolean) {
        if (pendingImportEntry?.fileIndex != entry.fileIndex) return
        pendingImportEntry = null
        importToken++
        cancelImportTimeout()
        doneCount++
        reportProgress()
        if (success) {
            log("LEGACY: ${entry.typeName} index=${entry.fileIndex} imported; archiving on watch")
            sendArchive(entry.fileIndex)
        } else {
            log(
                "LEGACY: ${entry.typeName} index=${entry.fileIndex} not archived; " +
                    "it stays pending on the watch for the next sync",
            )
        }
        scheduleNext()
    }

    private fun armImportTimeout(entry: Entry, token: Int, expected: Int) {
        cancelImportTimeout()
        val runnable = Runnable {
            if (!done && expected == generation && token == importToken &&
                pendingImportEntry?.fileIndex == entry.fileIndex
            ) {
                log(
                    "LEGACY: import of ${entry.typeName} index=${entry.fileIndex} " +
                        "did not finish; leaving it pending on the watch",
                )
                finishImportedFile(entry, success = false)
            }
        }
        importTimeout = runnable
        handler.postDelayed(runnable, IMPORT_TIMEOUT_MS)
    }

    private fun cancelImportTimeout() {
        importTimeout?.let { handler.removeCallbacks(it) }
        importTimeout = null
    }

    private fun scheduleNext() {
        // Give the watch a moment to apply SET_FILE_FLAGS before the next
        // DOWNLOAD_REQUEST. The BLE write queue preserves order.
        val expected = generation
        handler.postDelayed({
            if (!done && expected == generation) startNext()
        }, 200)
    }

    // ---- Incoming messages -------------------------------------------------

    private fun handleResponse(payload: ByteArray) {
        if (payload.size < 3) return
        val originalType = u16(payload, 0)
        val status = payload[2].toInt() and 0xFF
        val data = payload.copyOfRange(3, payload.size)
        when (originalType) {
            MT_DOWNLOAD_REQUEST -> handleDownloadStatus(status, data)
            MT_DIRECTORY_FILTER -> {
                if (status != 0) {
                    // The FR245 docs note that 5007 "does not seem to do
                    // anything"; keep going anyway.
                    log("LEGACY: directory filter status=$status; continuing")
                }
                if (phase == Phase.WAIT_FILTER) {
                    log("LEGACY: directory filter answered")
                    requestSupportedFileTypes()
                }
            }
            MT_SUPPORTED_FILE_TYPES -> {
                if (status != 0) {
                    log("LEGACY: supported file types status=$status; continuing")
                } else {
                    logSupportedFileTypes(data)
                }
                if (phase == Phase.WAIT_SUPPORTED_TYPES) {
                    requestDirectory()
                }
            }
            MT_FILE_TRANSFER_DATA -> {
                // ACK for our data ACK; nothing to do.
            }
            MT_SET_FILE_FLAGS -> {
                if (status != 0) log("LEGACY: archive request status=$status")
            }
        }
    }

    private fun handleDownloadStatus(status: Int, data: ByteArray) {
        if (status != 0) {
            fail("The watch refused the download request (status $status).")
            return
        }
        val parsedStatus = parseDownloadStatus(data)
        if (parsedStatus == null) {
            fail("The watch sent a short download status.")
            return
        }
        val downloadStatus = parsedStatus.first
        val maxSize = parsedStatus.second
        val entry = current
        if (downloadStatus != 0) {
            val reason = downloadStatusName(downloadStatus)
            if (entry == null) {
                fail("The watch could not open its file directory ($reason).")
            } else {
                log("LEGACY: file ${entry.fileIndex} could not be opened ($reason); skipping")
                current = null
                phase = Phase.IDLE
                cancelTimeout()
                val expected = generation
                handler.postDelayed({
                    if (!done && expected == generation) startNext()
                }, 200)
            }
            return
        }
        if (entry == null) {
            // Directory response.
            if (maxSize <= 0) {
                log("LEGACY: watch announced no directory; waiting for file notifications")
                phase = Phase.IDLE
                cancelTimeout()
                if (entries.isNotEmpty()) {
                    startNext()
                } else {
                    armTimeout(2_000, "file list")
                }
                return
            }
            log("LEGACY: directory size=$maxSize")
            beginReceive(Phase.DIRECTORY_RECEIVING, maxSize)
            return
        }
        val size = if (maxSize > 0) maxSize else entry.size
        log("LEGACY: file ${entry.fileIndex} size=$size")
        beginReceive(Phase.FILE_RECEIVING, size)
    }

    private fun handleFileAvailable(payload: ByteArray) {
        sendAck(MT_FILE_AVAILABLE)
        if (payload.size < DIRECTORY_ENTRY_SIZE) return
        val fileIndex = u16(payload, 0)
        val dataType = payload[2].toInt() and 0xFF
        val subType = payload[3].toInt() and 0xFF
        val fileNumber = u16(payload, 4)
        val specificFlags = payload[6].toInt() and 0xFF
        val fileFlags = payload[7].toInt() and 0xFF
        val size = u32(payload, 8)
        val timestamp = u32(payload, 12)
        if (fileIndex == 0 || dataType != FIT_DATA_TYPE || size <= 0) return
        val entry = Entry(
            fileIndex = fileIndex,
            dataType = dataType,
            subType = subType,
            fileNumber = fileNumber,
            specificFlags = specificFlags,
            fileFlags = fileFlags,
            size = size,
            timestamp = timestamp,
        )
        if (!entry.isActivity && !entry.isWellness) return
        if (entry.isActivity && isActivityKnown(entry.uid)) return
        if (entry.isWellness && isWellnessKnown(entry.uid)) return
        if (current?.fileIndex == entry.fileIndex ||
            entries.any { it.fileIndex == entry.fileIndex }
        ) {
            return
        }

        entries.addLast(entry)
        total = doneCount + entries.size + (if (current != null) 1 else 0)
        reportProgress()
        log("LEGACY: file available ${entry.typeName} index=${entry.fileIndex} size=${entry.size}")
        if (phase == Phase.IDLE && current == null) {
            startNext()
        } else if (phase == Phase.DIRECTORY_REQUESTED) {
            // Some firmware answers the directory request only with
            // FILE_AVAILABLE notifications. If no directory status follows,
            // start the queued files after a short grace period.
            val expected = generation
            handler.postDelayed({
                if (!done && expected == generation && current == null &&
                    phase == Phase.DIRECTORY_REQUESTED && entries.isNotEmpty()
                ) {
                    cancelTimeout()
                    phase = Phase.IDLE
                    startNext()
                }
            }, 2_000)
        }
    }

    private fun handleSynchronization(payload: ByteArray) {
        sendAck(MT_SYNCHRONIZATION)
        val type = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
        val size = if (payload.size > 1) payload[1].toInt() and 0xFF else 0
        val mask = if (size > 0 && payload.size >= 2 + size) {
            payload.copyOfRange(2, 2 + size).joinToString("") { "%02x".format(it) }
        } else {
            ""
        }
        log("LEGACY: synchronization type=$type mask=$mask")
        // This is the official trigger: the watch is now ready for the
        // directory handshake.
        startFileList()
    }

    private fun logSupportedFileTypes(data: ByteArray) {
        if (data.isEmpty()) return
        val count = data[0].toInt() and 0xFF
        val names = ArrayList<String>()
        var offset = 1
        repeat(count) {
            if (offset + 3 > data.size) return@repeat
            val dataType = data[offset].toInt() and 0xFF
            val subType = data[offset + 1].toInt() and 0xFF
            val nameLen = data[offset + 2].toInt() and 0xFF
            offset += 3
            if (offset + nameLen > data.size) return@repeat
            val name = String(data, offset, nameLen, Charsets.UTF_8)
            offset += nameLen
            names.add("$dataType/$subType:$name")
        }
        if (names.isNotEmpty()) log("LEGACY: supported types ${names.joinToString(", ")}")
    }

    // ---- Messaging ---------------------------------------------------------

    private fun sendDownloadRequest(fileIndex: Int) {
        session.sendGfdi(Gfdi.build(MT_DOWNLOAD_REQUEST, downloadRequestPayload(fileIndex)))
    }

    private fun sendDataStatus(offset: Int, transferStatus: Int) {
        session.sendGfdi(
            Gfdi.buildResponse(
                MT_FILE_TRANSFER_DATA,
                Gfdi.Status.ACK.code,
                dataStatusPayload(offset, transferStatus),
            ),
        )
    }

    private fun sendArchive(fileIndex: Int) {
        val payload = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(fileIndex.toShort())
            .put(FILE_FLAG_ARCHIVE.toByte())
            .array()
        log("LEGACY → SetFileFlags archive index=$fileIndex")
        session.sendGfdi(Gfdi.build(MT_SET_FILE_FLAGS, payload))
    }

    private fun sendAck(type: Int) {
        session.sendGfdi(Gfdi.buildResponse(type, Gfdi.Status.ACK.code))
    }

    // ---- Completion / errors ----------------------------------------------

    private fun finish() {
        if (done) return
        done = true
        phase = Phase.DONE
        cancelTimeout()
        pendingImportEntry = null
        importToken++
        cancelImportTimeout()
        onProgress(ActivitySyncProgress(done = doneCount, total = total, currentSize = null))
        log("LEGACY: complete files=$doneCount")
        onFinished()
    }

    private fun fail(message: String) {
        if (done) return
        done = true
        phase = Phase.DONE
        cancelTimeout()
        pendingImportEntry = null
        importToken++
        cancelImportTimeout()
        log("LEGACY: $message")
        onError(message)
    }

    private fun reportProgress() {
        onProgress(
            ActivitySyncProgress(
                done = doneCount,
                total = total.coerceAtLeast(doneCount),
                currentSize = current?.size,
            ),
        )
    }

    private fun armTimeout(ms: Long, what: String) {
        cancelTimeout()
        val expected = generation
        val runnable = Runnable {
            if (expected == generation && !done) {
                fail("The watch stopped responding while waiting for $what.")
            }
        }
        timeout = runnable
        handler.postDelayed(runnable, ms)
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun downloadStatusName(code: Int): String = when (code) {
        1 -> "INDEX_NOT_FOUND"
        2 -> "NOT_READABLE"
        3 -> "NOT_READY"
        4 -> "INVALID_REQUEST"
        5 -> "CRC_MISMATCH"
        6 -> "RANGE_EXCEEDS_FILE"
        else -> "UNKNOWN"
    }

    private fun filterName(filter: Int): String = when (filter) {
        FILTER_NO_FILTER -> "no-filter"
        FILTER_DEFAULT -> "default"
        FILTER_ONLY_NEW -> "only-new"
        else -> "unknown($filter)"
    }

    internal data class FileChunk(val crc: Int, val offset: Long, val data: ByteArray)

    internal companion object {
        const val MT_DOWNLOAD_REQUEST = 5002
        const val MT_FILE_TRANSFER_DATA = 5004
        const val MT_DIRECTORY_FILTER = 5007
        const val MT_SET_FILE_FLAGS = 5008
        const val MT_FILE_AVAILABLE = 5009
        const val MT_SUPPORTED_FILE_TYPES = 5031
        const val MT_SYNCHRONIZATION = 5037

        const val REQUEST_TYPE_NEW = 1
        const val FILTER_NO_FILTER = 0
        const val FILTER_DEFAULT = 1
        const val FILTER_ONLY_NEW = 3
        const val FILE_FLAG_ARCHIVE = 0x10
        const val FILE_FLAG_READ = 0x80

        const val TRANSFER_OK = 0
        const val TRANSFER_ABORT = 2
        const val TRANSFER_CRC_MISMATCH = 3
        const val TRANSFER_OFFSET_MISMATCH = 4

        const val FIT_DATA_TYPE = 128
        const val FIT_ACTIVITY_SUBTYPE = 4

        const val DOWNLOAD_REQUEST_SIZE = 14
        const val DIRECTORY_ENTRY_SIZE = 16
        const val FILE_DATA_HEADER = 7

        const val MAX_FILE_SIZE = 64L * 1024 * 1024
        const val STEP_TIMEOUT_MS = 3_000L
        const val SYNC_SESSION_TIMEOUT_MS = 5_000L
        const val IMPORT_TIMEOUT_MS = 30_000L

        val WELLNESS_TYPES = setOf(
            "FIT_TYPE_29", // monitoring day
            "FIT_TYPE_32", // monitoring delta
            "FIT_TYPE_44", // biometric / metrics
            "FIT_TYPE_49", // sleep
            "FIT_TYPE_72", // Firstbeat daily analytics
        )

        /** Payload for `DOWNLOAD_REQUEST (5002)`; the status reply supplies the size. */
        internal fun downloadRequestPayload(fileIndex: Int): ByteArray =
            ByteBuffer.allocate(DOWNLOAD_REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(fileIndex.toShort())
                .putInt(0) // data offset
                .put(REQUEST_TYPE_NEW.toByte())
                .putShort(0) // CRC seed
                .putInt(0) // data size
                .put(0) // request flags
                .array()

        /** Payload for the ACK sent after every `FILE_TRANSFER_DATA (5004)` chunk. */
        internal fun dataStatusPayload(offset: Int, transferStatus: Int): ByteArray =
            ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(transferStatus.toByte())
                .putInt(offset)
                .array()

        /** Parses `[download_status:u8][max_file_size:u32]`; null when truncated. */
        internal fun parseDownloadStatus(data: ByteArray): Pair<Int, Long>? {
            if (data.size < 5) return null
            return (data[0].toInt() and 0xFF) to u32(data, 1)
        }

        /** Parses `[flags:u8][crc:u16][offset:u32][data…]`; null when truncated. */
        internal fun fileData(payload: ByteArray): FileChunk? {
            if (payload.size < FILE_DATA_HEADER) return null
            return FileChunk(
                crc = u16(payload, 1),
                offset = u32(payload, 3),
                data = payload.copyOfRange(FILE_DATA_HEADER, payload.size),
            )
        }

        /**
         * Garmin's parser always skips the 16-byte root/directory header.
         * Some firmware answers with entries directly, so fall back to offset
         * 0 only when the first block looks like a real FIT entry.
         */
        internal fun directoryStart(bytes: ByteArray): Int =
            if (bytes.size >= 32 && (bytes[2].toInt() and 0xFF) != FIT_DATA_TYPE) {
                DIRECTORY_ENTRY_SIZE
            } else {
                0
            }

        /**
         * Parses the 16-byte legacy directory entries, ignoring non-FIT traffic.
         *
         * [includeArchived] is used by the read-only full scan: it keeps
         * entries that Garmin's client would hide (no READ flag, ARCHIVE set)
         * so they can be logged and diagnosed. [includeAllTypes] also keeps
         * FIT subtypes this client does not import.
         */
        internal fun parseEntries(
            bytes: ByteArray,
            start: Int = 0,
            includeArchived: Boolean = false,
            includeAllTypes: Boolean = false,
        ): List<Entry> {
            val out = ArrayList<Entry>()
            var offset = start
            while (offset + DIRECTORY_ENTRY_SIZE <= bytes.size) {
                val fileIndex = u16(bytes, offset)
                val dataType = bytes[offset + 2].toInt() and 0xFF
                val subType = bytes[offset + 3].toInt() and 0xFF
                val fileNumber = u16(bytes, offset + 4)
                val specificFlags = bytes[offset + 6].toInt() and 0xFF
                val fileFlags = bytes[offset + 7].toInt() and 0xFF
                val size = u32(bytes, offset + 8)
                val timestamp = u32(bytes, offset + 12)
                offset += DIRECTORY_ENTRY_SIZE

                if (fileIndex == 0 || dataType != FIT_DATA_TYPE || size <= 0) continue
                if (!includeArchived) {
                    // Match Garmin's FileManagerCompat.listFiles filter: the
                    // watch must grant READ and must not have archived it yet.
                    if (fileFlags and FILE_FLAG_READ == 0) continue
                    if (fileFlags and FILE_FLAG_ARCHIVE != 0) continue
                }
                val entry = Entry(
                    fileIndex = fileIndex,
                    dataType = dataType,
                    subType = subType,
                    fileNumber = fileNumber,
                    specificFlags = specificFlags,
                    fileFlags = fileFlags,
                    size = size,
                    timestamp = timestamp,
                )
                if (entry.isActivity || entry.isWellness || includeAllTypes) out.add(entry)
            }
            return out
        }

        internal fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        internal fun u32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
