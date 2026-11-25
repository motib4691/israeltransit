package il.co.israeltransit.offline

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.routing.FareCalculator
import il.co.israeltransit.offline.routing.FareResult
import il.co.israeltransit.offline.routing.GraphHopperWalkingRouter
import il.co.israeltransit.offline.routing.TransitRouteEngine
import il.co.israeltransit.offline.routing.WalkingRoute
import il.co.israeltransit.offline.routing.WalkingRouteResult
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.time.Duration
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val journeyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun JourneyDetailsScreen(
    journey: TransitRouteEngine.Journey,
    repository: TransitRepository,
    walkingRouter: GraphHopperWalkingRouter,
    mapStorage: OfflineMapStorage,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var isMapFullScreen by remember { mutableStateOf(false) }
    var fareInfoToShow by remember { mutableStateOf<FareResult?>(null) }

    BackHandler {
        if (isMapFullScreen) {
            isMapFullScreen = false
        } else {
            onBack()
        }
    }

    // דיאלוג תעריף נסיעה ('דרך שווה')
    fareInfoToShow?.let { fare ->
        AlertDialog(
            onDismissRequest = { fareInfoToShow = null },
            title = { Text(stringResource(R.string.ui_0e0b84)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ui_0e4472, fare.priceNis),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(fare.description, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { fareInfoToShow = null }) {
                    Text(stringResource(R.string.ui_39d88b))
                }
            }
        )
    }

    val firstLeg = journey.legs.firstOrNull()
    val initialLat = journey.initialWalk?.fromLat ?: firstLeg?.fromLat ?: 31.7683
    val initialLon = journey.initialWalk?.fromLon ?: firstLeg?.fromLon ?: 35.2137
    val mapFile: File = mapStorage.getMapFile()

    val totalMeters = journey.legs.sumOf { leg ->
        TransitRouteEngine.calculateDistanceMeters(leg.fromLat, leg.fromLon, leg.toLat, leg.toLon) ?: 3000.0
    }
    val isTrain = journey.legs.any { it.routeId.contains("train") }
    val fare = remember(journey) {
        FareCalculator.calculateFare(totalMeters, isTrain, journey.durationMinutes)
    }

    // חישוב מסלולי ההליכה האמיתיים מ-GraphHopper עבור כל קטעי ההליכה במסלול
    var walkingRoutesMap by remember(journey) { mutableStateOf<Map<String, WalkingRoute>>(emptyMap()) }
    var journeyMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

    LaunchedEffect(journey) {
        val computed = withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, WalkingRoute>()

            journey.initialWalk?.let { iw ->
                if (iw.toLat != null && iw.toLon != null) {
                    val res = walkingRouter.findRoute(iw.fromLat, iw.fromLon, iw.toLat, iw.toLon)
                    if (res is WalkingRouteResult.Success) {
                        map["initial"] = res.route
                    }
                }
            }

            for (i in 0 until journey.legs.lastIndex) {
                val cur = journey.legs[i]
                val next = journey.legs[i + 1]
                if (cur.toStopId != next.fromStopId &&
                    cur.toLat != null && cur.toLon != null &&
                    next.fromLat != null && next.fromLon != null
                ) {
                    val res = walkingRouter.findRoute(cur.toLat, cur.toLon, next.fromLat, next.fromLon)
                    if (res is WalkingRouteResult.Success) {
                        map["transfer_$i"] = res.route
                    }
                }
            }

            journey.finalWalk?.let { fw ->
                if (fw.fromLat != null && fw.fromLon != null && fw.toLat != null && fw.toLon != null) {
                    val res = walkingRouter.findRoute(fw.fromLat, fw.fromLon, fw.toLat, fw.toLon)
                    if (res is WalkingRouteResult.Success) {
                        map["final"] = res.route
                    }
                }
            }

            map
        }
        walkingRoutesMap = computed
    }

    fun drawDetailedRouteOnMap(map: MapLibreMap, walks: Map<String, WalkingRoute>) {
        map.getStyle { style ->
            if (!style.isFullyLoaded) return@getStyle

            val allBoundsCoords = mutableListOf<LatLng>()
            val transitLineFeatures = mutableListOf<Feature>()
            val walkLineFeatures = mutableListOf<Feature>()
            val markerFeatures = mutableListOf<Feature>()

            // קטע הליכה ראשוני
            journey.initialWalk?.let { iw ->
                allBoundsCoords.add(LatLng(iw.fromLat, iw.fromLon))
                markerFeatures.add(
                    Feature.fromGeometry(Point.fromLngLat(iw.fromLon, iw.fromLat)).apply {
                        addStringProperty("marker_color", "#2E7D32")
                    }
                )
                val ghWalk = walks["initial"]
                if (ghWalk != null && ghWalk.points.size >= 2) {
                    val pts = ghWalk.points.map {
                        allBoundsCoords.add(LatLng(it.latitude, it.longitude))
                        Point.fromLngLat(it.longitude, it.latitude)
                    }
                    walkLineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
                } else if (iw.toLat != null && iw.toLon != null) {
                    walkLineFeatures.add(
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                listOf(
                                    Point.fromLngLat(iw.fromLon, iw.fromLat),
                                    Point.fromLngLat(iw.toLon, iw.toLat)
                                )
                            )
                        )
                    )
                }
            }

            // קטעי תחבורה ציבורית והחלפות ביניהם
            journey.legs.forEachIndexed { idx, leg ->
                val legPts = mutableListOf<Point>()
                if (leg.fromLat != null && leg.fromLon != null) {
                    allBoundsCoords.add(LatLng(leg.fromLat, leg.fromLon))
                    legPts.add(Point.fromLngLat(leg.fromLon, leg.fromLat))
                    val color = if (idx == 0 && journey.initialWalk == null) "#2E7D32" else "#EF6C00"
                    markerFeatures.add(
                        Feature.fromGeometry(Point.fromLngLat(leg.fromLon, leg.fromLat)).apply {
                            addStringProperty("marker_color", color)
                        }
                    )
                }
                if (leg.toLat != null && leg.toLon != null) {
                    allBoundsCoords.add(LatLng(leg.toLat, leg.toLon))
                    legPts.add(Point.fromLngLat(leg.toLon, leg.toLat))
                    val isFinalStop = idx == journey.legs.lastIndex && journey.finalWalk == null
                    val color = if (isFinalStop) "#C62828" else "#EF6C00"
                    markerFeatures.add(
                        Feature.fromGeometry(Point.fromLngLat(leg.toLon, leg.toLat)).apply {
                            addStringProperty("marker_color", color)
                        }
                    )
                }
                if (legPts.size >= 2) {
                    transitLineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(legPts)))
                }

                if (idx < journey.legs.lastIndex) {
                    val nextLeg = journey.legs[idx + 1]
                    if (leg.toStopId != nextLeg.fromStopId) {
                        val ghTransfer = walks["transfer_$idx"]
                        if (ghTransfer != null && ghTransfer.points.size >= 2) {
                            val pts = ghTransfer.points.map {
                                allBoundsCoords.add(LatLng(it.latitude, it.longitude))
                                Point.fromLngLat(it.longitude, it.latitude)
                            }
                            walkLineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
                        } else if (leg.toLat != null && leg.toLon != null && nextLeg.fromLat != null && nextLeg.fromLon != null) {
                            walkLineFeatures.add(
                                Feature.fromGeometry(
                                    LineString.fromLngLats(
                                        listOf(
                                            Point.fromLngLat(leg.toLon, leg.toLat),
                                            Point.fromLngLat(nextLeg.fromLon, nextLeg.fromLat)
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            // קטע הליכה מסכם
            journey.finalWalk?.let { fw ->
                if (fw.toLat != null && fw.toLon != null) {
                    allBoundsCoords.add(LatLng(fw.toLat, fw.toLon))
                    markerFeatures.add(
                        Feature.fromGeometry(Point.fromLngLat(fw.toLon, fw.toLat)).apply {
                            addStringProperty("marker_color", "#C62828")
                        }
                    )
                }
                val ghFinal = walks["final"]
                if (ghFinal != null && ghFinal.points.size >= 2) {
                    val pts = ghFinal.points.map {
                        allBoundsCoords.add(LatLng(it.latitude, it.longitude))
                        Point.fromLngLat(it.longitude, it.latitude)
                    }
                    walkLineFeatures.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
                } else if (fw.fromLat != null && fw.fromLon != null && fw.toLat != null && fw.toLon != null) {
                    walkLineFeatures.add(
                        Feature.fromGeometry(
                            LineString.fromLngLats(
                                listOf(
                                    Point.fromLngLat(fw.fromLon, fw.fromLat),
                                    Point.fromLngLat(fw.toLon, fw.toLat)
                                )
                            )
                        )
                    )
                }
            }

            if (allBoundsCoords.size >= 2) {
                val boundsBuilder = LatLngBounds.Builder()
                allBoundsCoords.forEach { boundsBuilder.include(it) }
                try {
                    map.easeCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 90), 900)
                } catch (_: Exception) {
                    map.easeCamera(CameraUpdateFactory.newLatLngZoom(allBoundsCoords.first(), 13.5))
                }
            } else if (allBoundsCoords.size == 1) {
                map.easeCamera(CameraUpdateFactory.newLatLngZoom(allBoundsCoords.first(), 14.5))
            }

            // שכבת נסיעה (כחול מלא)
            val transitSourceId = "journey-path-source"
            val transitLayerId = "journey-path-layer"
            if (style.getLayer(transitLayerId) != null) style.removeLayer(transitLayerId)
            if (style.getSource(transitSourceId) != null) style.removeSource(transitSourceId)
            style.addSource(GeoJsonSource(transitSourceId, FeatureCollection.fromFeatures(transitLineFeatures)))
            style.addLayer(
                LineLayer(transitLayerId, transitSourceId).withProperties(
                    lineColor("#1769AA"),
                    lineWidth(4.5f),
                    lineCap("round"),
                    lineJoin("round")
                )
            )

            // שכבת הליכה (ירוק מקווקו לפי שבילים)
            val walkSourceId = "journey-walk-source"
            val walkLayerId = "journey-walk-layer"
            if (style.getLayer(walkLayerId) != null) style.removeLayer(walkLayerId)
            if (style.getSource(walkSourceId) != null) style.removeSource(walkSourceId)
            style.addSource(GeoJsonSource(walkSourceId, FeatureCollection.fromFeatures(walkLineFeatures)))
            style.addLayer(
                LineLayer(walkLayerId, walkSourceId).withProperties(
                    lineColor("#2E7D32"),
                    lineWidth(4.0f),
                    lineCap("round"),
                    lineJoin("round"),
                    lineDasharray(arrayOf(2.0f, 1.8f))
                )
            )

            // שכבת נקודות עצירה ויעדים
            val stopsSourceId = "journey-stops-source"
            val stopsLayerId = "journey-stops-layer"
            if (style.getLayer(stopsLayerId) != null) style.removeLayer(stopsLayerId)
            if (style.getSource(stopsSourceId) != null) style.removeSource(stopsSourceId)
            style.addSource(GeoJsonSource(stopsSourceId, FeatureCollection.fromFeatures(markerFeatures)))
            style.addLayer(
                CircleLayer(stopsLayerId, stopsSourceId).withProperties(
                    circleColor(Expression.get("marker_color")),
                    circleRadius(7.5f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2.5f)
                )
            )
        }
    }

    LaunchedEffect(journeyMapInstance, walkingRoutesMap, journey) {
        journeyMapInstance?.let { map ->
            drawDetailedRouteOnMap(map, walkingRoutesMap)
        }
    }

    if (isMapFullScreen) {
        Box(modifier = Modifier.fillMaxSize()) {
            OfflineMapView(
                pmtilesFile = mapFile,
                modifier = Modifier.fillMaxSize(),
                initialLatitude = initialLat,
                initialLongitude = initialLon,
                initialZoom = 13.0,
                onMapReady = { map ->
                    journeyMapInstance = map
                    drawDetailedRouteOnMap(map, walkingRoutesMap)
                }
            )
            FilledTonalButton(
                onClick = { isMapFullScreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(stringResource(R.string.ui_28ae89))
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.ui_1a22ab),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(stringResource(R.string.ui_fff64f))
            }
        }

        Text(
            stringResource(R.string.ui_95f2be, journey.departureAt.format(journeyTimeFormatter), journey.arrivalAt.format(journeyTimeFormatter), journey.durationMinutes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    val summary = buildString {
                        append("פירוט מסלול נסיעה:\n")
                        append("יציאה: ${journey.departureAt.format(journeyTimeFormatter)} | הגעה: ${journey.arrivalAt.format(journeyTimeFormatter)} (${journey.durationMinutes} דק')\n\n")
                        journey.initialWalk?.let { iw ->
                            val dist = walkingRoutesMap["initial"]?.distanceMeters?.roundToInt() ?: iw.distanceMeters.roundToInt()
                            append("הליכה לתחנה: מ-${iw.fromName} אל ${iw.toStopName} (כ-$dist מ', ${iw.walkMinutes} דק')\n")
                            walkingRoutesMap["initial"]?.instructions?.forEach { inst ->
                                append("  • ${inst.text} (${inst.distanceMeters.roundToInt()} מ')\n")
                            }
                            append("\n")
                        }
                        journey.legs.forEachIndexed { i, leg ->
                            append("${i + 1}. קו ${leg.lineNumber} (${leg.agencyName})\n")
                            append("   עליה: ${leg.fromStopName} בשעה ${leg.departureAt.format(journeyTimeFormatter)}\n")
                            append("   ירידה: ${leg.toStopName} בשעה ${leg.arrivalAt.format(journeyTimeFormatter)}\n")
                            walkingRoutesMap["transfer_$i"]?.let { tr ->
                                append("   הליכה להחלפה (${tr.distanceMeters.roundToInt()} מ'):\n")
                                tr.instructions.forEach { inst ->
                                    append("     • ${inst.text} (${inst.distanceMeters.roundToInt()} מ')\n")
                                }
                            }
                        }
                        journey.finalWalk?.let { fw ->
                            val dist = walkingRoutesMap["final"]?.distanceMeters?.roundToInt() ?: fw.distanceMeters.roundToInt()
                            append("הליכה מסכמת ליעד: כ-$dist מ' (${fw.walkMinutes} דק') אל ${fw.toStopName}\n")
                            walkingRoutesMap["final"]?.instructions?.forEach { inst ->
                                append("  • ${inst.text} (${inst.distanceMeters.roundToInt()} מ')\n")
                            }
                        }
                    }
                    clipboardManager.setText(AnnotatedString(summary))
                    Toast.makeText(context, "סיכום הנסיעה הועתק ללוח!", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(stringResource(R.string.ui_1f9277), fontSize = 12.sp)
            }

            FilledTonalButton(
                onClick = { fareInfoToShow = fare },
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(stringResource(R.string.ui_d10b6d, fare.priceNis), fontSize = 12.sp)
            }
        }

        // הליכה ראשונית מכתובת המוצא לתחנת העלייה
        journey.initialWalk?.let { iw ->
            var showInitWalkDetails by remember { mutableStateOf(true) }
            val ghWalk = walkingRoutesMap["initial"]
            val distMeters = ghWalk?.distanceMeters?.roundToInt() ?: iw.distanceMeters.roundToInt()

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showInitWalkDetails = !showInitWalkDetails },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.ui_41fa60, iw.toStopName),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(if (showInitWalkDetails) "▲" else "▼", fontSize = 11.sp)
                    }
                    Text(
                        stringResource(R.string.ui_4dac3d, iw.fromName, iw.toStopName, distMeters, iw.walkMinutes),
                        style = MaterialTheme.typography.bodySmall
                    )
                    AnimatedVisibility(visible = showInitWalkDetails && ghWalk != null && ghWalk.instructions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            ghWalk?.instructions?.forEachIndexed { idx, inst ->
                                val distSuffix = if (inst.distanceMeters >= 5) " (${inst.distanceMeters.roundToInt()} מ')" else ""
                                Text(
                                    "${idx + 1}. ${inst.text}$distSuffix",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        journey.legs.forEachIndexed { legIdx, leg ->
            var showIntermediateStops by remember { mutableStateOf(false) }
            var intermediateStops by remember { mutableStateOf(emptyList<TransitRepository.RouteStop>()) }

            LaunchedEffect(showIntermediateStops) {
                if (showIntermediateStops && intermediateStops.isEmpty()) {
                    val allStops = withContext(Dispatchers.IO) { repository.getStopsForLine(leg.routeId, 300) }
                    val fromIndex = allStops.indexOfFirst { it.stopId == leg.fromStopId }
                    val toIndex = allStops.indexOfFirst { it.stopId == leg.toStopId }
                    intermediateStops = if (fromIndex != -1 && toIndex != -1 && fromIndex < toIndex) {
                        allStops.subList(fromIndex, toIndex + 1)
                    } else emptyList()
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransitLineBadge(lineNumber = leg.lineNumber, agencyName = leg.agencyName)
                        Column {
                            Text(leg.lineName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(leg.agencyName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Text(
                        stringResource(R.string.ui_d8e898, leg.fromStopName, leg.departureAt.format(journeyTimeFormatter)),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showIntermediateStops = !showIntermediateStops }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.ui_a23664),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(if (showIntermediateStops) "▲" else "▼", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    AnimatedVisibility(visible = showIntermediateStops) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (intermediateStops.isEmpty()) {
                                Text(stringResource(R.string.ui_fbf5b8), style = MaterialTheme.typography.bodySmall)
                            } else {
                                intermediateStops.forEachIndexed { sIdx, s ->
                                    Text("${sIdx + 1}. ${s.name} (תחנה ${s.stopCode})", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    Text(
                        stringResource(R.string.ui_10a009, leg.toStopName, leg.arrivalAt.format(journeyTimeFormatter)),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            if (legIdx < journey.legs.lastIndex) {
                val nextLeg = journey.legs[legIdx + 1]
                val waitMinutes = Duration.between(leg.arrivalAt, nextLeg.departureAt).toMinutes()
                val sameStop = leg.toStopId == nextLeg.fromStopId
                var showWalkingDetails by remember { mutableStateOf(false) }
                val ghTransfer = walkingRoutesMap["transfer_$legIdx"]

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (sameStop) {
                            Text(stringResource(R.string.ui_31b21f, leg.toStopName), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(stringResource(R.string.ui_6da8c7, nextLeg.lineNumber, waitMinutes), style = MaterialTheme.typography.bodySmall)
                        } else {
                            val transferDist = ghTransfer?.distanceMeters?.roundToInt()
                            val distText = if (transferDist != null) " (כ-$transferDist מ')" else ""
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showWalkingDetails = !showWalkingDetails },
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    stringResource(R.string.ui_b211a8, distText),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(if (showWalkingDetails) "▲" else "▼", fontSize = 11.sp)
                            }
                            AnimatedVisibility(visible = showWalkingDetails) {
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    if (ghTransfer != null && ghTransfer.instructions.isNotEmpty()) {
                                        ghTransfer.instructions.forEachIndexed { idx, inst ->
                                            val d = if (inst.distanceMeters >= 5) " (${inst.distanceMeters.roundToInt()} מ')" else ""
                                            Text("${idx + 1}. ${inst.text}$d", style = MaterialTheme.typography.bodySmall)
                                        }
                                    } else {
                                        Text(stringResource(R.string.ui_0283fb, leg.toStopName), style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.ui_b42667), style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.ui_bea720, nextLeg.fromStopName), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text(stringResource(R.string.ui_daf8c5, nextLeg.lineNumber, waitMinutes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        journey.finalWalk?.let { walk ->
            var showFinalWalkDetails by remember { mutableStateOf(true) }
            val ghFinal = walkingRoutesMap["final"]
            val distMeters = ghFinal?.distanceMeters?.roundToInt() ?: walk.distanceMeters.roundToInt()

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFinalWalkDetails = !showFinalWalkDetails },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_0511ad), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (ghFinal != null && ghFinal.instructions.isNotEmpty()) {
                            Text(if (showFinalWalkDetails) "▲" else "▼", fontSize = 11.sp)
                        }
                    }
                    Text(
                        stringResource(R.string.ui_dfaa47, distMeters, walk.walkMinutes, walk.fromStopName, walk.toStopName),
                        style = MaterialTheme.typography.bodySmall
                    )
                    AnimatedVisibility(visible = showFinalWalkDetails && ghFinal != null && ghFinal.instructions.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            ghFinal?.instructions?.forEachIndexed { idx, inst ->
                                val d = if (inst.distanceMeters >= 5) " (${inst.distanceMeters.roundToInt()} מ')" else ""
                                Text("${idx + 1}. ${inst.text}$d", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (mapStorage.isMapAvailable()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    OfflineMapView(
                        pmtilesFile = mapFile,
                        modifier = Modifier.fillMaxSize(),
                        initialLatitude = initialLat,
                        initialLongitude = initialLon,
                        initialZoom = 13.0,
                        onMapReady = { map ->
                            journeyMapInstance = map
                            drawDetailedRouteOnMap(map, walkingRoutesMap)
                        }
                    )
                    FilledTonalButton(
                        onClick = { isMapFullScreen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.ui_401142), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
