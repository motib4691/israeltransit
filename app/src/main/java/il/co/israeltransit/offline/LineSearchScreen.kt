package il.co.israeltransit.offline

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.database.TransitV6Codec
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
import java.time.LocalDateTime

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
        val variants = linkedMapOf<Int, RouteVariant>()

        try {
            db.rawQuery(
                """
                SELECT
                    rp.pattern_id,
                    COALESCE(rp.direction_id, ''),
                    COALESCE(h.text, ''),
                    s.stop_id,
                    COALESCE(s.stop_name, ''),
                    rps.stop_sequence,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM routes r
                JOIN route_patterns rp ON rp.route_idx = r.route_idx
                LEFT JOIN headsigns h ON h.headsign_idx = rp.headsign_idx
                JOIN route_pattern_stops rps ON rps.pattern_id = rp.pattern_id
                JOIN stops s ON s.stop_idx = rps.stop_idx
                WHERE r.route_id = ?
                ORDER BY rp.stop_count DESC, rp.pattern_id, rps.stop_sequence
                """.trimIndent(),
                arrayOf(routeId)
            ).use { cursor ->
                var currentPatternId = -1
                var directionId = ""
                var headsign = ""
                val stops = mutableListOf<TransitRepository.RouteStop>()

                fun savePattern() {
                    if (currentPatternId < 0 || stops.isEmpty()) return
                    if (!variants.containsKey(currentPatternId)) {
                        variants[currentPatternId] = RouteVariant(
                            directionId = directionId,
                            headsign = headsign,
                            stops = stops.toList()
                        )
                    }
                }

                while (cursor.moveToNext()) {
                    val patternId = cursor.getInt(0)
                    if (patternId != currentPatternId) {
                        savePattern()
                        stops.clear()
                        currentPatternId = patternId
                        directionId = cursor.getString(1) ?: ""
                        headsign = cursor.getString(2) ?: ""
                    }

                    stops.add(
                        TransitRepository.RouteStop(
                            stopId = cursor.getString(3) ?: "",
                            name = cursor.getString(4) ?: "",
                            sequence = cursor.getInt(5),
                            arrivalTime = null,
                            departureTime = null,
                            stopCode = cursor.getString(6) ?: ""
                        )
                    )
                }

                savePattern()
            }
        } finally {
            db.close()
        }

        return variants.values.toList()
    }
}

private fun getTripStopTimes(database: TransitDatabase, tripId: String): Map<String, String> {
    val parts = tripId.split(":")
    if (parts.size != 2) return emptyMap()
    val patternId = parts[0].toIntOrNull() ?: return emptyMap()
    val tripIndex = parts[1].toIntOrNull() ?: return emptyMap()
    if (tripIndex < 0) return emptyMap()

    val db = database.openReadOnly() ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    try {
        var tripCount = 0
        var tripsBlob: ByteArray? = null
        db.rawQuery(
            "SELECT trip_count, trips_blob FROM pattern_schedules WHERE pattern_id = ? LIMIT 1",
            arrayOf(patternId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                tripCount = cursor.getInt(0)
                tripsBlob = cursor.getBlob(1)
            }
        }

        val blob = tripsBlob ?: return emptyMap()
        if (tripIndex >= tripCount) return emptyMap()

        val schedule = TransitV6Codec.decodeSchedule(tripCount, blob)
        val startSec = schedule.startSecs[tripIndex]
        val profileId = schedule.profileIds[tripIndex]

        var stopCount = 0
        var profBlob: ByteArray? = null
        db.rawQuery(
            "SELECT stop_count, data FROM timing_profiles WHERE profile_id = ? LIMIT 1",
            arrayOf(profileId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stopCount = cursor.getInt(0)
                profBlob = cursor.getBlob(1)
            }
        }

        val pBlob = profBlob ?: return emptyMap()
        val profile = TransitV6Codec.decodeProfile(stopCount, pBlob)

        val stopIds = ArrayList<String>(stopCount)
        db.rawQuery(
            """
            SELECT s.stop_id
            FROM route_pattern_stops rps
            JOIN stops s ON s.stop_idx = rps.stop_idx
            WHERE rps.pattern_id = ?
            ORDER BY rps.stop_sequence
            """.trimIndent(),
            arrayOf(patternId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                stopIds.add(cursor.getString(0) ?: "")
            }
        }

        val count = minOf(stopIds.size, profile.depOffsets.size)
        for (i in 0 until count) {
            val sId = stopIds[i]
            if (sId.isEmpty()) continue
            val sec = startSec + profile.depOffsets[i]
            val h = (sec / 3600) % 24
            val m = (sec % 3600) / 60
            result[sId] = String.format("%02d:%02d", h, m)
        }
    } finally {
        db.close()
    }
    return result
}

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
    var detailsContext by remember { mutableStateOf<TransitRepository.LineDetailsContext?>(null) }

    var isAllAlternativesMode by remember { mutableStateOf(false) }
    var manualSubVariantId by remember { mutableStateOf<String?>(null) }
    var showDifferencesDialog by remember { mutableStateOf(false) }

    var selectedStopSequenceIndex by rememberSaveable { mutableIntStateOf(0) }
    var userPickedStopInList by remember { mutableStateOf(false) }

    var variants by remember { mutableStateOf(emptyList<RouteVariant>()) }
    var dailyDepartures by remember { mutableStateOf(emptyList<TransitScheduleRepository.Departure>()) }

    var isTodayMode by remember(selectedRouteId, isAllAlternativesMode) { mutableStateOf(true) }
    var selectedDayOfWeek by remember(selectedRouteId, isAllAlternativesMode) {
        mutableStateOf(scheduleRepo.currentServiceDate().dayOfWeek)
    }
    var showDayPickerDialog by remember { mutableStateOf(false) }
    var autoCheckedEndOfDay by remember(selectedRouteId, isAllAlternativesMode) { mutableStateOf(false) }

    var nextDepartureInfo by remember { mutableStateOf<TransitScheduleRepository.LineNextDepartureInfo?>(null) }
    var selectedTripIdForTimeline by remember { mutableStateOf<String?>(null) }
    var selectedTripRouteId by remember { mutableStateOf<String?>(null) }
    var tripStopTimesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var displayedLineForCard by remember { mutableStateOf<TransitRepository.TransitLine?>(null) }

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
        if (rId != null) {
            if (selectedLine?.routeId != rId) {
                withContext(Dispatchers.IO) {
                    repository.getLine(rId)
                }?.let { loadedLine ->
                    selectedLine = loadedLine
                }
            }
            detailsContext = withContext(Dispatchers.IO) {
                repository.getLineDetailsContext(rId)
            }
        } else {
            selectedLine = null
            detailsContext = null
        }
    }

    BackHandler(enabled = isMapFullScreen || (selectedLine != null && initialRouteId == null)) {
        if (isMapFullScreen) {
            isMapFullScreen = false
        } else {
            selectedLine = null
            selectedRouteId = null
            isAllAlternativesMode = false
            manualSubVariantId = null
            userPickedStopInList = false
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

    val activeQueryRouteIds = remember(selectedLine, detailsContext, isAllAlternativesMode, manualSubVariantId) {
        val baseId = selectedLine?.routeId ?: return@remember emptyList()
        if (manualSubVariantId != null) {
            listOf(manualSubVariantId!!)
        } else {
            val ctx = detailsContext
            when {
                ctx == null -> listOf(baseId)
                isAllAlternativesMode && ctx.alternatives.size > 1 ->
                    ctx.alternatives.flatMap { it.routeIds }.distinct().ifEmpty { listOf(baseId) }
                else ->
                    ctx.alternatives.getOrNull(ctx.activeAlternativeIndex)?.routeIds?.ifEmpty { listOf(baseId) }
                        ?: listOf(baseId)
            }
        }
    }

    val displayedStopsRouteId = remember(selectedLine, manualSubVariantId, selectedTripRouteId, nextDepartureInfo) {
        manualSubVariantId
            ?: selectedTripRouteId
            ?: nextDepartureInfo?.nextRouteId
            ?: selectedLine?.routeId
    }

    LaunchedEffect(displayedStopsRouteId) {
        val rId = displayedStopsRouteId
        if (rId != null) {
            variants = withContext(Dispatchers.IO) { variantRepository.getVariants(rId) }
            displayedLineForCard = if (selectedLine?.routeId == rId) {
                selectedLine
            } else {
                withContext(Dispatchers.IO) { repository.getLine(rId) }
            }
        }
    }

    val activeVariant = variants.firstOrNull()
    val currentStops = activeVariant?.stops ?: emptyList()
    val refStop = currentStops.getOrNull(selectedStopSequenceIndex)

    LaunchedEffect(activeQueryRouteIds) {
        if (activeQueryRouteIds.isNotEmpty() && !autoCheckedEndOfDay) {
            val now = LocalDateTime.now()
            val serviceToday = scheduleRepo.currentServiceDate(now)
            val todayDeps = withContext(Dispatchers.IO) {
                scheduleRepo.getDeparturesForDayOfWeek(activeQueryRouteIds, serviceToday.dayOfWeek, now = now)
            }
            if (todayDeps.isNotEmpty() && todayDeps.none { !it.departureAt.isBefore(now) }) {
                isTodayMode = false
                selectedDayOfWeek = serviceToday.plusDays(1).dayOfWeek
            }
            autoCheckedEndOfDay = true
        }
    }

    LaunchedEffect(activeQueryRouteIds, refStop?.stopId, userPickedStopInList) {
        if (activeQueryRouteIds.isNotEmpty()) {
            val stopIdForQuery = if (userPickedStopInList || activeQueryRouteIds.size == 1) refStop?.stopId else null
            val info = withContext(Dispatchers.IO) {
                scheduleRepo.getNextDepartureForLineAtStop(activeQueryRouteIds, stopIdForQuery)
            }
            nextDepartureInfo = info
            if (info.nextTripId != null && selectedTripIdForTimeline == null) {
                selectedTripIdForTimeline = info.nextTripId
                selectedTripRouteId = info.nextRouteId
            }
        }
    }

    LaunchedEffect(activeQueryRouteIds, isTodayMode, selectedDayOfWeek) {
        if (activeQueryRouteIds.isNotEmpty()) {
            val now = LocalDateTime.now()
            val targetDay = if (isTodayMode) scheduleRepo.currentServiceDate(now).dayOfWeek else selectedDayOfWeek
            val deps = withContext(Dispatchers.IO) {
                scheduleRepo.getDeparturesForDayOfWeek(activeQueryRouteIds, targetDay, now = now)
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
                    selectedTripRouteId = nextDep.routeId
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

    if (showDifferencesDialog && detailsContext != null) {
        val ctx = detailsContext!!
        AlertDialog(
            onDismissRequest = { showDifferencesDialog = false },
            title = {
                Text(stringResource(R.string.ui_7d8b87), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (ctx.alternatives.size > 1) {
                        Text(stringResource(R.string.ui_fafbb6), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        ctx.alternatives.forEach { alt ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(alt.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                                Text(alt.differenceSummary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!isAllAlternativesMode && ctx.subVariants.size > 1) {
                        if (ctx.alternatives.size > 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        Text(stringResource(R.string.ui_68de62), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        ctx.subVariants.forEach { sub ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sub.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                Text(sub.differenceSummary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDifferencesDialog = false }) { Text("סגירה") }
            }
        )
    }

    if (showDayPickerDialog) {
        AlertDialog(
            onDismissRequest = { showDayPickerDialog = false },
            title = {
                Text(stringResource(R.string.ui_18ed27), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
            title = { Text(stringResource(R.string.ui_4df88a)) },
            text = { Text(stringResource(R.string.ui_7b8f20, item)) },
            confirmButton = {
                TextButton(onClick = {
                    SearchHistoryHelper.removeHistory(context, "line_history", item)
                    searchHistory = SearchHistoryHelper.getHistory(context, "line_history")
                    historyItemToDelete = null
                }) { Text(stringResource(R.string.ui_07aecd)) }
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
                    Text(stringResource(R.string.ui_5d32ff, stop.stopCode), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.ui_078639), fontWeight = FontWeight.Bold, fontSize = 13.sp)

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
                        Text(stringResource(R.string.ui_b2d2ea), fontSize = 13.sp)
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
            title = if (selectedLine == null) stringResource(R.string.ui_44a8a7) else "קו ${selectedLine!!.lineNumber}",
            onBack = onBack
        )

        if (selectedLine == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                label = { Text(stringResource(R.string.ui_6bba92)) },
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
                    placeholder = { Text(stringResource(R.string.ui_42aa26), fontSize = 13.sp, maxLines = 1) },
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
                            text = { Text(stringResource(R.string.ui_f7a127), fontWeight = FontWeight.Bold) },
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
                Text(stringResource(R.string.ui_d7aedf), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
                            Text(stringResource(R.string.ui_e041ba, item), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
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
                                    isAllAlternativesMode = false
                                    manualSubVariantId = null
                                    userPickedStopInList = false
                                    selectedStopSequenceIndex = 0
                                    selectedTripIdForTimeline = null
                                    selectedTripRouteId = null
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
                                        isAllAlternativesMode = false
                                        manualSubVariantId = null
                                        userPickedStopInList = false
                                        selectedStopSequenceIndex = 0
                                        selectedTripIdForTimeline = null
                                        selectedTripRouteId = null
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
            val baseLine = selectedLine!!
            val cardLine = displayedLineForCard ?: baseLine
            val ctx = detailsContext
            val oppRouteId = if (isAllAlternativesMode && ctx != null) {
                ctx.alternatives.getOrNull(ctx.activeAlternativeIndex)?.oppositeRouteIds?.firstOrNull()
                    ?: ctx.alternatives.firstNotNullOfOrNull { it.oppositeRouteIds.firstOrNull() }
            } else {
                ctx?.oppositeRouteIds?.firstOrNull()
            }

            val normOrigCity = OperatorTheme.normalizeHebrew(cardLine.originCity)
            val normDestCity = OperatorTheme.normalizeHebrew(cardLine.destinationCity)
            val isUrbanLine = cardLine.originCity.isNotBlank() && cardLine.destinationCity.isNotBlank() &&
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
                            TransitLineBadge(lineNumber = cardLine.lineNumber, agencyName = cardLine.agencyName)
                            Text(cardLine.agencyName, fontWeight = FontWeight.Medium, fontSize = 13.sp)

                            val topCityLabel = if (isUrbanLine || cardLine.destinationCity.isBlank()) {
                                cardLine.originCity.ifBlank { cardLine.destinationCity }
                            } else {
                                "מ- ${cardLine.originCity} ← ל- ${cardLine.destinationCity}"
                            }

                            if (topCityLabel.isNotBlank()) {
                                Text("• $topCityLabel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (oppRouteId != null) {
                            FilledTonalButton(
                                onClick = {
                                    manualSubVariantId = null
                                    userPickedStopInList = false
                                    selectedStopSequenceIndex = 0
                                    selectedTripIdForTimeline = null
                                    selectedTripRouteId = null
                                    selectedRouteId = oppRouteId
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .defaultMinSize(minWidth = 108.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ui_97a025),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    val originFull = if (!isUrbanLine && cardLine.originCity.isNotBlank() && !cardLine.originName.contains(cardLine.originCity)) {
                        "${cardLine.originName} - ${cardLine.originCity}"
                    } else {
                        cardLine.originName
                    }

                    val destFull = if (!isUrbanLine && cardLine.destinationCity.isNotBlank() && !cardLine.destinationName.contains(cardLine.destinationCity)) {
                        "${cardLine.destinationName} - ${cardLine.destinationCity}"
                    } else {
                        cardLine.destinationName
                    }

                    val mainHeading = if (destFull.isNotBlank()) "מ- $originFull ← ל- $destFull" else originFull

                    Text(mainHeading, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Text(stringResource(R.string.ui_e0e1dc, currentStops.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

                    // בורר חלופות עליון מובחן (רק כשיש לקו מספר חלופות בעלות מוצא/יעד שונה)
                    if (ctx != null && ctx.alternatives.size > 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            ctx.alternatives.forEachIndexed { idx, alt ->
                                val selected = !isAllAlternativesMode && ctx.activeAlternativeIndex == idx
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        isAllAlternativesMode = false
                                        manualSubVariantId = null
                                        userPickedStopInList = false
                                        selectedStopSequenceIndex = 0
                                        selectedTripIdForTimeline = null
                                        selectedTripRouteId = null
                                        selectedRouteId = alt.primaryLine.routeId
                                        selectedLine = alt.primaryLine
                                    },
                                    label = {
                                        Text(alt.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                                        selectedLabelColor = MaterialTheme.colorScheme.secondary
                                    )
                                )
                            }

                            FilterChip(
                                selected = isAllAlternativesMode,
                                onClick = {
                                    isAllAlternativesMode = true
                                    manualSubVariantId = null
                                    userPickedStopInList = false
                                    selectedStopSequenceIndex = 0
                                    selectedTripIdForTimeline = null
                                    selectedTripRouteId = null
                                },
                                label = {
                                    Text(stringResource(R.string.ui_402b9d), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                                    selectedLabelColor = MaterialTheme.colorScheme.secondary
                                )
                            )

                            OutlinedButton(
                                onClick = { showDifferencesDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text(stringResource(R.string.ui_e0fd56), fontSize = 11.sp, maxLines = 1, softWrap = false)
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_35311b), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)

                    // בורר משני קומפקטי לתתי-מסלולים בעלי אותם מוצא ויעד (ברירת מחדל: אוטומטי לפי שעה)
                    if (!isAllAlternativesMode && ctx != null && ctx.subVariants.size > 1) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = manualSubVariantId == null,
                                onClick = {
                                    manualSubVariantId = null
                                    selectedStopSequenceIndex = 0
                                    userPickedStopInList = false
                                    selectedTripIdForTimeline = null
                                    selectedTripRouteId = null
                                },
                                label = { Text(stringResource(R.string.ui_4f8abc), fontSize = 11.sp) },
                                shape = RoundedCornerShape(14.dp)
                            )

                            ctx.subVariants.forEach { sub ->
                                FilterChip(
                                    selected = manualSubVariantId == sub.routeId,
                                    onClick = {
                                        manualSubVariantId = sub.routeId
                                        selectedStopSequenceIndex = 0
                                        userPickedStopInList = false
                                        selectedTripIdForTimeline = null
                                        selectedTripRouteId = sub.routeId
                                    },
                                    label = { Text(sub.label, fontSize = 11.sp) },
                                    shape = RoundedCornerShape(14.dp)
                                )
                            }

                            if (ctx.alternatives.size <= 1) {
                                TextButton(
                                    onClick = { showDifferencesDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(stringResource(R.string.ui_e0fd56), fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    val info = nextDepartureInfo
                    if (info != null) {
                        val nowLocal = LocalDateTime.now()
                        when {
                            !info.activeToday -> {
                                Text(
                                    text = stringResource(R.string.ui_c5b4fc),
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                if (info.nextDepartureAt != null && info.nextDayOfWeek != null) {
                                    Text(
                                        text = stringResource(R.string.ui_502837, hebrewDayName(info.nextDayOfWeek), info.nextDepartureAt.format(timeFormatter)),
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                            info.todayServiceEnded -> {
                                Text(
                                    text = stringResource(R.string.ui_cd332d),
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                if (info.nextDepartureAt != null) {
                                    val whenText = if (info.isTomorrow) "מחר"
                                    else info.nextDayOfWeek?.let { hebrewDayName(it) } ?: "מחר"
                                    Text(
                                        text = stringResource(R.string.ui_4eb8de, whenText, info.nextDepartureAt.format(timeFormatter)),
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                            info.nextDepartureAt != null -> {
                                val diffMin = Duration.between(nowLocal, info.nextDepartureAt).toMinutes().coerceAtLeast(0)
                                Text(
                                    text = stringResource(R.string.ui_a977f3, info.nextDepartureAt.format(timeFormatter), formatWaitDuration(diffMin)),
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
                                    .clickable {
                                        selectedStopSequenceIndex = idx
                                        userPickedStopInList = true
                                    },
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
                    Text(stringResource(R.string.ui_5de725), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isTodayMode,
                            onClick = {
                                isTodayMode = true
                                selectedDayOfWeek = scheduleRepo.currentServiceDate().dayOfWeek
                            },
                            label = { Text(stringResource(R.string.ui_71958d), fontSize = 12.sp) },
                            shape = RoundedCornerShape(20.dp)
                        )
                        FilterChip(
                            selected = !isTodayMode,
                            onClick = { showDayPickerDialog = true },
                            label = {
                                Text(
                                    text = if (isTodayMode) stringResource(R.string.ui_4b545f) else "${hebrewDayName(selectedDayOfWeek)} ▾",
                                    fontSize = 12.sp
                                )
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }

                    if (dailyDepartures.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_c5b4fc),
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
                                            selectedTripRouteId = dep.routeId
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
                            Text(stringResource(R.string.ui_401142), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
