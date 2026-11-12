package il.co.israeltransit.offline.maps

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.ui.RouteFromButton
import il.co.israeltransit.offline.ui.RouteToButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale

private const val STOP_LAYER_ID = "transit-stops-individual"
private const val PICKED_PIN_SOURCE_ID = "transit-picked-pin-source"
private const val PICKED_PIN_LAYER_ID = "transit-picked-pin-layer"

private data class SelectedTransitStop(
    val id: String,
    val name: String,
    val stopCode: String = id,
    val latitude: Double? = null,
    val longitude: Double? = null
)

private data class PickedMapLocation(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

private data class QuickCityItem(
    val name: String,
    val lat: Double,
    val lon: Double
)

private val QUICK_CITIES = listOf(
    QuickCityItem("ירושלים", 31.7683, 35.2137),
    QuickCityItem("תל אביב - יפו", 32.0853, 34.7818),
    QuickCityItem("חיפה", 32.7940, 34.9896),
    QuickCityItem("ראשון לציון", 31.9730, 34.7925),
    QuickCityItem("פתח תקווה", 32.0840, 34.8878),
    QuickCityItem("אשדוד", 31.8044, 34.6553),
    QuickCityItem("נתניה", 32.3215, 34.8532),
    QuickCityItem("בני ברק", 32.0833, 34.8333),
    QuickCityItem("באר שבע", 31.2530, 34.7915),
    QuickCityItem("רחובות", 31.8928, 34.8113),
    QuickCityItem("בית שמש", 31.7470, 34.9881),
    QuickCityItem("מודיעין עילית", 31.9328, 35.0442),
    QuickCityItem("ביתר עילית", 31.6978, 35.1158),
    QuickCityItem("טבריה", 32.7922, 35.5312),
    QuickCityItem("צפת", 32.9646, 35.4960),
    QuickCityItem("אילת", 29.5577, 34.9519)
)

@Composable
fun TransitMapScreen(
    pmtilesFile: File,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 31.7683,
    initialLongitude: Double = 35.2137,
    initialZoom: Double = 12.0,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null,
    onOpenStopDetails: ((TransitRepository.TransitStop) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    showSearchBar: Boolean = true,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext

    val database = remember(applicationContext) {
        TransitDatabase(applicationContext)
    }
    val transitRepository = remember(database) {
        TransitRepository(database)
    }
    val stopDetailsRepository = remember(applicationContext) {
        TransitStopDetailsRepository(applicationContext)
    }
    val addressRepository = remember(applicationContext) {
        AddressSearchRepository(applicationContext)
    }
    val locationControl = remember(applicationContext) {
        TransitMyLocationControl(applicationContext)
    }

    val coroutineScope = rememberCoroutineScope()
    val hasTransitDb = remember { database.isDatabaseAvailable() }

    var activeMap by remember {
        mutableStateOf<MapLibreMap?>(null)
    }

    var selectedStop by remember {
        mutableStateOf<SelectedTransitStop?>(null)
    }

    var showStopActions by remember {
        mutableStateOf(false)
    }

    var selectedLines by remember {
        mutableStateOf<List<TransitStopLine>>(emptyList())
    }

    var isLoadingLines by remember {
        mutableStateOf(false)
    }

    var linesError by remember {
        mutableStateOf<String?>(null)
    }

    var pickedLocation by remember {
        mutableStateOf<PickedMapLocation?>(null)
    }

    var isSearchOpen by remember {
        mutableStateOf(false)
    }

    var searchQuery by remember {
        mutableStateOf("")
    }

    var addressResults by remember {
        mutableStateOf<List<AddressSearchRepository.AddressResult>>(emptyList())
    }

    var stopResults by remember {
        mutableStateOf<List<TransitRepository.TransitStop>>(emptyList())
    }

    var isSearching by remember {
        mutableStateOf(false)
    }

    fun updatePickedPinOnMap(latitude: Double?, longitude: Double?) {
        val map = activeMap ?: return
        map.getStyle { style ->
            if (!style.isFullyLoaded) return@getStyle
            try {
                val collection = if (latitude != null && longitude != null) {
                    FeatureCollection.fromFeatures(
                        arrayOf(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
                    )
                } else {
                    FeatureCollection.fromFeatures(emptyArray<Feature>())
                }

                val existing = style.getSource(PICKED_PIN_SOURCE_ID) as? GeoJsonSource
                if (existing != null) {
                    existing.setGeoJson(collection)
                } else {
                    style.addSource(GeoJsonSource(PICKED_PIN_SOURCE_ID, collection))
                }

                if (style.getLayer(PICKED_PIN_LAYER_ID) == null) {
                    style.addLayer(
                        CircleLayer(PICKED_PIN_LAYER_ID, PICKED_PIN_SOURCE_ID).withProperties(
                            circleColor("#D32F2F"),
                            circleRadius(9f),
                            circleStrokeColor("#FFFFFF"),
                            circleStrokeWidth(3f)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun animateCameraTo(lat: Double, lon: Double, zoom: Double) {
        activeMap?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .build()
            ),
            900
        )
    }

    fun selectStopAndLoadLines(stop: SelectedTransitStop) {
        pickedLocation = null
        updatePickedPinOnMap(null, null)

        selectedStop = stop
        showStopActions = false
        selectedLines = emptyList()
        linesError = null
        isLoadingLines = true

        coroutineScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) {
                    stopDetailsRepository.getLinesForStop(stop.id)
                }
                if (selectedStop?.id == stop.id) {
                    selectedLines = lines
                }
            } catch (exception: Exception) {
                if (selectedStop?.id == stop.id) {
                    linesError = exception.message ?: "לא ניתן לטעון את הקווים בתחנה"
                }
            } finally {
                if (selectedStop?.id == stop.id) {
                    isLoadingLines = false
                }
            }
        }
    }

    LaunchedEffect(searchQuery, isSearchOpen) {
        if (!isSearchOpen) return@LaunchedEffect
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            addressResults = emptyList()
            stopResults = emptyList()
            isSearching = false
        } else {
            delay(180)
            isSearching = true
            val (addrs, stops) = withContext(Dispatchers.IO) {
                addressRepository.search(q, 20) to transitRepository.searchStops(q, 15)
            }
            addressResults = addrs
            stopResults = stops
            isSearching = false
        }
    }

    fun moveToLocation() {
        val map = activeMap
        if (map == null) {
            Toast.makeText(context, "המפה עדיין נטענת", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            locationControl.moveToCurrentLocation(map)
                .onFailure { exception ->
                    Toast.makeText(
                        context,
                        exception.message ?: "לא ניתן לקבל את מיקום המכשיר",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                moveToLocation()
            } else {
                Toast.makeText(
                    context,
                    "לא ניתנה הרשאת מיקום. אפשר להמשיך להשתמש באפליקציה כרגיל.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    val mapReadyCallback = remember(
        context,
        pmtilesFile.absolutePath,
        onError
    ) {
        { map: MapLibreMap ->
            activeMap = map

            map.getStyle { style ->
                TransitStopMapLayer.install(
                    context = applicationContext,
                    style = style,
                    map = map,
                    onComplete = {
                        val clickListener =
                            MapLibreMap.OnMapClickListener { coordinate ->
                                if (isSearchOpen) {
                                    isSearchOpen = false
                                    return@OnMapClickListener true
                                }

                                val screenPoint = map.projection.toScreenLocation(coordinate)

                                if (
                                    TransitMapClusterController.handleClusterTap(
                                        map = map,
                                        style = style,
                                        screenPoint = screenPoint
                                    )
                                ) {
                                    return@OnMapClickListener true
                                }

                                val features = map.queryRenderedFeatures(
                                    screenPoint,
                                    STOP_LAYER_ID
                                )

                                val feature = features.firstOrNull()

                                if (feature == null) {
                                    selectedStop = null
                                    showStopActions = false
                                    selectedLines = emptyList()
                                    linesError = null
                                    isLoadingLines = false
                                    pickedLocation = null
                                    updatePickedPinOnMap(null, null)
                                    false
                                } else {
                                    val properties = feature.properties()

                                    val stopId = properties
                                        ?.get("stop_id")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString ?: ""

                                    val stopCode = properties
                                        ?.get("stop_code")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString ?: stopId

                                    val stopName = properties
                                        ?.get("stop_name")
                                        ?.takeIf { !it.isJsonNull }
                                        ?.asString
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "תחנה ללא שם"

                                    val geometry = feature.geometry() as? Point

                                    if (stopId.isNotBlank()) {
                                        selectStopAndLoadLines(
                                            SelectedTransitStop(
                                                id = stopId,
                                                name = stopName,
                                                stopCode = stopCode,
                                                latitude = geometry?.latitude(),
                                                longitude = geometry?.longitude()
                                            )
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }

                        val longClickListener =
                            MapLibreMap.OnMapLongClickListener { coordinate ->
                                isSearchOpen = false
                                selectedStop = null
                                showStopActions = false
                                selectedLines = emptyList()
                                linesError = null
                                isLoadingLines = false

                                val lat = coordinate.latitude
                                val lon = coordinate.longitude
                                val coordsLabel = String.format(
                                    Locale.US,
                                    "%.5f, %.5f",
                                    lat,
                                    lon
                                )

                                updatePickedPinOnMap(lat, lon)
                                pickedLocation = PickedMapLocation(
                                    title = "נקודה שנבחרה במפה",
                                    subtitle = coordsLabel,
                                    latitude = lat,
                                    longitude = lon
                                )

                                coroutineScope.launch {
                                    val nearestLabel = withContext(Dispatchers.IO) {
                                        addressRepository.findNearestPlaceLabel(lat, lon)
                                    }
                                    if (
                                        pickedLocation?.latitude == lat &&
                                        pickedLocation?.longitude == lon &&
                                        !nearestLabel.isNullOrBlank()
                                    ) {
                                        pickedLocation = pickedLocation?.copy(
                                            title = nearestLabel
                                        )
                                    }
                                }
                                true
                            }

                        map.addOnMapClickListener(clickListener)
                        map.addOnMapLongClickListener(longClickListener)
                    },
                    onError = onError
                )
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        OfflineMapView(
            pmtilesFile = pmtilesFile,
            modifier = Modifier.fillMaxSize(),
            initialLatitude = initialLatitude,
            initialLongitude = initialLongitude,
            initialZoom = initialZoom,
            onMapReady = mapReadyCallback,
            onMapError = onError
        )

        // שורת חיפוש וחזרה בסגנון קלאסי בראש המפה
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSearchBar) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = {
                                    if (isSearchOpen) {
                                        isSearchOpen = false
                                    } else {
                                        onBack()
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isSearchOpen) "✕" else "→",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isSearchOpen = true }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "🔍 חיפוש רחוב, עיר או תחנה..."
                                } else {
                                    searchQuery
                                },
                                color = if (searchQuery.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (searchQuery.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    addressResults = emptyList()
                                    stopResults = emptyList()
                                }
                            ) {
                                Text("✕", fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else if (onBack != null) {
                FilledTonalButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("חזרה")
                }
            }

            // חלונית תוצאות חיפוש נפתחת
            AnimatedVisibility(visible = isSearchOpen) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("הקלד רחוב, כתובת, עיר או מספר תחנה...")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Text("✕")
                                    }
                                }
                            }
                        )

                        if (isSearching) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (searchQuery.isBlank()) {
                            Text(
                                text = "מעבר מהיר לערים בישראל",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                            ) {
                                items(QUICK_CITIES) { city ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                isSearchOpen = false
                                                animateCameraTo(city.lat, city.lon, 13.5)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🏙️ ${city.name}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "עבור ←",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        } else if (addressResults.isEmpty() && stopResults.isEmpty()) {
                            Text(
                                text = "לא נמצאו רחובות, ערים או תחנות מתאימות.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                            ) {
                                if (addressResults.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "רחובות, כתובות ויישובים",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    items(addressResults) { addr ->
                                        val icon = when (addr.kind) {
                                            AddressSearchRepository.ResultKind.CITY,
                                            AddressSearchRepository.ResultKind.PLACE -> "🏙️"
                                            AddressSearchRepository.ResultKind.STREET -> "🛣️"
                                            AddressSearchRepository.ResultKind.ADDRESS -> "📍"
                                        }
                                        val zoom = when (addr.kind) {
                                            AddressSearchRepository.ResultKind.CITY -> 13.5
                                            AddressSearchRepository.ResultKind.PLACE -> 14.2
                                            AddressSearchRepository.ResultKind.STREET -> 16.0
                                            AddressSearchRepository.ResultKind.ADDRESS -> 17.0
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    isSearchOpen = false
                                                    selectedStop = null
                                                    showStopActions = false
                                                    animateCameraTo(addr.latitude, addr.longitude, zoom)
                                                    if (addr.kind == AddressSearchRepository.ResultKind.STREET ||
                                                        addr.kind == AddressSearchRepository.ResultKind.ADDRESS
                                                    ) {
                                                        updatePickedPinOnMap(addr.latitude, addr.longitude)
                                                        pickedLocation = PickedMapLocation(
                                                            title = addr.title,
                                                            subtitle = addr.subtitle,
                                                            latitude = addr.latitude,
                                                            longitude = addr.longitude
                                                        )
                                                    } else {
                                                        pickedLocation = null
                                                        updatePickedPinOnMap(null, null)
                                                    }
                                                }
                                                .padding(vertical = 9.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(icon, fontSize = 18.sp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = addr.title,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    text = addr.subtitle,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        )
                                    }
                                }

                                if (stopResults.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "תחנות תחבורה ציבורית",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(stopResults) { stop ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    isSearchOpen = false
                                                    val lat = stop.latitude
                                                    val lon = stop.longitude
                                                    if (lat != null && lon != null) {
                                                        animateCameraTo(lat, lon, 16.0)
                                                    }
                                                    selectStopAndLoadLines(
                                                        SelectedTransitStop(
                                                            id = stop.stopId,
                                                            name = stop.name,
                                                            stopCode = stop.stopCode,
                                                            latitude = lat,
                                                            longitude = lon
                                                        )
                                                    )
                                                }
                                                .padding(vertical = 9.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🚏", fontSize = 18.sp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stop.customName?.let { "⭐ $it (${stop.name})" } ?: stop.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp
                                                )
                                                Text(
                                                    text = "מספר תחנה (מק\"ט): ${stop.stopCode}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!hasTransitDb && !isSearchOpen) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 2.dp
                ) {
                    Text(
                        text = "להצגת תחנות וקווים על המפה, יש לייבא נתוני תחבורה במסך 'עדכון נתונים'.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // פקדי זום ומיקום צפים בצד המסך (ללא חפיפה לכפתורים העליונים או לכרטיס התחתון)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TransitMapControls(map = activeMap)

            FilledTonalIconButton(
                onClick = {
                    if (!locationControl.hasLocationProvider()) {
                        Toast.makeText(
                            context,
                            "לא זמינים במכשיר שירותי מיקום. אפשר להמשיך להשתמש באפליקציה כרגיל.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val fineGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        val coarseGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (fineGranted || coarseGranted) {
                            moveToLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Text("📍", fontSize = 20.sp)
            }
        }

        // כרטיסיות תחתונות: לחיצה על תחנה -> פותח פרטים -> לחיצה נוספת -> אפשרויות פעולה
        selectedStop?.let { stop ->
            val transitStop = TransitRepository.TransitStop(
                stopId = stop.id,
                name = stop.name,
                latitude = stop.latitude,
                longitude = stop.longitude,
                stopCode = stop.stopCode
            )

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStopActions = !showStopActions }
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stop.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "מספר תחנה (מק\"ט): ${stop.stopCode}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        TextButton(
                            onClick = {
                                selectedStop = null
                                showStopActions = false
                                selectedLines = emptyList()
                                linesError = null
                                isLoadingLines = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("סגירה")
                        }
                    }

                    Text(
                        text = "קווים בתחנה",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    when {
                        isLoadingLines -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Text(
                                    text = "טוען קווים...",
                                    modifier = Modifier.padding(start = 10.dp),
                                    fontSize = 13.sp
                                )
                            }
                        }

                        linesError != null -> {
                            Text(
                                text = linesError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }

                        selectedLines.isEmpty() -> {
                            Text(
                                text = "לא נמצאו קווים לתחנה זו",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                            ) {
                                items(
                                    items = selectedLines,
                                    key = { it.routeId }
                                ) { line ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showStopActions = true }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "קו ${line.lineNumber}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (line.lineName.isNotBlank()) {
                                            Text(
                                                text = line.lineName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!showStopActions) {
                        Text(
                            text = "לחץ על הפרטים לבחירת פעולה (פרטי תחנה / תכנון מסלול) ▾",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    AnimatedVisibility(visible = showStopActions) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )

                            if (onOpenStopDetails != null) {
                                Button(
                                    onClick = { onOpenStopDetails(transitStop) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("🚏 פתח פרטי תחנה")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (onPlanRouteFrom != null) {
                                    RouteFromButton(
                                        onClick = { onPlanRouteFrom(transitStop) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                if (onPlanRouteTo != null) {
                                    RouteToButton(
                                        onClick = { onPlanRouteTo(transitStop) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // כרטיסיית נקודה שנבחרה בלחיצה ארוכה על המפה או מחיפוש כתובת
        if (selectedStop == null) {
            pickedLocation?.let { picked ->
                val pointAsStop = TransitRepository.TransitStop(
                    stopId = "coord:${picked.latitude},${picked.longitude}",
                    name = picked.title,
                    latitude = picked.latitude,
                    longitude = picked.longitude,
                    stopCode = ""
                )

                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "📍 ${picked.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = picked.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(
                                onClick = {
                                    pickedLocation = null
                                    updatePickedPinOnMap(null, null)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("סגירה")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onPlanRouteFrom != null) {
                                RouteFromButton(
                                    onClick = { onPlanRouteFrom(pointAsStop) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (onPlanRouteTo != null) {
                                RouteToButton(
                                    onClick = { onPlanRouteTo(pointAsStop) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
