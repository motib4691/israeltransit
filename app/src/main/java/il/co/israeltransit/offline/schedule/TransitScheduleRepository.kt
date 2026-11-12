package il.co.israeltransit.offline.schedule

import android.database.sqlite.SQLiteDatabase
import il.co.israeltransit.offline.database.TransitDatabase
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

    private var calendarDatesExists: Boolean? = null

    private fun hasCalendarDates(db: SQLiteDatabase): Boolean {
        if (calendarDatesExists == null) {
            calendarDatesExists = try {
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='calendar_dates'",
                    null
                ).use { it.moveToFirst() }
            } catch (_: Exception) {
                false
            }
        }
        return calendarDatesExists == true
    }

    private fun weekdayColumn(date: LocalDate): String {
        return when (date.dayOfWeek.value) {
            1 -> "monday"
            2 -> "tuesday"
            3 -> "wednesday"
            4 -> "thursday"
            5 -> "friday"
            6 -> "saturday"
            else -> "sunday"
        }
    }

    private fun dateKey(date: LocalDate): String {
        return date.toString().replace("-", "")
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

            val serviceDates = listOf(
                today to currentSeconds,
                today.minusDays(1) to (currentSeconds + 86400)
            )

            val calendarDatesAvailable = hasCalendarDates(db)

            for ((serviceDate, minimumSeconds) in serviceDates) {
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
                        st.stop_id = ?
                        AND st.departure_time >= ?
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
                        st.stop_id = ?
                        AND st.departure_time >= ?
                        AND c.start_date <= ?
                        AND c.end_date >= ?
                        AND c.$weekday = '1'
                    ORDER BY st.departure_time ASC
                    LIMIT ?
                    """.trimIndent()
                }

                val args = if (calendarDatesAvailable) {
                    arrayOf(
                        stopId,
                        minimumSeconds.toString(),
                        date,
                        date,
                        date,
                        date,
                        limit.coerceIn(1, 200).toString()
                    )
                } else {
                    arrayOf(
                        stopId,
                        minimumSeconds.toString(),
                        date,
                        date,
                        limit.coerceIn(1, 200).toString()
                    )
                }

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

    fun getDailyDepartures(routeId: String, dayType: Int, referenceStopId: String? = null): List<Departure> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()
        val results = mutableListOf<Departure>()

        val weekdayCol = when (dayType) {
            1 -> "friday"
            2 -> "saturday"
            else -> "sunday"
        }

        try {
            val stopFilter = if (referenceStopId != null) "AND st.stop_id = ?" else "AND st.stop_sequence = 1"
            val sql = """
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
                WHERE r.route_id = ? $stopFilter AND c.$weekdayCol = '1'
                ORDER BY st.departure_time ASC
            """.trimIndent()

            val args = if (referenceStopId != null) arrayOf(routeId, referenceStopId) else arrayOf(routeId)

            db.rawQuery(sql, args).use { cursor ->
                val today = LocalDate.now()
                while (cursor.moveToNext()) {
                    val depSec = cursor.getInt(8)
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
                            departureSeconds = depSec,
                            arrivalSeconds = if (cursor.isNull(9)) null else cursor.getInt(9),
                            serviceDate = today,
                            departureAt = today.atStartOfDay().plusSeconds(depSec.toLong())
                        )
                    )
                }
            }
        } finally {
            db.close()
        }

        val sorted = results.distinctBy { it.departureSeconds }.sortedBy { it.departureSeconds }
        return sorted.mapIndexed { idx, dep ->
            dep.copy(
                isLastFridayTrip = (dayType == 1 && idx == sorted.lastIndex),
                isFirstSaturdayTrip = (dayType == 2 && idx == 0)
            )
        }
    }
}
