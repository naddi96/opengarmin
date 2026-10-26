package dev.opengarmin.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogExportTest {
    @Test
    fun `exports oldest first and redacts mac addresses`() {
        val text = SyncLogExport.text(
            deviceInfo = listOf("product" to "3076", "firmware" to "1370"),
            logs = listOf(
                "newest line",
                "connecting to D7:AA:AA:71:96:C8",
                "oldest line",
            ),
            exportedAtLabel = "2026-10-26 17:30",
        )

        assertTrue(text.startsWith("OpenGarmin sync log"))
        assertTrue(text.contains("Exported: 2026-10-26 17:30"))
        assertTrue(text.contains("Device: product: 3076 · firmware: 1370"))
        assertTrue(text.contains("Lines: 3 (oldest first)"))
        assertTrue(text.indexOf("oldest line") < text.indexOf("connecting to"))
        assertTrue(text.indexOf("connecting to") < text.indexOf("newest line"))
        assertFalse(text.contains("D7:AA:AA:71:96:C8"))
        assertTrue(text.contains("connecting to **:**:**:**:**:**"))
    }

    @Test
    fun `empty log still produces a readable file`() {
        val text = SyncLogExport.text(
            deviceInfo = emptyList(),
            logs = emptyList(),
            exportedAtLabel = "now",
        )
        assertTrue(text.contains("Lines: 0 (oldest first)"))
        assertTrue(text.contains("(no log lines)"))
        assertFalse(text.contains("Device:"))
    }
}
