package dev.opengarmin.app.diagnostics

/**
 * Identifies repetitive keepalive/ack frames that would otherwise push the
 * useful part of a sync log out of its ring buffer.
 *
 * While the watch is idle it exchanges `NOTIFICATION_SUBSCRIPTION (5036)`
 * frames roughly every second. Each pair produced three log lines, so an
 * eight-minute stall could fill the whole log with nothing else.
 */
internal object SyncLogNoise {
    private val patterns = listOf(
        // Parsed watch request: GFDI type=5036 seq=… len=2 0000
        Regex("^GFDI type=5036 "),
        // Raw phone response to that request: [handle][00][size=12][type=RESPONSE+seq]
        Regex("^TX\\(0\\) [0-9a-f]{2}00020c0104"),
        // Raw watch request: [handle][00][size=8][type=5036+seq]
        Regex("^RX [0-9a-f]{2}0002080324"),
    )

    fun isNoise(line: String): Boolean = patterns.any { it.containsMatchIn(line) }
}
