package dev.opengarmin.app.diagnostics

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure formatting and redaction helpers for the local error store. Kept free of
 * Android APIs so the privacy rules and the dedup fingerprint can be unit
 * tested on the JVM.
 */
internal object DiagnosticsFormat {
    const val MAX_MESSAGE_CHARS = 500
    const val MAX_STACK_CHARS = 8_000
    const val MAX_CONTEXT_VALUE_CHARS = 200
    const val MAX_CONTEXT_ENTRIES = 20

    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val skKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
    private val assignedSecret = Regex(
        "(?i)\\b(api[_-]?key|token|secret|password|passwd|authorization)\\b\\s*[:=]\\s*[^\\s,;&\"']+",
    )
    private val querySecret = Regex("(?i)([?&](?:key|api_key|token|access_token|secret)=)[^&\\s]+")
    private val macAddress = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val longHex = Regex("(?i)\\b[0-9a-f]{32,}\\b")

    /** One-line message, secrets removed, hard-capped. */
    fun message(raw: String): String {
        val flattened = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return cap(redact(flattened), MAX_MESSAGE_CHARS)
    }

    /** Multi-line stack trace, secrets removed, hard-capped. */
    fun stackTrace(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return cap(redact(text), MAX_STACK_CHARS)
    }

    /** JSON with only the caller-supplied, allowlisted context values. */
    fun context(values: Map<String, String>): String {
        val json = JSONObject()
        values.entries.take(MAX_CONTEXT_ENTRIES).forEach { (key, value) ->
            val safeKey = redact(key).replace(Regex("[\\r\\n\\t]+"), " ").trim().take(40)
            if (safeKey.isEmpty()) return@forEach
            val safeValue = redact(value.replace(Regex("[\\r\\n\\t]+"), " "))
                .trim()
                .take(MAX_CONTEXT_VALUE_CHARS)
            json.put(safeKey, safeValue)
        }
        return json.toString()
    }

    /**
     * Stable grouping key. Line numbers, file names and timestamps are excluded
     * so the same failure aggregates across builds and sessions.
     */
    fun fingerprint(area: String, code: String, throwable: Throwable?, message: String): String {
        val frame = throwable?.stackTrace?.firstOrNull()
            ?.let { "${it.className}.${it.methodName}" }
            ?: throwable?.javaClass?.name
            ?: message.take(80)
        val raw = "$area|$code|$frame"
        return sha256(raw)
    }

    private fun redact(text: String): String {
        var result = text
        result = bearer.replace(result) { "Bearer ***" }
        result = skKey.replace(result) { "sk-***" }
        result = assignedSecret.replace(result) { match ->
            match.groupValues[1] + "=***"
        }
        result = querySecret.replace(result) { match ->
            match.groupValues[1] + "***"
        }
        result = macAddress.replace(result) { "**:**:**:**:**:**" }
        result = longHex.replace(result) { "***" }
        return result
    }

    private fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
