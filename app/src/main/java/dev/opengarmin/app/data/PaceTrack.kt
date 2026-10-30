package dev.opengarmin.app.data

import dev.opengarmin.protocol.Fit
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

/** Where the elevation profile of a [PaceCourse] came from. */
enum class PaceCourseSource {
    FLAT,
    ACTIVITY,
    IMPORTED,
    DRAWN,
    PLAN,
}

/** One georeferenced sample from an imported or drawn track. */
data class PaceTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    /** Cumulative distance in metres, filled by [PaceTrack]. */
    val distanceMeters: Double = 0.0,
)

/** A parsed GPX/TCX/FIT track, ready to become a [PaceCourse]. */
data class PaceTrack(
    val name: String,
    val points: List<PaceTrackPoint>,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val fileName: String? = null,
) {
    val hasElevation: Boolean get() = points.any { it.elevationMeters != null }
}

data class PaceTrackImport(
    val track: PaceTrack,
    val course: PaceCourse,
)

object PaceTrackParser {
    private const val MAX_POINTS = 8_000

    /**
     * Parses a GPX, TCX or FIT byte stream. XML formats are detected from the
     * root element rather than the file extension, because watches and export
     * sites are inconsistent about those. Returns a readable error for files
     * that contain no usable coordinates.
     */
    fun parse(fileName: String, bytes: ByteArray): PaceTrack {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension == "fit" || looksLikeFit(bytes)) {
            return fromFit(fileName, bytes)
        }
        val text = bytes.toString(Charsets.UTF_8)
        return when {
            text.contains("<gpx", ignoreCase = true) -> fromGpx(fileName, text)
            text.contains("TrainingCenterDatabase", ignoreCase = true) ||
                text.contains("<Trackpoint", ignoreCase = true) -> fromTcx(fileName, text)
            else -> throw PaceTrackException("Unsupported file. Choose a GPX, TCX or FIT track.")
        }
    }

    private fun looksLikeFit(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) return false
        return bytes[8] == 'F'.code.toByte() &&
            bytes[9] == 'I'.code.toByte() &&
            bytes[10] == 'T'.code.toByte()
    }

    private fun fromFit(fileName: String, bytes: ByteArray): PaceTrack {
        val parsed = runCatching { Fit.parse(bytes) }
            .getOrElse { throw PaceTrackException("The FIT file could not be read.") }
        val samples = parsed.records.mapNotNull { record ->
            val latitude = record.latitude ?: return@mapNotNull null
            val longitude = record.longitude ?: return@mapNotNull null
            if (!validCoordinate(latitude, longitude)) return@mapNotNull null
            Triple(latitude, longitude, record.altitudeMeters) to record.distanceMeters
        }
        if (samples.size < 2) throw PaceTrackException("The FIT file has no GPS track.")
        return buildTrack(
            name = fileName.substringBeforeLast('.').ifBlank { "FIT track" },
            fileName = fileName,
            rawPoints = samples.map { (point, distance) ->
                RawPoint(point.first, point.second, point.third, distance)
            },
        )
    }

    private fun fromGpx(fileName: String, xml: String): PaceTrack {
        val document = parseXml(xml)
        val points = document.getElementsByTagName("trkpt").asElements()
            .ifEmpty { document.getElementsByTagName("rtept").asElements() }
            .mapNotNull { element ->
                val latitude = element.getAttribute("lat").toDoubleOrNull() ?: return@mapNotNull null
                val longitude = element.getAttribute("lon").toDoubleOrNull() ?: return@mapNotNull null
                if (!validCoordinate(latitude, longitude)) return@mapNotNull null
                RawPoint(
                    latitude = latitude,
                    longitude = longitude,
                    elevation = element.childText("ele")?.toDoubleOrNull(),
                )
            }
        if (points.size < 2) throw PaceTrackException("The GPX file has no GPS track.")
        return buildTrack(
            name = document.getElementsByTagName("name").item(0)?.textContent
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fileName.substringBeforeLast('.'),
            fileName = fileName,
            rawPoints = points,
        )
    }

    private fun fromTcx(fileName: String, xml: String): PaceTrack {
        val document = parseXml(xml)
        val points = document.getElementsByTagName("Trackpoint").asElements().mapNotNull { element ->
            val latitude = element.descendantText("LatitudeDegrees")?.toDoubleOrNull()
            val longitude = element.descendantText("LongitudeDegrees")?.toDoubleOrNull()
            if (latitude == null || longitude == null || !validCoordinate(latitude, longitude)) {
                return@mapNotNull null
            }
            RawPoint(
                latitude = latitude,
                longitude = longitude,
                elevation = element.descendantText("AltitudeMeters")?.toDoubleOrNull(),
            )
        }
        if (points.size < 2) throw PaceTrackException("The TCX file has no GPS track.")
        return buildTrack(
            name = fileName.substringBeforeLast('.'),
            fileName = fileName,
            rawPoints = points,
        )
    }

    /** Builds a track from ordered points, computing distance, ascent and descent. */
    fun fromPoints(name: String, points: List<PaceTrackPoint>, fileName: String? = null): PaceTrack {
        val raw = points.map { RawPoint(it.latitude, it.longitude, it.elevationMeters) }
        if (raw.size < 2) throw PaceTrackException("Add at least two points to the map.")
        return buildTrack(name = name, fileName = fileName, rawPoints = raw)
    }

    fun courseFrom(
        track: PaceTrack,
        source: PaceCourseSource = PaceCourseSource.IMPORTED,
        id: String = "track-${track.name.hashCode()}",
    ): PaceCourse = PaceCourse(
        id = id,
        name = track.name,
        distanceMeters = track.distanceMeters,
        gainsPerKm = gainsPerKm(track.points, track.distanceMeters),
        source = source,
        fileName = track.fileName,
        ascentMeters = track.ascentMeters,
        descentMeters = track.descentMeters,
        points = track.points,
    )

    private data class RawPoint(
        val latitude: Double,
        val longitude: Double,
        val elevation: Double?,
        val suppliedDistance: Double? = null,
    )

    private fun buildTrack(name: String, fileName: String?, rawPoints: List<RawPoint>): PaceTrack {
        val ordered = if (rawPoints.size > MAX_POINTS) {
            rawPoints.filterIndexed { index, _ ->
                index % ((rawPoints.size + MAX_POINTS - 1) / MAX_POINTS) == 0
            }.toMutableList().apply {
                if (lastOrNull() !== rawPoints.last()) add(rawPoints.last())
            }
        } else {
            rawPoints
        }
        val points = ArrayList<PaceTrackPoint>(ordered.size)
        var distance = 0.0
        var previous: RawPoint? = null
        var previousSupplied: Double? = null
        for (raw in ordered) {
            val delta = when {
                raw.suppliedDistance != null && previousSupplied != null ->
                    max(0.0, raw.suppliedDistance - previousSupplied)
                previous != null -> haversineMeters(
                    previous.latitude,
                    previous.longitude,
                    raw.latitude,
                    raw.longitude,
                )
                else -> 0.0
            }
            distance += delta
            points.add(
                PaceTrackPoint(
                    latitude = raw.latitude,
                    longitude = raw.longitude,
                    elevationMeters = raw.elevation,
                    distanceMeters = distance,
                ),
            )
            previous = raw
            previousSupplied = raw.suppliedDistance
        }
        if (distance < 100.0) {
            throw PaceTrackException("The track is too short. Choose a route of at least 100 m.")
        }
        val (ascent, descent) = ascentAndDescent(points)
        return PaceTrack(
            name = name,
            points = points,
            distanceMeters = distance,
            ascentMeters = ascent,
            descentMeters = descent,
            fileName = fileName,
        )
    }

    /** Total positive and negative elevation change, with noise filtering. */
    fun ascentAndDescent(points: List<PaceTrackPoint>): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        var reference: Double? = null
        var pending = 0.0
        for (point in points) {
            val elevation = point.elevationMeters ?: continue
            val previous = reference
            if (previous == null) {
                reference = elevation
                continue
            }
            pending += elevation - previous
            if (kotlin.math.abs(pending) >= 0.8) {
                if (pending > 0) ascent += pending else descent += -pending
                reference = elevation
                pending = 0.0
            }
        }
        return ascent to descent
    }

    /**
     * Distributes positive elevation change into kilometre bins without
     * normalising the final partial kilometre. A gain that crosses a bin
     * boundary is split proportionally, so [PaceCourse.gainsPerKm] always keeps
     * the real shape of the track.
     */
    fun gainsPerKm(points: List<PaceTrackPoint>, distanceMeters: Double): List<Double> {
        if (distanceMeters <= 0.0 || points.size < 2) return emptyList()
        val bins = max(1, kotlin.math.ceil(distanceMeters / 1_000.0).toInt())
        val gains = DoubleArray(bins)
        var previous = points.first()
        for (point in points.drop(1)) {
            val elevationDelta = (point.elevationMeters ?: previous.elevationMeters)
                ?.let { current ->
                    val before = previous.elevationMeters ?: return@let null
                    current - before
                }
            val start = previous.distanceMeters
            val end = point.distanceMeters
            if (elevationDelta != null && elevationDelta > 0 && end > start) {
                var cursor = start
                while (cursor < end - 0.001) {
                    val bin = (cursor / 1_000.0).toInt().coerceIn(0, gains.lastIndex)
                    val binEnd = (bin + 1) * 1_000.0
                    val chunkEnd = minOf(end, binEnd)
                    val fraction = (chunkEnd - cursor) / (end - start)
                    gains[bin] += elevationDelta * fraction
                    cursor = chunkEnd
                }
            }
            previous = point
        }
        return gains.toList()
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return runCatching {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrElse { throw PaceTrackException("The track file is not valid XML.") }
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.childText(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val text = element.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun Element.descendantText(tag: String): String? = childText(tag)

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return earthRadius * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}

class PaceTrackException(message: String) : Exception(message)
