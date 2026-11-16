package il.co.israeltransit.offline.schedule

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
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
        val nextDayOfWeek: DayOfWeek?
    )

    private fun hasCalendarDates(db: SQLiteDatabase): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='calendar_dates'",
                null
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasParentStationColumn(db: SQLiteDatabase): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info(stops)", null).use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                if (nameCol >= 0) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameCol) == "parent_station") return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun weekdayColumn(date: LocalDate): String = weekdayColumn(date.dayOfWeek)

    private fun weekdayColumn(dayOfWeek: DayOfWeek): String {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY -> "monday"
            DayOfWeek.TUESDAY -> "tuesday"
            DayOfWeek.WEDNESDAY -> "wednesday"
            DayOfWeek.THURSDAY -> "thursday"
            DayOfWeek.FRIDAY -> "friday"
            DayOfWeek.SATURDAY -> "saturday"
            DayOfWeek.SUNDAY -> "sunday"
        }
    }

    private fun dateKey(date: LocalDate): String {
        return date.toString().replace("-", "")
    }

    private fun parseDateKey(key: String?): LocalDate? {
        if (key.isNullOrBlank() || key.length != 8) return null
        return try {
            LocalDate.of(
                key.substring(0, 4).toInt(),
                key.substring(4, 6).toInt(),
                key.substring(6, 8).toInt()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * ממיר שעות שלאחר חצות (00:00 עד 03:59) לסוף יום השירות (אחרי 23:59)
     */
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
            val today = now.toLocalDate()
            val currentSeconds = now.hour * 3600 + now.minute * 60 + now.second
            val tomorrow = today.plusDays(1)

            val serviceWindows = listOf(
                Triple(today, currentSeconds, Int.MAX_VALUE),
                Triple(today.minusDays(1), currentSeconds + 86400, Int.MAX_VALUE),
                Triple(tomorrow, 0, 14399)
            )

            val calendarDatesAvailable = hasCalendarDates(db)
            val hasParentStation = hasParentStationColumn(db)
            val stopCondition = if (hasParentStation) {
                "(st.stop_id = ? OR st.stop_id IN (SELECT stop_id FROM stops WHERE parent_station = ?))"
            } else {
                "st.stop_id = ?"
            }

            for ((serviceDate, minimumSeconds, maximumSeconds) in serviceWindows) {
                val weekday = weekdayColumn(serviceDate)
                val date = dateKey(serviceDate)

                val sql = if (calendarDatesAvailable) {
                    """
                    SELECT
                        t.trip_id,
                        r.route_id,
                        COALESCE(r.route_short_name, ''),
                        COALESCE(r.route_long_name, ''),
                        COALESCE(a.agency_name, ''),
                        COALESCE(t.trip_headsign, ''),
                        s.stop_id,
                        COALESCE(s.stop_name, ''),
                        st.departure_time,
                        st.arrival_time
                    FROM stop_times st
                    JOIN trips t ON t.trip_id = st.trip_id
                    JOIN routes r ON r.route_id = t.route_id
                    LEFT JOIN agency a ON a.agency_id = r.agency_id
                    JOIN stops s ON s.stop_id = st.stop_id
                    LEFT JOIN calendar c ON c.service_id = t.service_id
                    WHERE
                        $stopCondition
                        AND st.departure_time >= ?
                        AND st.departure_time <= ?
                        AND (
                            EXISTS (
                                SELECT 1 FROM calendar_dates cd
                                WHERE cd.service_id = t.service_id
                                  AND cd.date = ?
                                  AND cd.exception_type = '1'
                            )
                            OR (
                                c.start_date <= ?
                                AND c.end_date >= ?
                                AND c.$weekday = '1'
                                AND NOT EXISTS (
                                    SELECT 1 FROM calendar_dates cd
                                    WHERE cd.service_id = t.service_id
                                      AND cd.date = ?
                                      AND cd.exception_type = '2'
                                )
                            )
                        )
                    ORDER BY st.departure_time ASC
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    SELECT
                        t.trip_id,
                        r.route_id,
                        COALESCE(r.route_short_name, ''),
                        COALESCE(r.route_long_name, ''),
                        COALESCE(a.agency_name, ''),
                        COALESCE(t.trip_headsign, ''),
                        s.stop_id,
                        COALESCE(s.stop_name, ''),
                        st.departure_time,
                        st.arrival_time
                    FROM stop_times st
                    JOIN trips t ON t.trip_id = st.trip_id
                    JOIN routes r ON r.route_id = t.route_id
                    LEFT JOIN agency a ON a.agency_id = r.agency_id
                    JOIN stops s ON s.stop_id = st.stop_id
                    JOIN calendar c ON c.service_id = t.service_id
                    WHERE
                        $stopCondition
                        AND st.departure_time >= ?
                        AND st.departure_time <= ?
                        AND c.start_date <= ?
                        AND c.end_date >= ?
                        AND c.$weekday = '1'
                    ORDER BY st.departure_time ASC
                    LIMIT ?
                    """.trimIndent()
                }

                val stopArgs = if (hasParentStation) listOf(stopId, stopId) else listOf(stopId)
                val timeArgs = listOf(minimumSeconds.toString(), maximumSeconds.toString())
                val calArgs = if (calendarDatesAvailable) {
                    listOf(date, date, date, date, limit.coerceIn(1, 200).toString())
                } else {
                    listOf(date, date, limit.coerceIn(1, 200).toString())
                }

                val args = (stopArgs + timeArgs + calArgs).toTypedArray()

                db.rawQuery(sql, args).use { cursor ->
                    while (cursor.moveToNext()) {
                        val departureSeconds = cursor.getInt(8)
                        val departureAt = serviceDate.atStartOfDay().plusSeconds(departureSeconds.toLong())

                        results.add(
                            Departure(
                                tripId = cursor.getString(0) ?: "",
                                routeId = cursor.getString(1) ?: "",
                                lineNumber = cursor.getString(2) ?: "",
                                lineName = cursor.getString(3) ?: "",
                                agencyName = cursor.getString(4) ?: "",
                                headsign = cursor.getString(5) ?: "",
                                stopId = cursor.getString(6) ?: "",
                                stopName = cursor.getString(7) ?: "",
                                departureSeconds = departureSeconds,
                                arrivalSeconds = if (cursor.isNull(9)) null else cursor.getInt(9),
                                serviceDate = serviceDate,
                                departureAt = departureAt
                            )
                        )
                    }
                }
            }

        } finally {
            db.close()
        }

        return results
            .filter { !it.departureAt.isBefore(now) }
            .distinctBy { it.tripId to it.stopId }
            .sortedBy { it.departureAt }
            .take(limit.coerceIn(1, 200))
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

    /**
     * מחזיר לכל קו שעובר בתחנה האם הוא פעיל ביום השירות הנוכחי,
     * ואת זמני ההגעה הקרובים שלו (בדקות מעכשיו) בלי להיות מוגבל ל-10 היציאות הראשונות בתחנה.
     */
    fun getLineStatusesForStop(
        stopId: String,
        now: LocalDateTime = LocalDateTime.now()
    ): Map<String, LineStopStatus> {
        if (stopId.isBlank()) return emptyMap()
        val db = databaseProvider.openReadOnly() ?: return emptyMap()

        val activeRoutes = mutableSetOf<String>()
        val timesByRoute = mutableMapOf<String, MutableList<LocalDateTime>>()

        try {
            val serviceDate = currentServiceDate(now)
            val nextDate = serviceDate.plusDays(1)
            val calendarDatesAvailable = hasCalendarDates(db)
            val hasParentStation = hasParentStationColumn(db)
            val stopCondition = if (hasParentStation) {
                "(st.stop_id = ? OR st.stop_id IN (SELECT stop_id FROM stops WHERE parent_station = ?))"
            } else {
                "st.stop_id = ?"
            }

            fun queryWindow(dateObj: LocalDate, minSec: Int, maxSec: Int, treatBelow4AsNextDay: Boolean) {
                val weekday = weekdayColumn(dateObj)
                val dateStr = dateKey(dateObj)

                val sql = if (calendarDatesAvailable) {
                    """
                    SELECT t.route_id, st.departure_time
                    FROM stop_times st
                    JOIN trips t ON t.trip_id = st.trip_id
                    LEFT JOIN calendar c ON c.service_id = t.service_id
                    WHERE $stopCondition
                      AND st.departure_time >= ?
                      AND st.departure_time <= ?
                      AND (
                          EXISTS (
                              SELECT 1 FROM calendar_dates cd
                              WHERE cd.service_id = t.service_id
                                AND cd.date = ?
                                AND cd.exception_type = '1'
                          )
                          OR (
                              c.start_date <= ?
                              AND c.end_date >= ?
                              AND c.$weekday = '1'
                              AND NOT EXISTS (
                                  SELECT 1 FROM calendar_dates cd
                                  WHERE cd.service_id = t.service_id
                                    AND cd.date = ?
                                    AND cd.exception_type = '2'
                              )
                          )
                      )
                    """.trimIndent()
                } else {
                    """
                    SELECT t.route_id, st.departure_time
                    FROM stop_times st
                    JOIN trips t ON t.trip_id = st.trip_id
                    JOIN calendar c ON c.service_id = t.service_id
                    WHERE $stopCondition
                      AND st.departure_time >= ?
                      AND st.departure_time <= ?
                      AND c.start_date <= ?
                      AND c.end_date >= ?
                      AND c.$weekday = '1'
                    """.trimIndent()
                }

                val stopArgs = if (hasParentStation) listOf(stopId, stopId) else listOf(stopId)
                val timeArgs = listOf(minSec.toString(), maxSec.toString())
                val calArgs = if (calendarDatesAvailable) {
                    listOf(dateStr, dateStr, dateStr, dateStr)
                } else {
                    listOf(dateStr, dateStr)
                }

                db.rawQuery(sql, (stopArgs + timeArgs + calArgs).toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val routeId = cursor.getString(0) ?: continue
                        val depSec = cursor.getInt(1)
                        val effectiveSec = if (treatBelow4AsNextDay) toServiceDaySeconds(depSec) else depSec
                        val depAt = dateObj.atStartOfDay().plusSeconds(effectiveSec.toLong())

                        activeRoutes.add(routeId)
                        if (!depAt.isBefore(now)) {
                            timesByRoute.getOrPut(routeId) { mutableListOf() }.add(depAt)
                        }
                    }
                }
            }

            queryWindow(serviceDate, 0, Int.MAX_VALUE, treatBelow4AsNextDay = true)
            if (now.hour >= 4) {
                queryWindow(nextDate, 0, 14399, treatBelow4AsNextDay = false)
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
        if (serviceId.isBlank()) return false
        val db = databaseProvider.openReadOnly() ?: return false

        try {
            val weekday = weekdayColumn(date)
            val dateValue = dateKey(date)

            if (hasCalendarDates(db)) {
                val sql = """
                    SELECT 1 WHERE EXISTS (
                        SELECT 1 FROM calendar_dates
                        WHERE service_id = ? AND date = ? AND exception_type = '1'
                    )
                    OR (
                        EXISTS (
                            SELECT 1 FROM calendar
                            WHERE service_id = ? AND start_date <= ? AND end_date >= ? AND $weekday = '1'
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM calendar_dates
                            WHERE service_id = ? AND date = ? AND exception_type = '2'
                        )
                    )
                    LIMIT 1
                """.trimIndent()

                db.rawQuery(
                    sql,
                    arrayOf(serviceId, dateValue, serviceId, dateValue, dateValue, serviceId, dateValue)
                ).use { cursor ->
                    return cursor.moveToFirst()
                }
            } else {
                db.rawQuery(
                    """
                    SELECT 1
                    FROM calendar
                    WHERE service_id = ?
                      AND start_date <= ?
                      AND end_date >= ?
                      AND $weekday = '1'
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(serviceId, dateValue, dateValue)
                ).use { cursor ->
                    return cursor.moveToFirst()
                }
            }
        } finally {
            db.close()
        }
    }

    private fun queryRouteDeparturesForDate(
        db: SQLiteDatabase,
        routeId: String,
        serviceDate: LocalDate,
        referenceStopId: String?
    ): List<Departure> {
        val weekday = weekdayColumn(serviceDate)
        val dateStr = dateKey(serviceDate)
        val calendarDatesAvailable = hasCalendarDates(db)
        val stopFilter = if (!referenceStopId.isNullOrBlank()) "AND st.stop_id = ?" else "AND st.stop_sequence = 1"

        val sql = if (calendarDatesAvailable) {
            """
            SELECT
                t.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, ''),
                st.stop_id,
                '',
                st.departure_time,
                st.arrival_time
            FROM trips t
            JOIN routes r ON r.route_id = t.route_id
            JOIN stop_times st ON st.trip_id = t.trip_id
            LEFT JOIN calendar c ON c.service_id = t.service_id
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id = ? $stopFilter
              AND (
                  EXISTS (
                      SELECT 1 FROM calendar_dates cd
                      WHERE cd.service_id = t.service_id
                        AND cd.date = ?
                        AND cd.exception_type = '1'
                  )
                  OR (
                      c.start_date <= ?
                      AND c.end_date >= ?
                      AND c.$weekday = '1'
                      AND NOT EXISTS (
                          SELECT 1 FROM calendar_dates cd
                          WHERE cd.service_id = t.service_id
                            AND cd.date = ?
                            AND cd.exception_type = '2'
                      )
                  )
              )
            ORDER BY st.departure_time ASC
            """.trimIndent()
        } else {
            """
            SELECT
                t.trip_id,
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(t.trip_headsign, ''),
                st.stop_id,
                '',
                st.departure_time,
                st.arrival_time
            FROM trips t
            JOIN routes r ON r.route_id = t.route_id
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN calendar c ON c.service_id = t.service_id
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id = ? $stopFilter
              AND c.start_date <= ?
              AND c.end_date >= ?
              AND c.$weekday = '1'
            ORDER BY st.departure_time ASC
            """.trimIndent()
        }

        val baseArgs = if (!referenceStopId.isNullOrBlank()) listOf(routeId, referenceStopId) else listOf(routeId)
        val calArgs = if (calendarDatesAvailable) {
            listOf(dateStr, dateStr, dateStr, dateStr)
        } else {
            listOf(dateStr, dateStr)
        }

        val results = mutableListOf<Departure>()
        db.rawQuery(sql, (baseArgs + calArgs).toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val rawDepSec = cursor.getInt(8)
                val serviceDepSec = toServiceDaySeconds(rawDepSec)
                results.add(
                    Departure(
                        tripId = cursor.getString(0) ?: "",
                        routeId = cursor.getString(1) ?: "",
                        lineNumber = cursor.getString(2) ?: "",
                        lineName = cursor.getString(3) ?: "",
                        agencyName = cursor.getString(4) ?: "",
                        headsign = cursor.getString(5) ?: "",
                        stopId = cursor.getString(6) ?: "",
                        stopName = "",
                        departureSeconds = serviceDepSec,
                        arrivalSeconds = if (cursor.isNull(9)) null else cursor.getInt(9),
                        serviceDate = serviceDate,
                        departureAt = serviceDate.atStartOfDay().plusSeconds(serviceDepSec.toLong())
                    )
                )
            }
        }

        return results.distinctBy { it.departureSeconds }.sortedBy { it.departureSeconds }
    }

    private fun isDatabaseValidOnDate(db: SQLiteDatabase, date: LocalDate): Boolean {
        val dateStr = dateKey(date)
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
        routeId: String,
        dayOfWeek: DayOfWeek
    ): LocalDate? {
        val weekday = weekdayColumn(dayOfWeek)
        try {
            db.rawQuery(
                """
                SELECT MAX(c.end_date)
                FROM trips t
                JOIN calendar c ON c.service_id = t.service_id
                WHERE t.route_id = ? AND c.$weekday = '1'
                """.trimIndent(),
                arrayOf(routeId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val endDate = parseDateKey(cursor.getString(0)) ?: return null
                    var candidate = endDate
                    for (i in 0 until 7) {
                        if (candidate.dayOfWeek == dayOfWeek) return candidate
                        candidate = candidate.minusDays(1)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * שליפת לוח הזמנים היומי עבור יום בשבוע (ראשון..שבת) ללא תלות בתאריך,
     * עם מיון תקין של נסיעות שאחרי חצות (עד 04:00) בסוף יום השירות.
     */
    fun getDeparturesForDayOfWeek(
        routeId: String,
        dayOfWeek: DayOfWeek,
        referenceStopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): List<Departure> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()
        return try {
            val serviceToday = currentServiceDate(now)
            val daysAhead = (dayOfWeek.value - serviceToday.dayOfWeek.value + 7) % 7
            val candidateDate = serviceToday.plusDays(daysAhead.toLong())

            val dbCoversCandidate = isDatabaseValidOnDate(db, candidateDate)
            val effectiveDate = if (dbCoversCandidate) {
                candidateDate
            } else {
                findFallbackDateForDayOfWeek(db, routeId, dayOfWeek) ?: candidateDate
            }

            val deps = queryRouteDeparturesForDate(db, routeId, effectiveDate, referenceStopId)
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

    /**
     * מחזיר מידע מדויק על הנסיעה הבאה של קו בתחנה: האם פעיל היום, האם הסתיים להיום,
     * ומתי האוטובוס הבא (היום, מחר או ביום הפעיל הבא).
     */
    fun getNextDepartureForLineAtStop(
        routeId: String,
        stopId: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): LineNextDepartureInfo {
        val db = databaseProvider.openReadOnly() ?: return LineNextDepartureInfo(
            activeToday = false,
            todayServiceEnded = false,
            nextDepartureAt = null,
            nextTripId = null,
            isTomorrow = false,
            nextDayOfWeek = null
        )

        try {
            val serviceToday = currentServiceDate(now)
            val dbValidToday = isDatabaseValidOnDate(db, serviceToday)

            val todayDeps = if (dbValidToday) {
                queryRouteDeparturesForDate(db, routeId, serviceToday, stopId)
            } else {
                val fallback = findFallbackDateForDayOfWeek(db, routeId, serviceToday.dayOfWeek)
                if (fallback != null) {
                    queryRouteDeparturesForDate(db, routeId, fallback, stopId).map {
                        it.copy(
                            serviceDate = serviceToday,
                            departureAt = serviceToday.atStartOfDay().plusSeconds(it.departureSeconds.toLong())
                        )
                    }
                } else emptyList()
            }

            val activeToday = todayDeps.isNotEmpty()
            val nextToday = todayDeps.firstOrNull { !it.departureAt.isBefore(now) }

            if (nextToday != null) {
                return LineNextDepartureInfo(
                    activeToday = true,
                    todayServiceEnded = false,
                    nextDepartureAt = nextToday.departureAt,
                    nextTripId = nextToday.tripId,
                    isTomorrow = false,
                    nextDayOfWeek = serviceToday.dayOfWeek
                )
            }

            // לא נמצאה נסיעה נוספת בהמשך היום הנוכחי -> חיפוש ביום הפעיל הבא (1..7 ימים קדימה)
            for (offset in 1L..7L) {
                val futureDate = serviceToday.plusDays(offset)
                val futureDeps = if (isDatabaseValidOnDate(db, futureDate)) {
                    queryRouteDeparturesForDate(db, routeId, futureDate, stopId)
                } else if (!dbValidToday) {
                    val fallback = findFallbackDateForDayOfWeek(db, routeId, futureDate.dayOfWeek)
                    if (fallback != null) {
                        queryRouteDeparturesForDate(db, routeId, fallback, stopId).map {
                            it.copy(
                                serviceDate = futureDate,
                                departureAt = futureDate.atStartOfDay().plusSeconds(it.departureSeconds.toLong())
                            )
                        }
                    } else emptyList()
                } else emptyList()

                val firstFuture = futureDeps.firstOrNull()
                if (firstFuture != null) {
                    return LineNextDepartureInfo(
                        activeToday = activeToday,
                        todayServiceEnded = activeToday,
                        nextDepartureAt = firstFuture.departureAt,
                        nextTripId = firstFuture.tripId,
                        isTomorrow = (offset == 1L),
                        nextDayOfWeek = futureDate.dayOfWeek
                    )
                }
            }

            return LineNextDepartureInfo(
                activeToday = activeToday,
                todayServiceEnded = activeToday,
                nextDepartureAt = null,
                nextTripId = null,
                isTomorrow = false,
                nextDayOfWeek = null
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
        return getDeparturesForDayOfWeek(routeId, targetDay, referenceStopId)
    }
}
