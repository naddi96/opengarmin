package dev.opengarmin.app.ui.screens

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Point
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opengarmin.app.data.PaceCourse
import dev.opengarmin.app.data.PaceCourseSource
import dev.opengarmin.app.data.PaceMapException
import dev.opengarmin.app.data.PaceMapService
import dev.opengarmin.app.data.PaceTrackParser
import dev.opengarmin.app.data.PaceTrackPoint
import dev.opengarmin.app.i18n.AppStrings
import dev.opengarmin.app.i18n.LocalAppLanguage
import dev.opengarmin.app.i18n.Text
import dev.opengarmin.app.i18n.tr
import dev.opengarmin.app.ui.components.IconButtonRound
import dev.opengarmin.app.ui.components.PrimaryButton
import dev.opengarmin.app.ui.theme.PulseColors
import dev.opengarmin.app.ui.theme.PulseShapes
import dev.opengarmin.app.ui.theme.PulseSpacing
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import kotlin.math.roundToInt

/**
 * Freehand map editor for the "draw on the map" course source. Taps append
 * points; [draw route along the roads][PaceMapService.followRoads] replaces the
 * straight lines with a walking route; elevations come from Open-Meteo when the
 * user confirms the course.
 */
@Composable
fun PaceMapEditorScreen(
    initialPoints: List<PaceTrackPoint> = emptyList(),
    raceDistanceMeters: Double = 0.0,
    onCancel: () -> Unit,
    onConfirm: (PaceCourse) -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val points = remember {
        mutableStateListOf<GeoPoint>().apply {
            initialPoints.forEach { add(GeoPoint(it.latitude, it.longitude)) }
        }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var allowWithoutElevation by remember { mutableStateOf(false) }
    var pointRevision by remember { mutableStateOf(0) }

    val distanceMeters = remember(pointRevision, points.size) {
        points.zipWithNext().sumOf {
            PaceTrackParser.haversineMeters(
                it.first.latitude,
                it.first.longitude,
                it.second.latitude,
                it.second.longitude,
            )
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setBackgroundColor(0xFFE5E6E5.toInt())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(13.0)
            val first = initialPoints.firstOrNull()
            controller.setCenter(
                if (first != null) GeoPoint(first.latitude, first.longitude)
                else GeoPoint(41.9028, 12.4964),
            )
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                        points.add(GeoPoint(point.latitude, point.longitude))
                        pointRevision++
                        error = null
                        return true
                    }

                    override fun longPressHelper(point: GeoPoint): Boolean = false
                }),
            )
            overlays.add(DrawnRouteOverlay(points = { points }, density = resources.displayMetrics.density))
        }
    }

    LaunchedEffect(pointRevision, points.size) { mapView.invalidate() }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    fun toTrackPoints(): List<PaceTrackPoint> = points.map {
        PaceTrackPoint(it.latitude, it.longitude, elevationMeters = null)
    }

    fun confirm(withElevation: Boolean) {
        if (points.size < 2 || busy != null) return
        busy = AppStrings.translate("Reading elevation…", language)
        error = null
        val base = toTrackPoints()
        scope.launch {
            val result = runCatching {
                val withElevations = if (withElevation) {
                    PaceMapService.attachElevations(base, PaceMapService.elevations(base))
                } else {
                    base
                }
                val track = PaceTrackParser.fromPoints(
                    name = AppStrings.format(
                        "Drawn route · %s km",
                        language,
                        "%.1f".format(distanceMeters / 1_000.0),
                    ),
                    points = withElevations,
                )
                PaceTrackParser.courseFrom(
                    track = track,
                    source = PaceCourseSource.DRAWN,
                    id = "drawn-${System.currentTimeMillis()}",
                )
            }
            busy = null
            result.onSuccess(onConfirm).onFailure { failure ->
                error = failure.message
                    ?: AppStrings.translate("The course could not be built.", language)
                allowWithoutElevation = true
            }
        }
    }

    fun followRoads() {
        if (points.size < 2 || busy != null) return
        busy = AppStrings.translate("Following the roads…", language)
        error = null
        scope.launch {
            val result = runCatching {
                PaceMapService.followRoads(toTrackPoints())
            }
            busy = null
            result.onSuccess { routed ->
                points.clear()
                routed.forEach { points.add(GeoPoint(it.latitude, it.longitude)) }
                pointRevision++
                mapView.post {
                    if (points.isNotEmpty()) mapView.controller.setCenter(points.last())
                }
            }.onFailure { failure ->
                error = (failure as? PaceMapException)?.message
                    ?: AppStrings.translate("The route service could not be reached.", language)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Background)
            .padding(top = PulseSpacing.screenTop),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PulseSpacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonRound(Icons.AutoMirrored.Outlined.ArrowBack, tr("Back to course"), onCancel)
            Spacer(Modifier.width(PulseSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("Draw on the map", style = MaterialTheme.typography.headlineSmall, color = PulseColors.Text)
                Text(
                    tr("Tap the map to add points. %s km · %s points", "%.2f".format(distanceMeters / 1_000.0), points.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextMuted,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = PulseSpacing.xxl, vertical = PulseSpacing.lg)
                .clipToBounds(),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (busy != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PulseColors.Background.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(busy ?: "", style = MaterialTheme.typography.bodyMedium, color = PulseColors.Text)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PulseSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PulseSpacing.sm),
        ) {
            if (raceDistanceMeters > 0.0 && distanceMeters > 0.0 &&
                distanceMeters < raceDistanceMeters * 0.98
            ) {
                Text(
                    tr(
                        "The loop is %s km, the race is %s km. Add %s km to reach the right distance.",
                        "%.2f".format(distanceMeters / 1_000.0),
                        "%.2f".format(raceDistanceMeters / 1_000.0),
                        "%.2f".format((raceDistanceMeters - distanceMeters) / 1_000.0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.Yellow,
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = PulseColors.Coral)
            }
            Text(
                tr("© OpenStreetMap contributors · elevations by Open-Meteo"),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.sm)) {
                EditorButton(
                    label = tr("Undo"),
                    enabled = points.isNotEmpty() && busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    if (points.isNotEmpty()) {
                        points.removeAt(points.lastIndex)
                        pointRevision++
                    }
                }
                EditorButton(
                    label = tr("Clear"),
                    enabled = points.isNotEmpty() && busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    points.clear()
                    pointRevision++
                }
                EditorButton(
                    label = tr("Follow roads"),
                    enabled = points.size >= 2 && busy == null,
                    modifier = Modifier.weight(1.4f),
                ) { followRoads() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PulseSpacing.md)) {
                EditorButton(
                    label = tr("Cancel"),
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                    onClick = onCancel,
                )
                PrimaryButton(
                    text = if (allowWithoutElevation) tr("Use without elevation") else tr("Use this course"),
                    onClick = { confirm(withElevation = !allowWithoutElevation) },
                    enabled = points.size >= 2 && busy == null,
                    modifier = Modifier.weight(2f),
                )
            }
            Spacer(Modifier.height(PulseSpacing.xl))
        }
    }
}

@Composable
private fun EditorButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(PulseSpacing.touchTarget)
            .clip(PulseShapes.medium)
            .background(PulseColors.Surface)
            .border(1.dp, PulseColors.BorderStrong, PulseShapes.medium)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) PulseColors.Text else PulseColors.TextMuted,
            maxLines = 1,
        )
    }
}

/** Draws the user's waypoints and a straight preview line between them. */
private class DrawnRouteOverlay(
    private val points: () -> List<GeoPoint>,
    density: Float,
) : Overlay() {
    private val routePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = PulseColors.Accent.toArgb()
    }
    private val casingPaint = Paint(routePaint).apply {
        strokeWidth = 8f * density
        color = android.graphics.Color.WHITE
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = PulseColors.Text.toArgb()
    }
    private val lastDotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = PulseColors.Accent.toArgb()
    }
    private val dotRadius = 5f * density
    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = PulseColors.TextOnAccent.toArgb()
    }

    override fun draw(canvas: AndroidCanvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val items = points()
        if (items.isEmpty()) return
        val projection = mapView.projection
        val first = Point()
        val second = Point()
        for (index in 0 until items.lastIndex) {
            projection.toPixels(items[index], first)
            projection.toPixels(items[index + 1], second)
            canvas.drawLine(
                first.x.toFloat(),
                first.y.toFloat(),
                second.x.toFloat(),
                second.y.toFloat(),
                casingPaint,
            )
            canvas.drawLine(
                first.x.toFloat(),
                first.y.toFloat(),
                second.x.toFloat(),
                second.y.toFloat(),
                routePaint,
            )
        }
        items.forEachIndexed { index, point ->
            projection.toPixels(point, first)
            val x = first.x.toFloat()
            val y = first.y.toFloat()
            canvas.drawCircle(x, y, dotRadius, if (index == items.lastIndex) lastDotPaint else dotPaint)
            canvas.drawCircle(x, y, dotRadius, ringPaint)
        }
    }
}
