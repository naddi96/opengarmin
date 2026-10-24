package dev.opengarmin.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** One deduplicated diagnostic event, as shown in the debug screen. */
data class AppEvent(
    val id: Long,
    val createdAt: Long,
    val lastSeenAt: Long,
    val occurrences: Int,
    val level: String,
    val area: String,
    val code: String,
    val message: String,
    val stackTrace: String?,
    val contextJson: String,
    val sessionId: String,
)

/** A new event handed to [AppEventStore.record]. */
data class AppEventWrite(
    val level: String,
    val area: String,
    val code: String,
    val message: String,
    val throwable: Throwable? = null,
    /** Used when only raw text is available, e.g. an imported fallback file. */
    val stackTrace: String? = null,
    val context: Map<String, String> = emptyMap(),
)

/**
 * Dedicated SQLite store for app errors, intentionally separate from the Room
 * health database: the errors worth keeping often happen while that database is
 * locked, full or corrupt. A plain helper (no Room, no KSP) also lets the
 * uncaught-exception handler write synchronously before the process dies.
 *
 * The file lives in the app's private database directory and is never part of
 * the backup zip.
 */
class AppEventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, SCHEMA_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, " +
                "`occurrences` INTEGER NOT NULL DEFAULT 1, " +
                "`level` TEXT NOT NULL, " +
                "`area` TEXT NOT NULL, " +
                "`code` TEXT NOT NULL, " +
                "`message` TEXT NOT NULL, " +
                "`stacktrace` TEXT, " +
                "`context` TEXT NOT NULL DEFAULT '{}', " +
                "`sessionId` TEXT NOT NULL, " +
                "`fingerprint` TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_events_fingerprint` ON `$TABLE` (`fingerprint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_lastSeenAt` ON `$TABLE` (`lastSeenAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_area_code` ON `$TABLE` (`area`, `code`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `$META` (" +
                "`key` TEXT PRIMARY KEY NOT NULL, " +
                "`value` TEXT NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Diagnostic rows are not precious data. A breaking schema change drops
        // the log instead of carrying a migration chain; new columns can be
        // added with ALTER TABLE and kept.
        db.execSQL("DROP TABLE IF EXISTS `$TABLE`")
        db.execSQL("DROP TABLE IF EXISTS `$META`")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    /**
     * Inserts a new event or folds it into the existing row with the same
     * fingerprint. Never called on the main thread except from the crash
     * handler, where a synchronous write is the whole point.
     */
    @Synchronized
    fun record(event: AppEventWrite, sessionId: String, now: Long = System.currentTimeMillis()) {
        val message = DiagnosticsFormat.message(event.message)
        val stack = DiagnosticsFormat.stackTrace(
            event.throwable?.stackTraceToString() ?: event.stackTrace,
        )
        val context = DiagnosticsFormat.context(event.context)
        val fingerprint = DiagnosticsFormat.fingerprint(
            area = event.area,
            code = event.code,
            throwable = event.throwable,
            message = message,
        )

        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = db.compileStatement(
                "UPDATE `$TABLE` SET `lastSeenAt` = ?, `occurrences` = `occurrences` + 1, " +
                    "`level` = ?, `message` = ?, `stacktrace` = ?, `context` = ? " +
                    "WHERE `fingerprint` = ?",
            )
            update.bindLong(1, now)
            update.bindString(2, event.level)
            update.bindString(3, message)
            if (stack != null) update.bindString(4, stack) else update.bindNull(4)
            update.bindString(5, context)
            update.bindString(6, fingerprint)
            val updated = update.executeUpdateDelete()
            if (updated == 0) {
                val values = ContentValues().apply {
                    put("createdAt", now)
                    put("lastSeenAt", now)
                    put("occurrences", 1)
                    put("level", event.level)
                    put("area", event.area)
                    put("code", event.code)
                    put("message", message)
                    put("stacktrace", stack)
                    put("context", context)
                    put("sessionId", sessionId)
                    put("fingerprint", fingerprint)
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(limit: Int = 500, area: String? = null): List<AppEvent> {
        val selection = if (area == null) null else "`area` = ?"
        val args = if (area == null) null else arrayOf(area)
        val cursor = readableDatabase.query(
            TABLE,
            COLUMNS,
            selection,
            args,
            null,
            null,
            "`lastSeenAt` DESC",
            limit.toString(),
        )
        return cursor.use { it.mapToList() }
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM `$TABLE`", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM `$TABLE`")
    }

    /** Drops rows older than [maxAgeMs] and keeps at most [maxRows] newest rows. */
    fun prune(now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS, maxRows: Int = MAX_ROWS) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM `$TABLE` WHERE `lastSeenAt` < ?", arrayOf(now - maxAgeMs))
            db.execSQL(
                "DELETE FROM `$TABLE` WHERE `id` NOT IN (" +
                    "SELECT `id` FROM `$TABLE` ORDER BY `lastSeenAt` DESC LIMIT ?)",
                arrayOf(maxRows),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun meta(key: String): String? =
        readableDatabase.query(META, arrayOf("`value`"), "`key` = ?", arrayOf(key), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun putMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            META,
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** True when a fatal row was stored close to [timestamp] (same process death). */
    fun hasFatalNear(timestamp: Long, windowMs: Long = 15_000L): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM `$TABLE` WHERE `level` = ? AND `lastSeenAt` BETWEEN ? AND ? LIMIT 1",
            arrayOf(Diagnostics.LEVEL_FATAL, (timestamp - windowMs).toString(), (timestamp + windowMs).toString()),
        ).use { it.moveToFirst() }

    /** JSON document for the explicit "Export diagnostics" action. */
    fun exportJson(exportedAt: Long = System.currentTimeMillis()): String {
        val events = events(limit = MAX_ROWS)
        val array = JSONArray()
        for (event in events) {
            val context = runCatching { JSONObject(event.contextJson) }
                .getOrDefault(JSONObject())
            array.put(
                JSONObject().apply {
                    put("createdAt", event.createdAt)
                    put("lastSeenAt", event.lastSeenAt)
                    put("occurrences", event.occurrences)
                    put("level", event.level)
                    put("area", event.area)
                    put("code", event.code)
                    put("message", event.message)
                    put("stacktrace", event.stackTrace ?: JSONObject.NULL)
                    put("context", context)
                    put("sessionId", event.sessionId)
                },
            )
        }
        return JSONObject().apply {
            put("app", "dev.opengarmin.app")
            put("schema", SCHEMA_VERSION)
            put("exportedAt", exportedAt)
            put("eventCount", events.size)
            put("events", array)
        }.toString(2)
    }

    private fun Cursor.mapToList(): List<AppEvent> {
        val rows = ArrayList<AppEvent>(count)
        while (moveToNext()) {
            rows += AppEvent(
                id = getLong(0),
                createdAt = getLong(1),
                lastSeenAt = getLong(2),
                occurrences = getInt(3),
                level = getString(4),
                area = getString(5),
                code = getString(6),
                message = getString(7),
                stackTrace = if (isNull(8)) null else getString(8),
                contextJson = getString(9),
                sessionId = getString(10),
            )
        }
        return rows
    }

    companion object {
        const val DB_NAME = "opengarmin-diagnostics.db"
        const val SCHEMA_VERSION = 1
        private const val TABLE = "app_events"
        private const val META = "diagnostics_meta"
        private const val MAX_ROWS = 5_000
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private val COLUMNS = arrayOf(
            "`id`", "`createdAt`", "`lastSeenAt`", "`occurrences`", "`level`", "`area`",
            "`code`", "`message`", "`stacktrace`", "`context`", "`sessionId`",
        )
    }
}
