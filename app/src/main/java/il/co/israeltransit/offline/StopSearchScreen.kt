package il.co.israeltransit.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.OfflineMapView
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import il.co.israeltransit.offline.ui.OperatorTheme
import il.co.israeltransit.offline.ui.RouteFromButton
import il.co.israeltransit.offline.ui.RouteToButton
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun loadStopCoordinatesById(database: TransitDatabase, stopId: String): Pair<Double, Double>? {
    val db = database.openReadOnly() ?: return null
    return try {
        db.rawQuery(
            "SELECT stop_lat, stop_lon FROM stops WHERE stop_id = ? LIMIT 1",
            arrayOf(stopId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                cursor.getDouble(0) to cursor.getDouble(1)
            } else null
        }
    } finally {
        db.close()
    }
}

private fun loadSearchSavedRefs(context: Context): List<TransitRepository.SavedStopRef> {
    return try {
        val json = context.getSharedPreferences("saved_transit_stops", Context.MODE_PRIVATE)
            .getString("stops", "[]") ?: "[]"
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val sId = item.optString("stopId")
                val sCode = item.optString("stopCode").ifBlank { sId }
                add(TransitRepository.SavedStopRef(sId, item.optString("customName"), sCode))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun UpcomingDepartureRow(
    dep: TransitScheduleRepository.Departure,
    diffMinutes: Long,
    onClick: () -> Unit
) {
    val timeText = dep.departureAt.format(timeFormatter)
    val diffText = stringResource(R.string.ui_ee6287, diffMinutes)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        val lineNumWidthPx = textMeasurer.measure(
            text = dep.lineNumber,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ).size.width
        val headsignWidthPx = textMeasurer.measure(
            text = dep.headsign,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
        ).size.width
        val timeWidthPx = textMeasurer.measure(
            text = timeText,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
        ).size.width
        val diffWidthPx = textMeasurer.measure(
            text = diffText,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ).size.width

        val badgeWidthPx = lineNumWidthPx + with(density) { 46.dp.toPx() }
        val spacingPx = with(density) { 28.dp.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }

        val fitsSingleLine = (badgeWidthPx + headsignWidthPx + timeWidthPx + diffWidthPx + spacingPx) <= maxWidthPx

        if (fitsSingleLine) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransitLineBadge(lineNumber = dep.lineNumber, agencyName = dep.agencyName)
                    Text(
                        text = dep.headsign,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(timeText, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(
                        text = diffText,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransitLineBadge(lineNumber = dep.lineNumber, agencyName = dep.agencyName)
                    Text(dep.headsign, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(timeText, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(
                        text = diffText,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun StopSearchScreen(
    initialStop: TransitRepository.TransitStop? = null,
    onBack: () -> Unit,
    onNavigateToLine: (routeId: String, lineNumber: String) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val savedRefs = remember(context) { loadSearchSavedRefs(context) }

    var query by rememberSaveable { mutableStateOf("") }
    var groupedResults by remember { mutableStateOf(emptyList<TransitRepository.DualStopGroup>()) }
    var savedSelectedStopId by rememberSaveable(initialStop?.stopId) { mutableStateOf(initialStop?.stopId) }
    var selectedStop by remember { mutableStateOf(initialStop) }
    var oppositeStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }

    var platforms by remember { mutableStateOf(emptyList<TransitRepository.StopPlatform>()) }
    var selectedPlatformId by rememberSaveable(selectedStop?.stopId) { mutableStateOf<String?>(null) }
    var showPlatformPickerDialog by remember { mutableStateOf(false) }

    var stopCity by remember { mutableStateOf("") }
    var departures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }
    var lines by remember { mutableStateOf(emptyList<TransitRepository.TransitLine>()) }
    var lineStatuses by remember { mutableStateOf<Map<String, TransitScheduleRepository.LineStopStatus>>(emptyMap()) }
    var departuresLimit by remember { mutableIntStateOf(10) }

    var filterMode by remember { mutableStateOf("עכשיו") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTime by remember { mutableStateOf(LocalTime.now()) }

    var isMapFullScreen by remember { mutableStateOf(false) }

    var searchHistory by remember { mutableStateOf(SearchHistoryHelper.getHistory(context, "stop_history")) }
    var historyItemToDelete by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (selectedStop == null && savedSelectedStopId == null) {
            delay(250)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(initialStop) {
        if (initialStop != null) {
            selectedStop = initialStop
            savedSelectedStopId = initialStop.stopId
        }
    }

    LaunchedEffect(savedSelectedStopId) {
        val stopId = savedSelectedStopId
        if (stopId != null && selectedStop?.stopId != stopId) {
            withContext(Dispatchers.IO) {
                repository.getStopById(stopId, savedRefs)
            }?.let { restored ->
                selectedStop = restored
            }
        }
    }

    BackHandler(enabled = isMapFullScreen || (selectedStop != null && initialStop == null)) {
        if (isMapFullScreen) {
            isMapFullScreen = false
        } else {
            selectedStop = null
            savedSelectedStopId = null
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            groupedResults = emptyList()
        } else {
            delay(200)
            groupedResults = withContext(Dispatchers.IO) {
                repository.searchGroupedStops(q, 40, savedRefs)
            }
        }
    }

    LaunchedEffect(selectedStop?.stopId) {
        val stop = selectedStop
        if (stop != null) {
            if (stop.latitude == null || stop.longitude == null) {
                val coords = withContext(Dispatchers.IO) {
                    loadStopCoordinatesById(database, stop.stopId)
                }
                if (coords != null && selectedStop?.stopId == stop.stopId) {
                    selectedStop = stop.copy(latitude = coords.first, longitude = coords.second)
                }
            }
            oppositeStop = withContext(Dispatchers.IO) { repository.findOppositeStop(stop) }
            stopCity = withContext(Dispatchers.IO) { repository.getStopCity(stop.stopId) }
            platforms = withContext(Dispatchers.IO) { repository.getPlatformsForStop(stop.stopId) }
        } else {
            platforms = emptyList()
        }
    }

    LaunchedEffect(selectedStop?.stopId, selectedPlatformId, departuresLimit, selectedDate, selectedTime, filterMode) {
        val stop = selectedStop
        if (stop != null) {
            val effectiveStopId = selectedPlatformId ?: stop.stopId
            val nowTime = LocalDateTime.now()
            val queryTime = if (filterMode == "עכשיו") nowTime else LocalDateTime.of(selectedDate, selectedTime)
            departures = withContext(Dispatchers.IO) {
                scheduleRepo.upcomingDepartures(effectiveStopId, now = queryTime, limit = departuresLimit)
            }
            lines = withContext(Dispatchers.IO) { repository.getLinesForStop(effectiveStopId, 100) }
            lineStatuses = withContext(Dispatchers.IO) { scheduleRepo.getLineStatusesForStop(effectiveStopId, now = nowTime) }
        }
    }

    fun renderStopMarkerOnMap(map: MapLibreMap, lat: Double, lon: Double) {
        map.getStyle { style ->
            if (!style.isFullyLoaded) return@getStyle
            val sourceId = "single-stop-pin-source"
            val layerId = "single-stop-pin-layer"
            val collection = FeatureCollection.fromFeatures(
                arrayOf(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
            )
            val existing = style.getSource(sourceId) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(collection)
            } else {
                style.addSource(GeoJsonSource(sourceId, collection))
            }
            if (style.getLayer(layerId) == null) {
                style.addLayer(
                    CircleLayer(layerId, sourceId).withProperties(
                        circleColor("#1769AA"),
                        circleRadius(9f),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(3f)
                    )
                )
            }
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0), 600)
        }
    }

    if (showPlatformPickerDialog && platforms.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPlatformPickerDialog = false },
            title = {
                Text(stringResource(R.string.ui_199501), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val allSelected = selectedPlatformId == null
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (allSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPlatformId = null
                                showPlatformPickerDialog = false
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.ui_d4a991),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    platforms.forEach { platform ->
                        val isSelected = selectedPlatformId == platform.stopId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPlatformId = platform.stopId
                                    showPlatformPickerDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = platform.displayLabel,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.ui_ce47de, platform.stopCode),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlatformPickerDialog = false }) { Text("סגירה") }
            }
        )
    }

    historyItemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { historyItemToDelete = null },
            title = { Text(stringResource(R.string.ui_4df88a)) },
            text = { Text(stringResource(R.string.ui_269baf, item)) },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "stop_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "stop_history")
                    historyItemToDelete = null
                }) { Text(stringResource(R.string.ui_07aecd)) }
            },
            dismissButton = {
                TextButton(onClick = { historyItemToDelete = null }) { Text("ביטול") }
            }
        )
    }

    if (isMapFullScreen && selectedStop != null && mapStorage.isMapAvailable()) {
        val stop = selectedStop!!
        val lat = stop.latitude
        val lon = stop.longitude
        Box(modifier = Modifier.fillMaxSize()) {
            OfflineMapView(
                pmtilesFile = mapStorage.getMapFile(),
                modifier = Modifier.fillMaxSize(),
                initialLatitude = lat ?: 31.7683,
                initialLongitude = lon ?: 35.2137,
                initialZoom = 16.0,
                onMapReady = { map ->
                    if (lat != null && lon != null) {
                        renderStopMarkerOnMap(map, lat, lon)
                    }
                }
            )
            FilledTonalButton(
                onClick = { isMapFullScreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SearchScreenTopHeader(
            title = if (selectedStop == null) stringResource(R.string.ui_07ae91) else stringResource(R.string.ui_286744),
            onBack = onBack
        )

        if (selectedStop == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text(stringResource(R.string.ui_0b08a0)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Text(stringResource(R.string.ui_4ef76b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { query = item },
                                    onLongClick = { historyItemToDelete = item }
                                )
                                .padding(2.dp)
                        ) {
                            Text(item, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                        }
                    }
                }
            }

            groupedResults.forEach { group ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SearchHistoryHelper.addHistory(context, "stop_history", group.stop1.name)
                                    savedSelectedStopId = group.stop1.stopId
                                    selectedStop = group.stop1
                                }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (group.customName != null) {
                                    Text("⭐ ${group.customName}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(group.baseName, fontSize = 12.sp)
                                } else {
                                    Text(group.baseName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.ui_5d32ff, group.stop1.stopCode), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            }
                            Text("←", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        if (group.stop2 != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        SearchHistoryHelper.addHistory(context, "stop_history", group.stop2.name)
                                        savedSelectedStopId = group.stop2.stopId
                                        selectedStop = group.stop2
                                    }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(group.stop2.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(stringResource(R.string.ui_5d32ff, group.stop2.stopCode), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                                }
                                Text("←", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            val stop = selectedStop!!
            val activePlatform = platforms.firstOrNull { it.stopId == selectedPlatformId }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (activePlatform != null) {
                            stringResource(R.string.ui_53b5a3, stop.stopCode, activePlatform.displayLabel, activePlatform.stopCode)
                        } else {
                            stringResource(R.string.ui_5d32ff, stop.stopCode)
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onPlanRouteFrom?.let {
                            RouteFromButton(onClick = { it(stop) }, modifier = Modifier.weight(1f))
                        }
                        onPlanRouteTo?.let {
                            RouteToButton(onClick = { it(stop) }, modifier = Modifier.weight(1f))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val prefs = context.getSharedPreferences("saved_transit_stops", Context.MODE_PRIVATE)
                                val array = try { JSONArray(prefs.getString("stops", "[]")) } catch (_: Exception) { JSONArray() }
                                val obj = org.json.JSONObject().apply {
                                    put("stopId", stop.stopId)
                                    put("stopName", stop.name)
                                    put("customName", stop.name)
                                    put("stopCode", stop.stopCode)
                                }
                                array.put(obj)
                                prefs.edit().putString("stops", array.toString()).apply()
                                android.widget.Toast.makeText(context, "התחנה נוספה לתחנות שלי!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.ui_96bfed), fontSize = 12.sp)
                        }

                        if (oppositeStop != null) {
                            OutlinedButton(
                                onClick = {
                                    selectedPlatformId = null
                                    savedSelectedStopId = oppositeStop!!.stopId
                                    selectedStop = oppositeStop
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.ui_450f79), fontSize = 12.sp)
                            }
                        }
                    }

                    if (platforms.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { showPlatformPickerDialog = true },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (activePlatform != null) {
                                    stringResource(R.string.ui_f5cbac, activePlatform.displayLabel)
                                } else {
                                    stringResource(R.string.ui_0ba747, platforms.size)
                                },
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_c20379), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = filterMode == "עכשיו",
                            onClick = { filterMode = "עכשיו" },
                            label = { Text(stringResource(R.string.ui_62c60a)) },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = filterMode == stringResource(R.string.ui_71958d),
                            onClick = {
                                DatePickerDialog(context, { _, y, m, d ->
                                    selectedDate = LocalDate.of(y, m + 1, d)
                                    filterMode = "היום"
                                }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
                            },
                            label = { Text(if (filterMode == stringResource(R.string.ui_71958d)) "${selectedDate.dayOfMonth}/${selectedDate.monthValue}" else stringResource(R.string.ui_a92e9d)) },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                TimePickerDialog(context, { _, h, min ->
                                    selectedTime = LocalTime.of(h, min)
                                    filterMode = "היום"
                                }, selectedTime.hour, selectedTime.minute, true).show()
                            },
                            label = { Text("${selectedTime.format(timeFormatter)} ▾") },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }

                    if (departures.isEmpty()) {
                        Text(stringResource(R.string.ui_83e943), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val nowTime = LocalDateTime.now()
                        departures.forEach { dep ->
                            val diff = Duration.between(nowTime, dep.departureAt).toMinutes().coerceAtLeast(0)
                            UpcomingDepartureRow(
                                dep = dep,
                                diffMinutes = diff,
                                onClick = { onNavigateToLine(dep.routeId, dep.lineNumber) }
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                departuresLimit = if (departuresLimit == 10) 30 else 10
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(if (departuresLimit == 10) stringResource(R.string.ui_5652c1) else stringResource(R.string.ui_51efc4))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_9a66c2), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    if (lines.isEmpty()) {
                        Text(stringResource(R.string.ui_5be669), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val normStopCity = OperatorTheme.normalizeHebrew(stopCity)
                        val cityHighlightColor = MaterialTheme.colorScheme.primary

                        lines.forEach { line ->
                            val normOrigCity = OperatorTheme.normalizeHebrew(line.originCity)
                            val normDestCity = OperatorTheme.normalizeHebrew(line.destinationCity)
                            val isUrbanLine = (line.originCity.isBlank() && line.destinationCity.isBlank()) ||
                                (normOrigCity.isNotBlank() && normDestCity.isNotBlank() &&
                                    (normOrigCity == normDestCity || normOrigCity.contains(normDestCity) || normDestCity.contains(normOrigCity)))

                            val showOrigCity = !isUrbanLine && line.originCity.isNotBlank() &&
                                (normStopCity.isBlank() || (!normOrigCity.contains(normStopCity) && !normStopCity.contains(normOrigCity)))
                            val showDestCity = !isUrbanLine && line.destinationCity.isNotBlank() &&
                                (normStopCity.isBlank() || (!normDestCity.contains(normStopCity) && !normStopCity.contains(normDestCity)))

                            val status = lineStatuses[line.routeId]

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToLine(line.routeId, line.lineNumber) }
                                    .padding(vertical = 3.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.width(84.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = line.agencyName,
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        val originAnnotated = buildAnnotatedString {
                                            append("מוצא: ${line.originName.ifBlank { line.name }}")
                                            if (showOrigCity) {
                                                append(" ")
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cityHighlightColor)) {
                                                    append("(${line.originCity})")
                                                }
                                            }
                                        }
                                        Text(
                                            text = originAnnotated,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (line.destinationName.isNotBlank()) {
                                            val destAnnotated = buildAnnotatedString {
                                                append("יעד: ${line.destinationName}")
                                                if (showDestCity) {
                                                    append(" ")
                                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cityHighlightColor)) {
                                                        append("(${line.destinationCity})")
                                                    }
                                                }
                                            }
                                            Text(
                                                text = destAnnotated,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        when {
                                            status == null || !status.isActiveToday -> {
                                                Text(
                                                    text = stringResource(R.string.ui_214acd),
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            status.upcomingMinutes.isEmpty() -> {
                                                Text(
                                                    text = stringResource(R.string.ui_9ac0f8),
                                                    color = MaterialTheme.colorScheme.outline,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            else -> {
                                                Text(
                                                    text = "בעוד " + status.upcomingMinutes.joinToString(" • ") + " דק'",
                                                    color = Color(0xFF2E7D32),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Text("←", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (mapStorage.isMapAvailable()) {
                val lat = stop.latitude
                val lon = stop.longitude
                var stopMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

                LaunchedEffect(stopMapInstance, lat, lon) {
                    if (stopMapInstance != null && lat != null && lon != null) {
                        renderStopMarkerOnMap(stopMapInstance!!, lat, lon)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OfflineMapView(
                            pmtilesFile = mapStorage.getMapFile(),
                            modifier = Modifier.fillMaxSize(),
                            initialLatitude = lat ?: 31.7683,
                            initialLongitude = lon ?: 35.2137,
                            initialZoom = 16.0,
                            onMapReady = { map ->
                                stopMapInstance = map
                                if (lat != null && lon != null) {
                                    renderStopMarkerOnMap(map, lat, lon)
                                }
                            }
                        )
                        FilledTonalButton(
                            onClick = { isMapFullScreen = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.ui_401142), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
