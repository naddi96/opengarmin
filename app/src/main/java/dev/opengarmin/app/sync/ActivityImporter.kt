package dev.opengarmin.app.sync

import android.content.Context
import android.net.Uri
import dev.opengarmin.app.data.ActivityEntity
import dev.opengarmin.app.data.ActivityRepository
import dev.opengarmin.app.data.WellnessFileTypes
import dev.opengarmin.app.diagnostics.Diagnostics
import dev.opengarmin.app.util.formatDate
import dev.opengarmin.app.sportName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface FitImportResult {
    data class Imported(val activity: ActivityEntity) : FitImportResult

    data object AlreadyImported : FitImportResult

    data class Failed(val message: String) : FitImportResult
}

/**
 * Imports FIT activity and wellness files into the local database and reports
 * progress through [SyncUiState].
 */
class ActivityImporter(
    private val context: Context,
    private val repository: ActivityRepository,
    private val scope: CoroutineScope,
    private val ui: SyncUiState,
) {
    @Volatile
    private var knownWellnessUids: Set<String> = emptySet()

    /**
     * Item UIDs already stored in Room. The foreground sync checks this set in
     * addition to the observed activity list, so a sync that starts before the
     * database flow has emitted its first value cannot re-download history.
     */
    @Volatile
    private var knownActivityUids: Set<String> = emptySet()

    /** Activity UIDs whose imports failed often enough to stop retrying. */
    @Volatile
    private var quarantinedActivityUids: Set<String> = emptySet()

    /** Invoked on the main thread after a sync import so the controller can refresh its notification. */
    var onImportProgress: (() -> Unit)? = null

    /** Invoked after a new activity is stored, for automatic background analysis. */
    var onActivityImported: ((ActivityEntity) -> Unit)? = null

    fun setKnownWellnessUids(uids: Set<String>) {
        knownWellnessUids = uids
    }

    fun isKnownWellnessUid(uid: String): Boolean =
        uid.isNotEmpty() && uid in knownWellnessUids

    suspend fun reloadKnownWellnessUids() {
        knownWellnessUids = runCatching { repository.importedWellnessFileIds() }
            .getOrDefault(emptySet())
    }

    fun isKnownActivityUid(uid: String): Boolean =
        uid.isNotEmpty() && (uid in knownActivityUids || uid in quarantinedActivityUids)

    suspend fun reloadKnownActivityUids() {
        knownActivityUids = runCatching { repository.activityItemUids() }
            .getOrDefault(emptySet())
        quarantinedActivityUids = runCatching { repository.quarantinedActivityItemUids() }
            .getOrDefault(emptySet())
    }

    fun clearImportedLabel() {
        ui.lastImportedLabel = null
    }

    fun importFit(file: File, itemUid: String, onResult: ((Boolean) -> Unit)? = null) {
        ui.phase = SyncPhase.Importing
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importFit(file.name, file.readBytes(), itemUid) }
            }
            ui.phase = if (ui.activityProgress != null) SyncPhase.Syncing else SyncPhase.UpToDate
            result.onSuccess { entity ->
                if (entity == null) {
                    ui.log("FIT has no session or was already imported")
                } else {
                    if (itemUid.isNotEmpty()) knownActivityUids = knownActivityUids + itemUid
                    ui.importedThisSync++
                    ui.lastImportedLabel = importedLabel(entity)
                    ui.log(
                        "IMPORTED ${formatDate(entity.startTime)} ${sportName(entity.sport)} " +
                            "%.2f km".format(entity.distanceMeters / 1000.0),
                    )
                }
                onResult?.invoke(true)
                if (entity != null) onActivityImported?.invoke(entity)
            }.onFailure { e ->
                val message = e.message ?: "FIT import failed"
                Diagnostics.record(
                    area = Diagnostics.AREA_IMPORT,
                    code = "FIT_IMPORT_FAILED",
                    message = message,
                    throwable = e,
                    context = mapOf("file" to file.name),
                )
                if (itemUid.isNotEmpty()) {
                    runCatching { repository.recordActivityImportFailure(itemUid, message) }
                }
                ui.importErrorThisSync = message
                ui.log("FIT import failed: $message")
                onResult?.invoke(false)
            }
            if (ui.activityProgress != null) {
                onImportProgress?.invoke()
            }
        }
    }

    fun importFitFromUri(uri: Uri) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                Diagnostics.record(
                    area = Diagnostics.AREA_IMPORT,
                    code = "FIT_READ_FAILED",
                    message = "Could not read the selected FIT file.",
                    context = mapOf("uri" to uri.toString()),
                )
                ui.log("FIT import failed: could not read the selected file")
                return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "imported.fit"
            importFitBytes(name, bytes)
        }
    }

    /**
     * Imports a FIT payload that did not come from a document picker (currently
     * a Garmin Connect download). The bytes are kept in app storage so the
     * original watch/cloud file remains exportable.
     */
    suspend fun importFitBytes(name: String, bytes: ByteArray): FitImportResult {
        // Keep the selected FIT file in app storage so Export → FIT can
        // return the original file later. The timestamp prevents two imports
        // with the same display name from colliding.
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                File(context.filesDir, "import-${System.currentTimeMillis()}-${safeFitName(name)}")
                    .also { it.writeBytes(bytes) }
            }.getOrNull()
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                repository.importFit(stored?.name ?: safeFitName(name), bytes, itemUid = "")
            }
        }
        return result.fold(
            onSuccess = { entity ->
                if (entity == null) {
                    stored?.delete()
                    ui.log("FIT import: no session found or activity already exists")
                    FitImportResult.AlreadyImported
                } else {
                    ui.importedThisSync++
                    ui.lastImportedLabel = importedLabel(entity)
                    ui.log("IMPORTED from file: ${entity.fileName}")
                    onActivityImported?.invoke(entity)
                    FitImportResult.Imported(entity)
                }
            },
            onFailure = { e ->
                stored?.delete()
                Diagnostics.record(
                    area = Diagnostics.AREA_IMPORT,
                    code = "FIT_IMPORT_FAILED",
                    message = e.message ?: "FIT import failed",
                    throwable = e,
                    context = mapOf("file" to name),
                )
                ui.log("FIT import failed: ${e.message}")
                FitImportResult.Failed(e.message ?: "FIT import failed")
            },
        )
    }

    private fun safeFitName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { character ->
            if (character.isLetterOrDigit() || character in "._-") character else '_'
        }.joinToString("").take(100)
        val safe = cleaned.ifBlank { "imported.fit" }
        return if (safe.endsWith(".fit", ignoreCase = true)) safe else "$safe.fit"
    }

    fun importWellness(
        file: File,
        type: String,
        itemUid: String,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        scope.launch {
            val immutable = WellnessFileTypes.isImmutable(type)
            val result = withContext(Dispatchers.IO) {
                val days = runCatching { repository.importWellness(file.readBytes()) }
                if (days.isSuccess && immutable) {
                    runCatching { repository.markWellnessFileImported(itemUid, type) }
                }
                days
            }
            result.onSuccess { days ->
                if (days.isEmpty()) {
                    ui.log("WELLNESS $type contained no health samples")
                } else {
                    if (immutable) knownWellnessUids = knownWellnessUids + itemUid
                    days.forEach { day ->
                        val distance = "%.2f".format(day.distanceMeters / 1000.0)
                        ui.log(
                            "WELLNESS imported ${day.date}: ${day.steps} steps, " +
                                "$distance km, resting HR ${day.restingHeartRate ?: "—"}, " +
                                "sleep score ${day.sleepScore ?: "—"}, " +
                                "Body Battery ${day.bodyBatteryLatest ?: "—"}",
                        )
                    }
                }
                onResult?.invoke(true)
            }.onFailure { e ->
                Diagnostics.record(
                    area = Diagnostics.AREA_IMPORT,
                    code = "WELLNESS_IMPORT_FAILED",
                    message = e.message ?: "Wellness import failed",
                    throwable = e,
                    context = mapOf("type" to type, "itemUid" to itemUid),
                )
                ui.log("WELLNESS import failed: ${e.message}")
                onResult?.invoke(false)
            }
        }
    }

    private fun importedLabel(entity: ActivityEntity): String = buildString {
        append(formatDate(entity.startTime))
        append(" · ")
        append(sportName(entity.sport))
        if (entity.distanceMeters > 0) {
            append(" · ")
            append("%.2f km".format(entity.distanceMeters / 1000.0))
        }
    }
}
