package dev.opengarmin.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFormatTest {

    @Test
    fun `message redacts provider keys and credentials`() {
        val raw = "failed api_key=sk-live-1234567890 Bearer abc.def.ghi token: hunter2 " +
            "GET https://example.test/v1?access_token=secret-value&x=1"
        val message = DiagnosticsFormat.message(raw)

        assertFalse(message.contains("sk-live-1234567890"))
        assertFalse(message.contains("abc.def.ghi"))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("secret-value"))
        assertTrue(message.contains("api_key=***"))
        assertTrue(message.contains("access_token=***"))
    }

    @Test
    fun `message redacts mac addresses and long hashes`() {
        val message = DiagnosticsFormat.message(
            "watch AA:BB:CC:DD:EE:FF serial 0123456789abcdef0123456789abcdef",
        )
        assertFalse(message.contains("AA:BB:CC:DD:EE:FF", ignoreCase = true))
        assertFalse(message.contains("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `message is flattened and capped`() {
        val message = DiagnosticsFormat.message("line one\nline two\t" + "x".repeat(2_000))
        assertFalse(message.contains("\n"))
        assertFalse(message.contains("\t"))
        assertTrue(message.length <= DiagnosticsFormat.MAX_MESSAGE_CHARS + 1)
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun `stack trace keeps lines but is capped`() {
        val trace = DiagnosticsFormat.stackTrace("at a.b(C.kt:1)\n" + "y".repeat(20_000))
        assertTrue(trace!!.contains("at a.b(C.kt:1)"))
        assertTrue(trace!!.length <= DiagnosticsFormat.MAX_STACK_CHARS + 1)
    }

    @Test
    fun `blank stack trace becomes null`() {
        assertEquals(null, DiagnosticsFormat.stackTrace("   \n  "))
    }

    @Test
    fun `context keeps only allowlisted values and caps them`() {
        val values = LinkedHashMap<String, String>()
        values["secret"] = "token=abcdef"
        repeat(DiagnosticsFormat.MAX_CONTEXT_ENTRIES + 5) { values["k$it"] = "v$it" }
        val json = DiagnosticsFormat.context(values)

        val parsed = org.json.JSONObject(json)
        assertTrue(parsed.length() <= DiagnosticsFormat.MAX_CONTEXT_ENTRIES)
        assertEquals("token=***", parsed.getString("secret"))
        assertTrue(parsed.getString("k0").length <= DiagnosticsFormat.MAX_CONTEXT_VALUE_CHARS)
    }

    @Test
    fun `fingerprint is stable and separated by area and code`() {
        val error = IllegalStateException("boom")
        val first = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", error, "boom")
        val second = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", error, "boom")
        val otherArea = DiagnosticsFormat.fingerprint("import", "SYNC_FAILED", error, "boom")
        val otherCode = DiagnosticsFormat.fingerprint("sync", "SYNC_PERMANENT", error, "boom")

        assertEquals(first, second)
        assertNotEquals(first, otherArea)
        assertNotEquals(first, otherCode)
    }

    @Test
    fun `fingerprint ignores the message when a stack frame exists`() {
        val error = IllegalStateException("boom")
        val first = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", error, "first wording")
        val second = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", error, "second wording")
        assertEquals(first, second)
    }

    @Test
    fun `fingerprint falls back to the message without a throwable`() {
        val first = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", null, "same message")
        val second = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", null, "same message")
        val third = DiagnosticsFormat.fingerprint("sync", "SYNC_FAILED", null, "other message")
        assertEquals(first, second)
        assertNotEquals(first, third)
    }
}
