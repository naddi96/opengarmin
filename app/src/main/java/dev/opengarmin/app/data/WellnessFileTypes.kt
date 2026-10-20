package dev.opengarmin.app.data

/**
 * Wellness FIT file types the sync path pulls from the watch directory.
 *
 * Sleep and HRV files are immutable history: once imported, their UID can be
 * remembered so a later sync skips the download. Monitoring files are rewritten
 * by every wellness cut and must not be cached the same way. The current
 * monitoring (FIT_TYPE_32) delta is pushed by CutWellnessFilesRequest, so it is
 * intentionally excluded from the directory pull to avoid downloading dozens of
 * historical monitoring files on every activity sync.
 */
object WellnessFileTypes {
    val immutable = setOf("FIT_TYPE_44", "FIT_TYPE_49")

    fun isImmutable(type: String?): Boolean = type in immutable
}
