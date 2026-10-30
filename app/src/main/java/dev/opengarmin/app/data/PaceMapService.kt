package dev.opengarmin.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Free, keyless helpers for the "draw on the map" course source:
 * OSRM foot routing (routing.openstreetmap.de) and Open-Meteo's elevation
 * model. Both calls are bounded and fail with a readable message so the editor
 * can keep the user's drawn points.
 */
object PaceMapService {
    private const val ELEVATION_ENDPOINT = "https://api.open-meteo.com/v1/elevation"
    private const val FOOT_ROUTE_ENDPOINT =
        "https://routing.openstreetmap.de/routed-foot/route/v1/driving"
    private const val MAX_ELEVATION_POINTS = 100
    private const val MAX_ROUTE_WAYPOINTS = 25

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    /** Fetches elevations for [points], sampling them down to the API limit. */
    suspend fun elevations(points: List<PaceTrackPoint>): List<Double?> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        val sampled = sample(points, MAX_ELEVATION_POINTS)
        val url = buildString {
            append(ELEVATION_ENDPOINT)
            append("?latitude=")
            append(sampled.joinToString(",") { "%.6f".format(java.util.Locale.US, it.latitude) })
            append("&longitude=")
            append(sampled.joinToString(",") { "%.6f".format(java.util.Locale.US, it.longitude) })
        }
        val body = get(url, "The elevation service did not answer.")
        val array = runCatching { JSONObject(body).getJSONArray("elevation") }
            .getOrElse { throw PaceMapException("The elevation service returned an unexpected response.") }
        (0 until array.length()).map { index ->
            array.optDouble(index).takeIf { it.isFinite() }
        }
    }

    /**
     * Routes the drawn waypoints along foot paths and returns the full geometry.
     * Fewer than two points is an error; very long manual routes are sampled so
     * the public OSRM endpoint stays within its URL limits.
     */
    suspend fun followRoads(points: List<PaceTrackPoint>): List<PaceTrackPoint> =
        withContext(Dispatchers.IO) {
            if (points.size < 2) throw PaceMapException("Add at least two points first.")
            val waypoints = sample(points, MAX_ROUTE_WAYPOINTS)
            val coordinates = waypoints.joinToString(";") {
                "%.6f,%.6f".format(java.util.Locale.US, it.longitude, it.latitude)
            }
            val url = "$FOOT_ROUTE_ENDPOINT/$coordinates?overview=full&geometries=geojson&steps=false"
            val body = get(url, "The routing service did not answer.")
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw PaceMapException("The routing service returned an unexpected response.") }
            if (json.optString("code") != "Ok") {
                throw PaceMapException("No foot route was found between those points.")
            }
            val coordinatesJson = json.optJSONArray("routes")
                ?.optJSONObject(0)
                ?.optJSONObject("geometry")
                ?.optJSONArray("coordinates")
                ?: throw PaceMapException("No route geometry was returned.")
            val routed = ArrayList<PaceTrackPoint>(coordinatesJson.length())
            for (index in 0 until coordinatesJson.length()) {
                val pair = coordinatesJson.optJSONArray(index) ?: continue
                val longitude = pair.optDouble(0)
                val latitude = pair.optDouble(1)
                if (!latitude.isFinite() || !longitude.isFinite()) continue
                routed += PaceTrackPoint(latitude, longitude, elevationMeters = null)
            }
            if (routed.size < 2) throw PaceMapException("No foot route was returned.")
            sample(routed, MAX_ELEVATION_POINTS)
        }

    /**
     * Merges elevations into [points]. When the service samples or shortens the
     * list, elevation at a missing index is interpolated from its neighbours.
     */
    fun attachElevations(
        points: List<PaceTrackPoint>,
        elevations: List<Double?>,
    ): List<PaceTrackPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == elevations.size) {
            return points.mapIndexed { index, point ->
                point.copy(elevationMeters = elevations[index] ?: point.elevationMeters)
            }
        }
        return points.mapIndexed { index, point ->
            val position = if (points.size <= 1) 0.0 else {
                index.toDouble() / (points.size - 1) * (elevations.size - 1).coerceAtLeast(0)
            }
            val lower = position.toInt().coerceIn(0, elevations.lastIndex.coerceAtLeast(0))
            val upper = (lower + 1).coerceAtMost(elevations.lastIndex.coerceAtLeast(0))
            val fraction = position - lower
            val low = elevations.getOrNull(lower)
            val high = elevations.getOrNull(upper)
            val elevation = when {
                low != null && high != null -> low + (high - low) * fraction
                low != null -> low
                high != null -> high
                else -> null
            }
            point.copy(elevationMeters = elevation ?: point.elevationMeters)
        }
    }

    /** Evenly spaced sample that always keeps the first and last point. */
    fun sample(points: List<PaceTrackPoint>, maxPoints: Int): List<PaceTrackPoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { index ->
            points[(index * step).roundToInt().coerceIn(0, points.lastIndex)]
        }
    }

    private fun get(url: String, failure: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenGarmin-Pulse")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PaceMapException("$failure (HTTP ${response.code}).")
                }
                response.body?.string() ?: throw PaceMapException(failure)
            }
        }.getOrElse { error ->
            if (error is PaceMapException) throw error
            throw PaceMapException("$failure Check the connection and try again.")
        }
    }
}

class PaceMapException(message: String) : Exception(message)
