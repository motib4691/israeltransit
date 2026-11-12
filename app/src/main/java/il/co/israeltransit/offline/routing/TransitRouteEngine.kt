package il.co.israeltransit.offline.routing

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TransitRouteEngine(
    private val databaseProvider: TransitDatabase
) {
    companion object {
        const val MAX_ALLOWED_TRANSFERS = 5
        private const val MAX_LABELS_PER_STOP_PER_ROUND = 6
        private const val MAX_JOURNEY_MINUTES = 720L
        const val DEFAULT_MINIMUM_TRANSFER_MINUTES = 2L
        const val MAX_WALK_TRANSFER_DISTANCE_METERS = 350.0

        fun calculateDistanceMeters(
            lat1: Double?, lon1: Double?,
            lat2: Double?, lon2: Double?
        ): Double? {
            if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
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

    data class FinalWalk(
        val fromStopId: String,
        val fromStopName: String,
        val toStopId: String,
        val toStopName: String,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    data class Journey(
        val legs: List<RouteLeg>,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime,
        val durationMinutes: Long,
        val transfers: Int,
        val finalWalk: FinalWalk? = null
    )

    data class SearchResult(
        val journeys: List<Journey>,
        val error: String? = null
    )

    private data class Pattern(
        val id: Int,
        val routeId: String,
        val stops: List<String>
    )

    private data class BoardPosition(
        val patternId: Int,
        val index: Int
    )

    private data class BoardingCandidate(
        val index: Int,
        val label: Label
    )

    private data class TripStopTime(
        val stopId: String,
        val stopName: String,
        val arrivalSeconds: Int?,
        val departureSeconds: Int?,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    private data class TripRun(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val serviceDate: LocalDate,
        val stops: List<TripStopTime>
    )

    private data class RunCacheKey(
        val patternId: Int,
        val serviceDate: LocalDate
    )

    private data class Label(
        val arrivalAt: LocalDateTime,
        val previous: Label?,
        val leg: RouteLeg?
    )

    private data class NearbyStop(
        val stopId: String,
        val stopName: String,
        val distanceMeters: Double,
        val walkMinutes: Long
    )

    private data class CachedStop(
        val stopId: String,
        val name: String,
        val lat: Double,
        val lon: Double
    )

    // רשת מרחבית מהירה בזיכרון (In-Memory Spatial Index) לחיסול סריקות הדיסק
    private var stopsGrid: Map<Long, List<CachedStop>>? = null
    private var stopsById: Map<String, CachedStop>? = null
    private var cachedPatterns: Pair<Map<Int, Pattern>, Map<String, List<BoardPosition>>>? = null
    private var calendarDatesTableExists: Boolean? = null

    private fun ensureStopsLoaded(db: SQLiteDatabase) {
        if (stopsGrid != null && stopsById != null) return

        val grid = mutableMapOf<Long, MutableList<CachedStop>>()
        val byId = mutableMapOf<String, CachedStop>()

        db.rawQuery(
            """
            SELECT stop_id, COALESCE(stop_name, '') AS stop_name, stop_lat, stop_lon
            FROM stops
            WHERE stop_lat IS NOT NULL AND stop_lon IS NOT NULL
            """.trimIndent(),
            null
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow("stop_id")
            val nameCol = cursor.getColumnIndexOrThrow("stop_name")
            val latCol = cursor.getColumnIndexOrThrow("stop_lat")
            val lonCol = cursor.getColumnIndexOrThrow("stop_lon")

            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: ""
                val lat = cursor.getDouble(latCol)
                val lon = cursor.getDouble(lonCol)

                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                val stop = CachedStop(id, name, lat, lon)
                byId[id] = stop

                val key = spatialCellKey(lat, lon)
                grid.getOrPut(key) { mutableListOf() }.add(stop)
            }
        }

        stopsGrid = grid
        stopsById = byId
    }

    private fun spatialCellKey(lat: Double, lon: Double): Long {
        val latIdx = (lat * 200).toInt()  // תא של כ-500 מטר
        val lonIdx = (lon * 200).toInt()
        return (latIdx.toLong() shl 32) or (lonIdx.toLong() and 0xFFFFFFFFL)
    }

    private fun getNearbyStopsFast(
        stopId: String,
        maxDistanceMeters: Double = MAX_WALK_TRANSFER_DISTANCE_METERS
    ): List<NearbyStop> {
        val origin = stopsById?.get(stopId) ?: return emptyList()
        val grid = stopsGrid ?: return emptyList()

        val latIdx = (origin.lat * 200).toInt()
        val lonIdx = (origin.lon * 200).toInt()

        val candidates = mutableListOf<NearbyStop>()

        // סריקה מהירה בזיכרון של התא הנוכחי ו-8 התאים השכנים (ללא שום שאילתת SQL!)
        for (dLat in -1..1) {
            for (dLon in -1..1) {
                val key = ((latIdx + dLat).toLong() shl 32) or ((lonIdx + dLon).toLong() and 0xFFFFFFFFL)
                val cellStops = grid[key] ?: continue

                for (target in cellStops) {
                    if (target.stopId == stopId) continue
                    val dist = calculateDistanceMeters(
                        origin.lat, origin.lon,
                        target.lat, target.lon
                    ) ?: continue

                    if (dist <= maxDistanceMeters) {
                        candidates.add(
                            NearbyStop(
                                stopId = target.stopId,
                                stopName = target.name,
                                distanceMeters = dist,
                                walkMinutes = estimateWalkingMinutes(dist)
                            )
                        )
                    }
                }
            }
        }

        return candidates.sortedBy { it.distanceMeters }.take(6)
    }

    private fun hasCalendarDates(db: SQLiteDatabase): Boolean {
        if (calendarDatesTableExists == null) {
            calendarDatesTableExists = try {
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='calendar_dates'",
                    null
                ).use { it.moveToFirst() }
            } catch (_: Exception) {
                false
            }
        }
        return calendarDatesTableExists == true
    }

    private val weekdayColumns = listOf(
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday"
    )

    private fun weekdayColumn(date: LocalDate): String {
        return weekdayColumns[date.dayOfWeek.value - 1]
    }

    private fun dateKey(date: LocalDate): String {
        return date.toString().replace("-", "")
    }

    fun findJourneys(
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime = LocalDateTime.now(),
        maxTransfers: Int = 3,
        minimumTransferMinutes: Long = DEFAULT_MINIMUM_TRANSFER_MINUTES,
        allowWalkingTransfers: Boolean = true,
        maxResults: Int = 5
    ): SearchResult {

        if (originStopId.isBlank() || destinationStopId.isBlank()) {
            return SearchResult(emptyList(), "יש לבחור תחנת מוצא ותחנת יעד.")
        }

        if (originStopId == destinationStopId) {
            return SearchResult(emptyList(), "תחנת המוצא ותחנת היעד זהות.")
        }

        if (maxTransfers !in 0..MAX_ALLOWED_TRANSFERS) {
            return SearchResult(emptyList(), "מספר ההחלפות המבוקש אינו תקין (0 עד $MAX_ALLOWED_TRANSFERS).")
        }

        if (maxResults !in 1..20) {
            return SearchResult(emptyList(), "מספר התוצאות המבוקש אינו תקין.")
        }

        val effectiveMaxTransfers = maxTransfers.coerceIn(0, MAX_ALLOWED_TRANSFERS)
        val effectiveBufferMinutes = minimumTransferMinutes.coerceAtLeast(1L)

        val db = databaseProvider.openReadOnly()
            ?: return SearchResult(emptyList(), "לא נמצא מסד נתונים. יש לייבא תחילה את קובץ הנתונים במסך עדכון נתונים.")

        return try {
            ensureStopsLoaded(db)

            val loaded = cachedPatterns ?: loadPatterns(db).also { cachedPatterns = it }
            val patternsById = loaded.first
            val positionsByStop = loaded.second

            if (patternsById.isEmpty()) {
                return SearchResult(emptyList(), "לא נמצאו נתוני מסלולים במאגר. יש לייבא קובץ נתונים תקין.")
            }

            if (originStopId !in positionsByStop) {
                return SearchResult(emptyList(), "תחנת המוצא אינה מופיעה במסלולי התחבורה.")
            }

            if (destinationStopId !in positionsByStop) {
                return SearchResult(emptyList(), "תחנת היעד אינה מופיעה במסלולי התחבורה.")
            }

            calculateJourneys(
                db = db,
                patternsById = patternsById,
                positionsByStop = positionsByStop,
                originStopId = originStopId,
                destinationStopId = destinationStopId,
                departureTime = departureTime,
                maxTransfers = effectiveMaxTransfers,
                minimumTransferMinutes = effectiveBufferMinutes,
                allowWalkingTransfers = allowWalkingTransfers,
                maxResults = maxResults
            )
        } catch (e: Exception) {
            SearchResult(emptyList(), "אירעה שגיאה בחישוב המסלול: " + (e.message ?: "שגיאה לא ידועה"))
        } finally {
            db.close()
        }
    }

    private fun loadPatterns(
        db: SQLiteDatabase
    ): Pair<Map<Int, Pattern>, Map<String, List<BoardPosition>>> {

        val routeIds = mutableMapOf<Int, String>()
        val stopsByPattern = mutableMapOf<Int, MutableList<String>>()

        db.rawQuery(
            """
            SELECT pattern_id, route_id
            FROM route_patterns
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                routeIds[cursor.getInt(0)] = cursor.getString(1) ?: ""
            }
        }

        db.rawQuery(
            """
            SELECT pattern_id, stop_id
            FROM route_pattern_stops
            ORDER BY pattern_id, stop_sequence
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val patternId = cursor.getInt(0)
                val stopId = cursor.getString(1) ?: ""

                stopsByPattern
                    .getOrPut(patternId) { mutableListOf() }
                    .add(stopId)
            }
        }

        val patterns = mutableMapOf<Int, Pattern>()
        val positionsByStop = mutableMapOf<String, MutableList<BoardPosition>>()

        for ((patternId, stopIds) in stopsByPattern) {
            if (stopIds.size < 2) continue

            val routeId = routeIds[patternId] ?: continue

            patterns[patternId] = Pattern(
                id = patternId,
                routeId = routeId,
                stops = stopIds
            )

            stopIds.forEachIndexed { index, stopId ->
                positionsByStop
                    .getOrPut(stopId) { mutableListOf() }
                    .add(BoardPosition(patternId = patternId, index = index))
            }
        }

        return patterns to positionsByStop
    }

    private fun calculateJourneys(
        db: SQLiteDatabase,
        patternsById: Map<Int, Pattern>,
        positionsByStop: Map<String, List<BoardPosition>>,
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime,
        maxTransfers: Int,
        minimumTransferMinutes: Long,
        allowWalkingTransfers: Boolean,
        maxResults: Int
    ): SearchResult {

        var previousRound = mutableMapOf<String, MutableList<Label>>()
        val nearbyStopsCache = mutableMapOf<String, List<NearbyStop>>()

        previousRound[originStopId] = mutableListOf(
            Label(
                arrivalAt = departureTime,
                previous = null,
                leg = null
            )
        )

        val foundJourneys = mutableListOf<Journey>()
        var bestDestinationArrival: LocalDateTime? = null

        // מטמון נטול הגבלת 6 פריטים למשך החיפוש הנוכחי
        val runsCache = mutableMapOf<RunCacheKey, List<TripRun>>()

        fun labelSignature(label: Label): String {
            val parts = mutableListOf<String>()
            var current: Label? = label

            while (current != null) {
                val leg = current.leg
                if (leg != null) {
                    parts.add("${leg.tripId}@${leg.departureAt}:${leg.fromStopId}>${leg.toStopId}")
                }
                current = current.previous
            }

            return parts.asReversed().joinToString("|")
        }

        fun addLabel(
            target: MutableMap<String, MutableList<Label>>,
            stopId: String,
            candidate: Label
        ): Boolean {
            val labels = target.getOrPut(stopId) { mutableListOf() }
            val signature = labelSignature(candidate)

            if (labels.any { labelSignature(it) == signature }) {
                return false
            }

            labels.add(candidate)
            labels.sortWith(
                compareBy<Label> { it.arrivalAt }
                    .thenBy { labelSignature(it) }
            )

            if (labels.size > MAX_LABELS_PER_STOP_PER_ROUND) {
                labels.removeAt(labels.lastIndex)
            }

            return labels.any { labelSignature(it) == signature }
        }

        for (round in 1..(maxTransfers + 1)) {
            val nextRound = mutableMapOf<String, MutableList<Label>>()
            val boardPositionsByPattern = mutableMapOf<Int, MutableList<BoardingCandidate>>()

            for ((stopId, labels) in previousRound) {
                val stopsToExpand = mutableListOf<Pair<String, Long>>()
                stopsToExpand.add(stopId to 0L)

                if (round > 1 && allowWalkingTransfers) {
                    val nearby = nearbyStopsCache.getOrPut(stopId) {
                        getNearbyStopsFast(stopId)
                    }
                    for (ns in nearby) {
                        stopsToExpand.add(ns.stopId to ns.walkMinutes)
                    }
                }

                for ((targetStopId, walkMinutes) in stopsToExpand) {
                    val positions = positionsByStop[targetStopId].orEmpty()
                    for (label in labels) {
                        // גיזום: אם התווית מגיעה אחרי הגעה קיימת טובה יותר ליעד - דלג!
                        if (bestDestinationArrival != null && label.arrivalAt.isAfter(bestDestinationArrival)) {
                            continue
                        }

                        for (position in positions) {
                            val pattern = patternsById[position.patternId] ?: continue
                            if (position.index >= pattern.stops.lastIndex) continue
                            if (pattern.stops[position.index] != targetStopId) continue

                            val candidateLabel = if (walkMinutes > 0) {
                                label.copy(arrivalAt = label.arrivalAt.plusMinutes(walkMinutes))
                            } else {
                                label
                            }

                            boardPositionsByPattern
                                .getOrPut(position.patternId) { mutableListOf() }
                                .add(BoardingCandidate(index = position.index, label = candidateLabel))
                        }
                    }
                }
            }

            var changed = false

            for ((patternId, boardCandidates) in boardPositionsByPattern) {
                val pattern = patternsById[patternId] ?: continue
                val serviceDates = mutableSetOf<LocalDate>()

                for (candidate in boardCandidates) {
                    val requiredBoardingTime = if (candidate.label.leg == null) {
                        candidate.label.arrivalAt
                    } else {
                        candidate.label.arrivalAt.plusMinutes(minimumTransferMinutes)
                    }

                    serviceDates.add(requiredBoardingTime.toLocalDate())
                    serviceDates.add(requiredBoardingTime.toLocalDate().minusDays(1))
                }

                for (serviceDate in serviceDates) {
                    val cacheKey = RunCacheKey(patternId = patternId, serviceDate = serviceDate)

                    val runs = runsCache.getOrPut(cacheKey) {
                        loadPatternRuns(db, pattern, serviceDate)
                    }

                    for (run in runs) {
                        if (!matchesPattern(run, pattern)) continue

                        for (candidate in boardCandidates) {
                            val boardIndex = candidate.index
                            if (boardIndex >= run.stops.lastIndex) continue

                            val boardLabel = candidate.label
                            val boardStop = run.stops[boardIndex]

                            val earliestBoarding = if (boardLabel.leg == null) {
                                boardLabel.arrivalAt
                            } else {
                                boardLabel.arrivalAt.plusMinutes(minimumTransferMinutes)
                            }

                            val departureSeconds = boardStop.departureSeconds
                                ?: boardStop.arrivalSeconds
                                ?: continue

                            val actualDepartureAt = run.serviceDate.atStartOfDay()
                                .plusSeconds(departureSeconds.toLong())

                            if (actualDepartureAt.isBefore(earliestBoarding)) continue

                            // גיזום: נסיעה שיוצאת אחרי שכבר הגענו ליעד אינה יכולה לשפר
                            if (bestDestinationArrival != null && actualDepartureAt.isAfter(bestDestinationArrival)) {
                                continue
                            }

                            if (containsTripOccurrence(boardLabel, run.tripId, actualDepartureAt)) {
                                continue
                            }

                            for (alightIndex in (boardIndex + 1) until run.stops.size) {
                                val alightStop = run.stops[alightIndex]
                                val arrivalSeconds = alightStop.arrivalSeconds
                                    ?: alightStop.departureSeconds
                                    ?: continue

                                val arrivalAt = run.serviceDate.atStartOfDay()
                                    .plusSeconds(arrivalSeconds.toLong())

                                if (arrivalAt.isBefore(actualDepartureAt)) continue

                                // גיזום: ירידה שמאוחרת מההגעה הטובה ביותר הידועה
                                if (bestDestinationArrival != null && arrivalAt.isAfter(bestDestinationArrival)) {
                                    continue
                                }

                                val totalDuration = Duration.between(departureTime, arrivalAt).toMinutes()
                                if (totalDuration < 0 || totalDuration > MAX_JOURNEY_MINUTES) continue

                                val leg = RouteLeg(
                                    tripId = run.tripId,
                                    routeId = run.routeId,
                                    lineNumber = run.lineNumber,
                                    lineName = run.lineName,
                                    agencyName = run.agencyName,
                                    headsign = run.headsign,
                                    fromStopId = boardStop.stopId,
                                    fromStopName = boardStop.stopName,
                                    fromLat = boardStop.latitude,
                                    fromLon = boardStop.longitude,
                                    toStopId = alightStop.stopId,
                                    toStopName = alightStop.stopName,
                                    toLat = alightStop.latitude,
                                    toLon = alightStop.longitude,
                                    departureAt = actualDepartureAt,
                                    arrivalAt = arrivalAt
                                )

                                val newLabel = Label(
                                    arrivalAt = arrivalAt,
                                    previous = boardLabel,
                                    leg = leg
                                )

                                if (addLabel(nextRound, alightStop.stopId, newLabel)) {
                                    changed = true
                                }
                            }
                        }
                    }
                }
            }

            // בדיקת הגעה ליעד
            val destinationCandidates = mutableListOf<Pair<Label, FinalWalk?>>()

            for (l in nextRound[destinationStopId].orEmpty()) {
                destinationCandidates.add(l to null)
            }

            if (allowWalkingTransfers) {
                for ((stopId, labels) in nextRound) {
                    if (stopId != destinationStopId) {
                        val nearby = nearbyStopsCache.getOrPut(stopId) {
                            getNearbyStopsFast(stopId)
                        }
                        val targetMatch = nearby.firstOrNull { it.stopId == destinationStopId }
                        if (targetMatch != null) {
                            val fromStopName = labels.firstOrNull()?.leg?.toStopName ?: stopId
                            val finalWalk = FinalWalk(
                                fromStopId = stopId,
                                fromStopName = fromStopName,
                                toStopId = targetMatch.stopId,
                                toStopName = targetMatch.stopName,
                                distanceMeters = targetMatch.distanceMeters,
                                walkMinutes = targetMatch.walkMinutes
                            )
                            for (l in labels) {
                                val updatedLabel = l.copy(
                                    arrivalAt = l.arrivalAt.plusMinutes(targetMatch.walkMinutes)
                                )
                                destinationCandidates.add(updatedLabel to finalWalk)
                            }
                        }
                    }
                }
            }

            for ((destLabel, finalWalk) in destinationCandidates) {
                val journey = buildJourney(destLabel, finalWalk)
                if (journey.legs.isNotEmpty() && journey.transfers <= maxTransfers) {
                    foundJourneys.add(journey)
                    // עדכון זמן ההגעה הטוב ביותר ליעד לצורך גיזום חכם
                    if (bestDestinationArrival == null || journey.arrivalAt.isBefore(bestDestinationArrival)) {
                        bestDestinationArrival = journey.arrivalAt
                    }
                }
            }

            if (!changed) break
            previousRound = nextRound
        }

        val journeys = foundJourneys
            .distinctBy { journey ->
                val walkKey = journey.finalWalk?.let { ">${it.toStopId}" } ?: ""
                journey.legs.joinToString("|") {
                    "${it.tripId}@${it.departureAt}:${it.fromStopId}>${it.toStopId}"
                } + walkKey
            }
            .filter { it.transfers <= maxTransfers }
            .sortedWith(
                compareBy<Journey> { it.arrivalAt }
                    .thenBy { it.transfers }
                    .thenBy { it.departureAt }
            )
            .take(maxResults)

        return if (journeys.isEmpty()) {
            val message = if (maxTransfers == 0) {
                "לא נמצאו קווים ישירים בין התחנות. אפשר לאפשר החלפות במסך ההגדרות."
            } else if (!allowWalkingTransfers) {
                "לא נמצא מסלול מתאים ללא הליכה בין תחנות (עד $maxTransfers החלפות). באפשרותך לאפשר הליכה בהגדרות."
            } else {
                "לא נמצא מסלול מתאים במועד המבוקש (עד $maxTransfers החלפות)."
            }
            SearchResult(emptyList(), message)
        } else {
            SearchResult(journeys)
        }
    }

    private fun containsTripOccurrence(
        label: Label,
        tripId: String,
        departureAt: LocalDateTime
    ): Boolean {
        var current: Label? = label
        while (current != null) {
            val leg = current.leg
            if (leg != null && leg.tripId == tripId && leg.departureAt == departureAt) {
                return true
            }
            current = current.previous
        }
        return false
    }

    private fun matchesPattern(run: TripRun, pattern: Pattern): Boolean {
        if (run.routeId != pattern.routeId) return false
        if (run.stops.size != pattern.stops.size) return false
        return run.stops.indices.all { index ->
            run.stops[index].stopId == pattern.stops[index]
        }
    }

    private fun loadPatternRuns(
        db: SQLiteDatabase,
        pattern: Pattern,
        serviceDate: LocalDate
    ): List<TripRun> {

        val weekday = weekdayColumn(serviceDate)
        val date = dateKey(serviceDate)

        class RunBuilder(
            val tripId: String,
            val routeId: String,
            val lineNumber: String,
            val lineName: String,
            val agencyName: String,
            val headsign: String
        ) {
            val stops = mutableListOf<TripStopTime>()
        }

        val builders = LinkedHashMap<String, RunBuilder>()

        val sql = if (hasCalendarDates(db)) {
            """
            SELECT
                tp.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, ''),
                st.stop_id,
                COALESCE(s.stop_name, ''),
                st.arrival_time,
                st.departure_time,
                s.stop_lat,
                s.stop_lon
            FROM trip_patterns tp
            JOIN trips t ON t.trip_id = tp.trip_id
            JOIN routes r ON r.route_id = t.route_id
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            LEFT JOIN calendar c ON c.service_id = tp.service_id
            JOIN stop_times st ON st.trip_id = tp.trip_id
            LEFT JOIN stops s ON s.stop_id = st.stop_id
            WHERE tp.pattern_id = ?
              AND r.route_id = ?
              AND (
                  EXISTS (
                      SELECT 1 FROM calendar_dates cd
                      WHERE cd.service_id = tp.service_id
                        AND cd.date = ?
                        AND cd.exception_type = '1'
                  )
                  OR (
                      c.start_date <= ?
                      AND c.end_date >= ?
                      AND c.$weekday = '1'
                      AND NOT EXISTS (
                          SELECT 1 FROM calendar_dates cd
                          WHERE cd.service_id = tp.service_id
                            AND cd.date = ?
                            AND cd.exception_type = '2'
                      )
                  )
              )
            ORDER BY tp.trip_id, st.stop_sequence
            """.trimIndent()
        } else {
            """
            SELECT
                tp.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, ''),
                st.stop_id,
                COALESCE(s.stop_name, ''),
                st.arrival_time,
                st.departure_time,
                s.stop_lat,
                s.stop_lon
            FROM trip_patterns tp
            JOIN trips t ON t.trip_id = tp.trip_id
            JOIN routes r ON r.route_id = t.route_id
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            JOIN calendar c ON c.service_id = tp.service_id
            JOIN stop_times st ON st.trip_id = tp.trip_id
            LEFT JOIN stops s ON s.stop_id = st.stop_id
            WHERE tp.pattern_id = ?
              AND r.route_id = ?
              AND c.start_date <= ?
              AND c.end_date >= ?
              AND c.$weekday = '1'
            ORDER BY tp.trip_id, st.stop_sequence
            """.trimIndent()
        }

        val sqlArgs = if (hasCalendarDates(db)) {
            arrayOf(
                pattern.id.toString(),
                pattern.routeId,
                date,
                date,
                date,
                date
            )
        } else {
            arrayOf(
                pattern.id.toString(),
                pattern.routeId,
                date,
                date
            )
        }

        db.rawQuery(sql, sqlArgs).use { cursor ->
            while (cursor.moveToNext()) {
                val tripId = cursor.getString(0) ?: continue

                val builder = builders.getOrPut(tripId) {
                    RunBuilder(
                        tripId = tripId,
                        routeId = cursor.getString(1) ?: "",
                        lineNumber = cursor.getString(2) ?: "",
                        lineName = cursor.getString(3) ?: "",
                        agencyName = cursor.getString(4) ?: "",
                        headsign = cursor.getString(5) ?: ""
                    )
                }

                builder.stops.add(
                    TripStopTime(
                        stopId = cursor.getString(6) ?: "",
                        stopName = cursor.getString(7) ?: "",
                        arrivalSeconds = if (cursor.isNull(8)) null else cursor.getInt(8),
                        departureSeconds = if (cursor.isNull(9)) null else cursor.getInt(9),
                        latitude = if (cursor.isNull(10)) null else cursor.getDouble(10),
                        longitude = if (cursor.isNull(11)) null else cursor.getDouble(11)
                    )
                )
            }
        }

        return builders.values.map { builder ->
            TripRun(
                tripId = builder.tripId,
                routeId = builder.routeId,
                lineNumber = builder.lineNumber,
                lineName = builder.lineName,
                agencyName = builder.agencyName,
                headsign = builder.headsign,
                serviceDate = serviceDate,
                stops = builder.stops
            )
        }
    }

    private fun buildJourney(
        destinationLabel: Label,
        finalWalk: FinalWalk?
    ): Journey {
        val legs = mutableListOf<RouteLeg>()
        var current: Label? = destinationLabel

        while (current != null) {
            current.leg?.let { legs.add(it) }
            current = current.previous
        }

        legs.reverse()

        if (legs.isEmpty()) {
            throw IllegalStateException("לא ניתן לשחזר את פרטי המסלול.")
        }

        val first = legs.first()
        val finalArrivalAt = destinationLabel.arrivalAt
        val duration = Duration.between(first.departureAt, finalArrivalAt).toMinutes()

        return Journey(
            legs = legs,
            departureAt = first.departureAt,
            arrivalAt = finalArrivalAt,
            durationMinutes = duration,
            transfers = (legs.size - 1).coerceAtLeast(0),
            finalWalk = finalWalk
        )
    }
}
