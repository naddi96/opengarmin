package dev.opengarmin.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import dev.opengarmin.app.coach.ChatStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

data class BackupImportResult(val chatRestored: Boolean)

/**
 * Exports a consistent snapshot of the Room database together with the local
 * coach conversations, and restores such a backup later.
 *
 * A backup is a ZIP containing `opengarmin.db` and `chat.json`. A raw SQLite
 * database file is also accepted when importing, for flexibility.
 */
object BackupManager {
    private const val DB_ENTRY = "opengarmin.db"
    private const val CHAT_ENTRY = "chat.json"
    private const val MANIFEST_ENTRY = "backup.json"
    private const val SQLITE_HEADER = "SQLite format 3\u0000"

    fun export(context: Context, uri: Uri, chatStore: ChatStore) {
        val snapshot = File(context.cacheDir, "opengarmin-export-${System.currentTimeMillis()}.db")
        try {
            snapshotDatabase(context, snapshot)
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw BackupException("Could not open the selected location for writing.")
            BufferedOutputStream(output).use { buffered ->
                ZipOutputStream(buffered).use { zip ->
                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    snapshot.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(CHAT_ENTRY))
                    zip.write(chatStore.exportJson().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(
                        JSONObject()
                            .put("app", "OpenGarmin")
                            .put("schemaVersion", AppDatabase.SCHEMA_VERSION)
                            .put("createdAt", System.currentTimeMillis())
                            .toString()
                            .toByteArray(Charsets.UTF_8),
                    )
                    zip.closeEntry()
                }
            }
        } finally {
            snapshot.delete()
        }
    }

    /**
     * Imports a backup, replacing the live database and (when present) the
     * conversation history. The caller must restart database observers after
     * this returns.
     */
    fun import(context: Context, uri: Uri, chatStore: ChatStore): BackupImportResult {
        val download = File(context.cacheDir, "opengarmin-import-${System.currentTimeMillis()}.tmp")
        val extractedDb = File(context.cacheDir, "opengarmin-import-${System.currentTimeMillis()}.db")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                download.outputStream().use { out -> input.copyTo(out) }
            } ?: throw BackupException("Could not read the selected backup file.")
            if (download.length() == 0L) throw BackupException("The selected backup file is empty.")

            var chatJson: String? = null
            if (isZip(download)) {
                ZipInputStream(BufferedInputStream(FileInputStream(download))).use { zip ->
                    var foundDb = false
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/').lowercase()
                        when {
                            entry.isDirectory -> Unit
                            name == DB_ENTRY || name.endsWith(".db") -> {
                                extractedDb.outputStream().use { out -> zip.copyTo(out) }
                                foundDb = true
                            }
                            name == CHAT_ENTRY -> {
                                chatJson = zip.readBytes().toString(Charsets.UTF_8)
                            }
                        }
                        zip.closeEntry()
                    }
                    if (!foundDb) {
                        throw BackupException("This ZIP does not contain an OpenGarmin database.")
                    }
                }
            } else {
                download.copyTo(extractedDb, overwrite = true)
            }

            validateDatabase(extractedDb)
            AppDatabase.replaceDatabase(context, extractedDb)
            chatJson?.let { chatStore.importJson(it) }
            return BackupImportResult(chatRestored = chatJson != null)
        } finally {
            download.delete()
            extractedDb.delete()
        }
    }

    private fun snapshotDatabase(context: Context, target: File) {
        val database = AppDatabase.get(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val escaped = target.absolutePath.replace("'", "''")
            database.openHelper.writableDatabase.execSQL("VACUUM INTO '$escaped'")
        } else {
            // VACUUM INTO arrived in Android 10. On older versions, checkpoint
            // the write-ahead log so the main database file is complete.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                it.moveToFirst()
            }
            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            if (!dbFile.exists()) throw BackupException("The local database does not exist yet.")
            dbFile.copyTo(target, overwrite = true)
        }
    }

    private fun isZip(file: File): Boolean {
        val header = ByteArray(4)
        FileInputStream(file).use { input -> if (input.read(header) < header.size) return false }
        return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
    }

    private fun validateDatabase(file: File) {
        if (!file.exists() || file.length() < 512) {
            throw BackupException("The selected file is not a valid OpenGarmin database.")
        }
        val header = ByteArray(SQLITE_HEADER.length)
        FileInputStream(file).use { it.read(header) }
        if (String(header, Charsets.ISO_8859_1) != SQLITE_HEADER) {
            throw BackupException("The selected file is not a valid OpenGarmin database.")
        }

        val database = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw BackupException("The selected database could not be opened.")
        }
        try {
            val version = database.version
            if (version <= 0) {
                throw BackupException("The selected file is not an OpenGarmin backup.")
            }
            if (version > AppDatabase.SCHEMA_VERSION) {
                throw BackupException(
                    "This backup was created by a newer version of the app. Update the app first.",
                )
            }
            val hasActivities = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'activities'",
                null,
            ).use { it.moveToFirst() }
            if (!hasActivities) {
                throw BackupException("The selected database does not contain OpenGarmin data.")
            }
        } finally {
            database.close()
        }
    }
}
