package dev.opengarmin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset

class ActivityExportTest {
    private val start = 1_700_000_000L // 2023-11-14T22:13:20Z

    private val activity = ActivityEntity(
        id = 7,
        sourceKey = "start-distance-sport",
        fileName = "download_42.dec",
        startTime = start,
        sport = 1,
        subSport = 0,
        distanceMeters = 5_020.0,
        durationSeconds = 1_800.0,
        elapsedSeconds = 1_810.0,
        avgHeartRate = 150,
        maxHeartRate = 180,
        calories = 400,
        ascentMeters = 30,
        descentMeters = 35,
        importedAt = start + 3_600,
    )

    private fun record(
        offset: Long,
        lat: Double? = 45.0,
        lon: Double? = 9.0,
        hr: Int? = 140,
        cadence: Int? = 82,
        altitude: Double? = 120.0,
        distance: Double? = 100.0,
    ) = RecordEntity(
        activityId = activity.id,
        timestamp = start + offset,
        distanceMeters = distance,
        heartRate = hr,
        speedMps = 3.1,
        altitudeMeters = altitude,
        cadence = cadence,
        latitude = lat,
        longitude = lon,
        bodyBattery = 55,
    )

    @Test
    fun `generates readable filenames with the right extension`() {
        assertEquals(
            "opengarmin-2023-11-14-2213-running-5.02km.gpx",
            ActivityExport.fileName(activity, ActivityExportFormat.GPX, ZoneOffset.UTC),
        )
        assertEquals(
            "opengarmin-2023-11-14-2213-running.fit",
            ActivityExport.fileName(activity.copy(distanceMeters = 0.0), ActivityExportFormat.FIT, ZoneOffset.UTC),
        )
    }

    @Test
    fun `escapes XML reserved characters and strips invalid controls`() {
        assertEquals(
            "A &amp; B &lt; C &gt; &quot;D&quot; &apos;E&apos;",
            ActivityExport.xmlEscape("A & B < C > \"D\" 'E'"),
        )
        assertFalse(ActivityExport.xmlEscape("a\u0000b\u0008c").contains('\u0000'))
        assertFalse(ActivityExport.xmlEscape("a\u0000b\u0008c").contains('\u0008'))
    }

    @Test
    fun `gpx keeps only valid positions and includes time elevation and heart rate`() {
        val records = listOf(
            record(offset = 10),
            record(offset = 20, lat = null, lon = null),
            record(offset = 30, lat = 91.0, lon = 9.0),
            record(offset = 40, lat = -91.0, lon = 9.0),
            record(offset = 50, lat = 45.0, lon = 181.0),
            record(offset = 60, lat = 0.0, lon = 0.0),
            record(offset = 70, lat = Double.NaN, lon = 9.0),
        )

        assertTrue(ActivityExport.hasGps(records))
        val gpx = ActivityExport.gpx(activity, records)

        assertEquals(1, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("lat=\"45.0000000\" lon=\"9.0000000\""))
        assertTrue(gpx.contains("<ele>120.00</ele>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:30Z</time>"))
        assertTrue(gpx.contains("<gpxtpx:hr>140</gpxtpx:hr>"))
        assertTrue(gpx.contains("<name>Running · 5.02 km</name>"))
    }

    @Test
    fun `gpx without coordinates is an explicit error`() {
        val records = listOf(
            record(offset = 10, lat = null, lon = null),
            record(offset = 20, lat = 0.0, lon = 0.0),
        )
        assertFalse(ActivityExport.hasGps(records))
        val error = assertThrows(ActivityExportException::class.java) {
            ActivityExport.gpx(activity, records)
        }
        assertTrue(error.message!!.contains("no valid GPS"))
    }

    @Test
    fun `tcx synthesizes one lap when the activity has no laps`() {
        val records = listOf(
            record(offset = 10, hr = 145, cadence = 84, altitude = 122.5, distance = 120.0),
            record(offset = 70, hr = 151, cadence = 86, altitude = 124.0, distance = 300.0),
        )
        val tcx = ActivityExport.tcx(activity, records, emptyList())

        assertEquals(1, Regex("<Lap ").findAll(tcx).count())
        assertTrue(tcx.contains("<Id>2023-11-14T22:13:20Z</Id>"))
        assertTrue(tcx.contains("<TotalTimeSeconds>1800.000</TotalTimeSeconds>"))
        assertTrue(tcx.contains("<DistanceMeters>5020.000</DistanceMeters>"))
        assertTrue(tcx.contains("<Calories>400</Calories>"))
        assertTrue(tcx.contains("<AverageHeartRateBpm><Value>150</Value></AverageHeartRateBpm>"))
        assertTrue(tcx.contains("<MaximumHeartRateBpm><Value>180</Value></MaximumHeartRateBpm>"))
        assertTrue(tcx.contains("<Cadence>85</Cadence>"))
        assertEquals(2, Regex("<Trackpoint>").findAll(tcx).count())
        assertTrue(tcx.contains("<Time>2023-11-14T22:13:30Z</Time>"))
        assertTrue(tcx.contains("<LatitudeDegrees>45.0000000</LatitudeDegrees>"))
        assertTrue(tcx.contains("<AltitudeMeters>122.50</AltitudeMeters>"))
        assertTrue(tcx.contains("<HeartRateBpm><Value>145</Value></HeartRateBpm>"))
    }

    @Test
    fun `tcx assigns each record to one lap and tolerates missing data`() {
        val laps = listOf(
            LapEntity(
                activityId = activity.id,
                lapIndex = 0,
                startTime = start,
                durationSeconds = 900.0,
                distanceMeters = 2_500.0,
                avgHeartRate = 145,
                maxHeartRate = 170,
                avgSpeedMps = 2.78,
                calories = 200,
                ascentMeters = 10,
                descentMeters = 5,
            ),
            LapEntity(
                activityId = activity.id,
                lapIndex = 1,
                startTime = start + 900,
                durationSeconds = 900.0,
                distanceMeters = 2_520.0,
                avgHeartRate = 155,
                maxHeartRate = 180,
                avgSpeedMps = 2.80,
                calories = 200,
                ascentMeters = 20,
                descentMeters = 30,
            ),
        )
        val records = listOf(
            record(offset = 10, lat = null, lon = null, hr = null, cadence = null, altitude = null, distance = null),
            record(offset = 910),
        )

        val tcx = ActivityExport.tcx(activity, records, laps)
        assertEquals(2, Regex("<Lap ").findAll(tcx).count())
        assertEquals(2, Regex("<Trackpoint>").findAll(tcx).count())
        assertTrue(tcx.contains("<Lap StartTime=\"2023-11-14T22:13:20Z\">"))
        assertTrue(tcx.contains("<Lap StartTime=\"2023-11-14T22:28:20Z\">"))
        // Missing values are omitted, not zero-filled.
        assertFalse(tcx.contains("<HeartRateBpm><Value>0</Value></HeartRateBpm>"))
    }

    @Test
    fun `tcx with no records still emits a valid activity lap`() {
        val tcx = ActivityExport.tcx(activity, emptyList(), emptyList())
        assertTrue(tcx.contains("<Lap StartTime=\"2023-11-14T22:13:20Z\">"))
        assertTrue(tcx.contains("<TotalTimeSeconds>1800.000</TotalTimeSeconds>"))
        assertFalse(tcx.contains("<Track>"))
    }

    @Test
    fun `csv has documented columns ISO UTC times elapsed seconds and lap index`() {
        val records = listOf(
            record(offset = 10),
            record(offset = 70, hr = null, lat = null, lon = null),
        )
        val laps = listOf(
            LapEntity(
                activityId = activity.id,
                lapIndex = 0,
                startTime = start,
                durationSeconds = 1_800.0,
                distanceMeters = 5_020.0,
                avgHeartRate = 150,
                maxHeartRate = 180,
                avgSpeedMps = 2.79,
                calories = 400,
                ascentMeters = 30,
                descentMeters = 35,
            ),
        )

        val lines = ActivityExport.csv(activity, records, laps).trim().lines()
        assertEquals(ActivityExport.CSV_COLUMNS.joinToString(","), lines[0])
        assertEquals(3, lines.size)
        val first = lines[1].split(',')
        assertEquals("record", first[0])
        assertEquals("7", first[1])
        assertEquals("2023-11-14T22:13:20Z", first[2])
        assertEquals("Running", first[3])
        assertEquals("1", first[5])
        assertEquals("0", first[9])
        assertEquals("2023-11-14T22:13:30Z", first[10])
        assertEquals("10", first[11])
        assertEquals("100.000", first[12])
        assertEquals("140", first[13])
        assertEquals("45.0000000", first[17])
        assertEquals("55", first[19])
        val second = lines[2].split(',')
        assertEquals("70", second[11])
        assertEquals("", second[13])
        assertEquals("", second[17])
    }

    @Test
    fun `csv emits a lap row when there are no records and quotes fields`() {
        val csv = ActivityExport.csv(activity, emptyList(), emptyList())
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("lap,7,2023-11-14T22:13:20Z,Running"))
        assertEquals("\"a,b\"", ActivityExport.csvField("a,b"))
        assertEquals("\"a\"\"b\"", ActivityExport.csvField("a\"b"))
        assertEquals("\"line\nbreak\"", ActivityExport.csvField("line\nbreak"))
    }

    @Test
    fun `fit export uses the original file and rejects missing or non-FIT files`() {
        val valid = File.createTempFile("opengarmin-export", ".fit")
        try {
            val header = ByteArray(12)
            header[0] = 12
            header[1] = 0x10
            for ((index, char) in ".FIT".withIndex()) header[8 + index] = char.code.toByte()
            valid.writeBytes(header)

            val bytes = ActivityExport.readFitFile(valid)
            assertEquals(12, bytes.size)
            assertTrue(ActivityExport.isFitFile(bytes))

            val output = ByteArrayOutputStream()
            ActivityExport.write(
                activity,
                emptyList(),
                emptyList(),
                ActivityExportFormat.FIT,
                valid,
                output,
            )
            assertTrue(ActivityExport.isFitFile(output.toByteArray()))
        } finally {
            valid.delete()
        }

        val missing = assertThrows(ActivityExportException::class.java) {
            ActivityExport.readFitFile(File("/does/not/exist.fit"))
        }
        assertTrue(missing.message!!.contains("no longer on this device"))

        val invalid = File.createTempFile("opengarmin-export", ".fit")
        try {
            invalid.writeBytes(ByteArray(12))
            assertThrows(ActivityExportException::class.java) {
                ActivityExport.readFitFile(invalid)
            }
        } finally {
            invalid.delete()
        }
    }
}
