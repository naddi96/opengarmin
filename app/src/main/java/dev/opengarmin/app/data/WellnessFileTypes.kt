package dev.opengarmin.app.data

/**
 * Wellness FIT file types the sync path pulls from the watch directory.
 *
 * `FIT_TYPE_44` (BIOMETRIC/METRICS) and `FIT_TYPE_49` (SLEEP_DATA) are
 * immutable history: once imported, their UID can be remembered so a later
 * sync skips the download. `FIT_TYPE_72` is the FR255's daily Firstbeat file;
 * its `fbt_lha_daily_data` messages carry sleep score, sleep window and
 * overnight rMSSD every day, including days when the watch does not expose a
 * separate SLEEP_DATA file. The same item UID is rewritten as new days are
 * added, so it is cached by UID + transaction id instead of UID alone.
 *
 * Monitoring files are rewritten by every wellness cut and must not be cached
 * the same way. The current monitoring (FIT_TYPE_32) delta is pushed by
 * CutWellnessFilesRequest, so it is intentionally excluded from the directory
 * pull to avoid downloading dozens of historical monitoring files on every
 * activity sync.
 */
object WellnessFileTypes {
    val pullable = setOf("FIT_TYPE_44", "FIT_TYPE_49", "FIT_TYPE_72")
    val immutable = setOf("FIT_TYPE_44", "FIT_TYPE_49", "FIT_TYPE_72")

    fun isPullable(type: String?): Boolean = type in pullable

    fun isImmutable(type: String?): Boolean = type in immutable

    /**
     * Cache key for an already-imported wellness item. Firstbeat's daily file
     * keeps its UID but is rewritten as days are added, so its transaction id
     * is part of the key; sleep/metrics files use the UID alone.
     */
    fun cacheKey(type: String?, uid: String, transactionId: Int): String =
        if (type == "FIT_TYPE_72" && transactionId != 0) "$uid:$transactionId" else uid
}
