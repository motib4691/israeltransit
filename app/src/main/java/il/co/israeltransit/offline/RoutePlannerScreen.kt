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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.AddressSearchRepository
import il.co.israeltransit.offline.maps.OfflineMapStorage
import il.co.israeltransit.offline.maps.TransitMapScreen
import il.co.israeltransit.offline.maps.TransitMyLocationControl
import il.co.israeltransit.offline.routing.GraphHopperWalkingRouter
import il.co.israeltransit.offline.routing.TransitRouteEngine
import il.co.israeltransit.offline.schedule.TransitScheduleRepository
import il.co.israeltransit.offline.ui.TransitLineBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    return when {
        stop.stopId.startsWith("city:") -> TransitRouteEngine.RouteEndpoint.City(
            cityName = stop.stopId.removePrefix("city:")
        )
        stop.stopId.startsWith("group:") -> {
            val ids = stop.stopId.removePrefix("group:")
                .split(",")
                .filter { it.isNotBlank() }
            TransitRouteEngine.RouteEndpoint.StopGroup(
                stopIds = ids,
                name = stop.name
            )
        }
        stop.stopId.startsWith("coord:") && stop.latitude != null && stop.longitude != null -> {
            TransitRouteEngine.RouteEndpoint.Location(
                name = stop.name,
                latitude = stop.latitude,
                longitude = stop.longitude
            )
        }
        else -> TransitRouteEngine.RouteEndpoint.Stop(
            stopId = stop.stopId,
            name = stop.name
        )
    }
}

private fun addressToSyntheticStop(addr: AddressSearchRepository.AddressResult): TransitRepository.TransitStop {
    if (addr.kind == AddressSearchRepository.ResultKind.CITY) {
        return TransitRepository.TransitStop(
            stopId = "city:${addr.title}",
            name = addr.title,
            latitude = addr.latitude,
            longitude = addr.longitude,
            stopCode = ""
        )
    }
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

private fun dualGroupToAutoStop(group: TransitRepository.DualStopGroup): TransitRepository.TransitStop {
    val ids = listOfNotNull(group.stop1.stopId, group.stop2?.stopId).distinct()
    return if (ids.size >= 2) {
        TransitRepository.TransitStop(
            stopId = "group:${ids.joinToString(",")}",
            name = group.baseName,
            latitude = group.stop1.latitude,
            longitude = group.stop1.longitude,
            stopCode = "",
            customName = group.customName
        )
    } else {
        group.stop1
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopSearchResultCard(
    group: TransitRepository.DualStopGroup,
    onSelectAuto: (TransitRepository.TransitStop) -> Unit,
    onSelectSpecific: (TransitRepository.TransitStop) -> Unit
) {
    val hasDual = group.stop2 != null
    var expanded by remember(group.stop1.stopId, group.stop2?.stopId) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (hasDual) {
                                onSelectAuto(dualGroupToAutoStop(group))
                            } else {
                                onSelectSpecific(group.stop1)
                            }
                        }
                        .padding(10.dp)
                ) {
                    if (group.customName != null) {
                        Text(
                            "⭐ ${group.customName}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                        Text(group.baseName, fontSize = 12.sp)
                    } else {
                        Text("🚏 ${group.baseName}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (!hasDual) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.ui_cbe9f7, group.stop1.stopCode),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (hasDual) {
                    Box(
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (expanded) "▴" else "▾",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && hasDual) {
                val stop2 = group.stop2
                if (stop2 != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(group.stop1, stop2).forEach { individualStop ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    modifier = Modifier.clickable { onSelectSpecific(individualStop) }
                                ) {
                                    Text(
                                        text = stringResource(R.string.ui_cbe9f7, individualStop.stopCode),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val plannerTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
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
    var timeMode by remember { mutableStateOf(RouteTimeMode.NOW) }
    var departureTime by remember { mutableStateOf(LocalDateTime.now()) }

    var expandedJourneyIndex by remember { mutableIntStateOf(-1) }
    var detailedJourney by remember { mutableStateOf<TransitRouteEngine.Journey?>(null) }

    var pickingOnMapForField by remember { mutableStateOf<String?>(null) }
    var showMapNotInstalledDialog by remember { mutableStateOf(false) }

    var searchCardTopY by remember { mutableIntStateOf(0) }
    var originSectionOffsetY by remember { mutableIntStateOf(0) }
    var destSectionOffsetY by remember { mutableIntStateOf(0) }

    val originFocusRequester = remember { FocusRequester() }
    val destFocusRequester = remember { FocusRequester() }

    fun searchRoute(overrideMode: RouteTimeMode = timeMode, overrideTime: LocalDateTime = departureTime) {
        val origin = selectedOrigin ?: return
        val destination = selectedDestination ?: return

        if (origin.stopId == destination.stopId || calculating) return

        val queryTime = if (overrideMode == RouteTimeMode.NOW) {
            LocalDateTime.now().also { departureTime = it }
        } else {
            overrideTime
        }
        val isCityToCity = origin.stopId.startsWith("city:") && destination.stopId.startsWith("city:")

        calculating = true
        routeResult = null
        expandedJourneyIndex = -1

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                routeEngine.findJourneys(
                    origin = toRouteEndpoint(origin),
                    destination = toRouteEndpoint(destination),
                    departureTime = queryTime,
                    maxTransfers = settings.maxTransfers,
                    minimumTransferMinutes = settings.minTransferMinutes.toLong(),
                    allowWalkingTransfers = settings.allowWalkingTransfers,
                    maxResults = if (isCityToCity) 10 else 5,
                    transitModePreference = settings.transitModePreference,
                    isArriveBy = (overrideMode == RouteTimeMode.ARRIVE_BY)
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
            delay(80)
            scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
        } else {
            originFocusRequester.requestFocus()
        }
    }

    // גלילה אוטומטית לשדה הפעיל בראש המסך בזמן הקלדה כדי שיוצגו תוצאות מעל המקלדת
    LaunchedEffect(
        activeField,
        originQuery,
        destinationQuery,
        selectedOrigin,
        selectedDestination,
        originStopResults,
        originAddressResults,
        destinationStopResults,
        destinationAddressResults,
        searchCardTopY,
        originSectionOffsetY,
        destSectionOffsetY
    ) {
        if (activeField == "destination" && selectedDestination == null && destinationQuery.isNotEmpty()) {
            delay(60)
            val targetY = (searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0)
            scrollState.animateScrollTo(targetY)
        } else if (activeField == "origin" && selectedOrigin == null && originQuery.isNotEmpty()) {
            delay(60)
            val targetY = (searchCardTopY + originSectionOffsetY - 8).coerceAtLeast(0)
            scrollState.animateScrollTo(targetY)
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
                    delay(80)
                    scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
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
                    delay(80)
                    scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
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

    BackHandler(enabled = pickingOnMapForField != null) {
        pickingOnMapForField = null
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

    // מסך פירוט מסלול מלא נפרד
    if (detailedJourney != null) {
        JourneyDetailsScreen(
            journey = detailedJourney!!,
            repository = repository,
            walkingRouter = walkingRouter,
            mapStorage = mapStorage,
            onBack = { detailedJourney = null }
        )
        return
    }

    if (showMapNotInstalledDialog) {
        AlertDialog(
            onDismissRequest = { showMapNotInstalledDialog = false },
            title = { Text(stringResource(R.string.ui_884984)) },
            text = {
                Text("חבילת המפה לא הותקנה עדיין במכשיר.\nתכנון הנסיעה בין תחנות וזמני היציאה פועלים גם ללא מפה.")
            },
            confirmButton = {
                TextButton(onClick = { showMapNotInstalledDialog = false }) { Text("הבנתי") }
            }
        )
    }

    val isTypingActive = (activeField == "destination" && selectedDestination == null && destinationQuery.isNotEmpty()) ||
        (activeField == "origin" && selectedOrigin == null && originQuery.isNotEmpty())

    // מסך תכנון מסלול ראשי
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // כותרת עליונה + כפתור חזרה למסך הבית
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.ui_b2c126),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    stringResource(R.string.ui_55a91f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // כרטיס שדות החיפוש המאוחד
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    searchCardTopY = coords.positionInParent().y.roundToInt()
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // כותרת מוצא + GPS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            originSectionOffsetY = coords.positionInParent().y.roundToInt()
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.ui_0b8eef), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    FilledTonalButton(
                        onClick = {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) findAndSetNearestStopFromGPS()
                            else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                        enabled = !detectingLocation,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(if (detectingLocation) "מאתר..." else "📍 המיקום שלי", fontSize = 11.sp, maxLines = 1, softWrap = false)
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
                        label = {
                            Text(
                                stringResource(R.string.ui_290c39),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            if (originQuery.isNotEmpty()) {
                                IconButton(onClick = { originQuery = ""; selectedOrigin = null }) { Text("✕") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = {
                            activeField = "destination"
                            destFocusRequester.requestFocus()
                            scope.launch {
                                delay(60)
                                scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
                            }
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

                // תוצאות חיפוש מוצא (רחובות/כתובות/ערים + תחנות)
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
                                    scope.launch {
                                        delay(60)
                                        scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
                                    }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(addr.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(addr.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    originStopResults.take(5).forEach { group ->
                        val selectStopAction: (TransitRepository.TransitStop) -> Unit = { chosenStop ->
                            selectedOrigin = chosenStop
                            originQuery = chosenStop.name
                            originStopResults = emptyList()
                            originAddressResults = emptyList()
                            activeField = "destination"
                            destFocusRequester.requestFocus()
                            scope.launch {
                                delay(60)
                                scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
                            }
                        }
                        StopSearchResultCard(
                            group = group,
                            onSelectAuto = selectStopAction,
                            onSelectSpecific = selectStopAction
                        )
                    }
                }

                // כפתור החלפת מוצא ויעד קומפקטי וצמוד בין שני השדות
                Box(
                    modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⇅", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                stringResource(R.string.ui_8844ea),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // כותרת יעד
                Text(
                    stringResource(R.string.ui_83ab34),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        destSectionOffsetY = coords.positionInParent().y.roundToInt()
                    }
                )

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
                                    scope.launch {
                                        delay(60)
                                        scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
                                    }
                                }
                            },
                        label = {
                            Text(
                                stringResource(R.string.ui_17e0c1),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
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
                                    val autoStop = dualGroupToAutoStop(firstStop)
                                    selectedDestination = autoStop
                                    destinationQuery = autoStop.name
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

                // תוצאות חיפוש יעד (רחובות/כתובות/ערים + תחנות)
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(addr.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(addr.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    destinationStopResults.take(5).forEach { group ->
                        val selectDestAction: (TransitRepository.TransitStop) -> Unit = { chosenStop ->
                            selectedDestination = chosenStop
                            destinationQuery = chosenStop.name
                            destinationStopResults = emptyList()
                            destinationAddressResults = emptyList()
                            keyboardController?.hide()
                            searchRoute()
                        }
                        StopSearchResultCard(
                            group = group,
                            onSelectAuto = selectDestAction,
                            onSelectSpecific = selectDestAction
                        )
                    }
                }
            }
        }

        // תחנות שמורות אישיות
        if (savedStops.isNotEmpty()) {
            Text(stringResource(R.string.ui_cd78bd), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
                                    activeField = "destination"
                                    destFocusRequester.requestFocus()
                                    scope.launch {
                                        delay(60)
                                        scrollState.animateScrollTo((searchCardTopY + destSectionOffsetY - 8).coerceAtLeast(0))
                                    }
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
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        DepartureDateTimePicker(
            mode = timeMode,
            value = departureTime,
            onModeChange = { newMode ->
                timeMode = newMode
                routeResult = null
            },
            onValueChange = { newValue ->
                departureTime = newValue
                if (selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId) {
                    searchRoute(overrideMode = timeMode, overrideTime = newValue)
                } else {
                    routeResult = null
                }
            },
            onRefreshNow = {
                val now = LocalDateTime.now()
                timeMode = RouteTimeMode.NOW
                departureTime = now
                if (selectedOrigin != null && selectedDestination != null && selectedOrigin?.stopId != selectedDestination?.stopId) {
                    searchRoute(overrideMode = RouteTimeMode.NOW, overrideTime = now)
                } else {
                    routeResult = null
                }
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
                    Text(stringResource(R.string.ui_30d89c), modifier = Modifier.padding(14.dp))
                }
            } else {
                Text(stringResource(R.string.ui_d78d89), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)

                if (res.arrivalDeadlineMissed) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = "לא ניתן להגיע עד לשעה המבוקשת. מוצגת הנסיעה הקרובה ביותר:",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                    }
                }

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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedJourneyIndex = if (isExpanded) -1 else idx }
                                .padding(14.dp)
                        ) {
                            // שורה עליונה: משך הנסיעה מימין ושעות יציאה ← הגעה משמאל
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.ui_c16405, journey.durationMinutes),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false
                                )

                                Column(horizontalAlignment = Alignment.End) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = journey.departureAt.format(plannerTimeFormatter),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "←",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = journey.arrivalAt.format(plannerTimeFormatter),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.ui_132113, diffMin),
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // שורת תגיות הקווים וההליכה עם גלישה אוטומטית למסכים צרים וכיוון מימין לשמאל
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically
                            ) {
                                journey.initialWalk?.let {
                                    Text("🚶", fontSize = 13.sp)
                                    Text("←", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                journey.legs.forEachIndexed { i, leg ->
                                    TransitLineBadge(lineNumber = leg.lineNumber, agencyName = leg.agencyName)
                                    if (i < journey.legs.lastIndex) {
                                        Text("←", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                journey.finalWalk?.let {
                                    Text("←", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("🚶", fontSize = 13.sp)
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
                                            stringResource(R.string.ui_be3f05, iw.walkMinutes, iw.distanceMeters.roundToInt(), iw.toStopName),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    journey.legs.forEach { leg ->
                                        Text(
                                            stringResource(R.string.ui_936a88, leg.lineNumber, leg.fromStopName, leg.toStopName),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    journey.finalWalk?.let { fw ->
                                        Text(
                                            stringResource(R.string.ui_9fc0bb, fw.walkMinutes, fw.distanceMeters.roundToInt(), fw.toStopName),
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
                                        Text(
                                            stringResource(R.string.ui_83dce9),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isTypingActive) {
            Spacer(modifier = Modifier.height(160.dp))
        }
    }
}
