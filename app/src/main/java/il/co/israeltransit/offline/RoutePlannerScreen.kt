package il.co.israeltransit.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.AddressSearchRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.maps.TransitMapScreen
import il.co.israeltransit.offline.maps.TransitMyLocationControl
import il.co.israeltransit.offline.routing.FareCalculator
import il.co.israeltransit.offline.routing.FareResult
import il.co.israeltransit.offline.routing.GraphHopperWalkingRouter
import il.co.israeltransit.offline.routing.TransitRouteEngine
import il.co.israeltransit.offline.routing.WalkingRoute
import il.co.israeltransit.offline.routing.WalkingRouteResult
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class PlannerSavedStop(
    val stopId: String,
    val stopName: String,
    val customName: String,
    val stopCode: String = stopId
)

private fun loadPlannerSavedStops(context: Context): List<PlannerSavedStop> {
    return try {
        val json = context.getSharedPreferences(
            "saved_transit_stops",
            Context.MODE_PRIVATE
        ).getString("stops", "[]") ?: "[]"

        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val sId = item.optString("stopId")
                val sCode = item.optString("stopCode").ifBlank { sId }
                add(
                    PlannerSavedStop(
                        stopId = sId,
                        stopName = item.optString("stopName"),
                        customName = item.optString("customName"),
                        stopCode = sCode
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun toRouteEndpoint(stop: TransitRepository.TransitStop): TransitRouteEngine.RouteEndpoint {
    return if (stop.stopId.startsWith("coord:") && stop.latitude != null && stop.longitude != null) {
        TransitRouteEngine.RouteEndpoint.Location(
            name = stop.name,
            latitude = stop.latitude,
            longitude = stop.longitude
        )
    } else {
        TransitRouteEngine.RouteEndpoint.Stop(
            stopId = stop.stopId,
            name = stop.name
        )
    }
}

private fun addressToSyntheticStop(addr: AddressSearchRepository.AddressResult): TransitRepository.TransitStop {
    val label = if (
        addr.subtitle.isNotBlank() &&
        !addr.subtitle.startsWith("עיר") &&
        !addr.title.contains(addr.subtitle.removePrefix("רחוב • "))
    ) {
        "${addr.title}, ${addr.subtitle.removePrefix("רחוב • ")}"
    } else {
        addr.title
    }
    return TransitRepository.TransitStop(
        stopId = "coord:${addr.latitude},${addr.longitude}",
        name = label,
        latitude = addr.latitude,
        longitude = addr.longitude,
        stopCode = ""
    )
}

private val plannerTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun RoutePlannerScreen(
    onBack: () -> Unit,
    initialOrigin: TransitRepository.TransitStop? = null,
    initialDestination: TransitRepository.TransitStop? = null,
    focusDestinationInitially: Boolean = false,
    onClearInitialStops: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current

    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val addressRepository = remember(context) { AddressSearchRepository(context) }
    val walkingRouter = remember(context) { GraphHopperWalkingRouter(context) }
    val routeEngine = remember(database, walkingRouter) { TransitRouteEngine(database, walkingRouter) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val settings = remember(context) { TransitSettings(context) }
    val locationControl = remember(context) { TransitMyLocationControl(context) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val savedStops = remember(context) { loadPlannerSavedStops(context) }
    val savedRefs = remember(savedStops) {
        savedStops.map {
            TransitRepository.SavedStopRef(
                stopId = it.stopId,
                customName = it.customName,
                stopCode = it.stopCode
            )
        }
    }

    var originQuery by remember { mutableStateOf(initialOrigin?.name.orEmpty()) }
    var destinationQuery by remember { mutableStateOf(initialDestination?.name.orEmpty()) }

    var originStopResults by remember { mutableStateOf(emptyList<TransitRepository.DualStopGroup>()) }
    var originAddressResults by remember { mutableStateOf(emptyList<AddressSearchRepository.AddressResult>()) }
    var destinationStopResults by remember { mutableStateOf(emptyList<TransitRepository.DualStopGroup>()) }
    var destinationAddressResults by remember { mutableStateOf(emptyList<AddressSearchRepository.AddressResult>()) }

    var selectedOrigin by remember { mutableStateOf(initialOrigin) }
    var selectedDestination by remember { mutableStateOf(initialDestination) }

    var activeField by remember {
        mutableStateOf(if (focusDestinationInitially || initialOrigin != null) "destination" else "origin")
    }

    var searching by remember { mutableStateOf(false) }
    var calculating by remember { mutableStateOf(false) }
    var detectingLocation by remember { mutableStateOf(false) }

    var routeResult by remember { mutableStateOf<TransitRouteEngine.SearchResult?>(null) }
    var departureTime by remember { mutableStateOf(LocalDateTime.now()) }

    var expandedJourneyIndex by remember { mutableIntStateOf(-1) }
    var detailedJourney by remember { mutableStateOf<TransitRouteEngine.Journey?>(null) }
    var isMapFullScreen by remember { mutableStateOf(false) }
    var fareInfoToShow by remember { mutableStateOf<FareResult?>(null) }

    var pickingOnMapForField by remember { mutableStateOf<String?>(null) }
    var showMapNotInstalledDialog by remember { mutableStateOf(false) }

    val originFocusRequester = remember { FocusRequester() }
    val destFocusRequester = remember { FocusRequester() }

    fun searchRoute() {
        val origin = selectedOrigin ?: return
        val destination = selectedDestination ?: return

        if (origin.stopId == destination.stopId || calculating) return

        calculating = true
        routeResult = null
        expandedJourneyIndex = -1

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                routeEngine.findJourneys(
                    origin = toRouteEndpoint(origin),
                    destination = toRouteEndpoint(destination),
                    departureTime = departureTime,
                    maxTransfers = settings.maxTransfers,
                    minimumTransferMinutes = settings.minTransferMinutes.toLong(),
                    allowWalkingTransfers = settings.allowWalkingTransfers,
                    maxResults = 5
                )
            }
            routeResult = result
            calculating = false
        }
    }

    LaunchedEffect(Unit) {
        delay(250)
        if (initialOrigin != null && initialDestination != null) {
            keyboardController?.hide()
        } else if (focusDestinationInitially || initialOrigin != null) {
            destFocusRequester.requestFocus()
            scrollState.animateScrollTo(260)
        } else {
            originFocusRequester.requestFocus()
        }
    }

    fun findAndSetNearestStopFromGPS() {
        detectingLocation = true
        scope.launch {
            val locationResult = locationControl.getCurrentLocation()
            detectingLocation = false

            locationResult.onSuccess { loc ->
                val nearest = withContext(Dispatchers.IO) {
                    repository.findNearestStop(loc.latitude, loc.longitude)
                }

                if (nearest != null) {
                    selectedOrigin = nearest
                    originQuery = nearest.name
                    originStopResults = emptyList()
                    originAddressResults = emptyList()
                    activeField = "destination"
                    routeResult = null
                    Toast.makeText(
                        context,
                        "נבחרה התחנה הקרובה: ${nearest.name} (תחנה ${nearest.stopCode})",
                        Toast.LENGTH_SHORT
                    ).show()
                    destFocusRequester.requestFocus()
                    scrollState.animateScrollTo(260)
                } else {
                    val label = withContext(Dispatchers.IO) {
                        addressRepository.findNearestPlaceLabel(loc.latitude, loc.longitude)
                    } ?: "המיקום הנוכחי שלי"
                    selectedOrigin = TransitRepository.TransitStop(
                        stopId = "coord:${loc.latitude},${loc.longitude}",
                        name = label,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        stopCode = ""
                    )
                    originQuery = label
                    originStopResults = emptyList()
                    originAddressResults = emptyList()
                    activeField = "destination"
                    routeResult = null
                    destFocusRequester.requestFocus()
                    scrollState.animateScrollTo(260)
                }
            }.onFailure {
                Toast.makeText(
                    context,
                    "לא ניתן לקבל מיקום כעת. באפשרותך לבחור תחנה או כתובת ידנית.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            findAndSetNearestStopFromGPS()
        } else {
            Toast.makeText(
                context,
                "לא ניתנה הרשאת מיקום. באפשרותך לבחור תחנה או כתובת ידנית.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(initialOrigin, initialDestination) {
        if (initialOrigin != null) {
            selectedOrigin = initialOrigin
            originQuery = initialOrigin.name
            originStopResults = emptyList()
            originAddressResults = emptyList()
            activeField = "destination"
        }
        if (initialDestination != null) {
            selectedDestination = initialDestination
            destinationQuery = initialDestination.name
            destinationStopResults = emptyList()
            destinationAddressResults = emptyList()
        }
        if (initialOrigin != null || initialDestination != null) {
            routeResult = null
            onClearInitialStops()
        }
    }

    // חיפוש מוצא משולב (כתובות + תחנות)
    LaunchedEffect(originQuery, activeField) {
        if (activeField != "origin") return@LaunchedEffect
        val value = originQuery.trim()
        if (value.isEmpty() || (selectedOrigin != null && value == selectedOrigin?.name)) {
            originStopResults = emptyList()
            originAddressResults = emptyList()
        } else {
            delay(200)
            searching = true
            val (stops, addrs) = withContext(Dispatchers.IO) {
                repository.searchGroupedStops(value, 20, savedRefs) to
                    addressRepository.search(value, 15)
            }
            originStopResults = stops
            originAddressResults = addrs
            searching = false
        }
    }

    // חיפוש יעד משולב (כתובות + תחנות)
    LaunchedEffect(destinationQuery, activeField) {
        if (activeField != "destination") return@LaunchedEffect
        val value = destinationQuery.trim()
        if (value.isEmpty() || (selectedDestination != null && value == selectedDestination?.name)) {
            destinationStopResults = emptyList()
            destinationAddressResults = emptyList()
        } else {
            delay(200)
            searching = true
            val (stops, addrs) = withContext(Dispatchers.IO) {
                repository.searchGroupedStops(value, 20, savedRefs) to
                    addressRepository.search(value, 15)
            }
            destinationStopResults = stops
            destinationAddressResults = addrs
            searching = false
        }
    }

    // הפעלה אוטומטית ברגע ששני השדות נבחרו
    LaunchedEffect(selectedOrigin, selectedDestination) {
        if (selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId) {
            searchRoute()
        }
    }

    BackHandler(enabled = detailedJourney != null || isMapFullScreen || pickingOnMapForField != null) {
        if (isMapFullScreen) {
            isMapFullScreen = false
        } else if (pickingOnMapForField != null) {
            pickingOnMapForField = null
        } else {
            detailedJourney = null
        }
    }

    // בחירת תחנה או נקודה על המפה (הודעת ההוראות מופיעה למעלה ולא מסתירה את האישור בתחתית)
    if (pickingOnMapForField != null) {
        if (!mapStorage.isMapAvailable()) {
            pickingOnMapForField = null
            showMapNotInstalledDialog = true
        } else {
            val targetField = pickingOnMapForField
            val bannerMsg = if (targetField == "origin") {
                "בחר תחנה או לחץ לחיצה ארוכה על נקודה כדי להגדיר מוצא"
            } else {
                "בחר תחנה או לחץ לחיצה ארוכה על נקודה כדי להגדיר יעד"
            }

            TransitMapScreen(
                pmtilesFile = mapStorage.getMapFile(),
                modifier = Modifier.fillMaxSize(),
                onPlanRouteFrom = if (targetField == "origin") {
                    { picked ->
                        selectedOrigin = picked
                        originQuery = picked.name
                        originStopResults = emptyList()
                        originAddressResults = emptyList()
                        pickingOnMapForField = null
                        activeField = "destination"
                        routeResult = null
                    }
                } else null,
                onPlanRouteTo = if (targetField == "destination") {
                    { picked ->
                        selectedDestination = picked
                        destinationQuery = picked.name
                        destinationStopResults = emptyList()
                        destinationAddressResults = emptyList()
                        pickingOnMapForField = null
                        routeResult = null
                    }
                } else null,
                onBack = { pickingOnMapForField = null },
                showSearchBar = true,
                topBannerMessage = bannerMsg
            )
            return
        }
    }

    // דיאלוג תעריף נסיעה ('דרך שווה')
    fareInfoToShow?.let { fare ->
        AlertDialog(
            onDismissRequest = { fareInfoToShow = null },
            title = { Text("תעריף נסיעה משוער ('דרך שווה')") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "מחיר הנסיעה: ${fare.priceNis} ₪",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(fare.description, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { fareInfoToShow = null }) {
                    Text("סגירה")
                }
            }
        )
    }

    // מסך פירוט מסלול מלא ייעודי
    if (detailedJourney != null) {
        val journey = detailedJourney!!
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

        LaunchedEffect(journeyMapInstance, walkingRoutesMap, detailedJourney) {
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
                    Text("יציאה ממסך מלא")
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
                    "פירוט מסלול נסיעה",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { detailedJourney = null },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("חזרה לתוצאות")
                }
            }

            Text(
                "${journey.departureAt.format(plannerTimeFormatter)} – ${journey.arrivalAt.format(plannerTimeFormatter)} (${journey.durationMinutes} דקות)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val summary = buildString {
                            append("פירוט מסלול נסיעה:\n")
                            append("יציאה: ${journey.departureAt.format(plannerTimeFormatter)} | הגעה: ${journey.arrivalAt.format(plannerTimeFormatter)} (${journey.durationMinutes} דק')\n\n")
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
                                append("   עליה: ${leg.fromStopName} בשעה ${leg.departureAt.format(plannerTimeFormatter)}\n")
                                append("   ירידה: ${leg.toStopName} בשעה ${leg.arrivalAt.format(plannerTimeFormatter)}\n")
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
                    Text("📋 העתק סיכום נסיעה", fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = { fareInfoToShow = fare },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("💳 תעריף: ${fare.priceNis} ₪", fontSize = 12.sp)
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
                                "🚶 הליכה אל תחנת העלייה (${iw.toStopName})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(if (showInitWalkDetails) "▲" else "▼", fontSize = 11.sp)
                        }
                        Text(
                            "מ־${iw.fromName} אל ${iw.toStopName} • כ-$distMeters מ' (כ-${iw.walkMinutes} דק')",
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
                            "• עליה בתחנה: ${leg.fromStopName} > בשעה ${leg.departureAt.format(plannerTimeFormatter)}",
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
                                    "נסיעה ➔ לחץ לרשימת תחנות הביניים",
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
                                    Text("טוען תחנות...", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    intermediateStops.forEachIndexed { sIdx, s ->
                                        Text("${sIdx + 1}. ${s.name} (תחנה ${s.stopCode})", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        Text(
                            "• ירידה בתחנה: ${leg.toStopName} > בשעה ${leg.arrivalAt.format(plannerTimeFormatter)}",
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
                                Text("החלפה באותה תחנה (${leg.toStopName})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("המתנה לקו ${nextLeg.lineNumber}: כ-${waitMinutes} דקות", style = MaterialTheme.typography.bodySmall)
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
                                        "🚶 הליכה בין תחנות ההחלפה$distText ➔ לחץ להוראות",
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
                                            Text("1. צא מ-${leg.toStopName} והתקדם ברגל ברחוב", style = MaterialTheme.typography.bodySmall)
                                            Text("2. עבור את מעבר החצייה במידת הצורך", style = MaterialTheme.typography.bodySmall)
                                            Text("3. הגע לרציף תחנת ${nextLeg.fromStopName}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Text("המתנה משוערת לקו ${nextLeg.lineNumber}: כ-${waitMinutes} דקות", style = MaterialTheme.typography.bodySmall)
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
                            Text("🚶 הליכה מסכמת אל היעד", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (ghFinal != null && ghFinal.instructions.isNotEmpty()) {
                                Text(if (showFinalWalkDetails) "▲" else "▼", fontSize = 11.sp)
                            }
                        }
                        Text(
                            "כ-$distMeters מטרים (כ-${walk.walkMinutes} דקות) מ־${walk.fromStopName} אל ${walk.toStopName}",
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
                            pmtilesFile = mapStorage.getMapFile(),
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
                            Text("מסך מלא", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        return
    }

    if (showMapNotInstalledDialog) {
        AlertDialog(
            onDismissRequest = { showMapNotInstalledDialog = false },
            title = { Text("מפה לא מותקנת") },
            text = {
                Text("חבילת המפה לא הותקנה עדיין במכשיר.\nתכנון הנסיעה בין תחנות וזמני היציאה פועלים גם ללא מפה.")
            },
            confirmButton = {
                TextButton(onClick = { showMapNotInstalledDialog = false }) { Text("הבנתי") }
            }
        )
    }

    // מסך תכנון מסלול ראשי
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // כותרת עליונה + כפתור חזרה למסך הבית
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "תכנון מסלול",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("חזרה למסך הבית")
            }
        }

        // כרטיס שדות החיפוש המאוחד
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // כותרת מוצא + GPS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("מוצא", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    FilledTonalButton(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) findAndSetNearestStopFromGPS()
                            else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                        enabled = !detectingLocation,
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(if (detectingLocation) "מאתר..." else "📍 המיקום שלי", fontSize = 11.sp)
                    }
                }

                // שדה מוצא
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = originQuery,
                        onValueChange = {
                            originQuery = it
                            selectedOrigin = null
                            activeField = "origin"
                            routeResult = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(originFocusRequester)
                            .onFocusChanged { if (it.isFocused) activeField = "origin" },
                        label = { Text("הקלד מוצא (שם רחוב/כתובת או תחנה)", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (originQuery.isNotEmpty()) {
                                IconButton(onClick = { originQuery = ""; selectedOrigin = null }) { Text("✕") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = {
                            destFocusRequester.requestFocus()
                            activeField = "destination"
                            scope.launch { scrollState.animateScrollTo(260) }
                        })
                    )

                    OutlinedButton(
                        onClick = { pickingOnMapForField = "origin" },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("🗺️", fontSize = 16.sp)
                    }
                }

                // תוצאות חיפוש מוצא (רחובות/כתובות + תחנות)
                if (activeField == "origin" && (originAddressResults.isNotEmpty() || originStopResults.isNotEmpty())) {
                    originAddressResults.take(5).forEach { addr ->
                        val synthetic = addressToSyntheticStop(addr)
                        val icon = when (addr.kind) {
                            AddressSearchRepository.ResultKind.CITY,
                            AddressSearchRepository.ResultKind.PLACE -> "🏙️"
                            AddressSearchRepository.ResultKind.STREET -> "🛣️"
                            AddressSearchRepository.ResultKind.ADDRESS -> "📍"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedOrigin = synthetic
                                    originQuery = synthetic.name
                                    originStopResults = emptyList()
                                    originAddressResults = emptyList()
                                    activeField = "destination"
                                    destFocusRequester.requestFocus()
                                    scope.launch { scrollState.animateScrollTo(260) }
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(icon, fontSize = 16.sp)
                                Column {
                                    Text(addr.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(addr.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    originStopResults.take(5).forEach { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedOrigin = group.stop1
                                    originQuery = group.baseName
                                    originStopResults = emptyList()
                                    originAddressResults = emptyList()
                                    activeField = "destination"
                                    destFocusRequester.requestFocus()
                                    scope.launch { scrollState.animateScrollTo(260) }
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (group.customName != null) {
                                    Text("⭐ ${group.customName}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                                    Text(group.baseName, fontSize = 12.sp)
                                } else {
                                    Text("🚏 ${group.baseName}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                if (group.stop2 != null) {
                                    Text("תחנה דו-כיוונית (מק\"ט ${group.stop1.stopCode} / ${group.stop2.stopCode})", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("תחנה ${group.stop1.stopCode}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }

                // כפתור החלפת מוצא ויעד מעוצב וקומפקטי בין שני השדות
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val oldOrig = selectedOrigin
                            val oldOrigQ = originQuery
                            selectedOrigin = selectedDestination
                            originQuery = destinationQuery
                            selectedDestination = oldOrig
                            destinationQuery = oldOrigQ

                            originStopResults = emptyList()
                            originAddressResults = emptyList()
                            destinationStopResults = emptyList()
                            destinationAddressResults = emptyList()
                            routeResult = null

                            if (selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId) {
                                searchRoute()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⇅", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("החלפת מוצא ויעד", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // כותרת יעד
                Text("יעד", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                // שדה יעד
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = destinationQuery,
                        onValueChange = {
                            destinationQuery = it
                            selectedDestination = null
                            activeField = "destination"
                            routeResult = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(destFocusRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    activeField = "destination"
                                    scope.launch { scrollState.animateScrollTo(260) }
                                }
                            },
                        label = { Text("הקלד יעד (שם רחוב/כתובת או תחנה)", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (destinationQuery.isNotEmpty()) {
                                IconButton(onClick = { destinationQuery = ""; selectedDestination = null }) { Text("✕") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboardController?.hide()
                            if (selectedDestination == null) {
                                val firstAddr = destinationAddressResults.firstOrNull()
                                val firstStop = destinationStopResults.firstOrNull()
                                if (firstStop != null) {
                                    selectedDestination = firstStop.stop1
                                    destinationQuery = firstStop.baseName
                                } else if (firstAddr != null) {
                                    val synthetic = addressToSyntheticStop(firstAddr)
                                    selectedDestination = synthetic
                                    destinationQuery = synthetic.name
                                }
                                destinationStopResults = emptyList()
                                destinationAddressResults = emptyList()
                            }
                            searchRoute()
                        })
                    )

                    OutlinedButton(
                        onClick = { pickingOnMapForField = "destination" },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("🗺️", fontSize = 16.sp)
                    }
                }

                // תוצאות חיפוש יעד (רחובות/כתובות + תחנות)
                if (activeField == "destination" && (destinationAddressResults.isNotEmpty() || destinationStopResults.isNotEmpty())) {
                    destinationAddressResults.take(5).forEach { addr ->
                        val synthetic = addressToSyntheticStop(addr)
                        val icon = when (addr.kind) {
                            AddressSearchRepository.ResultKind.CITY,
                            AddressSearchRepository.ResultKind.PLACE -> "🏙️"
                            AddressSearchRepository.ResultKind.STREET -> "🛣️"
                            AddressSearchRepository.ResultKind.ADDRESS -> "📍"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDestination = synthetic
                                    destinationQuery = synthetic.name
                                    destinationStopResults = emptyList()
                                    destinationAddressResults = emptyList()
                                    keyboardController?.hide()
                                    searchRoute()
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(icon, fontSize = 16.sp)
                                Column {
                                    Text(addr.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(addr.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    destinationStopResults.take(5).forEach { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDestination = group.stop1
                                    destinationQuery = group.baseName
                                    destinationStopResults = emptyList()
                                    destinationAddressResults = emptyList()
                                    keyboardController?.hide()
                                    searchRoute()
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (group.customName != null) {
                                    Text("⭐ ${group.customName}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                                    Text(group.baseName, fontSize = 12.sp)
                                } else {
                                    Text("🚏 ${group.baseName}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                if (group.stop2 != null) {
                                    Text("תחנה דו-כיוונית (מק\"ט ${group.stop1.stopCode} / ${group.stop2.stopCode})", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("תחנה ${group.stop1.stopCode}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }

        // תחנות שמורות אישיות
        if (savedStops.isNotEmpty()) {
            Text("התחנות שלי לבחירה מהירה:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                savedStops.take(4).forEach { saved ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .clickable {
                                val s = TransitRepository.TransitStop(saved.stopId, saved.stopName, null, null, saved.stopCode, saved.customName)
                                if (activeField == "origin") {
                                    selectedOrigin = s
                                    originQuery = s.name
                                    originStopResults = emptyList()
                                    originAddressResults = emptyList()
                                    destFocusRequester.requestFocus()
                                    activeField = "destination"
                                    scope.launch { scrollState.animateScrollTo(260) }
                                } else {
                                    selectedDestination = s
                                    destinationQuery = s.name
                                    destinationStopResults = emptyList()
                                    destinationAddressResults = emptyList()
                                    keyboardController?.hide()
                                    searchRoute()
                                }
                            }
                            .padding(2.dp)
                    ) {
                        Text(
                            "⭐ ${saved.customName}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        DepartureDateTimePicker(
            value = departureTime,
            onValueChange = {
                departureTime = it
                routeResult = null
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                keyboardController?.hide()
                searchRoute()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId && !calculating,
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(if (calculating) "מחשב מסלול..." else "חפש מסלול")
        }

        if (calculating) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        // תוצאות חיפוש
        routeResult?.let { res ->
            if (res.error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(res.error, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else if (res.journeys.isEmpty()) {
                Card {
                    Text("לא נמצאו מסלולים מתאימים במועד המבוקש.", modifier = Modifier.padding(14.dp))
                }
            } else {
                Text("תוצאות חיפוש", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)

                res.journeys.forEachIndexed { idx, journey ->
                    val isExpanded = expandedJourneyIndex == idx
                    val diffMin = Duration.between(LocalDateTime.now(), journey.departureAt).toMinutes().coerceAtLeast(0)

                    var nextDeparturesReal by remember { mutableStateOf<List<String>>(emptyList()) }
                    val firstLeg = journey.legs.firstOrNull()

                    LaunchedEffect(journey) {
                        if (firstLeg != null) {
                            val upcoming = withContext(Dispatchers.IO) {
                                scheduleRepo.upcomingDepartures(firstLeg.fromStopId, now = firstLeg.departureAt.plusMinutes(1), limit = 8)
                                    .filter { it.lineNumber == firstLeg.lineNumber }
                                    .take(3)
                                    .map { it.departureAt.format(plannerTimeFormatter) }
                            }
                            nextDeparturesReal = upcoming
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedJourneyIndex = if (isExpanded) -1 else idx },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${journey.durationMinutes} דק'",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        journey.initialWalk?.let {
                                            Text("🚶 ➔", fontSize = 12.sp)
                                        }
                                        journey.legs.forEachIndexed { i, leg ->
                                            TransitLineBadge(lineNumber = leg.lineNumber, agencyName = leg.agencyName)
                                            if (i < journey.legs.lastIndex) {
                                                Text("➔", fontSize = 11.sp)
                                            }
                                        }
                                        journey.finalWalk?.let {
                                            Text("➔ 🚶", fontSize = 12.sp)
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${journey.departureAt.format(plannerTimeFormatter)} ➔ ${journey.arrivalAt.format(plannerTimeFormatter)}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "עוד $diffMin דקות",
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier.padding(top = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    journey.initialWalk?.let { iw ->
                                        Text(
                                            "• הליכה כ-${iw.walkMinutes} דק' (${iw.distanceMeters.roundToInt()} מ') אל תחנת ${iw.toStopName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    journey.legs.forEach { leg ->
                                        Text(
                                            "• קו ${leg.lineNumber} מ-${leg.fromStopName} ל-${leg.toStopName}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    journey.finalWalk?.let { fw ->
                                        Text(
                                            "• הליכה מסכמת כ-${fw.walkMinutes} דק' (${fw.distanceMeters.roundToInt()} מ') אל ${fw.toStopName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }

                                    if (nextDeparturesReal.isNotEmpty()) {
                                        Text(
                                            "יציאות נוספות: " + nextDeparturesReal.joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = { detailedJourney = journey },
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                    ) {
                                        Text("צפה בפירוט המסלול המלא והוראות הליכה")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
