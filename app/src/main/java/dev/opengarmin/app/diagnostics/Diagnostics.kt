package dev.opengarmin.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import dev.opengarmin.app.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide entry point for the local diagnostics log.
 *
 * Events are written asynchronously to a dedicated database so a failure in the
 * health database can never suppress them. Uncaught exceptions are the one
 * synchronous path: the process is about to die, so the write must finish
 * before the default handler runs.
 *
 * Nothing is uploaded. The log is only read by the hidden diagnostics screen
 * and by the explicit JSON export.
 */
object Diagnostics {
    const val LEVEL_WARN = "warn"
    const val LEVEL_ERROR = "error"
    const val LEVEL_FATAL = "fatal"

    const val AREA_SYNC = "sync"
    const val AREA_IMPORT = "import"
    const val AREA_ANALYSIS = "analysis"
    const val AREA_COACH = "coach"
    const val AREA_BACKUP = "backup"
    const val AREA_EXPORT = "export"
    const val AREA_CRASH = "crash"
    const val AREA_PROCESS = "process"

    private const val TAG = "Diagnostics"
    private const val FALLBACK_FILE = "diagnostics-crash.log"
    private const val MAX_FALLBACK_BYTES = 128 * 1024L
    private const val EXIT_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000

    private val installed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-writer").apply { isDaemon = true }
    }

    @Volatile
    private var store: AppEventStore? = null

    @Volatile
    private var appContext: Context? = null

    private var baseContext: Map<String, String> = emptyMap()

    /** Short id, stable for one process, used to tie a burst of events together. */
    val sessionId: String = UUID.randomUUID().toString().substring(0, 8)

    /** Installs the store and crash handler. Safe to call more than once. */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        appContext = app
        baseContext = mapOf(
            "appVersion" to BuildConfig.VERSION_NAME,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            "session" to sessionId,
        )
        val opened = runCatching { AppEventStore(app) }
            .onFailure { Log.w(TAG, "store unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
        store = opened
        // Install the handler even when the store failed to open: recordFatal
        // then appends to the fallback file instead of losing the crash.
        installCrashHandler(app)
        writer.execute {
            runCatching { opened?.prune() }
            runCatching { importFallbackFile(app, opened) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { importProcessExits(app, opened) }
            }
        }
    }

    /**
     * Fire-and-forget error record. Callers pass an allowlisted [context]; the
     * message and stack trace are redacted before they are stored.
     */
    fun record(
        area: String,
        code: String,
        message: String,
        level: String = LEVEL_ERROR,
        throwable: Throwable? = null,
        context: Map<String, String> = emptyMap(),
    ) {
        val eventStore = store ?: return
        val merged = baseContext + context
        writer.execute {
            runCatching {
                eventStore.record(
                    AppEventWrite(
                        level = level,
                        area = area,
                        code = code,
                        message = message,
                        throwable = throwable,
                        context = merged,
                    ),
                    sessionId = sessionId,
                )
            }.onFailure { Log.w(TAG, "record failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Synchronous record for the uncaught-exception handler. */
    private fun recordFatal(thread: Thread, throwable: Throwable) {
        val eventStore = store
        val app = appContext
        if (eventStore == null || app == null) {
            appendFallback(app, thread, throwable)
            return
        }
        runCatching {
            eventStore.record(
                AppEventWrite(
                    level = LEVEL_FATAL,
                    area = AREA_CRASH,
                    code = throwable.javaClass.simpleName.ifBlank { "UncaughtException" },
                    message = "Uncaught exception on ${thread.name}: " +
                        (throwable.message ?: throwable.javaClass.name),
                    throwable = throwable,
                    context = baseContext + mapOf("thread" to thread.name),
                ),
                sessionId = sessionId,
            )
        }.onFailure {
            appendFallback(app, thread, throwable)
        }
    }

    /** Newest first. Intended to be called from a background dispatcher. */
    fun events(limit: Int = 500, area: String? = null): List<AppEvent> =
        runCatching { store?.let { it.events(limit, area) } }.getOrNull() ?: emptyList()

    fun count(): Int = runCatching { store?.count() }.getOrNull() ?: 0

    fun clear() {
        runCatching { store?.clear() }
    }

    /** JSON export; returns null when the store could not be opened. */
    fun exportJson(): String? = runCatching { store?.exportJson() }.getOrNull()

    private fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordFatal(thread, throwable) }
                .onFailure { appendFallback(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * The OS already knows about crashes, ANRs and low-memory kills. Import the
     * ones newer than the last successful import, skipping any crash this app
     * already captured synchronously.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("InlinedApi")
    private fun importProcessExits(context: Context, eventStore: AppEventStore?) {
        if (eventStore == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val lastImported = eventStore.meta(KEY_LAST_EXIT_IMPORT)?.toLongOrNull() ?: 0L
        val floor = maxOf(lastImported, System.currentTimeMillis() - EXIT_LOOKBACK_MS)
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        }.getOrDefault(emptyList())
            .filter { it.timestamp > floor && it.reason in INTERESTING_REASONS }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        for (info in exits) {
            if (!eventStore.hasFatalNear(info.timestamp)) {
                val description = info.description?.take(300)
                eventStore.record(
                    AppEventWrite(
                        level = if (info.reason in FATAL_REASONS) LEVEL_FATAL else LEVEL_WARN,
                        area = AREA_PROCESS,
                        code = "PROCESS_EXIT_${info.reason}",
                        message = buildString {
                            append(exitReasonLabel(info.reason))
                            if (!description.isNullOrBlank()) append(": ").append(description)
                        },
                        context = baseContext + buildMap {
                            put("reason", info.reason.toString())
                            put("importance", info.importance.toString())
                            put("pssKb", info.pss.toString())
                            put("rssKb", info.rss.toString())
                        },
                    ),
                    sessionId = sessionId,
                    now = info.timestamp,
                )
            }
            eventStore.putMeta(KEY_LAST_EXIT_IMPORT, info.timestamp.toString())
        }
    }

    /**
     * If the SQLite write fails during a crash, at least leave a text trace that
     * the next launch imports into the store.
     */
    private fun appendFallback(context: Context?, thread: Thread, throwable: Throwable) {
        val app = context ?: return
        runCatching {
            val file = File(app.filesDir, FALLBACK_FILE)
            if (file.length() > MAX_FALLBACK_BYTES) file.delete()
            file.appendText(
                buildString {
                    append("--- ").append(System.currentTimeMillis()).append('\n')
                    append(thread.name).append('\n')
                    append(DiagnosticsFormat.stackTrace(throwable.stackTraceToString()).orEmpty())
                    append("\n\n")
                },
            )
        }
    }

    private fun importFallbackFile(context: Context, eventStore: AppEventStore?) {
        if (eventStore == null) return
        val file = File(context.filesDir, FALLBACK_FILE)
        if (!file.isFile) return
        val text = runCatching { file.readText().takeLast(64 * 1024) }.getOrNull()
        if (!text.isNullOrBlank()) {
            eventStore.record(
                AppEventWrite(
                    level = LEVEL_FATAL,
                    area = AREA_CRASH,
                    code = "UNCAUGHT_FALLBACK",
                    message = "Crash captured by the fallback file (the database write failed).",
                    stackTrace = text,
                    context = baseContext + mapOf("source" to "fallback-file"),
                ),
                sessionId = sessionId,
            )
        }
        runCatching { file.delete() }
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> "Process killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Process killed for low memory"
        ApplicationExitInfo.REASON_CRASH -> "Process crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native process crash"
        ApplicationExitInfo.REASON_ANR -> "Application not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Process initialization failed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Process killed for excessive resource use"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Process dependency died"
        ApplicationExitInfo.REASON_FREEZER -> "Process frozen by the system"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Process stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Process force-stopped by the user"
        ApplicationExitInfo.REASON_OTHER -> "Process exited for another reason"
        else -> "Process exit ($reason)"
    }

    private const val KEY_LAST_EXIT_IMPORT = "last_exit_import_ms"

    // The constants are inlined and only evaluated behind the API 30 guard in
    // importProcessExits().
    @SuppressLint("InlinedApi")
    private val FATAL_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    @SuppressLint("InlinedApi")
    private val INTERESTING_REASONS = FATAL_REASONS + setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_FREEZER,
        ApplicationExitInfo.REASON_OTHER,
    )
}
