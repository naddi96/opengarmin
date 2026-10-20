package dev.opengarmin.app.ui.components

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.opengarmin.app.data.RecordEntity
import dev.opengarmin.app.ui.theme.PulseColors
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import kotlin.math.roundToInt

private data class RouteSample(val point: GeoPoint, val speedMps: Double?)

/**
 * Draws the GPS trace as colour-coded line segments: blue for slower samples,
 * red for faster ones. Using a custom overlay instead of one Polyline keeps
 * the speed gradient without adding thousands of map overlays.
 */
private class SpeedRouteOverlay(
    private val route: List<RouteSample>,
) : Overlay() {
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val slowColor = PulseColors.RouteSlow.toArgb()
    private val fastColor = PulseColors.RouteFast.toArgb()
    private val lowSpeed: Double
    private val highSpeed: Double

    init {
        val speeds = route
            .mapNotNull { it.speedMps }
            .filter { it.isFinite() && it > 0.3 }
            .sorted()
        if (speeds.size < 2 || speeds.last() - speeds.first() < 0.15) {
            lowSpeed = speeds.firstOrNull() ?: 0.0
            highSpeed = lowSpeed
        } else {
            val lowIndex = (speeds.size * 0.05).toInt().coerceIn(0, speeds.lastIndex)
            val highIndex = (speeds.size * 0.95).toInt().coerceIn(0, speeds.lastIndex)
            lowSpeed = speeds[lowIndex]
            highSpeed = speeds[highIndex].takeIf { it > lowSpeed } ?: (lowSpeed + 0.5)
        }
    }

    override fun draw(canvas: AndroidCanvas, mapView: MapView, shadow: Boolean) {
        if (shadow || route.size < 2) return
        val projection = mapView.projection
        val first = Point()
        val second = Point()
        val step = if (route.size > 1_800) 2 else 1
        var index = 0
        while (index < route.lastIndex) {
            val next = minOf(index + step, route.lastIndex)
            projection.toPixels(route[index].point, first)
            projection.toPixels(route[next].point, second)
            paint.color = colorForSpeed(averageSpeed(index, next))
            canvas.drawLine(
                first.x.toFloat(),
                first.y.toFloat(),
                second.x.toFloat(),
                second.y.toFloat(),
                paint,
            )
            index = next
        }
    }

    private fun averageSpeed(index: Int, next: Int): Double {
        val first = route[index].speedMps
        val second = route[next].speedMps
        return when {
            first != null && second != null -> (first + second) / 2.0
            first != null -> first
            second != null -> second
            else -> 0.0
        }
    }

    private fun colorForSpeed(speed: Double): Int {
        val fraction = if (highSpeed > lowSpeed + 0.001) {
            ((speed - lowSpeed) / (highSpeed - lowSpeed)).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return lerpColor(slowColor, fastColor, fraction)
    }

    private fun lerpColor(start: Int, end: Int, fraction: Double): Int {
        val red = (android.graphics.Color.red(start) +
            (android.graphics.Color.red(end) - android.graphics.Color.red(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        val green = (android.graphics.Color.green(start) +
            (android.graphics.Color.green(end) - android.graphics.Color.green(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        val blue = (android.graphics.Color.blue(start) +
            (android.graphics.Color.blue(end) - android.graphics.Color.blue(start)) * fraction)
            .roundToInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(red, green, blue)
    }
}

@Composable
internal fun RouteMap(records: List<RecordEntity>, modifier: Modifier = Modifier) {
    val route = remember(records) {
        records.mapNotNull { record ->
            val lat = record.latitude ?: return@mapNotNull null
            val lon = record.longitude ?: return@mapNotNull null
            RouteSample(GeoPoint(lat, lon), record.speedMps)
        }
    }
    val points = remember(route) { route.map { it.point } }
    if (points.size < 2) {
        Text(
            "No GPS track in this activity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val context = LocalContext.current
    val mapView = remember(route) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            overlays.add(SpeedRouteOverlay(route))
            overlays.add(Marker(this).apply { position = points.first(); title = "Start" })
            overlays.add(Marker(this).apply { position = points.last(); title = "Finish" })
            val box = BoundingBox.fromGeoPoints(points)
            controller.setCenter(
                GeoPoint((box.latNorth + box.latSouth) / 2.0, (box.lonEast + box.lonWest) / 2.0),
            )
            controller.setZoom(16.0)
            post { zoomToBoundingBox(box, true, 64) }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
