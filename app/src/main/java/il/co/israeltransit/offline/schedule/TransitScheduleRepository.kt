package il.co.israeltransit.offline.schedule

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitV6Codec
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class TransitScheduleRepository(
    private val databaseProvider: TransitDatabase
) {

    data class Departure(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val stopId: String,
        val stopName: String,
        val departureSeconds: Int,
        val arrivalSeconds: Int?,
        val serviceDate: LocalDate,
        val departureAt: LocalDateTime,
        val isLastFridayTrip: Boolean = false,
        val isFirstSaturdayTrip: Boolean = false
    )

    data class LineStopStatus(
        val isActiveToday: Boolean,
        val upcomingMinutes: List<Long>
    )

    data class LineNextDepartureInfo(
        val activeToday: Boolean,
        val todayServiceEnded: Boolean,
        val nextDepartureAt: LocalDateTime?,
        val nextTripId: String?,
        val isTomorrow: Boolean,
        val nextDayOfWeek: DayOfWeek?,
        val nextRouteId: String? = null
    )

    private data class StopPatternHit(
        val patternId: Int,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val stopId: String,
        val stopName: String,
        val stopSeqZeroBased: Int,
        val tripCount: Int,
        val schedule: TransitV6Codec.DecodedSchedule
    )

    private data class RoutePatternData(
        val patternId: Int,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val stopSeqZeroBased: Int,
        val tripCount: Int,
        val schedule: TransitV6Codec.DecodedSchedule
    )

    private fun loadProfilesMap(
        db: SQLiteDatabase,
        profileIds: Set<Int>
    ): Map<Int, TransitV6Codec.DecodedProfile> {
        if (profileIds.isEmpty()) return emptyMap()
        val result = HashMap<Int, TransitV6Codec.DecodedProfile>(profileIds.size)
        val idsList = profileIds.toList()
        for (start in idsList.indices step 500) {
            val chunk = idsList.subList(start, minOf(start + 500, idsList.size))
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            db.rawQuery(
                "SELECT profile_id, stop_count, data FROM timing_profiles WHERE profile_id IN ($placeholders)",
                args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val pId = cursor.getInt(0)
                    val stopCount = cursor.getInt(1)
                    val blob = cursor.getBlob(2) ?: continue
                    result[pId] = TransitV6Codec.decodeProfile(stopCount, blob)
                }
            }
        }
        return result
    }

    private fun loadHeadsignsMap(
        db: SQLiteDatabase,
        headsignIds: Set<Int>
    ): Map<Int, String> {
        if (headsignIds.isEmpty()) return emptyMap()
        val result = HashMap<Int, String>(headsignIds.size)
        val idsList = headsignIds.toList()
        for (start in idsList.indices step 500) {
            val chunk = idsList.subList(start, minOf(start + 500, idsList.size))
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            db.rawQuery(
                "SELECT headsign_idx, text FROM headsigns WHERE headsign_idx IN ($placeholders)",
                args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getInt(0)] = cursor.getString(1) ?: ""
                }
            }
        }
        return result
    }

    private fun toServiceDaySeconds(rawSeconds: Int): Int {
        return if (rawSeconds in 0 until 14400) rawSeconds + 86400 else rawSeconds
    }

    fun currentServiceDate(now: LocalDateTime = LocalDateTime.now()): LocalDate {
        return if (now.hour < 4) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    fun upcomingDepartures(
        stopId: String,
        now: LocalDateTime = LocalDateTime.now(),
        limit: Int = 30
    ): List<Departure> {

        if (stopId.isBlank()) return emptyList()

        val db = databaseProvider.openReadOnly() ?: return emptyList()
        val results = mutableListOf<Departure>()

        try {
            val hits = mutableListOf<StopPatternHit>()
            val scheduleCache = HashMap<Int, TransitV6Codec.DecodedSchedule>()
            val neededProfiles = HashSet<Int>()

            db.rawQuery(
                """
                SELECT
                    rp.pattern_id,
                    r.route_id,
                    COALESCE(r.route_short_name, ''),
                    COALESCE(r.route_long_name, ''),
                    COALESCE(a.agency_name, ''),
                    s.stop_id,
                    COALESCE(s.stop_name, ''),
                    rps.stop_sequence,
                    ps.trip_count,
                    ps.trips_blob
                FROM stops s
                JOIN route_pattern_stops rps ON rps.stop_idx = s.stop_idx
                JOIN route_patterns rp ON rp.pattern_id = rps.pattern_id
                JOIN pattern_schedules ps ON ps.pattern_id = rp.pattern_id
                JOIN routes r ON r.route_idx = rp.route_idx
                LEFT JOIN agency a ON a.agency_id = r.agency_id
                WHERE s.stop_id = ? OR s.parent_station = ?
                """.trimIndent(),
                arrayOf(stopId, stopId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val patternId = cursor.getInt(0)
                    val tripCount = cursor.getInt(8)
                    val schedule = scheduleCache.getOrPut(patternId) {
                        val blob = cursor.getBlob(9) ?: ByteArray(0)
                        val decoded = TransitV6Codec.decodeSchedule(tripCount, blob)
                        for (pId in decoded.profileIds) {
                            neededProfiles.add(pId)
                        }
                        decoded
                    }
                    hits.add(
                        StopPatternHit(
                            patternId = patternId,
                            routeId = cursor.getString(1) ?: "",
                            lineNumber = cursor.getString(2) ?: "",
                            lineName = cursor.getString(3) ?: "",
                            agencyName = cursor.getString(4) ?: "",
                            stopId = cursor.getString(5) ?: "",
                            stopName = cursor.getString(6) ?: "",
                            stopSeqZeroBased = (cursor.getInt(7) - 1).coerceAtLeast(0),
                            tripCount = tripCount,
                            schedule = schedule
                        )
                    )
                }
            }

            if (hits.isEmpty()) return emptyList()

            val profiles = loadProfilesMap(db, neededProfiles)
            val bounds = TransitV6Codec.queryDateBounds(db)

            val today = now.toLocalDate()
            val currentSeconds = now.hour * 3600 + now.minute * 60 + now.second
            val tomorrow = today.plusDays(1)

            val serviceWindows = listOf(
                Triple(today, currentSeconds, Int.MAX_VALUE),
                Triple(today.minusDays(1), currentSeconds + 86400, Int.MAX_VALUE),
                Triple(tomorrow, 0, 14399)
            )

            val neededHeadsigns = HashSet<Int>()
            data class PendingDep(
                val hit: StopPatternHit,
                val tripIndex: Int,
                val headsignIdx: Int,
                val depSec: Int,
                val arrSec: Int,
                val serviceDate: LocalDate,
                val departureAt: LocalDateTime
            )

            val pending = mutableListOf<PendingDep>()
            val activeServicesCache = HashMap<LocalDate, Set<Int>>()

            for ((serviceDate, minimumSeconds, maximumSeconds) in serviceWindows) {
                val effectiveDate = TransitV6Codec.resolveEffectiveDate(db, serviceDate, bounds)
                val activeServices = activeServicesCache.getOrPut(effectiveDate) {
                    TransitV6Codec.loadActiveServiceIndices(db, effectiveDate)
                }
                if (activeServices.isEmpty()) continue

                for (hit in hits) {
                    val sched = hit.schedule
                    for (tripIdx in 0 until hit.tripCount) {
                        if (sched.serviceIndices[tripIdx] !in activeServices) continue
                        val profile = profiles[sched.profileIds[tripIdx]] ?: continue
                        if (hit.stopSeqZeroBased >= profile.depOffsets.size) continue

                        val startSec = sched.startSecs[tripIdx]
                        val depSec = startSec + profile.depOffsets[hit.stopSeqZeroBased]
                        if (depSec < minimumSeconds || depSec > maximumSeconds) continue

                        val departureAt = serviceDate.atStartOfDay().plusSeconds(depSec.toLong())
                        if (departureAt.isBefore(now)) continue

                        val arrSec = startSec + profile.arrOffsets[hit.stopSeqZeroBased]
                        val hIdx = sched.headsignIndices[tripIdx]
                        neededHeadsigns.add(hIdx)

                        pending.add(
                            PendingDep(
                                hit = hit,
                                tripIndex = tripIdx,
                                headsignIdx = hIdx,
                                depSec = depSec,
                                arrSec = arrSec,
                                serviceDate = serviceDate,
                                departureAt = departureAt
                            )
                        )
                    }
                }
            }

            if (pending.isEmpty()) return emptyList()

            val sortedPending = pending
                .distinctBy { "${it.hit.patternId}:${it.tripIndex}" to it.hit.stopId }
                .sortedBy { it.departureAt }
                .take(limit.coerceIn(1, 200))

            val headsigns = loadHeadsignsMap(db, sortedPending.mapTo(HashSet()) { it.headsignIdx })

            for (item in sortedPending) {
                results.add(
                    Departure(
                        tripId = "${item.hit.patternId}:${item.tripIndex}",
                        routeId = item.hit.routeId,
                        lineNumber = item.hit.lineNumber,
                        lineName = item.hit.lineName,
                        agencyName = item.hit.agencyName,
                        headsign = headsigns[item.headsignIdx] ?: "",
                        stopId = item.hit.stopId,
                        stopName = item.hit.stopName,
                        departureSeconds = item.depSec,
                        arrivalSeconds = item.arrSec,
                        serviceDate = item.serviceDate,
                        departureAt = item.departureAt
                    )
                )
            }
        } finally {
            db.close()
        }

        return results
    }

    fun nextDeparture(
        stopId: String,
        now: LocalDateTime = LocalDateTime.now()
    ): Departure? {
        return upcomingDepartures(
            stopId = stopId,
            now = now,
            limit = 1
        ).firstOrNull()
    }

    fun getLineStatusesForStop(
        stopId: String,
        now: LocalDateTime = LocalDateTime.now()
    ): Map<String, LineStopStatus> {
        if (stopId.isBlank()) return emptyMap()
        val db = databaseProvider.openReadOnly() ?: return emptyMap()

        val activeRoutes = mutableSetOf<String>()
        val timesByRoute = mutableMapOf<String, MutableList<LocalDateTime>>()

        try {
            val hits = mutableListOf<StopPatternHit>()
            val scheduleCache = HashMap<Int, TransitV6Codec.DecodedSchedule>()
            val neededProfiles = HashSet<Int>()

            db.rawQuery(
                """
                SELECT
                    rp.pattern_id,
                    r.route_id,
                    rps.stop_sequence,
                    ps.trip_count,
                    ps.trips_blob
                FROM stops s
                JOIN route_pattern_stops rps ON rps.stop_idx = s.stop_idx
                JOIN route_patterns rp ON rp.pattern_id = rps.pattern_id
                JOIN pattern_schedules ps ON ps.pattern_id = rp.pattern_id
                JOIN routes r ON r.route_idx = rp.route_idx
                WHERE s.stop_id = ? OR s.parent_station = ?
                """.trimIndent(),
                arrayOf(stopId, stopId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val patternId = cursor.getInt(0)
                    val tripCount = cursor.getInt(3)
                    val schedule = scheduleCache.getOrPut(patternId) {
                        val blob = cursor.getBlob(4) ?: ByteArray(0)
                        val decoded = TransitV6Codec.decodeSchedule(tripCount, blob)
                        for (pId in decoded.profileIds) {
                            neededProfiles.add(pId)
                        }
                        decoded
                    }
                    hits.add(
                        StopPatternHit(
                            patternId = patternId,
                            routeId = cursor.getString(1) ?: "",
                            lineNumber = "",
                            lineName = "",
                            agencyName = "",
                            stopId = "",
                            stopName = "",
                            stopSeqZeroBased = (cursor.getInt(2) - 1).coerceAtLeast(0),
                            tripCount = tripCount,
                            schedule = schedule
                        )
                    )
                }
            }

            if (hits.isEmpty()) return emptyMap()

            val profiles = loadProfilesMap(db, neededProfiles)
            val bounds = TransitV6Codec.queryDateBounds(db)

            val serviceDate = currentServiceDate(now)
            val nextDate = serviceDate.plusDays(1)
            val effectiveServiceDate = TransitV6Codec.resolveEffectiveDate(db, serviceDate, bounds)
            val activeToday = TransitV6Codec.loadActiveServiceIndices(db, effectiveServiceDate)

            val activeTomorrow = if (now.hour >= 4) {
                val effectiveNextDate = TransitV6Codec.resolveEffectiveDate(db, nextDate, bounds)
                TransitV6Codec.loadActiveServiceIndices(db, effectiveNextDate)
            } else emptySet()

            for (hit in hits) {
                val sched = hit.schedule
                for (tripIdx in 0 until hit.tripCount) {
                    val sIdx = sched.serviceIndices[tripIdx]
                    val inToday = sIdx in activeToday
                    val inTomorrow = now.hour >= 4 && sIdx in activeTomorrow
                    if (!inToday && !inTomorrow) continue

                    val profile = profiles[sched.profileIds[tripIdx]] ?: continue
                    if (hit.stopSeqZeroBased >= profile.depOffsets.size) continue

                    val rawDepSec = sched.startSecs[tripIdx] + profile.depOffsets[hit.stopSeqZeroBased]

                    if (inToday) {
                        val effectiveSec = toServiceDaySeconds(rawDepSec)
                        val depAt = serviceDate.atStartOfDay().plusSeconds(effectiveSec.toLong())
                        activeRoutes.add(hit.routeId)
                        if (!depAt.isBefore(now)) {
                            timesByRoute.getOrPut(hit.routeId) { mutableListOf() }.add(depAt)
                        }
                    }

                    if (inTomorrow && rawDepSec in 0..14399) {
                        val depAt = nextDate.atStartOfDay().plusSeconds(rawDepSec.toLong())
                        activeRoutes.add(hit.routeId)
                        if (!depAt.isBefore(now)) {
                            timesByRoute.getOrPut(hit.routeId) { mutableListOf() }.add(depAt)
                        }
                    }
                }
            }
        } finally {
            db.close()
        }

        val result = mutableMapOf<String, LineStopStatus>()
        for (routeId in activeRoutes) {
            val minutes = timesByRoute[routeId].orEmpty()
                .distinct()
                .sorted()
                .take(3)
                .map { Duration.between(now, it).toMinutes().coerceAtLeast(0) }
            result[routeId] = LineStopStatus(isActiveToday = true, upcomingMinutes = minutes)
        }
        return result
    }

    fun isServiceActive(
        serviceId: String,
        date: LocalDate
    ): Boolean {
        val serviceIdx = serviceId.toIntOrNull() ?: return false
        val db = databaseProvider.openReadOnly() ?: return false

        return try {
            val bounds = TransitV6Codec.queryDateBounds(db)
            val effectiveDate = TransitV6Codec.resolveEffectiveDate(db, date, bounds)
            val active = TransitV6Codec.loadActiveServiceIndices(db, effectiveDate)
            serviceIdx in active
        } finally {
            db.close()
        }
    }

    private fun queryRoutesDeparturesForDate(
        db: SQLiteDatabase,
        routeIds: List<String>,
        effectiveDate: LocalDate,
        displayDate: LocalDate,
        referenceStopId: String?
    ): List<Departure> {
        val cleanIds = routeIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return emptyList()

        val activeServices = TransitV6Codec.loadActiveServiceIndices(db, effectiveDate)
        if (activeServices.isEmpty()) return emptyList()

        val placeholders = cleanIds.joinToString(",") { "?" }
        val patternDataList = mutableListOf<RoutePatternData>()
        val neededProfiles = HashSet<Int>()

        val sql = if (!referenceStopId.isNullOrBlank()) {
            """
            SELECT
                rp.pattern_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                rps.stop_sequence,
                ps.trip_count,
                ps.trips_blob
            FROM routes r
            JOIN route_patterns rp ON rp.route_idx = r.route_idx
            JOIN pattern_schedules ps ON ps.pattern_id = rp.pattern_id
            JOIN route_pattern_stops rps ON rps.pattern_id = rp.pattern_id
            JOIN stops s ON s.stop_idx = rps.stop_idx
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id IN ($placeholders) AND s.stop_id = ?
            """.trimIndent()
        } else {
            """
            SELECT
                rp.pattern_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                1 AS stop_sequence,
                ps.trip_count,
                ps.trips_blob
            FROM routes r
            JOIN route_patterns rp ON rp.route_idx = r.route_idx
            JOIN pattern_schedules ps ON ps.pattern_id = rp.pattern_id
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id IN ($placeholders)
            """.trimIndent()
        }

        val args = if (!referenceStopId.isNullOrBlank()) {
            (cleanIds + referenceStopId).toTypedArray()
        } else {
            cleanIds.toTypedArray()
        }

        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val patternId = cursor.getInt(0)
                val tripCount = cursor.getInt(6)
                val blob = cursor.getBlob(7) ?: ByteArray(0)
                val decoded = TransitV6Codec.decodeSchedule(tripCount, blob)
                for (pId in decoded.profileIds) {
                    neededProfiles.add(pId)
                }
                patternDataList.add(
                    RoutePatternData(
                        patternId = patternId,
                        routeId = cursor.getString(1) ?: "",
                        lineNumber = cursor.getString(2) ?: "",
                        lineName = cursor.getString(3) ?: "",
                        agencyName = cursor.getString(4) ?: "",
                        stopSeqZeroBased = (cursor.getInt(5) - 1).coerceAtLeast(0),
                        tripCount = tripCount,
                        schedule = decoded
                    )
                )
            }
        }

        if (patternDataList.isEmpty()) return emptyList()

        val profiles = loadProfilesMap(db, neededProfiles)
        val neededHeadsigns = HashSet<Int>()
        data class PendingRouteDep(
            val pData: RoutePatternData,
            val tripIdx: Int,
            val headsignIdx: Int,
            val serviceDepSec: Int,
            val arrSec: Int
        )
        val pending = mutableListOf<PendingRouteDep>()

        for (pData in patternDataList) {
            val sched = pData.schedule
            for (tripIdx in 0 until pData.tripCount) {
                if (sched.serviceIndices[tripIdx] !in activeServices) continue
                val profile = profiles[sched.profileIds[tripIdx]] ?: continue
                if (pData.stopSeqZeroBased >= profile.depOffsets.size) continue

                val startSec = sched.startSecs[tripIdx]
                val rawDepSec = startSec + profile.depOffsets[pData.stopSeqZeroBased]
                val arrSec = startSec + profile.arrOffsets[pData.stopSeqZeroBased]
                val serviceDepSec = toServiceDaySeconds(rawDepSec)
                val hIdx = sched.headsignIndices[tripIdx]
                neededHeadsigns.add(hIdx)

                pending.add(
                    PendingRouteDep(
                        pData = pData,
                        tripIdx = tripIdx,
                        headsignIdx = hIdx,
                        serviceDepSec = serviceDepSec,
                        arrSec = arrSec
                    )
                )
            }
        }

        if (pending.isEmpty()) return emptyList()
        val headsigns = loadHeadsignsMap(db, neededHeadsigns)
        val resolvedStopId = referenceStopId ?: ""

        val results = pending.map { item ->
            Departure(
                tripId = "${item.pData.patternId}:${item.tripIdx}",
                routeId = item.pData.routeId,
                lineNumber = item.pData.lineNumber,
                lineName = item.pData.lineName,
                agencyName = item.pData.agencyName,
                headsign = headsigns[item.headsignIdx] ?: "",
                stopId = resolvedStopId,
                stopName = "",
                departureSeconds = item.serviceDepSec,
                arrivalSeconds = item.arrSec,
                serviceDate = displayDate,
                departureAt = displayDate.atStartOfDay().plusSeconds(item.serviceDepSec.toLong())
            )
        }

        return results
            .distinctBy { it.tripId }
            .sortedWith(compareBy<Departure> { it.departureSeconds }.thenBy { it.tripId })
            .distinctBy { it.departureSeconds }
    }

    private fun isDatabaseValidOnDate(db: SQLiteDatabase, date: LocalDate): Boolean {
        val dateStr = TransitV6Codec.toDateInt(date).toString()
        return try {
            db.rawQuery(
                "SELECT 1 FROM calendar WHERE start_date <= ? AND end_date >= ? LIMIT 1",
                arrayOf(dateStr, dateStr)
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    private fun findFallbackDateForDayOfWeek(
        db: SQLiteDatabase,
        routeIds: List<String>,
        dayOfWeek: DayOfWeek
    ): LocalDate? {
        val cleanIds = routeIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return null
        val placeholders = cleanIds.joinToString(",") { "?" }
        val bit = TransitV6Codec.weekdayBit(dayOfWeek)
        try {
            val endDateInt = db.rawQuery(
                """
                SELECT MAX(end_date) FROM calendar
                WHERE (weekday_mask & ?) != 0
                """.trimIndent(),
                arrayOf(bit.toString())
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            } ?: return null

            val endDate = TransitV6Codec.fromDateInt(endDateInt) ?: return null
            var candidate = endDate
            for (i in 0 until 7) {
                if (candidate.dayOfWeek == dayOfWeek) return candidate
                candidate = candidate.minusDays(1)
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun getDeparturesForDayOfWeek(
        routeId: String,
        dayOfWeek: DayOfWeek,
        referenceStopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): List<Departure> = getDeparturesForDayOfWeek(listOf(routeId), dayOfWeek, referenceStopId, now)

    fun getDeparturesForDayOfWeek(
        routeIds: List<String>,
        dayOfWeek: DayOfWeek,
        referenceStopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): List<Departure> {
        val cleanIds = routeIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return emptyList()

        val db = databaseProvider.openReadOnly() ?: return emptyList()
        return try {
            val serviceToday = currentServiceDate(now)
            val daysAhead = (dayOfWeek.value - serviceToday.dayOfWeek.value + 7) % 7
            val candidateDate = serviceToday.plusDays(daysAhead.toLong())
            val bounds = TransitV6Codec.queryDateBounds(db)

            val effectiveDate = if (isDatabaseValidOnDate(db, candidateDate)) {
                candidateDate
            } else {
                TransitV6Codec.resolveEffectiveDate(db, candidateDate, bounds)
                    .takeIf { isDatabaseValidOnDate(db, it) }
                    ?: findFallbackDateForDayOfWeek(db, cleanIds, dayOfWeek)
                    ?: candidateDate
            }

            val deps = queryRoutesDeparturesForDate(db, cleanIds, effectiveDate, effectiveDate, referenceStopId)
            deps.mapIndexed { idx, dep ->
                dep.copy(
                    isLastFridayTrip = (dayOfWeek == DayOfWeek.FRIDAY && idx == deps.lastIndex),
                    isFirstSaturdayTrip = (dayOfWeek == DayOfWeek.SATURDAY && idx == 0)
                )
            }
        } finally {
            db.close()
        }
    }

    fun getNextDepartureForLineAtStop(
        routeId: String,
        stopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): LineNextDepartureInfo = getNextDepartureForLineAtStop(listOf(routeId), stopId, now)

    fun getNextDepartureForLineAtStop(
        routeIds: List<String>,
        stopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): LineNextDepartureInfo {
        val cleanIds = routeIds.filter { it.isNotBlank() }.distinct()
        val emptyResult = LineNextDepartureInfo(
            activeToday = false,
            todayServiceEnded = false,
            nextDepartureAt = null,
            nextTripId = null,
            isTomorrow = false,
            nextDayOfWeek = null,
            nextRouteId = null
        )
        if (cleanIds.isEmpty()) return emptyResult

        val db = databaseProvider.openReadOnly() ?: return emptyResult

        try {
            val serviceToday = currentServiceDate(now)
            val bounds = TransitV6Codec.queryDateBounds(db)
            val effectiveToday = TransitV6Codec.resolveEffectiveDate(db, serviceToday, bounds)

            val todayDeps = queryRoutesDeparturesForDate(
                db = db,
                routeIds = cleanIds,
                effectiveDate = effectiveToday,
                displayDate = serviceToday,
                referenceStopId = stopId
            )

            val activeToday = todayDeps.isNotEmpty()
            val nextToday = todayDeps.firstOrNull { !it.departureAt.isBefore(now) }

            if (nextToday != null) {
                return LineNextDepartureInfo(
                    activeToday = true,
                    todayServiceEnded = false,
                    nextDepartureAt = nextToday.departureAt,
                    nextTripId = nextToday.tripId,
                    isTomorrow = false,
                    nextDayOfWeek = serviceToday.dayOfWeek,
                    nextRouteId = nextToday.routeId
                )
            }

            for (offset in 1L..7L) {
                val futureDate = serviceToday.plusDays(offset)
                val effectiveFuture = TransitV6Codec.resolveEffectiveDate(db, futureDate, bounds)
                val futureDeps = queryRoutesDeparturesForDate(
                    db = db,
                    routeIds = cleanIds,
                    effectiveDate = effectiveFuture,
                    displayDate = futureDate,
                    referenceStopId = stopId
                )

                val firstFuture = futureDeps.firstOrNull()
                if (firstFuture != null) {
                    return LineNextDepartureInfo(
                        activeToday = activeToday,
                        todayServiceEnded = activeToday,
                        nextDepartureAt = firstFuture.departureAt,
                        nextTripId = firstFuture.tripId,
                        isTomorrow = (offset == 1L),
                        nextDayOfWeek = futureDate.dayOfWeek,
                        nextRouteId = firstFuture.routeId
                    )
                }
            }

            return LineNextDepartureInfo(
                activeToday = activeToday,
                todayServiceEnded = activeToday,
                nextDepartureAt = null,
                nextTripId = null,
                isTomorrow = false,
                nextDayOfWeek = null,
                nextRouteId = null
            )
        } finally {
            db.close()
        }
    }

    fun getDailyDepartures(routeId: String, dayType: Int, referenceStopId: String? = null): List<Departure> {
        val targetDay = when (dayType) {
            1 -> DayOfWeek.FRIDAY
            2 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        return getDeparturesForDayOfWeek(listOf(routeId), targetDay, referenceStopId)
    }
}
