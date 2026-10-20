package dev.opengarmin.app.data

import dev.opengarmin.app.coach.sportLabel
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** File formats supported by the local activity exporter. */
enum class ActivityExportFormat(
    val extension: String,
    val mimeType: String,
) {
    FIT("fit", "application/vnd.ant.fit"),
    GPX("gpx", "application/gpx+xml"),
    TCX("tcx", "application/vnd.garmin.tcx+xml"),
    CSV("csv", "text/csv"),
}

class ActivityExportException(message: String) : Exception(message)

/**
 * Writes one activity to FIT, GPX, TCX or CSV without contacting any service.
 *
 * FIT is copied from the decoded watch file that the sync or the FIT importer
 * stored on disk. The other formats are generated from the local Room records
 * and laps. Missing data is left empty; no samples or positions are invented.
 */
object ActivityExport {
    /** Columns, in order, of the CSV export (units match the names). */
    val CSV_COLUMNS = listOf(
        "row_type",
        "activity_id",
        "activity_start_utc",
        "activity_sport",
        "activity_sub_sport",
        "lap_index",
        "lap_start_utc",
        "lap_duration_s",
        "lap_distance_m",
        "record_index",
        "timestamp_utc",
        "elapsed_s",
        "distance_m",
        "heart_rate_bpm",
        "speed_mps",
        "altitude_m",
        "cadence_rpm",
        "latitude_deg",
        "longitude_deg",
        "body_battery",
    )

    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US)
    private val isoInstant = DateTimeFormatter.ISO_INSTANT

    /**
     * A readable, file-system-safe name such as
     * `opengarmin-2023-11-14-2213-run-5.02km.gpx`.
     */
    fun fileName(
        activity: ActivityEntity,
        format: ActivityExportFormat,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val start = Instant.ofEpochSecond(activity.startTime.coerceAtLeast(0))
        val stamp = fileNameFormatter.withZone(zone).format(start)
        val sport = slug(sportLabel(activity.sport))
        val distance = if (activity.distanceMeters > 0.0 && activity.distanceMeters.isFinite()) {
            "-${"%.2f".format(Locale.US, activity.distanceMeters / 1000.0)}km"
        } else {
            ""
        }
        return "opengarmin-$stamp-$sport$distance.${format.extension}"
    }

    /** True when at least one record has a usable latitude/longitude pair. */
    fun hasGps(records: List<RecordEntity>): Boolean = records.any { it.hasValidPosition() }

    /**
     * Reads the original decoded FIT file. A missing or non-FIT file is a hard
     * error so the caller never substitutes a generated activity file.
     */
    fun readFitFile(file: File?): ByteArray {
        if (file == null || !file.isFile) {
            throw ActivityExportException(
                "The original FIT file is no longer on this device. Re-sync the activity or import its FIT file first.",
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            throw ActivityExportException("Could not read the original FIT file.")
        }
        if (!isFitFile(bytes)) {
            throw ActivityExportException("The stored file is not a valid FIT activity.")
        }
        return bytes
    }

    /** FIT files start with a 12- or 14-byte header and carry `.FIT` at bytes 8..11. */
    fun isFitFile(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) return false
        return String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT"
    }

    fun write(
        activity: ActivityEntity,
        records: List<RecordEntity>,
        laps: List<LapEntity>,
        format: ActivityExportFormat,
        fitFile: File?,
        output: OutputStream,
    ) {
        when (format) {
            ActivityExportFormat.FIT -> output.write(readFitFile(fitFile))
            ActivityExportFormat.GPX -> {
                if (!hasGps(records)) {
                    throw ActivityExportException("This activity has no valid GPS points to export as GPX.")
                }
                output.write(gpx(activity, records).toByteArray(Charsets.UTF_8))
            }
            ActivityExportFormat.TCX -> output.write(tcx(activity, records, laps).toByteArray(Charsets.UTF_8))
            ActivityExportFormat.CSV -> output.write(csv(activity, records, laps).toByteArray(Charsets.UTF_8))
        }
        output.flush()
    }

    // ---------------------------------------------------------------------
    // GPX
    // ---------------------------------------------------------------------

    fun gpx(activity: ActivityEntity, records: List<RecordEntity>): String {
        val points = records
            .filter { it.hasValidPosition() }
            .sortedBy { it.timestamp }
        if (points.isEmpty()) {
            throw ActivityExportException("This activity has no valid GPS points to export as GPX.")
        }
        val name = activityName(activity)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"OpenGarmin\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
            append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
            append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            append("  <metadata>\n")
            append("    <name>").append(xmlEscape(name)).append("</name>\n")
            append("    <time>").append(iso(activity.startTime)).append("</time>\n")
            append("  </metadata>\n")
            append("  <trk>\n")
            append("    <name>").append(xmlEscape(name)).append("</name>\n")
            append("    <type>").append(xmlEscape(sportLabel(activity.sport).lowercase(Locale.US))).append("</type>\n")
            append("    <trkseg>\n")
            for (record in points) {
                append("      <trkpt lat=\"").append(decimal(record.latitude!!, 7))
                append("\" lon=\"").append(decimal(record.longitude!!, 7)).append("\">\n")
                record.altitudeMeters?.takeIf { it.isFinite() }?.let {
                    append("        <ele>").append(decimal(it, 2)).append("</ele>\n")
                }
                if (record.timestamp > 0) {
                    append("        <time>").append(iso(record.timestamp)).append("</time>\n")
                }
                if (record.heartRate != null) {
                    append("        <extensions>\n")
                    append("          <gpxtpx:TrackPointExtension>\n")
                    append("            <gpxtpx:hr>").append(record.heartRate).append("</gpxtpx:hr>\n")
                    append("          </gpxtpx:TrackPointExtension>\n")
                    append("        </extensions>\n")
                }
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
            append("  </trk>\n")
            append("</gpx>\n")
        }
    }

    // ---------------------------------------------------------------------
    // TCX
    // ---------------------------------------------------------------------

    fun tcx(activity: ActivityEntity, records: List<RecordEntity>, laps: List<LapEntity>): String {
        val sortedRecords = records.sortedBy { it.timestamp }
        val exportedLaps = buildLaps(activity, sortedRecords, laps)
        val sport = tcxSport(activity.sport)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<TrainingCenterDatabase ")
            append("xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
            append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
            append("xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 ")
            append("http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">\n")
            append("  <Activities>\n")
            append("    <Activity Sport=\"").append(sport).append("\">\n")
            append("      <Id>").append(iso(activity.startTime)).append("</Id>\n")
            for (lap in exportedLaps) {
                append("      <Lap StartTime=\"").append(iso(lap.startTime)).append("\">\n")
                append("        <TotalTimeSeconds>").append(decimal(lap.durationSeconds, 3)).append("</TotalTimeSeconds>\n")
                append("        <DistanceMeters>").append(decimal(lap.distanceMeters, 3)).append("</DistanceMeters>\n")
                lap.calories?.let { append("        <Calories>").append(it).append("</Calories>\n") }
                lap.avgHeartRate?.let {
                    append("        <AverageHeartRateBpm><Value>").append(it).append("</Value></AverageHeartRateBpm>\n")
                }
                lap.maxHeartRate?.let {
                    append("        <MaximumHeartRateBpm><Value>").append(it).append("</Value></MaximumHeartRateBpm>\n")
                }
                append("        <Intensity>Active</Intensity>\n")
                lap.cadence?.let { append("        <Cadence>").append(it).append("</Cadence>\n") }
                if (lap.points.isNotEmpty()) {
                    append("        <Track>\n")
                    for (record in lap.points) {
                        append("          <Trackpoint>\n")
                        if (record.timestamp > 0) {
                            append("            <Time>").append(iso(record.timestamp)).append("</Time>\n")
                        }
                        if (record.hasValidPosition()) {
                            append("            <Position>\n")
                            append("              <LatitudeDegrees>")
                                .append(decimal(record.latitude!!, 7)).append("</LatitudeDegrees>\n")
                            append("              <LongitudeDegrees>")
                                .append(decimal(record.longitude!!, 7)).append("</LongitudeDegrees>\n")
                            append("            </Position>\n")
                        }
                        record.altitudeMeters?.takeIf { it.isFinite() }?.let {
                            append("            <AltitudeMeters>").append(decimal(it, 2)).append("</AltitudeMeters>\n")
                        }
                        record.distanceMeters?.takeIf { it.isFinite() }?.let {
                            append("            <DistanceMeters>").append(decimal(it, 2)).append("</DistanceMeters>\n")
                        }
                        record.heartRate?.let {
                            append("            <HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>\n")
                        }
                        record.cadence?.let {
                            append("            <Cadence>").append(it).append("</Cadence>\n")
                        }
                        append("          </Trackpoint>\n")
                    }
                    append("        </Track>\n")
                }
                append("      </Lap>\n")
            }
            append("    </Activity>\n")
            append("  </Activities>\n")
            append("</TrainingCenterDatabase>\n")
        }
    }

    // ---------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------

    fun csv(activity: ActivityEntity, records: List<RecordEntity>, laps: List<LapEntity>): String {
        val sortedRecords = records.sortedBy { it.timestamp }
        val exportedLaps = buildLaps(activity, sortedRecords, laps)
        val lapByRecord = assignExportLapIndexes(sortedRecords, exportedLaps)
        return buildString {
            append(CSV_COLUMNS.joinToString(",")).append('\n')
            if (sortedRecords.isEmpty()) {
                for (lap in exportedLaps) {
                    appendCsvRow(
                        "lap",
                        activity,
                        lap,
                        lapIndex = lap.index,
                        record = null,
                        recordIndex = null,
                    )
                }
            } else {
                sortedRecords.forEachIndexed { index, record ->
                    // buildLaps/assignLapIndexes use zero-based positions;
                    // the CSV exposes the human-facing 1-based lap number.
                    val lapIndex = (lapByRecord[index] + 1).coerceAtLeast(1)
                    val lap = exportedLaps.firstOrNull { it.index == lapIndex }
                    appendCsvRow(
                        "record",
                        activity,
                        lap,
                        lapIndex,
                        record,
                        recordIndex = index,
                    )
                }
            }
        }
    }

    private fun StringBuilder.appendCsvRow(
        rowType: String,
        activity: ActivityEntity,
        lap: ExportLap?,
        lapIndex: Int?,
        record: RecordEntity?,
        recordIndex: Int?,
    ) {
        val activityStart = activity.startTime
        val elapsed = record?.let { (it.timestamp - activityStart).coerceAtLeast(0) }
        val values = listOf(
            rowType,
            activity.id.toString(),
            iso(activityStart),
            sportLabel(activity.sport),
            activity.subSport.toString(),
            lapIndex?.toString().orEmpty(),
            lap?.let { iso(it.startTime) }.orEmpty(),
            lap?.let { decimal(it.durationSeconds, 3) }.orEmpty(),
            lap?.let { decimal(it.distanceMeters, 3) }.orEmpty(),
            recordIndex?.toString().orEmpty(),
            record?.let { iso(it.timestamp) }.orEmpty(),
            elapsed?.toString().orEmpty(),
            record?.distanceMeters?.let { decimal(it, 3) }.orEmpty(),
            record?.heartRate?.toString().orEmpty(),
            record?.speedMps?.let { decimal(it, 4) }.orEmpty(),
            record?.altitudeMeters?.let { decimal(it, 2) }.orEmpty(),
            record?.cadence?.toString().orEmpty(),
            record?.latitude?.takeIf { it.isFinite() }?.let { decimal(it, 7) }.orEmpty(),
            record?.longitude?.takeIf { it.isFinite() }?.let { decimal(it, 7) }.orEmpty(),
            record?.bodyBattery?.toString().orEmpty(),
        )
        append(values.joinToString(",") { csvField(it) }).append('\n')
    }

    fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    // ---------------------------------------------------------------------
    // Laps and shared helpers
    // ---------------------------------------------------------------------

    data class ExportLap(
        /** 1-based lap number. */
        val index: Int,
        val startTime: Long,
        val durationSeconds: Double,
        val distanceMeters: Double,
        val avgHeartRate: Int?,
        val maxHeartRate: Int?,
        val cadence: Int?,
        val calories: Int?,
        val points: List<RecordEntity>,
    )

    /**
     * Builds a contiguous [0, size) lap assignment. Every record is assigned
     * exactly once, even when the watch omitted lap start times or a lap has
     * no samples. A missing laps table becomes one synthetic activity lap.
     */
    fun buildLaps(
        activity: ActivityEntity,
        records: List<RecordEntity>,
        laps: List<LapEntity>,
    ): List<ExportLap> {
        if (laps.isEmpty()) {
            val start = activity.startTime
            val end = records.lastOrNull()?.timestamp
            val duration = activity.durationSeconds.takeIf { it > 0.0 }
                ?: end?.let { (it - start).toDouble().coerceAtLeast(0.0) }
                ?: 0.0
            return listOf(
                ExportLap(
                    index = 1,
                    startTime = start,
                    durationSeconds = duration,
                    distanceMeters = activity.distanceMeters,
                    avgHeartRate = activity.avgHeartRate,
                    maxHeartRate = activity.maxHeartRate,
                    cadence = averageCadence(records),
                    calories = activity.calories,
                    points = records,
                ),
            )
        }

        val sortedLaps = laps.sortedBy { it.lapIndex }
        val assigned = assignLapIndexes(records, sortedLaps)
        var cursor = activity.startTime
        return sortedLaps.mapIndexed { position, lap ->
            val start = lap.startTime ?: cursor
            val points = records.filterIndexed { index, _ -> assigned[index] == position }
            val nextStart = sortedLaps.getOrNull(position + 1)?.startTime
            val inferredEnd = points.lastOrNull()?.timestamp
                ?: nextStart
                ?: (start + lap.durationSeconds.toLong())
            val duration = lap.durationSeconds.takeIf { it > 0.0 }
                ?: (inferredEnd - start).toDouble().coerceAtLeast(0.0)
            cursor = start + duration.toLong()
            ExportLap(
                index = position + 1,
                startTime = start,
                durationSeconds = duration,
                distanceMeters = lap.distanceMeters,
                avgHeartRate = lap.avgHeartRate,
                maxHeartRate = lap.maxHeartRate,
                cadence = averageCadence(points),
                calories = lap.calories,
                points = points,
            )
        }
    }

    private data class LapWindow(val index: Int, val start: Long, val end: Long)

    private fun lapIndexFor(record: RecordEntity, windows: List<LapWindow>): Int {
        if (windows.isEmpty()) return -1
        val exact = windows.firstOrNull { record.timestamp in it.start until it.end }
        return exact?.index
            ?: windows.minByOrNull { abs(record.timestamp - it.start) }?.index
            ?: 0
    }

    /**
     * Returns, for every record, the zero-based position of its lap in
     * [sortedLaps]. Records are assigned by time; anything outside the known
     * windows falls into the nearest lap so no sample is silently dropped.
     */
    private fun assignLapIndexes(
        records: List<RecordEntity>,
        sortedLaps: List<LapEntity>,
    ): List<Int> {
        if (sortedLaps.isEmpty()) return records.map { -1 }
        val starts = LongArray(sortedLaps.size)
        val ends = LongArray(sortedLaps.size)
        var cursor = sortedLaps.firstOrNull()?.startTime ?: 0L
        for (i in sortedLaps.indices) {
            val lap = sortedLaps[i]
            val start = lap.startTime ?: cursor
            val nextStart = sortedLaps.getOrNull(i + 1)?.startTime
            var end = if (lap.durationSeconds > 0.0) start + lap.durationSeconds.toLong() else nextStart ?: start
            if (end <= start && nextStart != null && nextStart > start) end = nextStart
            if (end <= start) end = start + 1
            starts[i] = start
            ends[i] = end
            cursor = end
        }
        // Make the first/last lap cover points that fall just outside the
        // reported windows (watch clock skew and rounding are common).
        records.minOfOrNull { it.timestamp }?.let { first ->
            if (first < starts.first()) starts[0] = first
        }
        records.maxOfOrNull { it.timestamp }?.let { last ->
            if (last >= ends.last()) ends[ends.lastIndex] = last + 1
        }

        val windows = sortedLaps.indices.map { LapWindow(it, starts[it], ends[it]) }
        return records.map { record -> lapIndexFor(record, windows) }
    }

    /** [assignLapIndexes] for a laps list that is already in export order. */
    private fun assignExportLapIndexes(
        records: List<RecordEntity>,
        laps: List<ExportLap>,
    ): List<Int> {
        if (laps.isEmpty()) return records.map { -1 }
        val windows = laps.map { lap ->
            LapWindow(
                index = lap.index - 1,
                start = lap.startTime,
                end = lap.startTime + lap.durationSeconds.toLong().coerceAtLeast(1),
            )
        }
        return records.map { record -> lapIndexFor(record, windows) }
    }

    private fun averageCadence(records: List<RecordEntity>): Int? {
        val values = records.mapNotNull { it.cadence }.filter { it >= 0 }
        return if (values.isEmpty()) null else values.average().toInt()
    }

    fun activityName(activity: ActivityEntity): String {
        val sport = sportLabel(activity.sport)
        return if (activity.distanceMeters > 0.0 && activity.distanceMeters.isFinite()) {
            "%s · %.2f km".format(Locale.US, sport, activity.distanceMeters / 1000.0)
        } else {
            sport
        }
    }

    fun xmlEscape(value: String): String {
        val cleaned = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                val allowed = codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D ||
                    codePoint in 0x20..0xD7FF ||
                    codePoint in 0xE000..0xFFFD ||
                    codePoint in 0x10000..0x10FFFF
                if (allowed) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        return cleaned
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun iso(seconds: Long): String =
        if (seconds <= 0L) "" else isoInstant.format(Instant.ofEpochSecond(seconds))

    private fun decimal(value: Double, decimals: Int): String {
        if (!value.isFinite()) return ""
        return ("%." + decimals + "f").format(Locale.US, value)
    }

    private fun slug(value: String): String {
        val normalized = value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return normalized.ifBlank { "activity" }
    }

    private fun tcxSport(sport: Int): String = when (sport) {
        1, 11, 12, 13, 14, 17 -> "Running"
        2, 21 -> "Biking"
        5, 41 -> "Swimming"
        4, 10, 62 -> "Other"
        else -> "Other"
    }

    private fun RecordEntity.hasValidPosition(): Boolean {
        val lat = latitude ?: return false
        val lon = longitude ?: return false
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        // Garmin uses (0, 0) as a missing-position sentinel.
        return !(lat == 0.0 && lon == 0.0)
    }
}
