package dev.opengarmin.app.diagnostics

/**
 * Formats the in-memory device log for the "Export log" action.
 *
 * The protocol log contains GDI/GFDI hex frames, so it is deliberately not
 * passed through the error-store redactor (which strips long hex identifiers).
 * Watch MAC addresses are still removed before the file leaves the app.
 */
internal object SyncLogExport {
    private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

    fun text(
        deviceInfo: List<Pair<String, String>>,
        logs: List<String>,
        exportedAtLabel: String,
    ): String = buildString {
        appendLine("OpenGarmin sync log")
        appendLine("Exported: $exportedAtLabel")
        if (deviceInfo.isNotEmpty()) {
            appendLine("Device: " + deviceInfo.joinToString(" · ") { "${it.first}: ${it.second}" })
        }
        appendLine("Lines: ${logs.size} (oldest first)")
        appendLine(
            "MAC addresses are redacted. The file still contains protocol frames and " +
                "the watch unit ID; review it before sharing.",
        )
        appendLine()
        if (logs.isEmpty()) {
            appendLine("(no log lines)")
        } else {
            logs.asReversed().forEach { line ->
                appendLine(redact(line))
            }
        }
    }

    private fun redact(line: String): String =
        macAddress.replace(line, "**:**:**:**:**:**")
}
