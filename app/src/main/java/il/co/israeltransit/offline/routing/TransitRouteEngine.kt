
package il.co.israeltransit.offline.routing

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.TransitModePreference
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitV6Codec
import il.co.israeltransit.offline.ui.OperatorTheme
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TransitRouteEngine(
    private val databaseProvider: TransitDatabase,
    private val walkingRouter: GraphHopperWalkingRouter? = null
) {
    companion object {
        const val MAX_ALLOWED_TRANSFERS = 5
        private const val MAX_JOURNEY_MINUTES = 720L
        const val DEFAULT_MINIMUM_TRANSFER_MINUTES = 2L
        const val MAX_WALK_TRANSFER_DISTANCE_METERS = 450.0
        const val MAX_TRANSFER_WALK_MINUTES = 6L
        private const val WALK_URBAN_DETOUR_FACTOR = 1.25
        const val MAX_ENDPOINT_WALK_METERS = 900.0
        private const val FALLBACK_ENDPOINT_WALK_METERS = 2000.0
        private const val MAX_ENDPOINT_CANDIDATES = 10
        private const val CITY_TO_CITY_MAX_RESULTS = 10
        private const val INF_SEC = Int.MAX_VALUE / 4
        private const val NEG_INF_SEC = Int.MIN_VALUE / 4

        private val CITY_REGEX = Regex(
            "עיר:\\s*([^\\n\\r]+?)(?=\\s+(?:רציף|קומה|רחוב|שכונה|אזור):|$)"
        )

        private val CITY_ALIASES = mapOf(
            "תלאביב" to "תלאביביפו",
            "תלאביביפו" to "תלאביב",
            "נוףהגליל" to "נצרתעילית",
            "נצרתעילית" to "נוףהגליל",
            "יהוד" to "יהודמונוסון",
            "יהודמונוסון" to "יהוד",
            "בנימינה" to "בנימינהגבעתעדה",
            "בנימינהגבעתעדה" to "בנימינה",
            "קדימה" to "קדימהצורן",
            "קדימהצורן" to "קדימה"
        )

        private val networkLock = Any()

        @Volatile
        private var cachedStamp: String? = null

        @Volatile
        private var cachedNetwork: TransitNetwork? = null

        fun calculateDistanceMeters(
            lat1: Double?, lon1: Double?,
            lat2: Double?, lon2: Double?
        ): Double? {
            if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
            if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return null
            val earthRadius = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return earthRadius * c
        }

        fun estimateWalkingMinutes(distanceMeters: Double?): Long {
            if (distanceMeters == null || distanceMeters <= 0.0) return 0L
            return ceil(distanceMeters / 70.0).toLong().coerceAtLeast(1L)
        }
    }

    sealed class RouteEndpoint {
        data class Stop(
            val stopId: String,
            val name: String = ""
        ) : RouteEndpoint()

        data class StopGroup(
            val stopIds: List<String>,
            val name: String = ""
        ) : RouteEndpoint()

        data class Location(
            val name: String,
            val latitude: Double,
            val longitude: Double
        ) : RouteEndpoint()

        data class City(
            val cityName: String
        ) : RouteEndpoint()
    }

    data class RouteLeg(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val fromStopId: String,
        val fromStopName: String,
        val fromLat: Double? = null,
        val fromLon: Double? = null,
        val toStopId: String,
        val toStopName: String,
        val toLat: Double? = null,
        val toLon: Double? = null,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime
    )

    data class InitialWalk(
        val fromName: String,
        val fromLat: Double,
        val fromLon: Double,
        val toStopId: String,
        val toStopName: String,
        val toLat: Double? = null,
        val toLon: Double? = null,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    data class FinalWalk(
        val fromStopId: String,
        val fromStopName: String,
        val fromLat: Double? = null,
        val fromLon: Double? = null,
        val toStopId: String,
        val toStopName: String,
        val toLat: Double? = null,
        val toLon: Double? = null,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    data class Journey(
        val legs: List<RouteLeg>,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime,
        val durationMinutes: Long,
        val transfers: Int,
        val initialWalk: InitialWalk? = null,
        val finalWalk: FinalWalk? = null
    )

    data class SearchResult(
        val journeys: List<Journey>,
        val error: String? = null,
        val arrivalDeadlineMissed: Boolean = false
    )

    private data class NearbyStop(
        val stopIdx: Int,
        val stopId: String,
        val stopName: String,
        val lat: Double,
        val lon: Double,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    private data class TransferNeighbor(
        val stopIdx: Int,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    private class PatternData(
        val patternId: Int,
        val routeIdx: Int,
        val stopIndices: IntArray,
        val tripCount: Int,
        val startSecs: IntArray,
        val serviceIndices: IntArray,
        val profileIds: IntArray,
        val headsignIndices: IntArray
    )

    private class CalendarData(
        val weekdayMask: Int,
        val startDate: Int,
        val endDate: Int
    )

    private class CalendarException(
        val serviceIdx: Int,
        val exceptionType: Int
    )

    private class TransitNetwork(
        val stopCount: Int,
        val stopIds: Array<String>,
        val stopNames: Array<String>,
        val stopLats: DoubleArray,
        val stopLons: DoubleArray,
        val stopCityNorms: Array<String>,
        val stopIdToIdx: HashMap<String, Int>,
        val parentToChildIndices: HashMap<String, IntArray>,
        val cityNormToStopIndices: HashMap<String, IntArray>,
        val stopsGrid: HashMap<Long, IntArray>,
        val routeIds: Array<String>,
        val routeLineNumbers: Array<String>,
        val routeLineNames: Array<String>,
        val routeAgencyNames: Array<String>,
        val routeIsRail: BooleanArray,
        val headsigns: Array<String>,
        val patterns: Array<PatternData?>,
        val patternsByStop: Array<IntArray>,
        val profiles: Array<TransitV6Codec.DecodedProfile?>,
        val calendars: Array<CalendarData?>,
        val calendarExceptionsByDate: HashMap<Int, List<CalendarException>>,
        val minDateInt: Int,
        val maxDateInt: Int
    ) {
        private val transferCache = ConcurrentHashMap<Int, List<TransferNeighbor>>()
        private val activeServiceCache = ConcurrentHashMap<LocalDate, BooleanArray>()

        fun resolveCanonicalCityNorm(rawCity: String): String {
            val norm = OperatorTheme.normalizeHebrew(rawCity)
            if (norm.isEmpty()) return ""
            if (cityNormToStopIndices.containsKey(norm)) return norm
            val alias = CITY_ALIASES[norm]
            if (alias != null && cityNormToStopIndices.containsKey(alias)) return alias
            return norm
        }

        fun getCityStopIndices(cityNorm: String): IntArray {
            if (cityNorm.isEmpty()) return IntArray(0)
            cityNormToStopIndices[cityNorm]?.let { return it }
            val alias = CITY_ALIASES[cityNorm]
            if (alias != null) {
                cityNormToStopIndices[alias]?.let { return it }
            }
            return IntArray(0)
        }

        fun getTransferNeighbors(stopIdx: Int): List<TransferNeighbor> {
            return transferCache.getOrPut(stopIdx) {
                val lat = stopLats[stopIdx]
                val lon = stopLons[stopIdx]
                if (!lat.isFinite() || !lon.isFinite()) return@getOrPut emptyList()

                val latIdx = (lat * 200).toInt()
                val lonIdx = (lon * 200).toInt()
                val candidates = mutableListOf<TransferNeighbor>()

                for (dLat in -1..1) {
                    for (dLon in -1..1) {
                        val key = ((latIdx + dLat).toLong() shl 32) or ((lonIdx + dLon).toLong() and 0xFFFFFFFFL)
                        val cell = stopsGrid[key] ?: continue
                        for (targetIdx in cell) {
                            if (targetIdx == stopIdx) continue
                            if (patternsByStop[targetIdx].isEmpty()) continue
                            val airDist = calculateDistanceMeters(
                                lat, lon,
                                stopLats[targetIdx], stopLons[targetIdx]
                            ) ?: continue
                            if (airDist > MAX_WALK_TRANSFER_DISTANCE_METERS) continue

                            val walkDist = airDist * WALK_URBAN_DETOUR_FACTOR
                            val walkMins = estimateWalkingMinutes(walkDist)
                            if (walkMins <= MAX_TRANSFER_WALK_MINUTES) {
                                candidates.add(TransferNeighbor(targetIdx, walkDist, walkMins))
                            }
                        }
                    }
                }
                candidates.sortedWith(compareBy<TransferNeighbor> { it.walkMinutes }.thenBy { it.distanceMeters }).take(8)
            }
        }

        fun getActiveServicesFor(date: LocalDate): BooleanArray {
            return activeServiceCache.getOrPut(date) {
                val effectiveDate = resolveEffectiveDateInMemory(date)
                val dateInt = TransitV6Codec.toDateInt(effectiveDate)
                val bit = TransitV6Codec.weekdayBit(effectiveDate.dayOfWeek)
                val flags = BooleanArray(calendars.size)

                for (sIdx in calendars.indices) {
                    val cal = calendars[sIdx] ?: continue
                    if (cal.startDate <= dateInt && cal.endDate >= dateInt && (cal.weekdayMask and bit) != 0) {
                        flags[sIdx] = true
                    }
                }

                val exceptions = calendarExceptionsByDate[dateInt]
                if (exceptions != null) {
                    for (ex in exceptions) {
                        if (ex.serviceIdx in flags.indices) {
                            if (ex.exceptionType == 1) flags[ex.serviceIdx] = true
                            else if (ex.exceptionType == 2) flags[ex.serviceIdx] = false
                        }
                    }
                }
                flags
            }
        }

        private fun resolveEffectiveDateInMemory(targetDate: LocalDate): LocalDate {
            if (minDateInt <= 0 || maxDateInt < minDateInt) return targetDate
            val targetInt = TransitV6Codec.toDateInt(targetDate)
            if (targetInt in minDateInt..maxDateInt) return targetDate

            val minDate = TransitV6Codec.fromDateInt(minDateInt) ?: return targetDate
            val maxDate = TransitV6Codec.fromDateInt(maxDateInt) ?: return targetDate
            val anchor = if (targetInt > maxDateInt) maxDate else minDate
            val desiredDow = targetDate.dayOfWeek
            val bit = TransitV6Codec.weekdayBit(desiredDow)
            val step = if (targetInt > maxDateInt) -1L else 1L

            var base: LocalDate? = null
            var cur = anchor
            for (i in 0 until 7) {
                if (cur.dayOfWeek == desiredDow) {
                    base = cur
                    break
                }
                cur = cur.plusDays(step)
            }
            val firstMatch = base ?: return targetDate

            var bestDate = firstMatch
            var bestCount = -1
            for (weekOffset in 0L..2L) {
                val checkDate = firstMatch.plusDays(weekOffset * 7L * step)
                val checkInt = TransitV6Codec.toDateInt(checkDate)
                if (checkInt !in minDateInt..maxDateInt) break
                var activeCount = 0
                for (cal in calendars) {
                    if (cal != null && cal.startDate <= checkInt && cal.endDate >= checkInt && (cal.weekdayMask and bit) != 0) {
                        activeCount++
                    }
                }
                if (activeCount > bestCount) {
                    bestCount = activeCount
                    bestDate = checkDate
                }
            }
            return bestDate
        }
    }

    private class RaptorStep(
        val stopIdx: Int,
        val arrivalSec: Int,
        val previous: RaptorStep?,
        val patternId: Int,
        val tripIndex: Int,
        val serviceDayOffset: Int,
        val boardStopIdx: Int,
        val boardSec: Int,
        val alightSec: Int,
        val isWalkTransfer: Boolean
    )

    private class ReverseStep(
        val boardStopIdx: Int,
        val boardSec: Int,
        val alightStopIdx: Int,
        val alightSec: Int,
        val patternId: Int,
        val tripIndex: Int,
        val serviceDayOffset: Int,
        val isWalkTransfer: Boolean,
        val next: ReverseStep?
    )

    private fun getOrLoadNetwork(): TransitNetwork? {
        val stamp = databaseProvider.getDatabaseStamp()
        if (stamp.isEmpty()) return null

        val existing = cachedNetwork
        if (existing != null && cachedStamp == stamp) {
            return existing
        }

        synchronized(networkLock) {
            val currentStamp = databaseProvider.getDatabaseStamp()
            if (currentStamp.isEmpty()) return null
            val again = cachedNetwork
            if (again != null && cachedStamp == currentStamp) {
                return again
            }

            val db = databaseProvider.openReadOnly() ?: return null
            return try {
                val loaded = loadNetworkFromDatabase(db)
                cachedNetwork = loaded
                cachedStamp = currentStamp
                loaded
            } finally {
                db.close()
            }
        }
    }

    private fun extractCityFromStopDesc(desc: String): String {
        if (desc.isBlank()) return ""
        return CITY_REGEX.find(desc)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun loadNetworkFromDatabase(db: SQLiteDatabase): TransitNetwork {
        var hasParentStation = false
        var hasStopDesc = false
        try {
            db.rawQuery("PRAGMA table_info(stops)", null).use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                if (nameCol >= 0) {
                    while (cursor.moveToNext()) {
                        when (cursor.getString(nameCol)) {
                            "parent_station" -> hasParentStation = true
                            "stop_desc" -> hasStopDesc = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val maxStopIdx = queryMaxInt(db, "SELECT COALESCE(MAX(stop_idx), -1) FROM stops")
        val stopCount = (maxStopIdx + 1).coerceAtLeast(0)
        val stopIds = Array(stopCount) { "" }
        val stopNames = Array(stopCount) { "" }
        val stopLats = DoubleArray(stopCount) { Double.NaN }
        val stopLons = DoubleArray(stopCount) { Double.NaN }
        val stopCityNorms = Array(stopCount) { "" }
        val stopParentIds = Array(stopCount) { "" }
        val stopIdToIdx = HashMap<String, Int>(stopCount * 2)
        val parentLists = HashMap<String, MutableList<Int>>()
        val gridBuckets = HashMap<Long, MutableList<Int>>()

        val parentSql = if (hasParentStation) "COALESCE(parent_station, '')" else "''"
        val descSql = if (hasStopDesc) "COALESCE(stop_desc, '')" else "''"

        db.rawQuery(
            """
            SELECT stop_idx, stop_id, COALESCE(stop_name, ''), stop_lat, stop_lon, $parentSql, $descSql
            FROM stops
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val idx = cursor.getInt(0)
                if (idx !in 0 until stopCount) continue
                val sId = cursor.getString(1) ?: ""
                val sName = cursor.getString(2) ?: ""
                val lat = if (cursor.isNull(3)) Double.NaN else cursor.getDouble(3)
                val lon = if (cursor.isNull(4)) Double.NaN else cursor.getDouble(4)
                val parent = cursor.getString(5) ?: ""
                val desc = cursor.getString(6) ?: ""

                stopIds[idx] = sId
                stopNames[idx] = sName
                stopLats[idx] = lat
                stopLons[idx] = lon
                stopParentIds[idx] = parent

                val rawCity = extractCityFromStopDesc(desc)
                if (rawCity.isNotEmpty()) {
                    stopCityNorms[idx] = OperatorTheme.normalizeHebrew(rawCity)
                }

                if (sId.isNotEmpty()) stopIdToIdx[sId] = idx
                if (parent.isNotEmpty()) {
                    parentLists.getOrPut(parent) { mutableListOf() }.add(idx)
                }
                if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val cellKey = spatialCellKey(lat, lon)
                    gridBuckets.getOrPut(cellKey) { mutableListOf() }.add(idx)
                }
            }
        }

        // Inherit city from parent_station if a child platform stop has no city in stop_desc
        for (idx in 0 until stopCount) {
            if (stopCityNorms[idx].isEmpty() && stopParentIds[idx].isNotEmpty()) {
                val parentIdx = stopIdToIdx[stopParentIds[idx]]
                if (parentIdx != null && stopCityNorms[parentIdx].isNotEmpty()) {
                    stopCityNorms[idx] = stopCityNorms[parentIdx]
                }
            }
        }

        val parentToChildIndices = HashMap<String, IntArray>(parentLists.size * 2)
        for ((k, list) in parentLists) {
            parentToChildIndices[k] = list.toIntArray()
        }
        val stopsGrid = HashMap<Long, IntArray>(gridBuckets.size * 2)
        for ((k, list) in gridBuckets) {
            stopsGrid[k] = list.toIntArray()
        }

        val maxRouteIdx = queryMaxInt(db, "SELECT COALESCE(MAX(route_idx), -1) FROM routes")
        val routeCount = (maxRouteIdx + 1).coerceAtLeast(0)
        val routeIds = Array(routeCount) { "" }
        val routeLineNumbers = Array(routeCount) { "" }
        val routeLineNames = Array(routeCount) { "" }
        val routeAgencyNames = Array(routeCount) { "" }
        val routeIsRail = BooleanArray(routeCount)

        db.rawQuery(
            """
            SELECT r.route_idx, r.route_id, COALESCE(r.route_short_name, ''),
                   COALESCE(r.route_long_name, ''), COALESCE(a.agency_name, ''),
                   COALESCE(r.route_type, '3')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val idx = cursor.getInt(0)
                if (idx !in 0 until routeCount) continue
                val rId = cursor.getString(1) ?: ""
                val shortName = cursor.getString(2) ?: ""
                val longName = cursor.getString(3) ?: ""
                val agencyName = cursor.getString(4) ?: ""
                val routeType = cursor.getString(5) ?: "3"

                routeIds[idx] = rId
                routeLineNumbers[idx] = shortName
                routeLineNames[idx] = longName
                routeAgencyNames[idx] = agencyName
                routeIsRail[idx] = routeType == "2" || routeType == "0" ||
                    agencyName.contains("רכבת ישראל") ||
                    agencyName.contains("כפיר") ||
                    agencyName.contains("דנקל") ||
                    agencyName.contains("תבל")
            }
        }

        val maxHeadsignIdx = queryMaxInt(db, "SELECT COALESCE(MAX(headsign_idx), -1) FROM headsigns")
        val headsigns = Array((maxHeadsignIdx + 1).coerceAtLeast(1)) { "" }
        db.rawQuery("SELECT headsign_idx, text FROM headsigns", null).use { cursor ->
            while (cursor.moveToNext()) {
                val idx = cursor.getInt(0)
                if (idx in headsigns.indices) {
                    headsigns[idx] = cursor.getString(1) ?: ""
                }
            }
        }

        val maxServiceIdx = queryMaxInt(db, "SELECT COALESCE(MAX(service_idx), -1) FROM calendar")
        val calendars = Array<CalendarData?>((maxServiceIdx + 1).coerceAtLeast(0)) { null }
        var minDateInt = Int.MAX_VALUE
        var maxDateInt = Int.MIN_VALUE

        db.rawQuery("SELECT service_idx, weekday_mask, start_date, end_date FROM calendar", null).use { cursor ->
            while (cursor.moveToNext()) {
                val idx = cursor.getInt(0)
                val mask = cursor.getInt(1)
                val startD = cursor.getInt(2)
                val endD = cursor.getInt(3)
                if (idx in calendars.indices) {
                    calendars[idx] = CalendarData(mask, startD, endD)
                }
                if (startD in 19000101 until minDateInt) minDateInt = startD
                if (endD in (maxDateInt + 1)..29991231) maxDateInt = endD
            }
        }
        if (minDateInt == Int.MAX_VALUE) minDateInt = 0
        if (maxDateInt == Int.MIN_VALUE) maxDateInt = 0

        val calendarExceptionsByDate = HashMap<Int, MutableList<CalendarException>>()
        try {
            db.rawQuery("SELECT service_idx, date, exception_type FROM calendar_dates", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val sIdx = cursor.getInt(0)
                    val dateInt = cursor.getInt(1)
                    val exType = cursor.getInt(2)
                    calendarExceptionsByDate.getOrPut(dateInt) { mutableListOf() }
                        .add(CalendarException(sIdx, exType))
                }
            }
        } catch (_: Exception) {
        }
        val exceptionsImmutable = HashMap<Int, List<CalendarException>>(calendarExceptionsByDate.size * 2)
        for ((d, list) in calendarExceptionsByDate) {
            exceptionsImmutable[d] = list
        }

        val maxProfileId = queryMaxInt(db, "SELECT COALESCE(MAX(profile_id), -1) FROM timing_profiles")
        val profiles = Array<TransitV6Codec.DecodedProfile?>((maxProfileId + 1).coerceAtLeast(0)) { null }
        db.rawQuery("SELECT profile_id, stop_count, data FROM timing_profiles", null).use { cursor ->
            while (cursor.moveToNext()) {
                val pId = cursor.getInt(0)
                val k = cursor.getInt(1)
                val blob = cursor.getBlob(2) ?: continue
                if (pId in profiles.indices) {
                    profiles[pId] = TransitV6Codec.decodeProfile(k, blob)
                }
            }
        }

        val maxPatternId = queryMaxInt(db, "SELECT COALESCE(MAX(pattern_id), 0) FROM route_patterns")
        val patternStopLists = Array<IntArray?>(maxPatternId + 1) { null }
        val patternStopPos = IntArray(maxPatternId + 1)

        db.rawQuery(
            "SELECT pattern_id, stop_count FROM route_patterns",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val pId = cursor.getInt(0)
                val sc = cursor.getInt(1)
                if (pId in patternStopLists.indices && sc > 0) {
                    patternStopLists[pId] = IntArray(sc)
                }
            }
        }

        val stopToPatternLists = Array(stopCount) { mutableListOf<Int>() }
        db.rawQuery(
            "SELECT pattern_id, stop_idx FROM route_pattern_stops ORDER BY pattern_id, stop_sequence",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val pId = cursor.getInt(0)
                val sIdx = cursor.getInt(1)
                if (pId !in patternStopLists.indices || sIdx !in 0 until stopCount) continue
                val arr = patternStopLists[pId] ?: continue
                val pos = patternStopPos[pId]
                if (pos < arr.size) {
                    arr[pos] = sIdx
                    patternStopPos[pId] = pos + 1
                    val list = stopToPatternLists[sIdx]
                    if (list.isEmpty() || list.last() != pId) {
                        list.add(pId)
                    }
                }
            }
        }

        val patterns = Array<PatternData?>(maxPatternId + 1) { null }
        db.rawQuery(
            """
            SELECT rp.pattern_id, rp.route_idx, ps.trip_count, ps.trips_blob
            FROM route_patterns rp
            JOIN pattern_schedules ps ON ps.pattern_id = rp.pattern_id
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val pId = cursor.getInt(0)
                val rIdx = cursor.getInt(1)
                val tripCount = cursor.getInt(2)
                val blob = cursor.getBlob(3) ?: continue
                if (pId !in patterns.indices) continue
                val stopArr = patternStopLists[pId] ?: continue
                if (patternStopPos[pId] < 2) continue

                val sched = TransitV6Codec.decodeSchedule(tripCount, blob)
                patterns[pId] = PatternData(
                    patternId = pId,
                    routeIdx = rIdx,
                    stopIndices = stopArr,
                    tripCount = tripCount,
                    startSecs = sched.startSecs,
                    serviceIndices = sched.serviceIndices,
                    profileIds = sched.profileIds,
                    headsignIndices = sched.headsignIndices
                )
            }
        }

        val patternsByStop = Array(stopCount) { idx ->
            stopToPatternLists[idx].toIntArray()
        }

        val cityBuckets = HashMap<String, MutableList<Int>>()
        for (idx in 0 until stopCount) {
            if (patternsByStop[idx].isEmpty()) continue
            val cityNorm = stopCityNorms[idx]
            if (cityNorm.isNotEmpty()) {
                cityBuckets.getOrPut(cityNorm) { mutableListOf() }.add(idx)
            }
        }
        val cityNormToStopIndices = HashMap<String, IntArray>(cityBuckets.size * 2)
        for ((k, list) in cityBuckets) {
            cityNormToStopIndices[k] = list.toIntArray()
        }

        return TransitNetwork(
            stopCount = stopCount,
            stopIds = stopIds,
            stopNames = stopNames,
            stopLats = stopLats,
            stopLons = stopLons,
            stopCityNorms = stopCityNorms,
            stopIdToIdx = stopIdToIdx,
            parentToChildIndices = parentToChildIndices,
            cityNormToStopIndices = cityNormToStopIndices,
            stopsGrid = stopsGrid,
            routeIds = routeIds,
            routeLineNumbers = routeLineNumbers,
            routeLineNames = routeLineNames,
            routeAgencyNames = routeAgencyNames,
            routeIsRail = routeIsRail,
            headsigns = headsigns,
            patterns = patterns,
            patternsByStop = patternsByStop,
            profiles = profiles,
            calendars = calendars,
            calendarExceptionsByDate = exceptionsImmutable,
            minDateInt = minDateInt,
            maxDateInt = maxDateInt
        )
    }

    private fun queryMaxInt(db: SQLiteDatabase, sql: String): Int {
        return db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else -1
        }
    }

    private fun spatialCellKey(lat: Double, lon: Double): Long {
        val latIdx = (lat * 200).toInt()
        val lonIdx = (lon * 200).toInt()
        return (latIdx.toLong() shl 32) or (lonIdx.toLong() and 0xFFFFFFFFL)
    }

    private fun findStopsNearCoordinate(
        network: TransitNetwork,
        latitude: Double,
        longitude: Double,
        maxDistanceMeters: Double,
        maxCount: Int
    ): List<NearbyStop> {
        val latIdx = (latitude * 200).toInt()
        val lonIdx = (longitude * 200).toInt()
        val cellRadius = ceil(maxDistanceMeters / 450.0).toInt().coerceIn(1, 6)

        val candidates = mutableListOf<NearbyStop>()
        for (dLat in -cellRadius..cellRadius) {
            for (dLon in -cellRadius..cellRadius) {
                val key = ((latIdx + dLat).toLong() shl 32) or ((lonIdx + dLon).toLong() and 0xFFFFFFFFL)
                val cell = network.stopsGrid[key] ?: continue

                for (stopIdx in cell) {
                    if (network.patternsByStop[stopIdx].isEmpty()) continue
                    val sLat = network.stopLats[stopIdx]
                    val sLon = network.stopLons[stopIdx]
                    val dist = calculateDistanceMeters(latitude, longitude, sLat, sLon) ?: continue
                    if (dist <= maxDistanceMeters) {
                        candidates.add(
                            NearbyStop(
                                stopIdx = stopIdx,
                                stopId = network.stopIds[stopIdx],
                                stopName = network.stopNames[stopIdx],
                                lat = sLat,
                                lon = sLon,
                                distanceMeters = dist,
                                walkMinutes = estimateWalkingMinutes(dist * WALK_URBAN_DETOUR_FACTOR)
                            )
                        )
                    }
                }
            }
        }

        return candidates.sortedBy { it.distanceMeters }.take(maxCount)
    }

    private fun resolveStopIndices(
        network: TransitNetwork,
        stopIds: List<String>
    ): List<Int> {
        val result = ArrayList<Int>(stopIds.size * 2)
        val seen = HashSet<Int>()
        for (sId in stopIds) {
            if (sId.isBlank()) continue
            val directIdx = network.stopIdToIdx[sId]
            if (directIdx != null && network.patternsByStop[directIdx].isNotEmpty() && seen.add(directIdx)) {
                result.add(directIdx)
            }
            val childIndices = network.parentToChildIndices[sId]
            if (childIndices != null) {
                for (cIdx in childIndices) {
                    if (network.patternsByStop[cIdx].isNotEmpty() && seen.add(cIdx)) {
                        result.add(cIdx)
                    }
                }
            }
        }
        return result
    }

    private fun resolveEndpointCityNorm(
        network: TransitNetwork,
        endpoint: RouteEndpoint
    ): String {
        return when (endpoint) {
            is RouteEndpoint.City -> network.resolveCanonicalCityNorm(endpoint.cityName)
            is RouteEndpoint.Stop -> {
                val indices = resolveStopIndices(network, listOf(endpoint.stopId))
                indices.firstNotNullOfOrNull { idx ->
                    network.stopCityNorms[idx].ifEmpty { null }
                }?.let { network.resolveCanonicalCityNorm(it) }.orEmpty()
            }
            is RouteEndpoint.StopGroup -> {
                val indices = resolveStopIndices(network, endpoint.stopIds)
                indices.firstNotNullOfOrNull { idx ->
                    network.stopCityNorms[idx].ifEmpty { null }
                }?.let { network.resolveCanonicalCityNorm(it) }.orEmpty()
            }
            is RouteEndpoint.Location -> {
                val nearby = findStopsNearCoordinate(
                    network,
                    endpoint.latitude,
                    endpoint.longitude,
                    MAX_ENDPOINT_WALK_METERS,
                    4
                ).ifEmpty {
                    findStopsNearCoordinate(
                        network,
                        endpoint.latitude,
                        endpoint.longitude,
                        FALLBACK_ENDPOINT_WALK_METERS,
                        4
                    )
                }
                nearby.firstNotNullOfOrNull { stop ->
                    network.stopCityNorms[stop.stopIdx].ifEmpty { null }
                }?.let { network.resolveCanonicalCityNorm(it) }.orEmpty()
            }
        }
    }

    private fun measureRealWalk(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        fallbackDistanceMeters: Double
    ): Pair<Double, Long> {
        val router = walkingRouter
        if (router != null && router.isAvailable()) {
            val result = router.findRoute(fromLat, fromLon, toLat, toLon)
            if (result is WalkingRouteResult.Success) {
                val mins = ceil(result.route.estimatedSeconds / 60.0).toLong().coerceAtLeast(1L)
                return result.route.distanceMeters to mins
            }
        }
        val estimatedDist = fallbackDistanceMeters * WALK_URBAN_DETOUR_FACTOR
        return estimatedDist to estimateWalkingMinutes(estimatedDist)
    }

    fun findJourneys(
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime = LocalDateTime.now(),
        maxTransfers: Int = 3,
        minimumTransferMinutes: Long = DEFAULT_MINIMUM_TRANSFER_MINUTES,
        allowWalkingTransfers: Boolean = true,
        maxResults: Int = 5,
        transitModePreference: TransitModePreference = TransitModePreference.ALL,
        isArriveBy: Boolean = false
    ): SearchResult {
        if (originStopId.isBlank() || destinationStopId.isBlank()) {
            return SearchResult(emptyList(), "יש לבחור תחנת מוצא ותחנת יעד.")
        }

        return findJourneys(
            origin = RouteEndpoint.Stop(originStopId),
            destination = RouteEndpoint.Stop(destinationStopId),
            departureTime = departureTime,
            maxTransfers = maxTransfers,
            minimumTransferMinutes = minimumTransferMinutes,
            allowWalkingTransfers = allowWalkingTransfers,
            maxResults = maxResults,
            transitModePreference = transitModePreference,
            isArriveBy = isArriveBy
        )
    }

    fun findJourneys(
        origin: RouteEndpoint,
        destination: RouteEndpoint,
        departureTime: LocalDateTime = LocalDateTime.now(),
        maxTransfers: Int = 3,
        minimumTransferMinutes: Long = DEFAULT_MINIMUM_TRANSFER_MINUTES,
        allowWalkingTransfers: Boolean = true,
        maxResults: Int = 5,
        transitModePreference: TransitModePreference = TransitModePreference.ALL,
        isArriveBy: Boolean = false
    ): SearchResult {

        if (origin is RouteEndpoint.Stop && destination is RouteEndpoint.Stop) {
            if (origin.stopId.isBlank() || destination.stopId.isBlank()) {
                return SearchResult(emptyList(), "יש לבחור תחנת מוצא ותחנת יעד.")
            }
            if (origin.stopId == destination.stopId) {
                return SearchResult(emptyList(), "תחנת המוצא ותחנת היעד זהות.")
            }
        }

        if (origin is RouteEndpoint.Location && destination is RouteEndpoint.Location) {
            val directDist = calculateDistanceMeters(
                origin.latitude, origin.longitude,
                destination.latitude, destination.longitude
            )
            if (directDist != null && directDist < 20.0) {
                return SearchResult(emptyList(), "כתובת המוצא וכתובת היעד זהות.")
            }
        }

        if (maxTransfers !in 0..MAX_ALLOWED_TRANSFERS) {
            return SearchResult(emptyList(), "מספר ההחלפות המבוקש אינו תקין (0 עד $MAX_ALLOWED_TRANSFERS).")
        }

        if (maxResults !in 1..20) {
            return SearchResult(emptyList(), "מספר התוצאות המבוקש אינו תקין.")
        }

        val effectiveMaxTransfers = maxTransfers.coerceIn(0, MAX_ALLOWED_TRANSFERS)
        val effectiveBufferSec = (minimumTransferMinutes.coerceAtLeast(1L) * 60L).toInt()

        val network = try {
            getOrLoadNetwork()
        } catch (e: Exception) {
            return SearchResult(emptyList(), "אירעה שגיאה בטעינת נתוני המסלולים: " + (e.message ?: "שגיאה לא ידועה"))
        } ?: return SearchResult(emptyList(), "לא נמצא מסד נתונים. יש לייבא תחילה את קובץ הנתונים במסך עדכון נתונים.")

        if (network.patterns.isEmpty()) {
            return SearchResult(emptyList(), "לא נמצאו נתוני מסלולים במאגר. יש לייבא קובץ נתונים תקין.")
        }

        return try {
            // 1. Both endpoints are Cities (עיר <-> עיר): direct lines only, including lines passing through both cities, up to 10 results
            if (origin is RouteEndpoint.City && destination is RouteEndpoint.City) {
                return searchCityToCity(
                    network = network,
                    originCity = origin.cityName,
                    destinationCity = destination.cityName,
                    targetTime = departureTime,
                    maxResults = maxOf(maxResults, CITY_TO_CITY_MAX_RESULTS),
                    transitModePreference = transitModePreference,
                    isArriveBy = isArriveBy
                )
            }

            // 2. City on one side only: enforce same-city empty check & transfer restrictions
            val originIsCity = origin is RouteEndpoint.City
            val destIsCity = destination is RouteEndpoint.City

            var blockedTransferCityNorm = ""
            var allowedTransferCityNorm = ""

            if (originIsCity) {
                val cityA = network.resolveCanonicalCityNorm((origin as RouteEndpoint.City).cityName)
                val destCity = resolveEndpointCityNorm(network, destination)
                if (cityA.isNotEmpty() && cityA == destCity) {
                    return SearchResult(
                        emptyList(),
                        "התחנה שנבחרה נמצאת בתוך ${origin.cityName}. בתכנון לפי עיר יש לבחור תחנה בעיר אחרת."
                    )
                }
                blockedTransferCityNorm = cityA
                allowedTransferCityNorm = destCity
            } else if (destIsCity) {
                val cityB = network.resolveCanonicalCityNorm((destination as RouteEndpoint.City).cityName)
                val originCity = resolveEndpointCityNorm(network, origin)
                if (cityB.isNotEmpty() && cityB == originCity) {
                    return SearchResult(
                        emptyList(),
                        "התחנה שנבחרה נמצאת בתוך ${destination.cityName}. בתכנון לפי עיר יש לבחור תחנה בעיר אחרת."
                    )
                }
                blockedTransferCityNorm = cityB
                allowedTransferCityNorm = originCity
            }

            // Build origin seeds and destination targets
            val originStopIndices = when (origin) {
                is RouteEndpoint.Stop -> resolveStopIndices(network, listOf(origin.stopId))
                is RouteEndpoint.StopGroup -> resolveStopIndices(network, origin.stopIds)
                is RouteEndpoint.City -> network.getCityStopIndices(network.resolveCanonicalCityNorm(origin.cityName)).toList()
                is RouteEndpoint.Location -> emptyList()
            }

            if (origin !is RouteEndpoint.Location && originStopIndices.isEmpty()) {
                val msg = if (originIsCity) "לא נמצאו תחנות פעילות בעיר המוצא שנבחרה." else "תחנת המוצא אינה מופיעה במסלולי התחבורה."
                return SearchResult(emptyList(), msg)
            }

            val destStopIndices = when (destination) {
                is RouteEndpoint.Stop -> resolveStopIndices(network, listOf(destination.stopId))
                is RouteEndpoint.StopGroup -> resolveStopIndices(network, destination.stopIds)
                is RouteEndpoint.City -> network.getCityStopIndices(network.resolveCanonicalCityNorm(destination.cityName)).toList()
                is RouteEndpoint.Location -> emptyList()
            }

            if (destination !is RouteEndpoint.Location && destStopIndices.isEmpty()) {
                val msg = if (destIsCity) "לא נמצאו תחנות פעילות בעיר היעד שנבחרה." else "תחנת היעד אינה מופיעה במסלולי התחבורה."
                return SearchResult(emptyList(), msg)
            }

            if (origin !is RouteEndpoint.Location && destination !is RouteEndpoint.Location) {
                val originSet = originStopIndices.toHashSet()
                if (destStopIndices.any { it in originSet }) {
                    return SearchResult(emptyList(), "תחנת המוצא ותחנת היעד זהות.")
                }
            }

            val initialWalks = HashMap<Int, InitialWalk?>()
            if (origin is RouteEndpoint.Location) {
                var nearbyOrigin = findStopsNearCoordinate(
                    network,
                    origin.latitude,
                    origin.longitude,
                    MAX_ENDPOINT_WALK_METERS,
                    MAX_ENDPOINT_CANDIDATES
                )
                if (nearbyOrigin.isEmpty()) {
                    nearbyOrigin = findStopsNearCoordinate(
                        network,
                        origin.latitude,
                        origin.longitude,
                        FALLBACK_ENDPOINT_WALK_METERS,
                        6
                    )
                }
                if (nearbyOrigin.isEmpty()) {
                    return SearchResult(emptyList(), "לא נמצאו תחנות תחבורה ציבורית בקרבת כתובת המוצא.")
                }
                for (stop in nearbyOrigin) {
                    val (realDist, realMins) = measureRealWalk(
                        origin.latitude,
                        origin.longitude,
                        stop.lat,
                        stop.lon,
                        stop.distanceMeters
                    )
                    initialWalks[stop.stopIdx] = InitialWalk(
                        fromName = origin.name,
                        fromLat = origin.latitude,
                        fromLon = origin.longitude,
                        toStopId = stop.stopId,
                        toStopName = stop.stopName,
                        toLat = stop.lat,
                        toLon = stop.lon,
                        distanceMeters = realDist,
                        walkMinutes = realMins
                    )
                }
            } else {
                for (idx in originStopIndices) {
                    initialWalks[idx] = null
                }
            }

            val destinationTargets = HashMap<Int, FinalWalk?>()
            if (destination is RouteEndpoint.Location) {
                var nearbyDest = findStopsNearCoordinate(
                    network,
                    destination.latitude,
                    destination.longitude,
                    MAX_ENDPOINT_WALK_METERS,
                    MAX_ENDPOINT_CANDIDATES
                )
                if (nearbyDest.isEmpty()) {
                    nearbyDest = findStopsNearCoordinate(
                        network,
                        destination.latitude,
                        destination.longitude,
                        FALLBACK_ENDPOINT_WALK_METERS,
                        6
                    )
                }
                if (nearbyDest.isEmpty()) {
                    return SearchResult(emptyList(), "לא נמצאו תחנות תחבורה ציבורית בקרבת כתובת היעד.")
                }
                for (stop in nearbyDest) {
                    val (realDist, realMins) = measureRealWalk(
                        stop.lat,
                        stop.lon,
                        destination.latitude,
                        destination.longitude,
                        stop.distanceMeters
                    )
                    destinationTargets[stop.stopIdx] = FinalWalk(
                        fromStopId = stop.stopId,
                        fromStopName = stop.stopName,
                        fromLat = stop.lat,
                        fromLon = stop.lon,
                        toStopId = "coord:${destination.latitude},${destination.longitude}",
                        toStopName = destination.name,
                        toLat = destination.latitude,
                        toLon = destination.longitude,
                        distanceMeters = realDist,
                        walkMinutes = realMins
                    )
                }
            } else {
                for (idx in destStopIndices) {
                    destinationTargets[idx] = null
                }

                // Allow walking from nearby stops to exact Stop / StopGroup destination (never when destination is a whole City)
                if (allowWalkingTransfers && !destIsCity && destStopIndices.isNotEmpty()) {
                    val primaryDestIdx = destStopIndices.first()
                    val destLat = network.stopLats[primaryDestIdx]
                    val destLon = network.stopLons[primaryDestIdx]
                    for (targetIdx in destinationTargets.keys.toList()) {
                        for (neighbor in network.getTransferNeighbors(targetIdx)) {
                            if (neighbor.stopIdx !in destinationTargets) {
                                if (blockedTransferCityNorm.isNotEmpty() &&
                                    network.stopCityNorms[neighbor.stopIdx] == blockedTransferCityNorm
                                ) {
                                    continue
                                }
                                val fromLat = network.stopLats[neighbor.stopIdx]
                                val fromLon = network.stopLons[neighbor.stopIdx]
                                destinationTargets[neighbor.stopIdx] = FinalWalk(
                                    fromStopId = network.stopIds[neighbor.stopIdx],
                                    fromStopName = network.stopNames[neighbor.stopIdx],
                                    fromLat = if (fromLat.isFinite()) fromLat else null,
                                    fromLon = if (fromLon.isFinite()) fromLon else null,
                                    toStopId = network.stopIds[targetIdx],
                                    toStopName = network.stopNames[targetIdx],
                                    toLat = if (destLat.isFinite()) destLat else null,
                                    toLon = if (destLon.isFinite()) destLon else null,
                                    distanceMeters = neighbor.distanceMeters,
                                    walkMinutes = neighbor.walkMinutes
                                )
                            }
                        }
                    }
                }
            }

            if (isArriveBy) {
                searchArriveBy(
                    network = network,
                    targetArrivalTime = departureTime,
                    initialWalks = initialWalks,
                    destinationTargets = destinationTargets,
                    maxTransfers = effectiveMaxTransfers,
                    transferBufferSec = effectiveBufferSec,
                    allowWalkingTransfers = allowWalkingTransfers,
                    maxResults = maxResults,
                    transitModePreference = transitModePreference,
                    blockedTransferCityNorm = blockedTransferCityNorm,
                    allowedTransferCityNorm = allowedTransferCityNorm,
                    dedupeByTrip = originIsCity || destIsCity
                )
            } else {
                val baseDate = departureTime.toLocalDate()
                val baseDepSec = departureTime.toLocalTime().toSecondOfDay()
                val initialSeeds = HashMap<Int, Pair<Int, InitialWalk?>>(initialWalks.size * 2)
                for ((stopIdx, walk) in initialWalks) {
                    val readySec = baseDepSec + ((walk?.walkMinutes ?: 0L) * 60L).toInt()
                    initialSeeds[stopIdx] = readySec to walk
                }

                runMultiWaveRaptor(
                    network = network,
                    baseDate = baseDate,
                    baseDepSec = baseDepSec,
                    initialSeeds = initialSeeds,
                    destinationTargets = destinationTargets,
                    maxTransfers = effectiveMaxTransfers,
                    transferBufferSec = effectiveBufferSec,
                    allowWalkingTransfers = allowWalkingTransfers,
                    maxResults = maxResults,
                    transitModePreference = transitModePreference,
                    blockedTransferCityNorm = blockedTransferCityNorm,
                    allowedTransferCityNorm = allowedTransferCityNorm,
                    dedupeByTrip = originIsCity || destIsCity
                )
            }
        } catch (e: Exception) {
            SearchResult(emptyList(), "אירעה שגיאה בחישוב המסלול: " + (e.message ?: "שגיאה לא ידועה"))
        }
    }

    private fun searchCityToCity(
        network: TransitNetwork,
        originCity: String,
        destinationCity: String,
        targetTime: LocalDateTime,
        maxResults: Int,
        transitModePreference: TransitModePreference,
        isArriveBy: Boolean
    ): SearchResult {
        val normA = network.resolveCanonicalCityNorm(originCity)
        val normB = network.resolveCanonicalCityNorm(destinationCity)
        if (normA.isEmpty() || normB.isEmpty()) {
            return SearchResult(emptyList(), "לא ניתן לזהות את העיר שנבחרה.")
        }
        if (normA == normB) {
            return SearchResult(emptyList(), "עיר המוצא ועיר היעד זהות.")
        }

        val stopsA = network.getCityStopIndices(normA)
        val stopsB = network.getCityStopIndices(normB)
        if (stopsA.isEmpty() || stopsB.isEmpty()) {
            return SearchResult(emptyList(), "לא נמצאו תחנות תחבורה ציבורית בערים שנבחרו.")
        }

        val isStopInA = BooleanArray(network.stopCount)
        for (s in stopsA) isStopInA[s] = true
        val isStopInB = BooleanArray(network.stopCount)
        for (s in stopsB) isStopInB[s] = true

        // Fast pre-filter: collect only patterns touching City A that subsequently touch City B
        val candidatePatternIds = HashSet<Int>()
        for (s in stopsA) {
            for (pId in network.patternsByStop[s]) {
                candidatePatternIds.add(pId)
            }
        }

        data class CityPatternSpan(
            val pattern: PatternData,
            val boardSeq: Int,
            val alightSeq: Int
        )

        val busOnly = transitModePreference == TransitModePreference.BUS_ONLY
        val preferTrain = transitModePreference == TransitModePreference.PREFER_TRAIN
        val matchingPatterns = ArrayList<CityPatternSpan>()

        for (pId in candidatePatternIds) {
            val pattern = network.patterns[pId] ?: continue
            if (busOnly && network.routeIsRail[pattern.routeIdx]) continue
            val stopsInPat = pattern.stopIndices
            var firstA = -1
            var firstBAfterA = -1
            for (seq in stopsInPat.indices) {
                val sIdx = stopsInPat[seq]
                if (firstA < 0 && isStopInA[sIdx]) {
                    firstA = seq
                } else if (firstA >= 0 && isStopInB[sIdx]) {
                    firstBAfterA = seq
                    break
                }
            }
            if (firstA >= 0 && firstBAfterA > firstA) {
                matchingPatterns.add(CityPatternSpan(pattern, firstA, firstBAfterA))
            }
        }

        if (matchingPatterns.isEmpty()) {
            val msg = if (busOnly) {
                "לא נמצאו קווי אוטובוס ישירים מ$originCity אל $destinationCity."
            } else {
                "לא נמצאו קווים ישירים מ$originCity אל $destinationCity."
            }
            return SearchResult(emptyList(), msg)
        }

        fun collectDirectCityTrips(
            anchorTime: LocalDateTime,
            arriveByMode: Boolean
        ): List<Journey> {
            val baseDate = anchorTime.toLocalDate()
            val anchorSec = anchorTime.toLocalTime().toSecondOfDay()
            val minWindowSec = if (arriveByMode) anchorSec - (MAX_JOURNEY_MINUTES * 60L).toInt() else anchorSec
            val maxWindowSec = if (arriveByMode) anchorSec else anchorSec + (MAX_JOURNEY_MINUTES * 60L).toInt()

            val dayOffsets = if (arriveByMode) {
                intArrayOf(-1, 0, 1)
            } else if (anchorSec < 5 * 3600) {
                intArrayOf(-1, 0, 1)
            } else {
                intArrayOf(0, 1)
            }
            val activeByOffset = Array(dayOffsets.size) { i ->
                network.getActiveServicesFor(baseDate.plusDays(dayOffsets[i].toLong()))
            }

            val baseMidnight = baseDate.atStartOfDay()
            val candidates = ArrayList<Pair<Int, Journey>>()

            for (span in matchingPatterns) {
                val pattern = span.pattern
                val bStopIdx = pattern.stopIndices[span.boardSeq]
                val aStopIdx = pattern.stopIndices[span.alightSeq]
                val rIdx = pattern.routeIdx

                val bLat = network.stopLats[bStopIdx]
                val bLon = network.stopLons[bStopIdx]
                val aLat = network.stopLats[aStopIdx]
                val aLon = network.stopLons[aStopIdx]

                for (dIdx in dayOffsets.indices) {
                    val dayOffsetSec = dayOffsets[dIdx] * 86400
                    val activeServices = activeByOffset[dIdx]

                    for (tripIdx in 0 until pattern.tripCount) {
                        val sIdx = pattern.serviceIndices[tripIdx]
                        if (sIdx !in activeServices.indices || !activeServices[sIdx]) continue

                        val profile = network.profiles[pattern.profileIds[tripIdx]] ?: continue
                        if (span.boardSeq >= profile.depOffsets.size || span.alightSeq >= profile.arrOffsets.size) continue

                        val startWithDay = dayOffsetSec + pattern.startSecs[tripIdx]
                        val depSec = startWithDay + profile.depOffsets[span.boardSeq]
                        val arrSec = startWithDay + profile.arrOffsets[span.alightSeq]
                        if (arrSec < depSec) continue

                        if (arriveByMode) {
                            if (arrSec > maxWindowSec || arrSec < minWindowSec) continue
                        } else {
                            if (depSec < minWindowSec || depSec > maxWindowSec) continue
                        }

                        val depAt = baseMidnight.plusSeconds(depSec.toLong())
                        val arrAt = baseMidnight.plusSeconds(arrSec.toLong())
                        val duration = Duration.between(depAt, arrAt).toMinutes()
                        if (duration < 0L || duration > MAX_JOURNEY_MINUTES) continue

                        val hIdx = pattern.headsignIndices[tripIdx]
                        val headsign = if (hIdx in network.headsigns.indices) network.headsigns[hIdx] else ""

                        val leg = RouteLeg(
                            tripId = "${pattern.patternId}:$tripIdx",
                            routeId = network.routeIds[rIdx],
                            lineNumber = network.routeLineNumbers[rIdx],
                            lineName = network.routeLineNames[rIdx],
                            agencyName = network.routeAgencyNames[rIdx],
                            headsign = headsign,
                            fromStopId = network.stopIds[bStopIdx],
                            fromStopName = network.stopNames[bStopIdx],
                            fromLat = if (bLat.isFinite()) bLat else null,
                            fromLon = if (bLon.isFinite()) bLon else null,
                            toStopId = network.stopIds[aStopIdx],
                            toStopName = network.stopNames[aStopIdx],
                            toLat = if (aLat.isFinite()) aLat else null,
                            toLon = if (aLon.isFinite()) aLon else null,
                            departureAt = depAt,
                            arrivalAt = arrAt
                        )

                        candidates.add(
                            rIdx to Journey(
                                legs = listOf(leg),
                                departureAt = depAt,
                                arrivalAt = arrAt,
                                durationMinutes = duration,
                                transfers = 0
                            )
                        )
                    }
                }
            }

            if (candidates.isEmpty()) return emptyList()

            val ordered = if (arriveByMode) {
                candidates.sortedWith(
                    compareByDescending<Pair<Int, Journey>> { (_, j) ->
                        if (preferTrain && network.routeIsRail[it.first]) j.arrivalAt.plusMinutes(5) else j.arrivalAt
                    }.thenByDescending { it.second.arrivalAt }
                        .thenByDescending { it.second.departureAt }
                )
            } else {
                candidates.sortedWith(
                    compareBy<Pair<Int, Journey>>(
                        { (rIdx, j) ->
                            if (preferTrain && network.routeIsRail[rIdx]) j.departureAt.minusMinutes(10) else j.departureAt
                        },
                        { it.second.departureAt },
                        { it.second.arrivalAt }
                    )
                )
            }

            // Pick best representative per distinct route first so multiple direct lines appear,
            // then fill up to maxResults (10) with next departures
            val selected = ArrayList<Journey>(maxResults)
            val usedTripKeys = HashSet<String>()
            val usedRoutes = HashSet<Int>()

            for ((rIdx, journey) in ordered) {
                if (selected.size >= maxResults) break
                val tripKey = journey.legs.first().tripId + "@" + journey.departureAt
                if (usedRoutes.add(rIdx) && usedTripKeys.add(tripKey)) {
                    selected.add(journey)
                }
            }

            if (selected.size < maxResults) {
                for ((_, journey) in ordered) {
                    if (selected.size >= maxResults) break
                    val tripKey = journey.legs.first().tripId + "@" + journey.departureAt
                    if (usedTripKeys.add(tripKey)) {
                        selected.add(journey)
                    }
                }
            }

            return if (arriveByMode) {
                selected.sortedWith(
                    compareByDescending<Journey> { it.arrivalAt }
                        .thenByDescending { it.departureAt }
                )
            } else {
                selected.sortedWith(
                    compareBy<Journey> { it.departureAt }
                        .thenBy { it.arrivalAt }
                )
            }
        }

        if (!isArriveBy) {
            val found = collectDirectCityTrips(targetTime, arriveByMode = false)
            return if (found.isEmpty()) {
                SearchResult(emptyList(), "לא נמצאו נסיעות ישירות מ$originCity אל $destinationCity במועד המבוקש.")
            } else {
                SearchResult(found)
            }
        } else {
            val found = collectDirectCityTrips(targetTime, arriveByMode = true)
            if (found.isNotEmpty()) {
                return SearchResult(found)
            }
            val now = LocalDateTime.now()
            val fallbackAnchor = maxOf(now, targetTime.minusHours(3))
            val fallback = collectDirectCityTrips(fallbackAnchor, arriveByMode = false)
            return if (fallback.isEmpty()) {
                SearchResult(emptyList(), "לא נמצאו נסיעות ישירות מ$originCity אל $destinationCity.")
            } else {
                SearchResult(
                    journeys = fallback.take(1),
                    arrivalDeadlineMissed = true
                )
            }
        }
    }

    private fun runMultiWaveRaptor(
        network: TransitNetwork,
        baseDate: LocalDate,
        baseDepSec: Int,
        initialSeeds: Map<Int, Pair<Int, InitialWalk?>>,
        destinationTargets: Map<Int, FinalWalk?>,
        maxTransfers: Int,
        transferBufferSec: Int,
        allowWalkingTransfers: Boolean,
        maxResults: Int,
        transitModePreference: TransitModePreference,
        blockedTransferCityNorm: String = "",
        allowedTransferCityNorm: String = "",
        dedupeByTrip: Boolean = false
    ): SearchResult {
        val busOnly = transitModePreference == TransitModePreference.BUS_ONLY
        val preferTrain = transitModePreference == TransitModePreference.PREFER_TRAIN

        val dayOffsets = if (baseDepSec < 5 * 3600) {
            intArrayOf(-1, 0, 1)
        } else {
            intArrayOf(0, 1)
        }
        val activeServicesByOffset = Array(dayOffsets.size) { i ->
            network.getActiveServicesFor(baseDate.plusDays(dayOffsets[i].toLong()))
        }

        val foundJourneys = ArrayList<Journey>(maxResults * 2)
        val seenKeys = HashSet<String>()

        var waveShiftSec = 0
        val maxWaves = if (maxResults > 1) 4 else 1

        for (wave in 0 until maxWaves) {
            val waveJourneys = runSingleRaptorWave(
                network = network,
                baseDate = baseDate,
                baseDepSec = baseDepSec,
                waveShiftSec = waveShiftSec,
                dayOffsets = dayOffsets,
                activeServicesByOffset = activeServicesByOffset,
                initialSeeds = initialSeeds,
                destinationTargets = destinationTargets,
                maxTransfers = maxTransfers,
                transferBufferSec = transferBufferSec,
                allowWalkingTransfers = allowWalkingTransfers,
                busOnly = busOnly,
                blockedTransferCityNorm = blockedTransferCityNorm,
                allowedTransferCityNorm = allowedTransferCityNorm
            )

            if (waveJourneys.isEmpty()) break

            var earliestFirstLegDepSec = Int.MAX_VALUE
            var addedThisWave = 0
            for (journey in waveJourneys) {
                val firstBoardSec = Duration.between(baseDate.atStartOfDay(), journey.legs.first().departureAt).seconds.toInt()
                if (firstBoardSec < earliestFirstLegDepSec) {
                    earliestFirstLegDepSec = firstBoardSec
                }
                val key = if (dedupeByTrip) {
                    journey.legs.joinToString("|") { "${it.tripId}@${it.departureAt}" }
                } else {
                    journeyKey(journey)
                }
                if (seenKeys.add(key)) {
                    foundJourneys.add(journey)
                    addedThisWave++
                }
            }

            if (foundJourneys.size >= maxResults || earliestFirstLegDepSec == Int.MAX_VALUE) break
            val nextShift = (earliestFirstLegDepSec - baseDepSec) + 60
            if (nextShift <= waveShiftSec || addedThisWave == 0 && wave > 0) break
            waveShiftSec = nextShift
        }

        val sorted = foundJourneys.sortedWith(
            compareBy<Journey>(
                { journey ->
                    if (preferTrain && journey.legs.any { leg -> findRouteIsRail(network, leg.routeId) }) {
                        journey.arrivalAt.minusMinutes(10)
                    } else {
                        journey.arrivalAt
                    }
                },
                { it.arrivalAt },
                { it.transfers },
                { it.departureAt }
            )
        ).take(maxResults)

        return if (sorted.isEmpty()) {
            val message = when {
                busOnly -> "לא נמצא מסלול מתאים באוטובוסים בלבד במועד המבוקש."
                maxTransfers == 0 -> "לא נמצאו קווים ישירים מתאימים. אפשר לאפשר החלפות במסך ההגדרות."
                !allowWalkingTransfers -> "לא נמצא מסלול מתאים ללא הליכה בין תחנות (עד $maxTransfers החלפות). באפשרותך לאפשר הליכה בהגדרות."
                else -> "לא נמצא מסלול מתאים במועד המבוקש (עד $maxTransfers החלפות)."
            }
            SearchResult(emptyList(), message)
        } else {
            SearchResult(sorted)
        }
    }

    private fun searchArriveBy(
        network: TransitNetwork,
        targetArrivalTime: LocalDateTime,
        initialWalks: Map<Int, InitialWalk?>,
        destinationTargets: Map<Int, FinalWalk?>,
        maxTransfers: Int,
        transferBufferSec: Int,
        allowWalkingTransfers: Boolean,
        maxResults: Int,
        transitModePreference: TransitModePreference,
        blockedTransferCityNorm: String = "",
        allowedTransferCityNorm: String = "",
        dedupeByTrip: Boolean = false
    ): SearchResult {
        val busOnly = transitModePreference == TransitModePreference.BUS_ONLY
        val preferTrain = transitModePreference == TransitModePreference.PREFER_TRAIN

        val baseDate = targetArrivalTime.toLocalDate()
        val targetArrSec = targetArrivalTime.toLocalTime().toSecondOfDay()
        val dayOffsets = intArrayOf(-1, 0, 1)
        val activeServicesByOffset = Array(dayOffsets.size) { i ->
            network.getActiveServicesFor(baseDate.plusDays(dayOffsets[i].toLong()))
        }

        val foundJourneys = ArrayList<Journey>(maxResults * 2)
        val seenKeys = HashSet<String>()

        var waveShiftSec = 0
        val maxWaves = if (maxResults > 1) 4 else 1

        for (wave in 0 until maxWaves) {
            val waveJourneys = runSingleArriveByWave(
                network = network,
                baseDate = baseDate,
                targetArrSec = targetArrSec,
                waveShiftSec = waveShiftSec,
                dayOffsets = dayOffsets,
                activeServicesByOffset = activeServicesByOffset,
                initialWalks = initialWalks,
                destinationTargets = destinationTargets,
                maxTransfers = maxTransfers,
                transferBufferSec = transferBufferSec,
                allowWalkingTransfers = allowWalkingTransfers,
                busOnly = busOnly,
                blockedTransferCityNorm = blockedTransferCityNorm,
                allowedTransferCityNorm = allowedTransferCityNorm
            )

            if (waveJourneys.isEmpty()) break

            var latestLastLegArrSec = Int.MIN_VALUE
            var addedThisWave = 0
            for (journey in waveJourneys) {
                val arrSec = Duration.between(baseDate.atStartOfDay(), journey.arrivalAt).seconds.toInt()
                if (arrSec > latestLastLegArrSec) {
                    latestLastLegArrSec = arrSec
                }
                val key = if (dedupeByTrip) {
                    journey.legs.joinToString("|") { "${it.tripId}@${it.departureAt}" }
                } else {
                    journeyKey(journey)
                }
                if (seenKeys.add(key)) {
                    foundJourneys.add(journey)
                    addedThisWave++
                }
            }

            if (foundJourneys.size >= maxResults || latestLastLegArrSec == Int.MIN_VALUE) break
            val nextShift = (targetArrSec - latestLastLegArrSec) + 60
            if (nextShift <= waveShiftSec || (addedThisWave == 0 && wave > 0)) break
            waveShiftSec = nextShift
        }

        if (foundJourneys.isNotEmpty()) {
            val sorted = foundJourneys.sortedWith(
                compareByDescending<Journey> { journey ->
                    if (preferTrain && journey.legs.any { leg -> findRouteIsRail(network, leg.routeId) }) {
                        journey.arrivalAt.plusMinutes(5)
                    } else {
                        journey.arrivalAt
                    }
                }.thenByDescending { it.arrivalAt }
                    .thenBy { it.transfers }
                    .thenByDescending { it.departureAt }
            ).take(maxResults)

            return SearchResult(sorted)
        }

        // Fallback: no journey can arrive by targetArrivalTime -> find closest journey arriving after it
        val now = LocalDateTime.now()
        val fallbackStart = maxOf(now, targetArrivalTime.minusHours(3))
        val fallbackDate = fallbackStart.toLocalDate()
        val fallbackDepSec = fallbackStart.toLocalTime().toSecondOfDay()
        val fallbackSeeds = HashMap<Int, Pair<Int, InitialWalk?>>(initialWalks.size * 2)
        for ((stopIdx, walk) in initialWalks) {
            val readySec = fallbackDepSec + ((walk?.walkMinutes ?: 0L) * 60L).toInt()
            fallbackSeeds[stopIdx] = readySec to walk
        }

        val fallbackResult = runMultiWaveRaptor(
            network = network,
            baseDate = fallbackDate,
            baseDepSec = fallbackDepSec,
            initialSeeds = fallbackSeeds,
            destinationTargets = destinationTargets,
            maxTransfers = maxTransfers,
            transferBufferSec = transferBufferSec,
            allowWalkingTransfers = allowWalkingTransfers,
            maxResults = 1,
            transitModePreference = transitModePreference,
            blockedTransferCityNorm = blockedTransferCityNorm,
            allowedTransferCityNorm = allowedTransferCityNorm,
            dedupeByTrip = dedupeByTrip
        )

        return if (fallbackResult.journeys.isNotEmpty()) {
            SearchResult(
                journeys = fallbackResult.journeys.take(1),
                arrivalDeadlineMissed = true
            )
        } else {
            fallbackResult
        }
    }

    private fun isTransferStopAllowed(
        network: TransitNetwork,
        stopIdx: Int,
        blockedTransferCityNorm: String,
        allowedTransferCityNorm: String
    ): Boolean {
        val stopCity = network.stopCityNorms[stopIdx]
        if (blockedTransferCityNorm.isNotEmpty() && stopCity == blockedTransferCityNorm) {
            return false
        }
        if (allowedTransferCityNorm.isNotEmpty() && stopCity != allowedTransferCityNorm) {
            return false
        }
        return true
    }

    private fun findRouteIsRail(network: TransitNetwork, routeId: String): Boolean {
        for (i in network.routeIds.indices) {
            if (network.routeIds[i] == routeId) return network.routeIsRail[i]
        }
        return false
    }

    private fun journeyKey(journey: Journey): String {
        val initKey = journey.initialWalk?.let { "${it.toStopId}>" } ?: ""
        val walkKey = journey.finalWalk?.let { ">${it.toStopId}" } ?: ""
        return initKey + journey.legs.joinToString("|") {
            "${it.tripId}@${it.departureAt}:${it.fromStopId}>${it.toStopId}"
        } + walkKey
    }

    private fun runSingleRaptorWave(
        network: TransitNetwork,
        baseDate: LocalDate,
        baseDepSec: Int,
        waveShiftSec: Int,
        dayOffsets: IntArray,
        activeServicesByOffset: Array<BooleanArray>,
        initialSeeds: Map<Int, Pair<Int, InitialWalk?>>,
        destinationTargets: Map<Int, FinalWalk?>,
        maxTransfers: Int,
        transferBufferSec: Int,
        allowWalkingTransfers: Boolean,
        busOnly: Boolean,
        blockedTransferCityNorm: String = "",
        allowedTransferCityNorm: String = ""
    ): List<Journey> {
        val stopCount = network.stopCount
        val maxEndSec = baseDepSec + (MAX_JOURNEY_MINUTES * 60L).toInt()

        var prevArr = IntArray(stopCount) { INF_SEC }
        var prevLabels = arrayOfNulls<RaptorStep>(stopCount)
        val bestOverallArr = IntArray(stopCount) { INF_SEC }

        val initialWalksByStop = HashMap<Int, InitialWalk>()
        var markedStops = BooleanArray(stopCount)

        for ((stopIdx, seed) in initialSeeds) {
            val (readySec, initWalk) = seed
            val effectiveReady = readySec + waveShiftSec
            if (effectiveReady > maxEndSec) continue
            prevArr[stopIdx] = effectiveReady
            bestOverallArr[stopIdx] = effectiveReady
            markedStops[stopIdx] = true
            if (initWalk != null) {
                initialWalksByStop[stopIdx] = initWalk
            }
        }

        val destStopList = destinationTargets.keys.toIntArray()
        var bestDestFinalSec = INF_SEC
        val roundDestSteps = ArrayList<Pair<RaptorStep, FinalWalk?>>()

        val activePatternMark = BooleanArray(network.patterns.size)
        val activePatternsQueue = IntArray(network.patterns.size)

        for (round in 1..(maxTransfers + 1)) {
            val curArr = IntArray(stopCount) { INF_SEC }
            val curLabels = arrayOfNulls<RaptorStep>(stopCount)
            val nextMarked = BooleanArray(stopCount)

            var activePatternCount = 0
            for (sIdx in 0 until stopCount) {
                if (!markedStops[sIdx]) continue
                val pats = network.patternsByStop[sIdx]
                for (pId in pats) {
                    if (!activePatternMark[pId]) {
                        activePatternMark[pId] = true
                        activePatternsQueue[activePatternCount++] = pId
                    }
                }
            }

            if (activePatternCount == 0) break

            val globalCutoffSec = minOf(
                maxEndSec,
                if (bestDestFinalSec < INF_SEC) bestDestFinalSec + 2700 else INF_SEC
            )

            for (pIdx in 0 until activePatternCount) {
                val patternId = activePatternsQueue[pIdx]
                activePatternMark[patternId] = false
                val pattern = network.patterns[patternId] ?: continue
                if (busOnly && network.routeIsRail[pattern.routeIdx]) continue

                val stopsInPat = pattern.stopIndices
                val patLen = stopsInPat.size
                if (patLen < 2 || pattern.tripCount == 0) continue

                for (boardSeq in 0 until (patLen - 1)) {
                    val boardStopIdx = stopsInPat[boardSeq]
                    if (!markedStops[boardStopIdx]) continue

                    val prevReady = prevArr[boardStopIdx]
                    if (prevReady >= globalCutoffSec) continue
                    val prevStep = prevLabels[boardStopIdx]
                    val minBoardSec = if (round == 1) prevReady else prevReady + transferBufferSec
                    if (minBoardSec >= globalCutoffSec) continue

                    for (dIdx in dayOffsets.indices) {
                        val dayOffset = dayOffsets[dIdx]
                        val dayOffsetSec = dayOffset * 86400
                        val activeServices = activeServicesByOffset[dIdx]

                        for (tripIdx in 0 until pattern.tripCount) {
                            val startWithDay = dayOffsetSec + pattern.startSecs[tripIdx]
                            if (startWithDay > globalCutoffSec) break

                            val sIdx = pattern.serviceIndices[tripIdx]
                            if (sIdx !in activeServices.indices || !activeServices[sIdx]) continue

                            val profile = network.profiles[pattern.profileIds[tripIdx]] ?: continue
                            if (boardSeq >= profile.depOffsets.size) continue

                            val boardDepSec = startWithDay + profile.depOffsets[boardSeq]
                            if (boardDepSec < minBoardSec || boardDepSec > globalCutoffSec) continue

                            if (prevStep != null &&
                                prevStep.patternId == patternId &&
                                prevStep.tripIndex == tripIdx &&
                                prevStep.serviceDayOffset == dayOffset
                            ) {
                                continue
                            }

                            for (alightSeq in (boardSeq + 1) until patLen) {
                                if (alightSeq >= profile.arrOffsets.size) break
                                val arrSec = startWithDay + profile.arrOffsets[alightSeq]
                                if (arrSec < boardDepSec) continue
                                if (arrSec > globalCutoffSec) break

                                val alightStopIdx = stopsInPat[alightSeq]
                                val existingCutoff = minOf(bestOverallArr[alightStopIdx], curArr[alightStopIdx])
                                if (arrSec < existingCutoff) {
                                    curArr[alightStopIdx] = arrSec
                                    bestOverallArr[alightStopIdx] = arrSec
                                    nextMarked[alightStopIdx] = true
                                    curLabels[alightStopIdx] = RaptorStep(
                                        stopIdx = alightStopIdx,
                                        arrivalSec = arrSec,
                                        previous = prevStep,
                                        patternId = patternId,
                                        tripIndex = tripIdx,
                                        serviceDayOffset = dayOffset,
                                        boardStopIdx = boardStopIdx,
                                        boardSec = boardDepSec,
                                        alightSec = arrSec,
                                        isWalkTransfer = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            var improvedAny = false
            for (dStopIdx in destStopList) {
                val arrAtStop = curArr[dStopIdx]
                val label = curLabels[dStopIdx]
                if (arrAtStop < INF_SEC && label != null) {
                    val fWalk = destinationTargets[dStopIdx]
                    val finalArrival = arrAtStop + ((fWalk?.walkMinutes ?: 0L) * 60L).toInt()
                    if (finalArrival <= maxEndSec) {
                        roundDestSteps.add(label to fWalk)
                        if (finalArrival < bestDestFinalSec) {
                            bestDestFinalSec = finalArrival
                        }
                    }
                }
            }

            for (i in 0 until stopCount) {
                if (nextMarked[i]) {
                    improvedAny = true
                    break
                }
            }
            if (!improvedAny) break
            if (round == maxTransfers + 1) break

            // Filter marked stops for next round based on city transfer restrictions
            val hasCityRestriction = blockedTransferCityNorm.isNotEmpty() || allowedTransferCityNorm.isNotEmpty()
            val nextRoundArr = IntArray(stopCount) { INF_SEC }
            val nextRoundLabels = arrayOfNulls<RaptorStep>(stopCount)
            val nextRoundMarked = BooleanArray(stopCount)

            for (sIdx in 0 until stopCount) {
                if (!nextMarked[sIdx]) continue
                if (hasCityRestriction && !isTransferStopAllowed(network, sIdx, blockedTransferCityNorm, allowedTransferCityNorm)) {
                    continue
                }
                nextRoundArr[sIdx] = curArr[sIdx]
                nextRoundLabels[sIdx] = curLabels[sIdx]
                nextRoundMarked[sIdx] = true
            }

            if (allowWalkingTransfers) {
                for (sIdx in 0 until stopCount) {
                    if (!nextRoundMarked[sIdx]) continue
                    val arrAtS = curArr[sIdx]
                    val stepAtS = curLabels[sIdx] ?: continue
                    if (arrAtS >= bestDestFinalSec + 2700) continue

                    for (neighbor in network.getTransferNeighbors(sIdx)) {
                        if (hasCityRestriction && !isTransferStopAllowed(network, neighbor.stopIdx, blockedTransferCityNorm, allowedTransferCityNorm)) {
                            continue
                        }
                        val walkArr = arrAtS + (neighbor.walkMinutes * 60L).toInt()
                        if (walkArr < maxEndSec && walkArr < nextRoundArr[neighbor.stopIdx] && walkArr < bestOverallArr[neighbor.stopIdx]) {
                            nextRoundArr[neighbor.stopIdx] = walkArr
                            bestOverallArr[neighbor.stopIdx] = walkArr
                            nextRoundMarked[neighbor.stopIdx] = true
                            nextRoundLabels[neighbor.stopIdx] = RaptorStep(
                                stopIdx = neighbor.stopIdx,
                                arrivalSec = walkArr,
                                previous = stepAtS,
                                patternId = 0,
                                tripIndex = -1,
                                serviceDayOffset = 0,
                                boardStopIdx = sIdx,
                                boardSec = arrAtS,
                                alightSec = walkArr,
                                isWalkTransfer = true
                            )
                        }
                    }
                }
            }

            var anyTransferAllowed = false
            for (i in 0 until stopCount) {
                if (nextRoundMarked[i]) {
                    anyTransferAllowed = true
                    break
                }
            }
            if (!anyTransferAllowed) break

            prevArr = nextRoundArr
            prevLabels = nextRoundLabels
            markedStops = nextRoundMarked
        }

        if (roundDestSteps.isEmpty()) return emptyList()

        return roundDestSteps.mapNotNull { (step, finalWalk) ->
            reconstructJourney(network, baseDate, step, initialWalksByStop, finalWalk)
        }
    }

    private fun runSingleArriveByWave(
        network: TransitNetwork,
        baseDate: LocalDate,
        targetArrSec: Int,
        waveShiftSec: Int,
        dayOffsets: IntArray,
        activeServicesByOffset: Array<BooleanArray>,
        initialWalks: Map<Int, InitialWalk?>,
        destinationTargets: Map<Int, FinalWalk?>,
        maxTransfers: Int,
        transferBufferSec: Int,
        allowWalkingTransfers: Boolean,
        busOnly: Boolean,
        blockedTransferCityNorm: String = "",
        allowedTransferCityNorm: String = ""
    ): List<Journey> {
        val stopCount = network.stopCount
        val effectiveTargetSec = targetArrSec - waveShiftSec
        val minStartSec = effectiveTargetSec - (MAX_JOURNEY_MINUTES * 60L).toInt()

        var prevMaxArr = IntArray(stopCount) { NEG_INF_SEC }
        var prevLabels = arrayOfNulls<ReverseStep>(stopCount)
        val bestOverallDep = IntArray(stopCount) { NEG_INF_SEC }
        var markedStops = BooleanArray(stopCount)

        for ((stopIdx, finalWalk) in destinationTargets) {
            val latestArrAtStop = effectiveTargetSec - ((finalWalk?.walkMinutes ?: 0L) * 60L).toInt()
            if (latestArrAtStop < minStartSec) continue
            prevMaxArr[stopIdx] = latestArrAtStop
            bestOverallDep[stopIdx] = latestArrAtStop
            markedStops[stopIdx] = true
        }

        val originStopList = initialWalks.keys.toIntArray()
        var bestOriginDepSec = NEG_INF_SEC
        val roundOriginSteps = ArrayList<Pair<ReverseStep, InitialWalk?>>()

        val activePatternMark = BooleanArray(network.patterns.size)
        val activePatternsQueue = IntArray(network.patterns.size)

        for (round in 1..(maxTransfers + 1)) {
            val curMaxDep = IntArray(stopCount) { NEG_INF_SEC }
            val curLabels = arrayOfNulls<ReverseStep>(stopCount)
            val nextMarked = BooleanArray(stopCount)

            var activePatternCount = 0
            for (sIdx in 0 until stopCount) {
                if (!markedStops[sIdx]) continue
                val pats = network.patternsByStop[sIdx]
                for (pId in pats) {
                    if (!activePatternMark[pId]) {
                        activePatternMark[pId] = true
                        activePatternsQueue[activePatternCount++] = pId
                    }
                }
            }

            if (activePatternCount == 0) break

            val globalFloorSec = maxOf(
                minStartSec,
                if (bestOriginDepSec > NEG_INF_SEC) bestOriginDepSec - 2700 else NEG_INF_SEC
            )

            for (pIdx in 0 until activePatternCount) {
                val patternId = activePatternsQueue[pIdx]
                activePatternMark[patternId] = false
                val pattern = network.patterns[patternId] ?: continue
                if (busOnly && network.routeIsRail[pattern.routeIdx]) continue

                val stopsInPat = pattern.stopIndices
                val patLen = stopsInPat.size
                if (patLen < 2 || pattern.tripCount == 0) continue

                for (alightSeq in (patLen - 1) downTo 1) {
                    val alightStopIdx = stopsInPat[alightSeq]
                    if (!markedStops[alightStopIdx]) continue

                    val prevBound = prevMaxArr[alightStopIdx]
                    if (prevBound <= globalFloorSec) continue
                    val nextStep = prevLabels[alightStopIdx]
                    val maxAlightArrSec = if (round == 1) prevBound else prevBound - transferBufferSec
                    if (maxAlightArrSec <= globalFloorSec) continue

                    for (dIdx in dayOffsets.indices) {
                        val dayOffset = dayOffsets[dIdx]
                        val dayOffsetSec = dayOffset * 86400
                        val activeServices = activeServicesByOffset[dIdx]

                        for (tripIdx in (pattern.tripCount - 1) downTo 0) {
                            val startWithDay = dayOffsetSec + pattern.startSecs[tripIdx]
                            if (startWithDay > maxAlightArrSec) continue

                            val sIdx = pattern.serviceIndices[tripIdx]
                            if (sIdx !in activeServices.indices || !activeServices[sIdx]) continue

                            val profile = network.profiles[pattern.profileIds[tripIdx]] ?: continue
                            if (alightSeq >= profile.arrOffsets.size) continue

                            val arrSec = startWithDay + profile.arrOffsets[alightSeq]
                            if (arrSec > maxAlightArrSec || arrSec < globalFloorSec) continue

                            if (nextStep != null &&
                                nextStep.patternId == patternId &&
                                nextStep.tripIndex == tripIdx &&
                                nextStep.serviceDayOffset == dayOffset
                            ) {
                                continue
                            }

                            for (boardSeq in (alightSeq - 1) downTo 0) {
                                if (boardSeq >= profile.depOffsets.size) continue
                                val depSec = startWithDay + profile.depOffsets[boardSeq]
                                if (depSec > arrSec) continue
                                if (depSec < globalFloorSec) break

                                val boardStopIdx = stopsInPat[boardSeq]
                                val existingFloor = maxOf(bestOverallDep[boardStopIdx], curMaxDep[boardStopIdx])
                                if (depSec > existingFloor) {
                                    curMaxDep[boardStopIdx] = depSec
                                    bestOverallDep[boardStopIdx] = depSec
                                    nextMarked[boardStopIdx] = true
                                    curLabels[boardStopIdx] = ReverseStep(
                                        boardStopIdx = boardStopIdx,
                                        boardSec = depSec,
                                        alightStopIdx = alightStopIdx,
                                        alightSec = arrSec,
                                        patternId = patternId,
                                        tripIndex = tripIdx,
                                        serviceDayOffset = dayOffset,
                                        isWalkTransfer = false,
                                        next = nextStep
                                    )
                                }
                            }
                        }
                    }
                }
            }

            var improvedAny = false
            for (oStopIdx in originStopList) {
                val depAtOrigin = curMaxDep[oStopIdx]
                val label = curLabels[oStopIdx]
                if (depAtOrigin > NEG_INF_SEC && label != null) {
                    val iWalk = initialWalks[oStopIdx]
                    val finalDep = depAtOrigin - ((iWalk?.walkMinutes ?: 0L) * 60L).toInt()
                    if (finalDep >= minStartSec) {
                        roundOriginSteps.add(label to iWalk)
                        if (finalDep > bestOriginDepSec) {
                            bestOriginDepSec = finalDep
                        }
                    }
                }
            }

            for (i in 0 until stopCount) {
                if (nextMarked[i]) {
                    improvedAny = true
                    break
                }
            }
            if (!improvedAny) break
            if (round == maxTransfers + 1) break

            val hasCityRestriction = blockedTransferCityNorm.isNotEmpty() || allowedTransferCityNorm.isNotEmpty()
            val nextRoundMaxDep = IntArray(stopCount) { NEG_INF_SEC }
            val nextRoundLabels = arrayOfNulls<ReverseStep>(stopCount)
            val nextRoundMarked = BooleanArray(stopCount)

            for (sIdx in 0 until stopCount) {
                if (!nextMarked[sIdx]) continue
                if (hasCityRestriction && !isTransferStopAllowed(network, sIdx, blockedTransferCityNorm, allowedTransferCityNorm)) {
                    continue
                }
                nextRoundMaxDep[sIdx] = curMaxDep[sIdx]
                nextRoundLabels[sIdx] = curLabels[sIdx]
                nextRoundMarked[sIdx] = true
            }

            if (allowWalkingTransfers) {
                for (sIdx in 0 until stopCount) {
                    if (!nextRoundMarked[sIdx]) continue
                    val depAtS = curMaxDep[sIdx]
                    val stepAtS = curLabels[sIdx] ?: continue
                    if (depAtS <= bestOriginDepSec - 2700) continue

                    for (neighbor in network.getTransferNeighbors(sIdx)) {
                        if (hasCityRestriction && !isTransferStopAllowed(network, neighbor.stopIdx, blockedTransferCityNorm, allowedTransferCityNorm)) {
                            continue
                        }
                        val walkDep = depAtS - (neighbor.walkMinutes * 60L).toInt()
                        if (walkDep > minStartSec && walkDep > nextRoundMaxDep[neighbor.stopIdx] && walkDep > bestOverallDep[neighbor.stopIdx]) {
                            nextRoundMaxDep[neighbor.stopIdx] = walkDep
                            bestOverallDep[neighbor.stopIdx] = walkDep
                            nextRoundMarked[neighbor.stopIdx] = true
                            nextRoundLabels[neighbor.stopIdx] = ReverseStep(
                                boardStopIdx = neighbor.stopIdx,
                                boardSec = walkDep,
                                alightStopIdx = sIdx,
                                alightSec = depAtS,
                                patternId = 0,
                                tripIndex = -1,
                                serviceDayOffset = 0,
                                isWalkTransfer = true,
                                next = stepAtS
                            )
                        }
                    }
                }
            }

            var anyTransferAllowed = false
            for (i in 0 until stopCount) {
                if (nextRoundMarked[i]) {
                    anyTransferAllowed = true
                    break
                }
            }
            if (!anyTransferAllowed) break

            prevMaxArr = nextRoundMaxDep
            prevLabels = nextRoundLabels
            markedStops = nextRoundMarked
        }

        if (roundOriginSteps.isEmpty()) return emptyList()

        return roundOriginSteps.mapNotNull { (step, initialWalk) ->
            reconstructArriveByJourney(network, baseDate, step, initialWalk, destinationTargets)
        }
    }

    private fun reconstructJourney(
        network: TransitNetwork,
        baseDate: LocalDate,
        destStep: RaptorStep,
        initialWalksByStop: Map<Int, InitialWalk>,
        finalWalk: FinalWalk?
    ): Journey? {
        val transitSteps = ArrayList<RaptorStep>(6)
        var cur: RaptorStep? = destStep
        while (cur != null) {
            if (!cur.isWalkTransfer && cur.patternId > 0) {
                transitSteps.add(cur)
            }
            cur = cur.previous
        }
        if (transitSteps.isEmpty()) return null
        transitSteps.reverse()

        val baseMidnight = baseDate.atStartOfDay()
        val legs = ArrayList<RouteLeg>(transitSteps.size)

        for (step in transitSteps) {
            val pattern = network.patterns[step.patternId] ?: return null
            val rIdx = pattern.routeIdx
            val hIdx = pattern.headsignIndices[step.tripIndex]
            val headsign = if (hIdx in network.headsigns.indices) network.headsigns[hIdx] else ""
            val fromLat = network.stopLats[step.boardStopIdx]
            val fromLon = network.stopLons[step.boardStopIdx]
            val toLat = network.stopLats[step.stopIdx]
            val toLon = network.stopLons[step.stopIdx]

            legs.add(
                RouteLeg(
                    tripId = "${step.patternId}:${step.tripIndex}",
                    routeId = network.routeIds[rIdx],
                    lineNumber = network.routeLineNumbers[rIdx],
                    lineName = network.routeLineNames[rIdx],
                    agencyName = network.routeAgencyNames[rIdx],
                    headsign = headsign,
                    fromStopId = network.stopIds[step.boardStopIdx],
                    fromStopName = network.stopNames[step.boardStopIdx],
                    fromLat = if (fromLat.isFinite()) fromLat else null,
                    fromLon = if (fromLon.isFinite()) fromLon else null,
                    toStopId = network.stopIds[step.stopIdx],
                    toStopName = network.stopNames[step.stopIdx],
                    toLat = if (toLat.isFinite()) toLat else null,
                    toLon = if (toLon.isFinite()) toLon else null,
                    departureAt = baseMidnight.plusSeconds(step.boardSec.toLong()),
                    arrivalAt = baseMidnight.plusSeconds(step.alightSec.toLong())
                )
            )
        }

        val firstBoardIdx = transitSteps.first().boardStopIdx
        val initialWalk = initialWalksByStop[firstBoardIdx]
        val firstLeg = legs.first()
        val lastLeg = legs.last()

        val journeyDepartureAt = if (initialWalk != null) {
            firstLeg.departureAt.minusMinutes(initialWalk.walkMinutes)
        } else {
            firstLeg.departureAt
        }
        val finalArrivalAt = if (finalWalk != null) {
            lastLeg.arrivalAt.plusMinutes(finalWalk.walkMinutes)
        } else {
            lastLeg.arrivalAt
        }
        val duration = Duration.between(journeyDepartureAt, finalArrivalAt).toMinutes()
        if (duration < 0L || duration > MAX_JOURNEY_MINUTES) return null

        return Journey(
            legs = legs,
            departureAt = journeyDepartureAt,
            arrivalAt = finalArrivalAt,
            durationMinutes = duration,
            transfers = (legs.size - 1).coerceAtLeast(0),
            initialWalk = initialWalk,
            finalWalk = finalWalk
        )
    }

    private fun reconstructArriveByJourney(
        network: TransitNetwork,
        baseDate: LocalDate,
        originStep: ReverseStep,
        initialWalk: InitialWalk?,
        destinationTargets: Map<Int, FinalWalk?>
    ): Journey? {
        val transitSteps = ArrayList<ReverseStep>(6)
        var cur: ReverseStep? = originStep
        while (cur != null) {
            if (!cur.isWalkTransfer && cur.patternId > 0) {
                transitSteps.add(cur)
            }
            cur = cur.next
        }
        if (transitSteps.isEmpty()) return null

        val baseMidnight = baseDate.atStartOfDay()
        val legs = ArrayList<RouteLeg>(transitSteps.size)

        for (step in transitSteps) {
            val pattern = network.patterns[step.patternId] ?: return null
            val rIdx = pattern.routeIdx
            val hIdx = pattern.headsignIndices[step.tripIndex]
            val headsign = if (hIdx in network.headsigns.indices) network.headsigns[hIdx] else ""
            val fromLat = network.stopLats[step.boardStopIdx]
            val fromLon = network.stopLons[step.boardStopIdx]
            val toLat = network.stopLons[step.alightStopIdx]
            val toLatActual = network.stopLats[step.alightStopIdx]

            legs.add(
                RouteLeg(
                    tripId = "${step.patternId}:${step.tripIndex}",
                    routeId = network.routeIds[rIdx],
                    lineNumber = network.routeLineNumbers[rIdx],
                    lineName = network.routeLineNames[rIdx],
                    agencyName = network.routeAgencyNames[rIdx],
                    headsign = headsign,
                    fromStopId = network.stopIds[step.boardStopIdx],
                    fromStopName = network.stopNames[step.boardStopIdx],
                    fromLat = if (fromLat.isFinite()) fromLat else null,
                    fromLon = if (fromLon.isFinite()) fromLon else null,
                    toStopId = network.stopIds[step.alightStopIdx],
                    toStopName = network.stopNames[step.alightStopIdx],
                    toLat = if (toLatActual.isFinite()) toLatActual else null,
                    toLon = if (toLat.isFinite()) toLat else null,
                    departureAt = baseMidnight.plusSeconds(step.boardSec.toLong()),
                    arrivalAt = baseMidnight.plusSeconds(step.alightSec.toLong())
                )
            )
        }

        val lastAlightIdx = transitSteps.last().alightStopIdx
        val finalWalk = destinationTargets[lastAlightIdx]
        val firstLeg = legs.first()
        val lastLeg = legs.last()

        val journeyDepartureAt = if (initialWalk != null) {
            firstLeg.departureAt.minusMinutes(initialWalk.walkMinutes)
        } else {
            firstLeg.departureAt
        }
        val finalArrivalAt = if (finalWalk != null) {
            lastLeg.arrivalAt.plusMinutes(finalWalk.walkMinutes)
        } else {
            lastLeg.arrivalAt
        }
        val duration = Duration.between(journeyDepartureAt, finalArrivalAt).toMinutes()
        if (duration < 0L || duration > MAX_JOURNEY_MINUTES) return null

        return Journey(
            legs = legs,
            departureAt = journeyDepartureAt,
            arrivalAt = finalArrivalAt,
            durationMinutes = duration,
            transfers = (legs.size - 1).coerceAtLeast(0),
            initialWalk = initialWalk,
            finalWalk = finalWalk
        )
    }
}
