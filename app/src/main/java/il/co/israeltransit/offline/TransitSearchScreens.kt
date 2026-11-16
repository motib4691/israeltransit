package il.co.israeltransit.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private val WEEK_DAYS_HEBREW = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY
)

private fun hebrewDayName(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.SUNDAY -> "יום ראשון"
    DayOfWeek.MONDAY -> "יום שני"
    DayOfWeek.TUESDAY -> "יום שלישי"
    DayOfWeek.WEDNESDAY -> "יום רביעי"
    DayOfWeek.THURSDAY -> "יום חמישי"
    DayOfWeek.FRIDAY -> "יום שישי"
    DayOfWeek.SATURDAY -> "יום שבת"
}

private fun formatWaitDuration(minutes: Long): String {
    if (minutes <= 0L) return "עכשיו"
    if (minutes < 60L) return "בעוד $minutes דקות"

    val hours = minutes / 60L
    val remMinutes = minutes % 60L

    return when {
        hours == 1L && remMinutes == 0L -> "בעוד שעה"
        hours == 1L -> "בעוד שעה ו-$remMinutes דקות"
        hours == 2L && remMinutes == 0L -> "בעוד שעתיים"
        hours == 2L -> "בעוד שעתיים ו-$remMinutes דקות"
        remMinutes == 0L -> "בעוד $hours שעות"
        else -> "בעוד $hours שעות ו-$remMinutes דקות"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreenTopHeader(
    title: String,
    onBack: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 148.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
        ) {
            Text(
                text = "חזרה למסך הבית",
                maxLines = 1,
                softWrap = false,
                fontSize = 13.sp
            )
        }
    }
}

private object SearchHistoryHelper {
    fun getHistory(context: Context, prefKey: String): List<String> {
        val prefs = context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
        val json = prefs.getString(prefKey, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(context: Context, prefKey: String, item: String) {
        val trimmed = item.trim()
        if (trimmed.isBlank()) return
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val array = JSONArray(current.take(8))
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }

    fun removeHistory(context: Context, prefKey: String, item: String) {
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(item)
        val array = JSONArray(current)
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }
}

private data class RouteVariant(
    val directionId: String,
    val headsign: String,
    val stops: List<TransitRepository.RouteStop>
)

private class RouteVariantRepository(
    private val database: TransitDatabase
) {
    fun getVariants(routeId: String): List<RouteVariant> {
        val db = database.openReadOnly() ?: return emptyList()
        val variants = linkedMapOf<String, RouteVariant>()

        try {
            db.rawQuery(
                """
                SELECT
                    t.trip_id,
                    COALESCE(t.direction_id, ''),
                    COALESCE(t.trip_headsign, ''),
                    st.stop_id,
                    COALESCE(s.stop_name, ''),
                    st.stop_sequence,
                    st.arrival_time,
                    st.departure_time,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                LEFT JOIN stops s ON s.stop_id = st.stop_id
                WHERE t.route_id = ?
                ORDER BY t.trip_id, st.stop_sequence
                """.trimIndent(),
                arrayOf(routeId)
            ).use { cursor ->
                var currentTripId: String? = null
                var directionId = ""
                var headsign = ""
                val stops = mutableListOf<TransitRepository.RouteStop>()

                fun saveTrip() {
                    if (currentTripId == null || stops.isEmpty()) return

                    val signature = buildString {
                        append(directionId)
                        append('|')
                        append(headsign)
                        append('|')
                        stops.forEach {
                            append(it.stopId)
                            append('>')
                        }
                    }

                    if (!variants.containsKey(signature)) {
                        variants[signature] = RouteVariant(
                            directionId = directionId,
                            headsign = headsign,
                            stops = stops.toList()
                        )
                    }
                }

                while (cursor.moveToNext()) {
                    val tripId = cursor.getString(0) ?: ""

                    if (tripId != currentTripId) {
                        saveTrip()
                        stops.clear()
                        currentTripId = tripId
                        directionId = cursor.getString(1) ?: ""
                        headsign = cursor.getString(2) ?: ""
                    }

                    stops.add(
                        TransitRepository.RouteStop(
                            stopId = cursor.getString(3) ?: "",
                            name = cursor.getString(4) ?: "",
                            sequence = cursor.getInt(5),
                            arrivalTime = if (cursor.isNull(6)) null else cursor.getInt(6),
                            departureTime = if (cursor.isNull(7)) null else cursor.getInt(7),
                            stopCode = cursor.getString(8) ?: ""
                        )
                    )
                }

                saveTrip()
            }
        } finally {
            db.close()
        }

        return variants.values.toList()
    }
}

private fun getTripStopTimes(database: TransitDatabase, tripId: String): Map<String, String> {
    val db = database.openReadOnly() ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    try {
        db.rawQuery(
            "SELECT stop_id, COALESCE(departure_time, arrival_time) FROM stop_times WHERE trip_id = ?",
            arrayOf(tripId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sId = cursor.getString(0) ?: continue
                val sec = cursor.getInt(1)
                val h = (sec / 3600) % 24
                val m = (sec % 3600) / 60
                result[sId] = String.format("%02d:%02d", h, m)
            }
        }
    } finally {
        db.close()
    }
    return result
}

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

// =========================================================================
// 1. מסך חיפוש תחנה (StopSearchScreen)
// =========================================================================

@Composable
private fun UpcomingDepartureRow(
    dep: TransitScheduleRepository.Departure,
    diffMinutes: Long,
    onClick: () -> Unit
) {
    val timeText = dep.departureAt.format(timeFormatter)
    val diffText = "עוד $diffMinutes דק'"
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
                    Text(
                        text = dep.headsign,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
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

    LaunchedEffect(selectedStop, departuresLimit, selectedDate, selectedTime, filterMode) {
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
            val nowTime = LocalDateTime.now()
            val queryTime = if (filterMode == "עכשיו") nowTime else LocalDateTime.of(selectedDate, selectedTime)
            departures = withContext(Dispatchers.IO) {
                scheduleRepo.upcomingDepartures(stop.stopId, now = queryTime, limit = departuresLimit)
            }
            lines = withContext(Dispatchers.IO) { repository.getLinesForStop(stop.stopId, 100) }
            lineStatuses = withContext(Dispatchers.IO) { scheduleRepo.getLineStatusesForStop(stop.stopId, now = nowTime) }
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

    historyItemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { historyItemToDelete = null },
            title = { Text("מחיקת פריט מההיסטוריה") },
            text = { Text("האם למחוק את \"$item\" מרשימת החיפושים האחרונים?") },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "stop_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "stop_history")
                    historyItemToDelete = null
                }) { Text("מחק") }
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SearchScreenTopHeader(
            title = if (selectedStop == null) "חיפוש תחנה" else "פרטי תחנה",
            onBack = onBack
        )

        if (selectedStop == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("שם תחנה, רחוב או מספר מהשלט (מק\"ט)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Text("חיפושים אחרונים (לחיצה ארוכה למחיקה):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                                Text("מספר תחנה (מק\"ט): ${group.stop1.stopCode}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
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
                                    Text("מספר תחנה (מק\"ט): ${group.stop2.stopCode}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                                }
                                Text("←", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            val stop = selectedStop!!

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("מספר תחנה (מק\"ט): ${stop.stopCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

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
                            Text("⭐ לתחנות שלי", fontSize = 12.sp)
                        }

                        if (oppositeStop != null) {
                            OutlinedButton(
                                onClick = {
                                    savedSelectedStopId = oppositeStop!!.stopId
                                    selectedStop = oppositeStop
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("תחנה נגדית ⟷", fontSize = 12.sp)
                            }
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
                    Text("אוטובוסים קרובים בתחנה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = filterMode == "עכשיו",
                            onClick = { filterMode = "עכשיו" },
                            label = { Text("עכשיו") },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = filterMode == "היום",
                            onClick = {
                                DatePickerDialog(context, { _, y, m, d ->
                                    selectedDate = LocalDate.of(y, m + 1, d)
                                    filterMode = "היום"
                                }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
                            },
                            label = { Text(if (filterMode == "היום") "${selectedDate.dayOfMonth}/${selectedDate.monthValue}" else "תאריך ▾") },
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
                        Text("לא נמצאו יציאות במועד שנבחר.", style = MaterialTheme.typography.bodySmall)
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
                            Text(if (departuresLimit == 10) "הצג 20 נסיעות נוספות" else "הסתר 20 נסיעות נוספות")
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
                    Text("קווים העוברים בתחנה זו", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    if (lines.isEmpty()) {
                        Text("לא נמצאו קווים.", style = MaterialTheme.typography.bodySmall)
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
                                        Text(text = originAnnotated, fontSize = 12.sp, fontWeight = FontWeight.Bold)

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
                                            Text(text = destAnnotated, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        when {
                                            status == null || !status.isActiveToday -> {
                                                Text(
                                                    text = "קו זה לא פעיל ביום הנוכחי",
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            status.upcomingMinutes.isEmpty() -> {
                                                Text(
                                                    text = "אין נסיעות נוספות להיום",
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
                            Text("מסך מלא", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. מסך חיפוש קו (LineSearchScreen)
// =========================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LineSearchScreen(
    initialLine: String? = null,
    initialRouteId: String? = null,
    onBack: () -> Unit,
    onNavigateToStop: (TransitRepository.TransitStop) -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val variantRepository = remember(database) { RouteVariantRepository(database) }
    val scheduleRepo = remember(database) { TransitScheduleRepository(database) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf(initialLine ?: "") }
    var cityFilter by remember { mutableStateOf("") }
    var agencyFilter by remember { mutableStateOf("") }
    var showAgencyDropdown by remember { mutableStateOf(false) }

    var groupedLines by remember { mutableStateOf(emptyList<TransitRepository.GroupedTransitLine>()) }

    var selectedRouteId by rememberSaveable(initialRouteId) { mutableStateOf(initialRouteId) }
    var selectedLine by remember { mutableStateOf<TransitRepository.TransitLine?>(null) }
    var oppositeLineForSelected by remember { mutableStateOf<TransitRepository.TransitLine?>(null) }
    var selectedStopSequenceIndex by rememberSaveable { mutableIntStateOf(0) }

    var variants by remember { mutableStateOf(emptyList<RouteVariant>()) }
    var selectedVariantIndex by remember { mutableIntStateOf(0) }
    var dailyDepartures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }

    var isTodayMode by remember(selectedRouteId) { mutableStateOf(true) }
    var selectedDayOfWeek by remember(selectedRouteId) { mutableStateOf(scheduleRepo.currentServiceDate().dayOfWeek) }
    var showDayPickerDialog by remember { mutableStateOf(false) }
    var autoCheckedEndOfDay by remember(selectedRouteId) { mutableStateOf(false) }

    var nextDepartureInfo by remember { mutableStateOf<TransitScheduleRepository.LineNextDepartureInfo?>(null) }
    var selectedTripIdForTimeline by remember { mutableStateOf<String?>(null) }
    var tripStopTimesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isMapFullScreen by remember { mutableStateOf(false) }
    var clickedStopForAction by remember { mutableStateOf<TransitRepository.RouteStop?>(null) }

    var searchHistory by remember { mutableStateOf(SearchHistoryHelper.getHistory(context, "line_history")) }
    var historyItemToDelete by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialLine.isNullOrBlank() && initialRouteId.isNullOrBlank() && selectedRouteId == null) {
            delay(250)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(initialRouteId) {
        if (!initialRouteId.isNullOrBlank()) {
            selectedRouteId = initialRouteId
        }
    }

    LaunchedEffect(selectedRouteId) {
        val rId = selectedRouteId
        if (rId != null && selectedLine?.routeId != rId) {
            withContext(Dispatchers.IO) {
                repository.getLine(rId)
            }?.let { loadedLine ->
                selectedLine = loadedLine
            }
        }
    }

    BackHandler(enabled = isMapFullScreen || (selectedLine != null && initialRouteId == null)) {
        if (isMapFullScreen) {
            isMapFullScreen = false
        } else {
            selectedLine = null
            selectedRouteId = null
        }
    }

    LaunchedEffect(query, cityFilter, agencyFilter) {
        val q = query.trim()
        val c = cityFilter.trim()
        val a = agencyFilter.trim()

        if (q.isEmpty() && c.isEmpty() && a.isEmpty()) {
            groupedLines = emptyList()
        } else {
            delay(200)
            groupedLines = withContext(Dispatchers.IO) {
                repository.searchGroupedLines(
                    query = q,
                    cityFilter = c,
                    agencyFilter = a,
                    limit = 50
                )
            }
        }
    }

    LaunchedEffect(selectedLine) {
        val line = selectedLine
        if (line != null) {
            variants = withContext(Dispatchers.IO) { variantRepository.getVariants(line.routeId) }
            oppositeLineForSelected = withContext(Dispatchers.IO) { repository.findOppositeLine(line) }

            if (!autoCheckedEndOfDay) {
                val now = LocalDateTime.now()
                val serviceToday = scheduleRepo.currentServiceDate(now)
                val todayDeps = withContext(Dispatchers.IO) {
                    scheduleRepo.getDeparturesForDayOfWeek(line.routeId, serviceToday.dayOfWeek, now = now)
                }
                if (todayDeps.isNotEmpty() && todayDeps.none { !it.departureAt.isBefore(now) }) {
                    isTodayMode = false
                    selectedDayOfWeek = serviceToday.plusDays(1).dayOfWeek
                }
                autoCheckedEndOfDay = true
            }
        }
    }

    val activeVariant = variants.getOrNull(selectedVariantIndex)
    val currentStops = activeVariant?.stops ?: emptyList()
    val refStop = currentStops.getOrNull(selectedStopSequenceIndex)

    LaunchedEffect(selectedLine, refStop?.stopId) {
        val line = selectedLine
        if (line != null) {
            val info = withContext(Dispatchers.IO) {
                scheduleRepo.getNextDepartureForLineAtStop(line.routeId, refStop?.stopId)
            }
            nextDepartureInfo = info
            if (info.nextTripId != null) {
                selectedTripIdForTimeline = info.nextTripId
            }
        }
    }

    LaunchedEffect(selectedLine, isTodayMode, selectedDayOfWeek) {
        val line = selectedLine
        if (line != null) {
            val now = LocalDateTime.now()
            val targetDay = if (isTodayMode) scheduleRepo.currentServiceDate(now).dayOfWeek else selectedDayOfWeek
            val deps = withContext(Dispatchers.IO) {
                scheduleRepo.getDeparturesForDayOfWeek(line.routeId, targetDay, now = now)
            }
            dailyDepartures = deps

            if (selectedTripIdForTimeline == null || deps.none { it.tripId == selectedTripIdForTimeline }) {
                val nowServiceSec = if (now.hour < 4) now.toLocalTime().toSecondOfDay() + 86400 else now.toLocalTime().toSecondOfDay()
                val isTargetToday = targetDay == scheduleRepo.currentServiceDate(now).dayOfWeek
                val nextDep = if (isTargetToday) {
                    deps.firstOrNull { it.departureSeconds >= nowServiceSec } ?: deps.firstOrNull()
                } else {
                    deps.firstOrNull()
                }
                if (nextDep != null) {
                    selectedTripIdForTimeline = nextDep.tripId
                }
            }
        }
    }

    LaunchedEffect(selectedTripIdForTimeline) {
        val tId = selectedTripIdForTimeline
        if (tId != null) {
            tripStopTimesMap = withContext(Dispatchers.IO) {
                getTripStopTimes(database, tId)
            }
        }
    }

    if (showDayPickerDialog) {
        AlertDialog(
            onDismissRequest = { showDayPickerDialog = false },
            title = {
                Text("בחירת יום בשבוע", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEK_DAYS_HEBREW.forEach { day ->
                        val isChosen = !isTodayMode && selectedDayOfWeek == day
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isChosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDayOfWeek = day
                                    isTodayMode = false
                                    showDayPickerDialog = false
                                }
                        ) {
                            Text(
                                text = hebrewDayName(day),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDayPickerDialog = false }) { Text("ביטול") }
            }
        )
    }

    historyItemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { historyItemToDelete = null },
            title = { Text("מחיקת פריט מההיסטוריה") },
            text = { Text("האם למחוק את קו \"$item\" מהחיפושים האחרונים?") },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "line_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "line_history")
                    historyItemToDelete = null
                }) { Text("מחק") }
            },
            dismissButton = {
                TextButton(onClick = { historyItemToDelete = null }) { Text("ביטול") }
            }
        )
    }

    clickedStopForAction?.let { stop ->
        val transitStop = TransitRepository.TransitStop(stop.stopId, stop.name, null, null, stop.stopCode)
        AlertDialog(
            onDismissRequest = { clickedStopForAction = null },
            title = {
                Text(stop.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("מספר תחנה (מק\"ט): ${stop.stopCode}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("בחר פעולה עבור תחנה זו:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onPlanRouteFrom?.let {
                            RouteFromButton(onClick = { clickedStopForAction = null; it(transitStop) }, modifier = Modifier.weight(1f))
                        }
                        onPlanRouteTo?.let {
                            RouteToButton(onClick = { clickedStopForAction = null; it(transitStop) }, modifier = Modifier.weight(1f))
                        }
                    }

                    Button(
                        onClick = {
                            clickedStopForAction = null
                            onNavigateToStop(transitStop)
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text("🚏 מעבר לפרטי תחנה זו", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { clickedStopForAction = null }) { Text("סגירה") }
            }
        )
    }

    val renderLineOnMap: (MapLibreMap) -> Unit = { map ->
        map.getStyle { style ->
            if (!style.isFullyLoaded) return@getStyle
            scope.launch {
                val stopCoordinates = withContext(Dispatchers.IO) {
                    val db = database.openReadOnly() ?: return@withContext emptyList()
                    try {
                        val coords = mutableListOf<LatLng>()
                        for (st in currentStops) {
                            db.rawQuery("SELECT stop_lat, stop_lon FROM stops WHERE stop_id = ? LIMIT 1", arrayOf(st.stopId)).use { c ->
                                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                                    coords.add(LatLng(c.getDouble(0), c.getDouble(1)))
                                }
                            }
                        }
                        coords
                    } finally {
                        db.close()
                    }
                }

                if (!style.isFullyLoaded) return@launch

                if (stopCoordinates.size >= 2) {
                    val boundsBuilder = LatLngBounds.Builder()
                    stopCoordinates.forEach { boundsBuilder.include(it) }
                    try {
                        map.easeCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 70), 800)
                    } catch (_: Exception) {
                        map.easeCamera(CameraUpdateFactory.newLatLngZoom(stopCoordinates.first(), 13.0))
                    }

                    val points = stopCoordinates.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val lineString = LineString.fromLngLats(points)
                    val lineSourceId = "line-path-source"
                    val lineLayerId = "line-path-layer"

                    if (style.getLayer(lineLayerId) != null) style.removeLayer(lineLayerId)
                    if (style.getSource(lineSourceId) != null) style.removeSource(lineSourceId)

                    style.addSource(GeoJsonSource(lineSourceId, Feature.fromGeometry(lineString)))
                    style.addLayer(
                        LineLayer(lineLayerId, lineSourceId).withProperties(
                            lineColor("#1769AA"),
                            lineWidth(4.5f),
                            lineCap("round"),
                            lineJoin("round")
                        )
                    )

                    val stopFeatures = stopCoordinates.mapIndexed { idx, coord ->
                        val color = when (idx) {
                            0 -> "#2E7D32"
                            stopCoordinates.lastIndex -> "#C62828"
                            else -> "#1976D2"
                        }
                        val radius = if (idx == 0 || idx == stopCoordinates.lastIndex) 7.0f else 4.5f
                        Feature.fromGeometry(Point.fromLngLat(coord.longitude, coord.latitude)).apply {
                            addStringProperty("stop_color", color)
                            addNumberProperty("stop_radius", radius)
                        }
                    }

                    val stopsSourceId = "line-stops-source"
                    val stopsLayerId = "line-stops-layer"
                    if (style.getLayer(stopsLayerId) != null) style.removeLayer(stopsLayerId)
                    if (style.getSource(stopsSourceId) != null) style.removeSource(stopsSourceId)

                    style.addSource(GeoJsonSource(stopsSourceId, FeatureCollection.fromFeatures(stopFeatures)))
                    style.addLayer(
                        CircleLayer(stopsLayerId, stopsSourceId).withProperties(
                            circleColor(Expression.get("stop_color")),
                            circleRadius(Expression.get("stop_radius")),
                            circleStrokeColor("#FFFFFF"),
                            circleStrokeWidth(1.5f)
                        )
                    )
                }
            }
        }
    }

    if (isMapFullScreen && selectedLine != null && mapStorage.isMapAvailable()) {
        Box(modifier = Modifier.fillMaxSize()) {
            OfflineMapView(
                pmtilesFile = mapStorage.getMapFile(),
                modifier = Modifier.fillMaxSize(),
                onMapReady = renderLineOnMap
            )
            FilledTonalButton(
                onClick = { isMapFullScreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SearchScreenTopHeader(
            title = if (selectedLine == null) "חיפוש קו" else "קו ${selectedLine!!.lineNumber}",
            onBack = onBack
        )

        if (selectedLine == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text("מספר קו") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Text("✕") }
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cityFilter,
                    onValueChange = { cityFilter = it },
                    modifier = Modifier.weight(1f).height(56.dp),
                    placeholder = { Text("עיר", fontSize = 13.sp, maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        if (cityFilter.isNotEmpty()) {
                            IconButton(onClick = { cityFilter = "" }, modifier = Modifier.size(24.dp)) {
                                Text("✕", fontSize = 11.sp)
                            }
                        }
                    }
                )

                Box(modifier = Modifier.weight(1f).height(56.dp)) {
                    OutlinedButton(
                        onClick = { showAgencyDropdown = true },
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = agencyFilter.ifBlank { "מפעיל ▾" },
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (agencyFilter.isNotBlank()) {
                                Text("✕", fontSize = 12.sp, modifier = Modifier.clickable { agencyFilter = "" })
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showAgencyDropdown,
                        onDismissRequest = { showAgencyDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("כל המפעילים", fontWeight = FontWeight.Bold) },
                            onClick = {
                                agencyFilter = ""
                                showAgencyDropdown = false
                            }
                        )
                        OperatorTheme.ALL_OPERATORS.forEach { op ->
                            DropdownMenuItem(
                                text = { Text(op, fontSize = 13.sp) },
                                onClick = {
                                    agencyFilter = op
                                    showAgencyDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Text("חיפושים אחרונים:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        query = item
                                        keyboardController?.hide()
                                    },
                                    onLongClick = { historyItemToDelete = item }
                                )
                                .padding(2.dp)
                        ) {
                            Text("קו $item", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                        }
                    }
                }
            }

            fun formatDirectionText(line: TransitRepository.TransitLine, isUrban: Boolean): String {
                val orig = if (isUrban || line.originCity.isBlank() || line.originName.contains(line.originCity)) {
                    line.originName.ifBlank { line.name }
                } else {
                    "${line.originName} - ${line.originCity}"
                }

                val dest = if (isUrban || line.destinationCity.isBlank() || line.destinationName.contains(line.destinationCity)) {
                    line.destinationName
                } else {
                    "${line.destinationName} - ${line.destinationCity}"
                }

                return if (dest.isNotBlank()) "מ- $orig ← ל- $dest" else orig
            }

            groupedLines.forEach { group ->
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
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TransitLineBadge(lineNumber = group.lineNumber, agencyName = group.agencyName)
                                Text(group.agencyName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (group.alternativeLabel.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = group.alternativeLabel,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (group.cityHeader.isNotBlank()) {
                                    Text(
                                        text = group.cityHeader,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SearchHistoryHelper.addHistory(context, "line_history", group.lineNumber)
                                    selectedRouteId = group.forwardLine.routeId
                                    selectedLine = group.forwardLine
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(formatDirectionText(group.forwardLine, group.isUrban), fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            }
                            Text("←", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        if (group.backwardLine != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        SearchHistoryHelper.addHistory(context, "line_history", group.lineNumber)
                                        selectedRouteId = group.backwardLine.routeId
                                        selectedLine = group.backwardLine
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatDirectionText(group.backwardLine, group.isUrban), fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                }
                                Text("←", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            val line = selectedLine!!

            val matchingGroup = groupedLines.firstOrNull {
                it.forwardLine.routeId == line.routeId || it.backwardLine?.routeId == line.routeId
            }
            val oppositeLine = if (matchingGroup != null) {
                if (matchingGroup.forwardLine.routeId == line.routeId) matchingGroup.backwardLine else matchingGroup.forwardLine
            } else {
                oppositeLineForSelected
            }

            val normOrigCity = OperatorTheme.normalizeHebrew(line.originCity)
            val normDestCity = OperatorTheme.normalizeHebrew(line.destinationCity)
            val isUrbanLine = line.originCity.isNotBlank() && line.destinationCity.isNotBlank() &&
                (normOrigCity == normDestCity || normOrigCity.contains(normDestCity) || normDestCity.contains(normOrigCity))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TransitLineBadge(lineNumber = line.lineNumber, agencyName = line.agencyName)
                            Text(line.agencyName, fontWeight = FontWeight.Medium, fontSize = 13.sp)

                            val topCityLabel = if (isUrbanLine || line.destinationCity.isBlank()) {
                                line.originCity.ifBlank { line.destinationCity }
                            } else {
                                "מ- ${line.originCity} ← ל- ${line.destinationCity}"
                            }

                            if (topCityLabel.isNotBlank()) {
                                Text("• $topCityLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (oppositeLine != null) {
                            FilledTonalButton(
                                onClick = {
                                    selectedRouteId = oppositeLine.routeId
                                    selectedLine = oppositeLine
                                    selectedStopSequenceIndex = 0
                                    selectedTripIdForTimeline = null
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .defaultMinSize(minWidth = 108.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "כיוון נגדי ⇄",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    val originFull = if (!isUrbanLine && line.originCity.isNotBlank() && !line.originName.contains(line.originCity)) {
                        "${line.originName} - ${line.originCity}"
                    } else {
                        line.originName
                    }

                    val destFull = if (!isUrbanLine && line.destinationCity.isNotBlank() && !line.destinationName.contains(line.destinationCity)) {
                        "${line.destinationName} - ${line.destinationCity}"
                    } else {
                        line.destinationName
                    }

                    val mainHeading = if (destFull.isNotBlank()) "מ- $originFull ← ל- $destFull" else originFull

                    Text(mainHeading, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Text("סה\"כ ${currentStops.size} תחנות במסלול הקו", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("רשימת התחנות", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)

                    val info = nextDepartureInfo
                    if (info != null) {
                        val nowLocal = LocalDateTime.now()
                        when {
                            !info.activeToday -> {
                                Text(
                                    text = "קו זה לא פעיל ביום זה",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                if (info.nextDepartureAt != null && info.nextDayOfWeek != null) {
                                    Text(
                                        text = "אוטובוס הבא בתחנה: ${hebrewDayName(info.nextDayOfWeek)} בשעה ${info.nextDepartureAt.format(timeFormatter)}",
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                            info.todayServiceEnded -> {
                                Text(
                                    text = "אין עוד נסיעות להיום",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                if (info.nextDepartureAt != null) {
                                    val whenText = if (info.isTomorrow) "מחר"
                                    else info.nextDayOfWeek?.let { hebrewDayName(it) } ?: "מחר"
                                    Text(
                                        text = "האוטובוס הבא בתחנה: $whenText בשעה ${info.nextDepartureAt.format(timeFormatter)}",
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                            info.nextDepartureAt != null -> {
                                val diffMin = Duration.between(nowLocal, info.nextDepartureAt).toMinutes().coerceAtLeast(0)
                                Text(
                                    text = "האוטובוס הבא בתחנה: ${info.nextDepartureAt.format(timeFormatter)} • ${formatWaitDuration(diffMin)}",
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    currentStops.forEachIndexed { idx, st ->
                        val isSelected = selectedStopSequenceIndex == idx
                        val stopTime = tripStopTimesMap[st.stopId] ?: ""

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .clickable { selectedStopSequenceIndex = idx },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${idx + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = st.name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { clickedStopForAction = st }
                            )
                            if (stopTime.isNotBlank()) {
                                Text(
                                    text = stopTime,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
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
                    Text("לוח זמנים יומי", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isTodayMode,
                            onClick = {
                                isTodayMode = true
                                selectedDayOfWeek = scheduleRepo.currentServiceDate().dayOfWeek
                            },
                            label = { Text("היום", fontSize = 12.sp) },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = !isTodayMode,
                            onClick = { showDayPickerDialog = true },
                            label = {
                                Text(
                                    text = if (isTodayMode) "בחירת יום ▾" else "${hebrewDayName(selectedDayOfWeek)} ▾",
                                    fontSize = 12.sp
                                )
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }

                    if (dailyDepartures.isEmpty()) {
                        Text(
                            text = "קו זה לא פעיל ביום זה",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    } else {
                        val nowLocal = LocalDateTime.now()
                        val nowServiceDate = scheduleRepo.currentServiceDate(nowLocal)
                        val nowServiceSeconds = if (nowLocal.hour < 4) {
                            nowLocal.toLocalTime().toSecondOfDay() + 86400
                        } else {
                            nowLocal.toLocalTime().toSecondOfDay()
                        }
                        val isViewingToday = isTodayMode || selectedDayOfWeek == nowServiceDate.dayOfWeek

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            dailyDepartures.forEach { dep ->
                                val isSpecial = dep.isLastFridayTrip || dep.isFirstSaturdayTrip
                                val isCurrentTrip = dep.tripId == selectedTripIdForTimeline
                                val isPastTrip = isViewingToday && dep.departureSeconds < nowServiceSeconds

                                val backgroundColor = when {
                                    isCurrentTrip -> MaterialTheme.colorScheme.primary
                                    isPastTrip -> Color.Transparent
                                    isSpecial -> Color(0xFFF57F17).copy(alpha = 0.20f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                }

                                val borderModifier = if (isPastTrip && !isCurrentTrip) {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                } else Modifier

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = backgroundColor,
                                    modifier = Modifier
                                        .then(borderModifier)
                                        .padding(1.dp)
                                        .clickable(enabled = !isPastTrip) {
                                            selectedTripIdForTimeline = dep.tripId
                                        }
                                ) {
                                    Text(
                                        text = dep.departureAt.format(timeFormatter),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                                        fontSize = 11.sp,
                                        fontWeight = if (isCurrentTrip) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            isCurrentTrip -> Color.White
                                            isPastTrip -> MaterialTheme.colorScheme.outline
                                            isSpecial -> Color(0xFFE65100)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (mapStorage.isMapAvailable()) {
                var lineMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }

                LaunchedEffect(lineMapInstance, currentStops) {
                    if (lineMapInstance != null && currentStops.size >= 2) {
                        renderLineOnMap(lineMapInstance!!)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OfflineMapView(
                            pmtilesFile = mapStorage.getMapFile(),
                            modifier = Modifier.fillMaxSize(),
                            onMapReady = { map ->
                                lineMapInstance = map
                                renderLineOnMap(map)
                            }
                        )
                        FilledTonalButton(
                            onClick = { isMapFullScreen = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("מסך מלא", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
