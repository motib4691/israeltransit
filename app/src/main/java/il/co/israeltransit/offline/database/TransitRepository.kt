package il.co.israeltransit.offline.database

import il.co.israeltransit.offline.ui.OperatorTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TransitRepository(
    private val databaseProvider: TransitDatabase
) {

    data class TransitLine(
        val routeId: String,
        val lineNumber: String,
        val name: String,
        val agencyId: String,
        val agencyName: String,
        val routeType: String,
        val originName: String = "",
        val destinationName: String = "",
        val originCity: String = "",
        val destinationCity: String = "",
        val routeDesc: String = ""
    )

    data class GroupedTransitLine(
        val lineNumber: String,
        val agencyName: String,
        val agencyId: String,
        val routeType: String,
        val cityHeader: String,
        val primaryOrigin: String,
        val primaryDestination: String,
        val forwardLine: TransitLine,
        val backwardLine: TransitLine? = null,
        val isUrban: Boolean = true
    )

    data class TransitStop(
        val stopId: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val stopCode: String = stopId,
        val customName: String? = null
    )

    data class DualStopGroup(
        val baseName: String,
        val stop1: TransitStop,
        val stop2: TransitStop? = null,
        val customName: String? = null
    )

    data class RouteStop(
        val stopId: String,
        val name: String,
        val sequence: Int,
        val arrivalTime: Int?,
        val departureTime: Int?,
        val stopCode: String = stopId
    )

    data class SavedStopRef(
        val stopId: String,
        val customName: String,
        val stopCode: String = stopId
    )

    private data class ParsedRouteName(
        val originStation: String,
        val originCity: String,
        val destStation: String,
        val destCity: String
    )

    /**
     * פירוק מדויק של שמות התחנות והערים מתוך route_long_name לפי הפורמט של משרד התחבורה:
     * תחנת מוצא-עיר מוצא<->תחנת יעד-עיר יעד-חלופה
     */
    private fun parseRouteLongName(longName: String): ParsedRouteName {
        val clean = longName.trim()
        if (clean.isEmpty()) return ParsedRouteName("", "", "", "")

        val mainParts = clean.split("<->", "⟷", "<=>")
        if (mainParts.size >= 2) {
            val rawOrigin = mainParts[0].trim()
            val rawDest = mainParts[1].trim()

            var originTokens = rawOrigin.split("-", "–").map { it.trim() }.filter { it.isNotEmpty() }
            while (originTokens.isNotEmpty() && (originTokens.last().all { it.isDigit() } || originTokens.last().contains("#"))) {
                originTokens = originTokens.dropLast(1)
            }

            val (origStation, origCity) = if (originTokens.size >= 2) {
                val city = originTokens.last().replace("#", "").trim()
                val station = originTokens.dropLast(1).joinToString("-")
                station to city
            } else {
                rawOrigin to ""
            }

            var destTokens = rawDest.split("-", "–").map { it.trim() }.filter { it.isNotEmpty() }
            while (destTokens.isNotEmpty() && (destTokens.last().all { it.isDigit() } || destTokens.last().contains("#"))) {
                destTokens = destTokens.dropLast(1)
            }

            val (destStation, destCity) = if (destTokens.size >= 2) {
                val city = destTokens.last().replace("#", "").trim()
                val station = destTokens.dropLast(1).joinToString("-")
                station to city
            } else if (destTokens.isNotEmpty()) {
                destTokens.first().replace("#", "").trim() to ""
            } else {
                rawDest to ""
            }

            return ParsedRouteName(origStation, origCity, destStation, destCity)
        }

        val parts = clean.split("-", "–").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 4 -> ParsedRouteName(parts[0], parts[1], parts[2], parts[3])
            parts.size == 3 -> ParsedRouteName(parts[0], parts[1], parts[2], parts[1])
            parts.size == 2 -> ParsedRouteName(parts[0], "", parts[1], "")
            parts.size == 1 -> ParsedRouteName(parts[0], "", "", "")
            else -> ParsedRouteName("", "", "", "")
        }
    }

    private fun queryLines(
        sql: String,
        args: Array<String>
    ): List<TransitLine> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(sql, args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val routeId = cursor.getString(0) ?: ""
                        val shortName = cursor.getString(1) ?: ""
                        val longName = cursor.getString(2) ?: ""
                        val agencyId = cursor.getString(3) ?: ""
                        val agencyName = cursor.getString(4) ?: ""
                        val routeType = cursor.getString(5) ?: ""
                        val routeDesc = if (cursor.columnCount > 6) cursor.getString(6) ?: "" else ""

                        val parsed = parseRouteLongName(longName)

                        add(
                            TransitLine(
                                routeId = routeId,
                                lineNumber = shortName,
                                name = longName,
                                agencyId = agencyId,
                                agencyName = agencyName,
                                routeType = routeType,
                                originName = parsed.originStation.ifBlank { longName },
                                destinationName = parsed.destStation,
                                originCity = parsed.originCity,
                                destinationCity = parsed.destCity,
                                routeDesc = routeDesc
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun searchLines(
        searchText: String,
        limit: Int = 100
    ): List<TransitLine> {
        val query = searchText.trim()
        if (query.isEmpty()) return emptyList()

        return queryLines(
            """
            SELECT
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(r.agency_id, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(r.route_type, ''),
                COALESCE(r.route_desc, '')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE
                r.route_short_name LIKE ?
            ORDER BY
                CASE WHEN r.route_short_name = ? THEN 0 ELSE 1 END,
                r.route_short_name
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                "$query%",
                query,
                limit.coerceIn(1, 500).toString()
            )
        )
    }

    /**
     * חילוץ שם עיר מתוך נקודת מוצא או יעד (לדוגמה: "תחנה מרכזית - ירושלים" -> "ירושלים")
     */
    private fun extractCity(placeName: String): String {
        val clean = placeName.trim()
        if (clean.isEmpty()) return ""
        val parts = clean.split("-", "–")
        if (parts.size >= 2) {
            val candidate = parts.last().trim()
            if (candidate.isNotEmpty() && !candidate.startsWith("רציף") && !candidate.startsWith("ת. מרכזית")) {
                return candidate
            }
            return parts.first().trim()
        }
        return clean
    }

    /**
     * עיצוב כותרת העיר/ערים: עיר אחת לקו עירוני, או עיר A ↔️ עיר B לקו בין-עירוני
     */
    private fun formatCityHeader(origin: String, destination: String): String {
        val cityA = origin.trim().ifEmpty { extractCity(origin) }
        val cityB = destination.trim().ifEmpty { extractCity(destination) }

        if (cityA.isBlank() && cityB.isBlank()) return ""
        if (cityA.isBlank()) return cityB
        if (cityB.isBlank()) return cityA

        val normA = OperatorTheme.normalizeHebrew(cityA)
        val normB = OperatorTheme.normalizeHebrew(cityB)

        return if (normA == normB || normA.contains(normB) || normB.contains(normA)) {
            cityA
        } else {
            "$cityA ⟷ $cityB"
        }
    }

    /**
     * חיפוש קווים מאוחד: מקבץ את שני הכיוונים של אותו קו לכרטיסייה אחת,
     * עם תמיכה בסינון לפי עיר ולפי מפעיל (אופציונליים).
     */
    fun searchGroupedLines(
        query: String = "",
        cityFilter: String = "",
        agencyFilter: String = "",
        limit: Int = 100
    ): List<GroupedTransitLine> {
        val cleanQuery = query.trim()
        val cleanCity = cityFilter.trim()
        val cleanAgency = agencyFilter.trim()

        if (cleanQuery.isEmpty() && cleanCity.isEmpty() && cleanAgency.isEmpty()) {
            return emptyList()
        }

        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        // חיפוש לפי מספר קו בלבד
        if (cleanQuery.isNotEmpty()) {
            conditions.add("r.route_short_name LIKE ?")
            args.add("$cleanQuery%")
        }

        if (cleanAgency.isNotEmpty()) {
            conditions.add("a.agency_name LIKE ?")
            args.add("%$cleanAgency%")
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE " + conditions.joinToString(" AND ") else ""

        val rawLines = queryLines(
            """
            SELECT
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(r.agency_id, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(r.route_type, ''),
                COALESCE(r.route_desc, '')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            $whereClause
            ORDER BY
                CASE WHEN r.route_short_name = ? THEN 0 ELSE 1 END,
                r.route_short_name,
                r.route_id
            LIMIT ?
            """.trimIndent(),
            (args + listOf(cleanQuery, (limit * 3).coerceIn(1, 1000).toString())).toTypedArray()
        )

        // קיבוץ לפי מק"ט קו רישוי מתוך route_desc + חלופה
        val groupedMap = linkedMapOf<String, MutableList<TransitLine>>()
        for (line in rawLines) {
            val descParts = line.routeDesc.split("-", "–")
            val lineRef = descParts.getOrNull(0)?.trim().orEmpty()
            val alternative = descParts.getOrNull(2)?.trim().orEmpty()

            val key = if (lineRef.isNotEmpty()) {
                "${line.agencyId}_${line.lineNumber}_${lineRef}_${alternative}"
            } else {
                val cA = line.originCity.ifBlank { extractCity(line.originName) }
                val cB = line.destinationCity.ifBlank { extractCity(line.destinationName) }
                "${line.agencyId}_${line.lineNumber}_${formatCityHeader(cA, cB)}"
            }

            groupedMap.getOrPut(key) { mutableListOf() }.add(line)
        }

        val result = mutableListOf<GroupedTransitLine>()
        for ((_, linesList) in groupedMap) {
            // כיוון 1 כהלוך וכיוון 2 כחזור
            val forward = linesList.firstOrNull {
                it.routeDesc.split("-", "–").getOrNull(1)?.trim() == "1"
            } ?: linesList.first()

            val backward = linesList.firstOrNull {
                it.routeDesc.split("-", "–").getOrNull(1)?.trim() == "2" && it != forward
            } ?: linesList.getOrNull(1)?.takeIf { it != forward }

            val originStation = forward.originName.ifBlank { forward.name }
            val destStation = forward.destinationName.ifBlank { backward?.originName.orEmpty() }

            val cityA = forward.originCity.ifBlank { backward?.destinationCity.orEmpty() }
            val cityB = forward.destinationCity.ifBlank { backward?.originCity.orEmpty() }

            val normA = OperatorTheme.normalizeHebrew(cityA)
            val normB = OperatorTheme.normalizeHebrew(cityB)
            val isUrban = cityA.isNotBlank() && cityB.isNotBlank() && (normA == normB || normA.contains(normB) || normB.contains(normA))

            val cityHeader = if (isUrban || cityB.isBlank()) {
                cityA.ifBlank { cityB }
            } else {
                "$cityA ⟷ $cityB"
            }

            // סינון לפי עיר: בדיקה אך ורק מול שמות הערים המבודדים
            if (cleanCity.isNotEmpty()) {
                val normCity = OperatorTheme.normalizeHebrew(cleanCity)
                val normOrigCity = OperatorTheme.normalizeHebrew(cityA)
                val normDestCity = OperatorTheme.normalizeHebrew(cityB)
                val matchesCity = normOrigCity.contains(normCity) || normDestCity.contains(normCity)
                if (!matchesCity) {
                    continue
                }
            }

            result.add(
                GroupedTransitLine(
                    lineNumber = forward.lineNumber,
                    agencyName = forward.agencyName,
                    agencyId = forward.agencyId,
                    routeType = forward.routeType,
                    cityHeader = cityHeader,
                    primaryOrigin = originStation,
                    primaryDestination = destStation,
                    forwardLine = forward,
                    backwardLine = backward,
                    isUrban = isUrban
                )
            )

            if (result.size >= limit) break
        }

        return result
    }

    fun getLine(routeId: String): TransitLine? {
        return queryLines(
            """
            SELECT
                r.route_id,
                COALESCE(r.route_short_name, ''),
                COALESCE(r.route_long_name, ''),
                COALESCE(r.agency_id, ''),
                COALESCE(a.agency_name, ''),
                COALESCE(r.route_type, ''),
                COALESCE(r.route_desc, '')
            FROM routes r
            LEFT JOIN agency a ON a.agency_id = r.agency_id
            WHERE r.route_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(routeId)
        ).firstOrNull()
    }

    /**
     * חיפוש תחנות ורחובות עם נירמול עברי חכם ותמיכה בהצגת תחנות שמורות אישיות בראש התוצאות
     */
    fun searchStops(
        searchText: String,
        limit: Int = 100,
        savedStops: List<SavedStopRef> = emptyList()
    ): List<TransitStop> {
        val raw = searchText.trim()
        if (raw.isEmpty()) return emptyList()

        val cleanQuery = raw.replace("[\"'״׳]".toRegex(), "")
        val normQuery = OperatorTheme.normalizeHebrew(raw)

        val matchedSavedStops = savedStops.filter { saved ->
            val normCustom = OperatorTheme.normalizeHebrew(saved.customName)
            normCustom.contains(normQuery) || saved.stopCode.startsWith(raw)
        }

        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            val dbStops = db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_name LIKE ? OR stop_name LIKE ? OR stop_code LIKE ? OR stop_id LIKE ?
                ORDER BY
                    CASE
                        WHEN stop_code = ? THEN 0
                        WHEN stop_id = ? THEN 1
                        WHEN stop_name LIKE ? THEN 2
                        ELSE 3
                    END,
                    stop_name
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    "%$raw%",
                    "%$cleanQuery%",
                    "$raw%",
                    "$raw%",
                    raw,
                    raw,
                    "$raw%",
                    limit.coerceIn(1, 500).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sId = cursor.getString(0) ?: ""
                        val sCode = cursor.getString(1) ?: ""
                        val sName = cursor.getString(2) ?: ""
                        val sLat = if (cursor.isNull(3)) null else cursor.getDouble(3)
                        val sLon = if (cursor.isNull(4)) null else cursor.getDouble(4)

                        val custom = savedStops.firstOrNull { it.stopId == sId }?.customName

                        add(
                            TransitStop(
                                stopId = sId,
                                stopCode = sCode,
                                name = sName,
                                latitude = sLat,
                                longitude = sLon,
                                customName = custom
                            )
                        )
                    }
                }
            }

            val finalStops = mutableListOf<TransitStop>()

            for (saved in matchedSavedStops) {
                val existing = dbStops.firstOrNull { it.stopId == saved.stopId }
                if (existing != null) {
                    finalStops.add(existing.copy(customName = saved.customName))
                } else {
                    db.rawQuery(
                        "SELECT stop_id, COALESCE(NULLIF(stop_code, ''), stop_id), COALESCE(stop_name, ''), stop_lat, stop_lon FROM stops WHERE stop_id = ? LIMIT 1",
                        arrayOf(saved.stopId)
                    ).use { c ->
                        if (c.moveToFirst()) {
                            finalStops.add(
                                TransitStop(
                                    stopId = c.getString(0) ?: saved.stopId,
                                    stopCode = c.getString(1) ?: saved.stopCode,
                                    name = c.getString(2) ?: "",
                                    latitude = if (c.isNull(3)) null else c.getDouble(3),
                                    longitude = if (c.isNull(4)) null else c.getDouble(4),
                                    customName = saved.customName
                                )
                            )
                        }
                    }
                }
            }

            for (st in dbStops) {
                if (finalStops.none { it.stopId == st.stopId }) {
                    finalStops.add(st)
                }
            }

            finalStops.take(limit)
        } finally {
            db.close()
        }
    }

    /**
     * איחוד תחנות נגדיות באותו שם לכרטיס משותף, עם תמיכה בעדיפות לשמות אישיים מתחנות שמורות
     */
    fun searchGroupedStops(
        searchText: String,
        limit: Int = 50,
        savedStops: List<SavedStopRef> = emptyList()
    ): List<DualStopGroup> {
        val stops = searchStops(searchText, limit * 2, savedStops)
        val isCodeSearch = searchText.trim().all { it.isDigit() }

        if (isCodeSearch) {
            return stops.map {
                DualStopGroup(
                    baseName = it.name,
                    stop1 = it,
                    stop2 = null,
                    customName = it.customName
                )
            }
        }

        val grouped = linkedMapOf<String, MutableList<TransitStop>>()
        for (stop in stops) {
            val key = stop.customName ?: OperatorTheme.normalizeHebrew(stop.name)
            grouped.getOrPut(key) { mutableListOf() }.add(stop)
        }

        return grouped.values.map { list ->
            DualStopGroup(
                baseName = list[0].name,
                stop1 = list[0],
                stop2 = list.getOrNull(1),
                customName = list[0].customName
            )
        }.take(limit)
    }

    fun findOppositeStop(stop: TransitStop): TransitStop? {
        val db = databaseProvider.openReadOnly() ?: return null
        return try {
            val sql = """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_name = ? AND stop_id != ?
                LIMIT 1
            """.trimIndent()

            db.rawQuery(sql, arrayOf(stop.name, stop.stopId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    TransitStop(
                        stopId = cursor.getString(0) ?: "",
                        stopCode = cursor.getString(1) ?: "",
                        name = cursor.getString(2) ?: "",
                        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4)
                    )
                } else null
            }
        } finally {
            db.close()
        }
    }

    fun findNearestStop(
        latitude: Double,
        longitude: Double,
        maxDistanceMeters: Double = 3000.0
    ): TransitStop? {
        val db = databaseProvider.openReadOnly() ?: return null
        val deltaLat = maxDistanceMeters / 111000.0
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
        val deltaLon = maxDistanceMeters / (111000.0 * cosLat)

        return try {
            db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE stop_lat BETWEEN ? AND ?
                  AND stop_lon BETWEEN ? AND ?
                LIMIT 60
                """.trimIndent(),
                arrayOf(
                    (latitude - deltaLat).toString(),
                    (latitude + deltaLat).toString(),
                    (longitude - deltaLon).toString(),
                    (longitude + deltaLon).toString()
                )
            ).use { cursor ->
                var closestStop: TransitStop? = null
                var minDistance = Double.MAX_VALUE

                while (cursor.moveToNext()) {
                    if (cursor.isNull(3) || cursor.isNull(4)) continue
                    val sLat = cursor.getDouble(3)
                    val sLon = cursor.getDouble(4)
                    val dist = haversineMeters(latitude, longitude, sLat, sLon)
                    if (dist < minDistance && dist <= maxDistanceMeters) {
                        minDistance = dist
                        closestStop = TransitStop(
                            stopId = cursor.getString(0) ?: "",
                            stopCode = cursor.getString(1) ?: "",
                            name = cursor.getString(2) ?: "",
                            latitude = sLat,
                            longitude = sLon
                        )
                    }
                }
                closestStop
            }
        } finally {
            db.close()
        }
    }

    private fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadius * c
    }

    fun getStopsForLine(
        routeId: String,
        limit: Int = 500
    ): List<RouteStop> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(
                """
                SELECT
                    s.stop_id,
                    COALESCE(s.stop_name, ''),
                    st.stop_sequence,
                    st.arrival_time,
                    st.departure_time,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                JOIN stops s ON s.stop_id = st.stop_id
                WHERE t.route_id = ?
                  AND t.trip_id = (
                      SELECT trip_id
                      FROM trips
                      WHERE route_id = ?
                      ORDER BY trip_id
                      LIMIT 1
                  )
                ORDER BY st.stop_sequence
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    routeId,
                    routeId,
                    limit.coerceIn(1, 2000).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            RouteStop(
                                stopId = cursor.getString(0) ?: "",
                                name = cursor.getString(1) ?: "",
                                sequence = cursor.getInt(2),
                                arrivalTime = if (cursor.isNull(3)) null else cursor.getInt(3),
                                departureTime = if (cursor.isNull(4)) null else cursor.getInt(4),
                                stopCode = cursor.getString(5) ?: ""
                            )
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    fun getLinesForStop(
        stopId: String,
        limit: Int = 100
    ): List<TransitLine> {
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            db.rawQuery(
                """
                SELECT DISTINCT
                    r.route_id,
                    COALESCE(r.route_short_name, ''),
                    COALESCE(r.route_long_name, ''),
                    COALESCE(r.agency_id, ''),
                    COALESCE(a.agency_name, ''),
                    COALESCE(r.route_type, ''),
                    COALESCE(r.route_desc, '')
                FROM stop_times st
                JOIN trips t ON t.trip_id = st.trip_id
                JOIN routes r ON r.route_id = t.route_id
                LEFT JOIN agency a ON a.agency_id = r.agency_id
                WHERE st.stop_id = ?
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    stopId,
                    limit.coerceIn(1, 500).toString()
                )
            ).use { cursor ->
                val lines = mutableListOf<TransitLine>()
                while (cursor.moveToNext()) {
                    val longName = cursor.getString(2) ?: ""
                    val parsed = parseRouteLongName(longName)

                    lines.add(
                        TransitLine(
                            routeId = cursor.getString(0) ?: "",
                            lineNumber = cursor.getString(1) ?: "",
                            name = longName,
                            agencyId = cursor.getString(3) ?: "",
                            agencyName = cursor.getString(4) ?: "",
                            routeType = cursor.getString(5) ?: "",
                            originName = parsed.originStation.ifBlank { longName },
                            destinationName = parsed.destStation,
                            originCity = parsed.originCity,
                            destinationCity = parsed.destCity,
                            routeDesc = cursor.getString(6) ?: ""
                        )
                    )
                }

                lines.sortedWith(
                    compareBy<TransitLine> { line ->
                        val isUrban = line.originCity.isNotBlank() && line.destinationCity.isNotBlank() &&
                                OperatorTheme.normalizeHebrew(line.originCity) == OperatorTheme.normalizeHebrew(line.destinationCity)
                        if (isUrban) 0 else 1
                    }.thenBy { it.lineNumber.toIntOrNull() ?: Int.MAX_VALUE }
                     .thenBy { it.lineNumber }
                )
            }
        } finally {
            db.close()
        }
    }
}
