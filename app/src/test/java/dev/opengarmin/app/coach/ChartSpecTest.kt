package dev.opengarmin.app.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSpecTest {
    @Test
    fun `parses a multi-series line chart`() {
        val spec = ChartSpec.fromJson(
            """
            {
              "type": "line",
              "title": "Heart rate",
              "xLabel": "Elapsed (min)",
              "unit": "bpm",
              "series": [
                {"name": "Oct 12 · Running", "points": [[0, 120], [5, 145], [10, 160]], "color": "teal"},
                {"name": "Oct 14 · Running", "points": [[0, 115], [6, 150], [12, 165]], "color": "coral"}
              ]
            }
            """.trimIndent(),
        )

        assertNotNull(spec)
        requireNotNull(spec)
        assertEquals(ChartType.Line, spec.type)
        assertEquals("Heart rate", spec.title)
        assertEquals("Elapsed (min)", spec.xLabel)
        assertEquals(2, spec.series.size)
        assertEquals("teal", spec.series[0].color)
        assertEquals(145f, spec.series[0].points[1].y, 0.01f)
    }

    @Test
    fun `parses bar shorthand from labels and values`() {
        val spec = ChartSpec.fromJson(
            """{"type":"bar","title":"Steps","labels":["Mon","Tue","Wed"],"values":[8000,12000,10500],"unit":"steps"}""",
        )

        assertNotNull(spec)
        requireNotNull(spec)
        assertEquals(ChartType.Bar, spec.type)
        assertEquals(listOf("Mon", "Tue", "Wed"), spec.categories)
        assertEquals(3, spec.series.single().points.size)
        assertEquals(12_000f, spec.series.single().points[1].y, 0.01f)
    }

    @Test
    fun `keeps sparse bar points when categories cover the range`() {
        val spec = ChartSpec.fromJson(
            """{"type":"bar","title":"Steps","categories":["Mon","Tue","Wed","Thu"],"series":[{"name":"Steps","points":[[0,1000],[2,3000]]}]}""",
        )

        assertEquals(4, spec?.categories?.size)
        assertEquals(2, spec?.series?.first()?.points?.size)
    }

    @Test
    fun `round trips through persisted json`() {
        val original = ChartSpec(
            type = ChartType.Line,
            title = "Speed · 2 activities",
            xLabel = "Distance (km)",
            yLabel = "Speed",
            unit = "km/h",
            series = listOf(
                ChartSeries("A", listOf(ChartPoint(0f, 8f), ChartPoint(5f, 9.5f)), "accent"),
                ChartSeries("B", listOf(ChartPoint(0f, 7f), ChartPoint(4f, 10f)), "blue"),
            ),
        )

        val parsed = ChartSpec.fromJson(original.toJson())

        assertEquals(original.title, parsed?.title)
        assertEquals(original.type, parsed?.type)
        assertEquals(original.series.size, parsed?.series?.size)
        assertEquals(original.series[1].points[1], parsed?.series?.get(1)?.points?.get(1))
    }

    @Test
    fun `rejects charts without usable points`() {
        assertNull(ChartSpec.fromJson("""{"type":"line","title":"Empty","series":[]}"""))
        assertNull(
            ChartSpec.fromJson(
                """{"type":"line","title":"One","series":[{"name":"A","points":[[0,1]]}]}""",
            ),
        )
        assertTrue(ChartSpec.fromJson("""{"type":"bar","values":[]}""") == null)
    }

    @Test
    fun `keeps only finite points and caps excessive series`() {
        val series = (0 until 10).joinToString(",") {
            """{"name":"S$it","points":[[0,1],[1,2]]}"""
        }
        val spec = ChartSpec.fromJson("""{"type":"line","title":"Many","series":[$series]}""")
        assertEquals(8, spec?.series?.size)

        val withBadPoint = ChartSpec.fromJson(
            """{"type":"line","title":"Bad","series":[{"name":"A","points":[{"x":0,"y":1},{"x":1,"y":"oops"},{"x":2,"y":3}]}]}""",
        )
        assertEquals(2, withBadPoint?.series?.first()?.points?.size)
    }

    @Test
    fun `formats axis values compactly`() {
        assertEquals("0", formatAxis(0f))
        assertEquals("42", formatAxis(42f))
        assertEquals("1.5", formatAxis(1.5f))
        assertEquals("12k", formatAxis(12_000f))
        assertEquals("1.5k", formatAxis(1_500f))
        assertFalse(formatAxis(12_000f).contains(","))
    }
}
