package il.co.israeltransit.offline.database

import android.database.sqlite.SQLiteDatabase
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
        val isUrban: Boolean = true,
        val alternativeLabel: String = "",
        val forwardRouteIds: List<String> = listOf(forwardLine.routeId),
        val backwardRouteIds: List<String> = backwardLine?.let { listOf(it.routeId) } ?: emptyList()
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

    data class StopPlatform(
        val stopId: String,
        val stopCode: String,
        val name: String,
        val platformCode: String,
        val displayLabel: String
    )

    data class SubVariantInfo(
        val routeId: String,
        val label: String,
        val stopCount: Int,
        val differenceSummary: String
    )

    data class LineAlternativeOption(
        val index: Int,
        val label: String,
        val primaryLine: TransitLine,
        val routeIds: List<String>,
        val oppositeRouteIds: List<String>,
        val differenceSummary: String
    )

    data class LineDetailsContext(
        val alternatives: List<LineAlternativeOption>,
        val activeAlternativeIndex: Int,
        val subVariants: List<SubVariantInfo>,
        val oppositeRouteIds: List<String>
    )

    private data class ParsedRouteName(
        val originStation: String,
        val originCity: String,
        val destStation: String,
        val destCity: String
    )

    private data class StopsSchemaInfo(
        val hasParentStation: Boolean,
        val hasStopDesc: Boolean,
        val hasPlatformCode: Boolean
    )

    private data class DirectionBundle(
        val dirCode: String,
        val primaryLine: TransitLine,
        val lines: List<TransitLine>,
        val normOrig: String,
        val normDest: String,
        val normOrigCity: String,
        val normDestCity: String,
        val isIntercity: Boolean,
        val isCircular: Boolean
    ) {
        val routeIds: List<String> get() = lines.map { it.routeId }
    }

    private data class PairedRouteBundle(
        val forward: DirectionBundle,
        val backward: DirectionBundle?
    )

    private fun getStopsSchemaInfo(db: SQLiteDatabase): StopsSchemaInfo {
        var hasParentStation = false
        var hasStopDesc = false
        var hasPlatformCode = false
        try {
            db.rawQuery("PRAGMA table_info(stops)", null).use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                if (nameCol >= 0) {
                    while (cursor.moveToNext()) {
                        when (cursor.getString(nameCol)) {
                            "parent_station" -> hasParentStation = true
                            "stop_desc" -> hasStopDesc = true
                            "platform_code" -> hasPlatformCode = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return StopsSchemaInfo(
            hasParentStation = hasParentStation,
            hasStopDesc = hasStopDesc,
            hasPlatformCode = hasPlatformCode
        )
    }

    private fun isAlternativeSuffixToken(token: String, altFromDesc: String): Boolean {
        val clean = token.trim()
        if (clean.isEmpty()) return true
        if (clean.contains("#")) return true
        if (altFromDesc.isNotBlank() && altFromDesc != "#" && clean.equals(altFromDesc, ignoreCase = true)) {
            return true
        }
        if (clean.all { it.isDigit() }) return true
        if (clean.matches(Regex("^\\d+[א-תa-zA-Z]{1,2}$"))) return true
        if (clean.matches(Regex("^[א-תa-zA-Z]\\d+$"))) return true
        if (clean.length == 1 && clean[0] in 'א'..'ת') return true
        return false
    }

    private fun splitStationAndCity(
        rawText: String,
        altFromDesc: String
    ): Pair<String, String> {
        var tokens = rawText.split("-", "–")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        while (tokens.size >= 2 && isAlternativeSuffixToken(tokens.last(), altFromDesc)) {
            tokens = tokens.dropLast(1)
        }

        if (tokens.size < 2) {
            val single = tokens.firstOrNull()?.replace("#", "")?.trim() ?: rawText.trim()
            return single to ""
        }

        val lastTwo = "${tokens[tokens.size - 2]}-${tokens.last()}"
        val lastThree = if (tokens.size >= 3) {
            "${tokens[tokens.size - 3]}-${tokens[tokens.size - 2]}-${tokens.last()}"
        } else ""

        return when {
            lastThree.replace(" ", "") == "מודיעין-מכבים-רעות" && tokens.size >= 4 -> {
                val city = tokens.takeLast(3).joinToString("-")
                val station = tokens.dropLast(3).joinToString("-")
                station to city
            }
            (lastTwo.replace(" ", "") in setOf("תלאביב-יפו", "בנימינה-גבעתעדה")) && tokens.size >= 3 -> {
                val city = tokens.takeLast(2).joinToString("-")
                val station = tokens.dropLast(2).joinToString("-")
                station to city
            }
            else -> {
                val city = tokens.last().replace("#", "").trim()
                val station = tokens.dropLast(1).joinToString("-")
                station to city
            }
        }
    }

    private fun parseRouteLongName(
        longName: String,
        routeDesc: String = ""
    ): ParsedRouteName {
        val clean = longName.trim()
        if (clean.isEmpty()) return ParsedRouteName("", "", "", "")

        val altFromDesc = routeDesc.split("-", "–").getOrNull(2)?.trim().orEmpty()

        val mainParts = clean.split("<->", "⟷", "<=>")
        if (mainParts.size >= 2) {
            val rawOrigin = mainParts[0].trim()
            val rawDest = mainParts[1].trim()

            val (origStation, origCity) = splitStationAndCity(rawOrigin, altFromDesc)
            val (destStation, destCity) = splitStationAndCity(rawDest, altFromDesc)

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

    /**
     * נרמול שם תחנת קצה לצורך התאמת כיוונים וחלופות בתחנות מרכזיות (רציפים / הורדה / קומה / ת.מרכזית)
     */
    private fun normalizeTerminalName(stationName: String): String {
        val cleaned = stationName
            .replace("[\"'״׳]".toRegex(), "")
            .replace(Regex("תחנה\\s+מרכזית|ת\\.\\s*מרכזית|ת\\s+מרכזית"), "תחנהמרכזית")
            .replace(Regex("/\\s*(?:הורדה|איסוף|רציפים|רציף|בינעירוני|עירוני|קומה\\s*\\d*|כניסה|יציאה)\\b"), " ")
            .replace(Regex("\\b(?:הורדה|איסוף|רציפים|רציף|בינעירוני|עירוני|קומה\\s*\\d*)\\b"), " ")
            .replace(Regex("[.\\-–/\\s]+"), "")
            .trim()
            .lowercase()

        return if (cleaned.isNotEmpty()) cleaned else OperatorTheme.normalizeHebrew(stationName)
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

                        val parsed = parseRouteLongName(longName, routeDesc)

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

    private fun alternativeSortKey(line: TransitLine): Pair<Int, String> {
        val alt = line.routeDesc.split("-", "–").getOrNull(2)?.trim().orEmpty()
        val isPrimary = alt.isEmpty() || alt == "#" || alt == "0" || alt == "1" || alt == "2"
        return (if (isPrimary) 0 else 1) to alt
    }

    private fun buildPairedBundles(linesList: List<TransitLine>): List<PairedRouteBundle> {
        val fallbackCityA = linesList.firstNotNullOfOrNull { it.originCity.ifBlank { null } }.orEmpty()
        val fallbackCityB = linesList.firstNotNullOfOrNull { it.destinationCity.ifBlank { null } }.orEmpty()

        // שלב א: מיזוג תתי-מסלולים באותו כיוון ובעלי אותן תחנות קצה מנורמלות
        val bundleMap = linkedMapOf<String, MutableList<TransitLine>>()
        for (line in linesList) {
            val dirCode = line.routeDesc.split("-", "–").getOrNull(1)?.trim().orEmpty()
            val normOrig = normalizeTerminalName(line.originName)
            val normDest = normalizeTerminalName(line.destinationName)
            val normOrigCity = OperatorTheme.normalizeHebrew(line.originCity)
            val normDestCity = OperatorTheme.normalizeHebrew(line.destinationCity)
            val key = "$dirCode|$normOrig|$normDest|$normOrigCity|$normDestCity"
            bundleMap.getOrPut(key) { mutableListOf() }.add(line)
        }

        val bundles = bundleMap.values.map { rawGroup ->
            val sorted = rawGroup.sortedWith(
                compareBy<TransitLine> { alternativeSortKey(it).first }.thenBy { alternativeSortKey(it).second }
            )
            val primary = sorted.first()
            val dirCode = primary.routeDesc.split("-", "–").getOrNull(1)?.trim().orEmpty()
            val normOrig = normalizeTerminalName(primary.originName)
            val normDest = normalizeTerminalName(primary.destinationName)
            val cOrig = primary.originCity.ifBlank { if (dirCode == "2") fallbackCityB else fallbackCityA }
            val cDest = primary.destinationCity.ifBlank { if (dirCode == "2") fallbackCityA else fallbackCityB }
            val normOrigCity = OperatorTheme.normalizeHebrew(cOrig)
            val normDestCity = OperatorTheme.normalizeHebrew(cDest)
            val isIntercity = normOrigCity.isNotBlank() && normDestCity.isNotBlank() &&
                normOrigCity != normDestCity && !normOrigCity.contains(normDestCity) && !normDestCity.contains(normOrigCity)
            val isCircular = normOrig.isNotBlank() && normOrig == normDest

            DirectionBundle(
                dirCode = dirCode,
                primaryLine = primary,
                lines = sorted,
                normOrig = normOrig,
                normDest = normDest,
                normOrigCity = normOrigCity,
                normDestCity = normDestCity,
                isIntercity = isIntercity,
                isCircular = isCircular
            )
        }.sortedWith(
            compareBy<DirectionBundle>(
                { when (it.dirCode) { "1" -> 0; "2" -> 1; else -> 2 } },
                { alternativeSortKey(it.primaryLine).first },
                { -it.lines.size }
            )
        )

        val used = BooleanArray(bundles.size)
        val pairs = mutableListOf<PairedRouteBundle>()

        fun matchScore(a: DirectionBundle, b: DirectionBundle): Int {
            val differentDirBonus = if (a.dirCode.isNotEmpty() && b.dirCode.isNotEmpty() && a.dirCode != b.dirCode) 1 else 0

            // דרגה 1: תחנות קצה הפוכות זהות (כולל נירמול תחנות מרכזיות רציפים/הורדה)
            if (a.normOrig.isNotBlank() && a.normDest.isNotBlank() &&
                a.normOrig == b.normDest && a.normDest == b.normOrig
            ) {
                if (a.isCircular && b.isCircular && differentDirBonus == 0) return 0
                return 300 + differentDirBonus * 10
            }

            // דרגה 2: קווים בין-עירוניים עם ערים הפוכות (גם אם שמות המסופים שונים מעט בעיר)
            if (a.isIntercity && b.isIntercity &&
                a.normOrigCity == b.normDestCity && a.normDestCity == b.normOrigCity
            ) {
                val partialTerminalMatch = if (a.normOrig == b.normDest || a.normDest == b.normOrig) 20 else 0
                return 200 + partialTerminalMatch + differentDirBonus * 10
            }

            // דרגה 3: שותפות בתחנת קצה מרכזית אחת (כגון קו 33 בכיוון 1 מול 2, ללא קווים סיבוביים)
            if (!a.isCircular && !b.isCircular &&
                a.dirCode in setOf("1", "2") && b.dirCode in setOf("1", "2") && a.dirCode != b.dirCode &&
                ((a.normDest.isNotBlank() && a.normDest == b.normOrig) ||
                    (a.normOrig.isNotBlank() && a.normOrig == b.normDest))
            ) {
                return 100 + differentDirBonus * 10
            }

            return 0
        }

        for (i in bundles.indices) {
            if (used[i]) continue
            used[i] = true
            val first = bundles[i]

            var bestIdx = -1
            var bestScore = 0
            for (j in bundles.indices) {
                if (used[j]) continue
                val score = matchScore(first, bundles[j])
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = j
                }
            }

            if (bestIdx != -1) {
                used[bestIdx] = true
                val second = bundles[bestIdx]
                if (first.dirCode == "2" && second.dirCode == "1") {
                    pairs.add(PairedRouteBundle(forward = second, backward = first))
                } else {
                    pairs.add(PairedRouteBundle(forward = first, backward = second))
                }
            } else {
                pairs.add(PairedRouteBundle(forward = first, backward = null))
            }
        }

        return pairs
    }

    /**
     * חיפוש קווים מאוחד: ממזג תתי-חלופות בעלות אותו מוצא ויעד לכרטיסייה אחת,
     * מפריד חלופות בעלות מוצא/יעד שונה ל'חלופה 1', 'חלופה 2', ומשדך כיוונים גם בתחנות מרכזיות.
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
            (args + listOf(cleanQuery, (limit * 8).coerceIn(1, 3000).toString())).toTypedArray()
        )

        val lineRefGroups = linkedMapOf<String, MutableList<TransitLine>>()
        for (line in rawLines) {
            val descParts = line.routeDesc.split("-", "–")
            val lineRef = descParts.getOrNull(0)?.trim().orEmpty()

            val key = if (lineRef.isNotEmpty()) {
                "${line.agencyId}_${line.lineNumber}_${lineRef}"
            } else {
                val cA = line.originCity.ifBlank { extractCity(line.originName) }
                val cB = line.destinationCity.ifBlank { extractCity(line.destinationName) }
                "${line.agencyId}_${line.lineNumber}_${formatCityHeader(cA, cB)}"
            }

            lineRefGroups.getOrPut(key) { mutableListOf() }.add(line)
        }

        val result = mutableListOf<GroupedTransitLine>()

        for ((_, linesList) in lineRefGroups) {
            val pairedBundles = buildPairedBundles(linesList)
            val fallbackCityA = linesList.firstNotNullOfOrNull { it.originCity.ifBlank { null } }.orEmpty()
            val fallbackCityB = linesList.firstNotNullOfOrNull { it.destinationCity.ifBlank { null } }.orEmpty()

            val matchingItems = mutableListOf<GroupedTransitLine>()

            for (bundle in pairedBundles) {
                val forward = bundle.forward.primaryLine
                val backward = bundle.backward?.primaryLine

                val originStation = forward.originName.ifBlank { forward.name }
                val destStation = forward.destinationName.ifBlank { backward?.originName.orEmpty() }

                val cityA = forward.originCity
                    .ifBlank { backward?.destinationCity.orEmpty() }
                    .ifBlank { fallbackCityA }
                val cityB = forward.destinationCity
                    .ifBlank { backward?.originCity.orEmpty() }
                    .ifBlank { fallbackCityB }

                val normA = OperatorTheme.normalizeHebrew(cityA)
                val normB = OperatorTheme.normalizeHebrew(cityB)
                val isUrban = cityA.isNotBlank() && cityB.isNotBlank() &&
                    (normA == normB || normA.contains(normB) || normB.contains(normA))

                val cityHeader = if (isUrban || cityB.isBlank()) {
                    cityA.ifBlank { cityB }
                } else {
                    "$cityA ⟷ $cityB"
                }

                if (cleanCity.isNotEmpty()) {
                    val normCity = OperatorTheme.normalizeHebrew(cleanCity)
                    val normOrigCity = OperatorTheme.normalizeHebrew(cityA)
                    val normDestCity = OperatorTheme.normalizeHebrew(cityB)
                    val matchesCity = normOrigCity.contains(normCity) || normDestCity.contains(normCity)
                    if (!matchesCity) {
                        continue
                    }
                }

                matchingItems.add(
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
                        isUrban = isUrban,
                        alternativeLabel = "",
                        forwardRouteIds = bundle.forward.routeIds,
                        backwardRouteIds = bundle.backward?.routeIds ?: emptyList()
                    )
                )
            }

            val showAlternativeNumbers = matchingItems.size > 1
            matchingItems.forEachIndexed { idx, item ->
                if (result.size < limit) {
                    result.add(
                        if (showAlternativeNumbers) item.copy(alternativeLabel = "חלופה ${idx + 1}")
                        else item
                    )
                }
            }

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
     * מחזיר את כל ההקשר של קו עבור מסך פרטי הקו:
     * 1. חלופות שונות של אותו קו (עבור הבורר העליון ומצב 'כל החלופות')
     * 2. תתי-מסלולים בעלי אותם מוצא ויעד (למצב אוטומטי לפי שעה + בורר ידני)
     * 3. השוואת תחנות אוטומטית להסבר ההבדלים בלחיצת כפתור
     */
    fun getLineDetailsContext(routeId: String): LineDetailsContext {
        val currentLine = getLine(routeId) ?: return LineDetailsContext(
            alternatives = emptyList(),
            activeAlternativeIndex = 0,
            subVariants = emptyList(),
            oppositeRouteIds = emptyList()
        )

        val lineRef = currentLine.routeDesc.split("-", "–").getOrNull(0)?.trim().orEmpty()
        val siblingLines = queryLines(
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
            WHERE r.route_short_name = ?
              AND r.agency_id = ?
            ORDER BY r.route_id
            LIMIT 100
            """.trimIndent(),
            arrayOf(currentLine.lineNumber, currentLine.agencyId)
        ).filter { candidate ->
            if (lineRef.isNotEmpty()) {
                candidate.routeDesc.split("-", "–").getOrNull(0)?.trim() == lineRef
            } else {
                val curHeader = formatCityHeader(currentLine.originCity, currentLine.destinationCity)
                val candHeader = formatCityHeader(candidate.originCity, candidate.destinationCity)
                curHeader.isBlank() || candHeader.isBlank() || curHeader == candHeader
            }
        }

        if (siblingLines.isEmpty()) {
            return LineDetailsContext(
                alternatives = listOf(
                    LineAlternativeOption(
                        index = 0,
                        label = "חלופה 1",
                        primaryLine = currentLine,
                        routeIds = listOf(currentLine.routeId),
                        oppositeRouteIds = emptyList(),
                        differenceSummary = ""
                    )
                ),
                activeAlternativeIndex = 0,
                subVariants = emptyList(),
                oppositeRouteIds = emptyList()
            )
        }

        val pairedBundles = buildPairedBundles(siblingLines)

        var activePairIdx = pairedBundles.indexOfFirst { pair ->
            pair.forward.lines.any { it.routeId == routeId } ||
                (pair.backward?.lines?.any { it.routeId == routeId } == true)
        }
        if (activePairIdx < 0) activePairIdx = 0

        val activePair = pairedBundles[activePairIdx]
        val isBackwardSide = activePair.backward?.lines?.any { it.routeId == routeId } == true

        val alternativeSides = pairedBundles.mapIndexedNotNull { idx, pair ->
            val activeBundle = if (isBackwardSide) {
                pair.backward ?: pair.forward
            } else {
                pair.forward
            }
            val oppositeBundle = if (isBackwardSide) {
                if (pair.backward != null) pair.forward else null
            } else {
                pair.backward
            }
            Triple(idx, activeBundle, oppositeBundle)
        }

        val stopsByRoute = mutableMapOf<String, List<String>>()
        for ((_, bundle, _) in alternativeSides) {
            for (l in bundle.lines) {
                if (l.routeId !in stopsByRoute) {
                    stopsByRoute[l.routeId] = getStopsForLine(l.routeId, 300).map { it.name }
                }
            }
        }

        fun buildDiffSummary(myStops: List<String>, otherStopsList: List<List<String>>, orig: String, dest: String): String {
            if (otherStopsList.isEmpty()) return "${myStops.size} תחנות"
            val allOtherStops = otherStopsList.flatten().toSet()
            val uniqueToMe = myStops.filter { it !in allOtherStops }
            val commonToOthers = otherStopsList.firstOrNull()?.filter { it !in myStops.toSet() }.orEmpty()

            return buildString {
                if (orig.isNotBlank() && dest.isNotBlank()) {
                    append("$orig ← $dest")
                }
                if (uniqueToMe.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append("עובר ב: ${uniqueToMe.take(2).joinToString(", ")}")
                } else if (commonToOthers.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append("ללא: ${commonToOthers.take(2).joinToString(", ")}")
                }
                if (isNotEmpty()) append(" ")
                append("(${myStops.size} תחנות)")
            }
        }

        val alternatives = alternativeSides.mapIndexed { listIdx, (pairIdx, bundle, oppBundle) ->
            val primaryStops = stopsByRoute[bundle.primaryLine.routeId].orEmpty()
            val otherPrimaryStops = alternativeSides
                .filterIndexed { otherIdx, _ -> otherIdx != listIdx }
                .map { stopsByRoute[it.second.primaryLine.routeId].orEmpty() }

            val summary = buildDiffSummary(
                myStops = primaryStops,
                otherStopsList = otherPrimaryStops,
                orig = bundle.primaryLine.originName,
                dest = bundle.primaryLine.destinationName
            )

            LineAlternativeOption(
                index = pairIdx,
                label = "חלופה ${listIdx + 1}",
                primaryLine = bundle.primaryLine,
                routeIds = bundle.routeIds,
                oppositeRouteIds = oppBundle?.routeIds ?: emptyList(),
                differenceSummary = summary
            )
        }

        val resolvedActiveAltIndex = alternatives.indexOfFirst { routeId in it.routeIds }.coerceAtLeast(0)
        val activeAlt = alternatives.getOrNull(resolvedActiveAltIndex)
        val activeSubIds = activeAlt?.routeIds ?: listOf(routeId)

        val subVariants = if (activeSubIds.size > 1) {
            activeSubIds.mapIndexed { idx, subId ->
                val myStops = stopsByRoute[subId].orEmpty()
                val others = activeSubIds.filter { it != subId }.map { stopsByRoute[it].orEmpty() }
                val allOtherSet = others.flatten().toSet()
                val unique = myStops.filter { it !in allOtherSet }
                val omitted = others.firstOrNull()?.filter { it !in myStops.toSet() }.orEmpty()

                val diff = when {
                    unique.isNotEmpty() -> "דרך ${unique.take(2).joinToString(", ")} (${myStops.size} תחנות)"
                    omitted.isNotEmpty() -> "מדלג על ${omitted.take(2).joinToString(", ")} (${myStops.size} תחנות)"
                    else -> "${myStops.size} תחנות"
                }

                SubVariantInfo(
                    routeId = subId,
                    label = "מסלול ${idx + 1}",
                    stopCount = myStops.size,
                    differenceSummary = diff
                )
            }
        } else {
            emptyList()
        }

        return LineDetailsContext(
            alternatives = alternatives,
            activeAlternativeIndex = resolvedActiveAltIndex,
            subVariants = subVariants,
            oppositeRouteIds = activeAlt?.oppositeRouteIds ?: emptyList()
        )
    }

    fun findOppositeLine(line: TransitLine): TransitLine? {
        val context = getLineDetailsContext(line.routeId)
        val oppId = context.oppositeRouteIds.firstOrNull() ?: return null
        return getLine(oppId)
    }

    private fun normalizeStopQuery(text: String): String {
        return text
            .replace("[\"'״׳]".toRegex(), "")
            .replace("[/\\-–]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .lowercase()
    }

    /**
     * חיפוש תחנות ורחובות עם נירמול עברי חכם (מתעלם מסימונים כמו ", ', / ומריבוי רווחים)
     * ותמיכה באיחוד רציפים של תחנות מרכזיות לתוצאה אחת
     */
    fun searchStops(
        searchText: String,
        limit: Int = 100,
        savedStops: List<SavedStopRef> = emptyList()
    ): List<TransitStop> {
        val raw = searchText.trim()
        if (raw.isEmpty()) return emptyList()

        val normQuery = normalizeStopQuery(raw)
        val tokens = normQuery.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty() && raw.isEmpty()) return emptyList()

        val matchedSavedStops = savedStops.filter { saved ->
            val normCustom = normalizeStopQuery(saved.customName)
            val matchesWords = tokens.isNotEmpty() && tokens.all { normCustom.contains(it) }
            matchesWords || saved.stopCode.startsWith(raw)
        }

        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            val schema = getStopsSchemaInfo(db)
            val parentSelect = if (schema.hasParentStation) "COALESCE(parent_station, '')" else "''"

            val normalizedStopSql =
                "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(stop_name, ''), '\"', ''), '''', ''), '״', ''), '׳', ''), '/', ' '), '-', ' '), '–', ' '))"

            val wordConditions = if (tokens.isNotEmpty()) {
                tokens.joinToString(" AND ") { "$normalizedStopSql LIKE ?" }
            } else {
                "stop_name LIKE ?"
            }

            val wordArgs = if (tokens.isNotEmpty()) {
                tokens.map { "%$it%" }
            } else {
                listOf("%$raw%")
            }

            val sql = """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    stop_lat,
                    stop_lon,
                    $parentSelect AS parent_id
                FROM stops
                WHERE ($wordConditions)
                   OR stop_code LIKE ?
                   OR stop_id LIKE ?
                ORDER BY
                    CASE
                        WHEN stop_code = ? THEN 0
                        WHEN stop_id = ? THEN 1
                        WHEN $parentSelect = '' THEN 2
                        ELSE 3
                    END,
                    CASE
                        WHEN $normalizedStopSql LIKE ? THEN 0
                        WHEN stop_name LIKE ? THEN 1
                        ELSE 2
                    END,
                    stop_name
                LIMIT ?
            """.trimIndent()

            val queryArgs = wordArgs + listOf(
                "$raw%",
                "$raw%",
                raw,
                raw,
                "$normQuery%",
                "$raw%",
                (limit * 4).coerceIn(1, 1000).toString()
            )

            data class RawStopRow(val stop: TransitStop, val parentId: String)

            val rawRows = db.rawQuery(sql, queryArgs.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sId = cursor.getString(0) ?: ""
                        val sCode = cursor.getString(1) ?: ""
                        val sName = cursor.getString(2) ?: ""
                        val sLat = if (cursor.isNull(3)) null else cursor.getDouble(3)
                        val sLon = if (cursor.isNull(4)) null else cursor.getDouble(4)
                        val parentId = cursor.getString(5) ?: ""

                        val custom = savedStops.firstOrNull { it.stopId == sId }?.customName

                        add(
                            RawStopRow(
                                stop = TransitStop(
                                    stopId = sId,
                                    stopCode = sCode,
                                    name = sName,
                                    latitude = sLat,
                                    longitude = sLon,
                                    customName = custom
                                ),
                                parentId = parentId
                            )
                        )
                    }
                }
            }

            val parentCache = mutableMapOf<String, TransitStop?>()
            fun loadParent(parentId: String): TransitStop? {
                return parentCache.getOrPut(parentId) {
                    db.rawQuery(
                        """
                        SELECT stop_id, COALESCE(NULLIF(stop_code, ''), stop_id), COALESCE(stop_name, ''), stop_lat, stop_lon
                        FROM stops WHERE stop_id = ? LIMIT 1
                        """.trimIndent(),
                        arrayOf(parentId)
                    ).use { c ->
                        if (c.moveToFirst()) {
                            val pId = c.getString(0) ?: parentId
                            TransitStop(
                                stopId = pId,
                                stopCode = c.getString(1) ?: pId,
                                name = c.getString(2) ?: "",
                                latitude = if (c.isNull(3)) null else c.getDouble(3),
                                longitude = if (c.isNull(4)) null else c.getDouble(4),
                                customName = savedStops.firstOrNull { it.stopId == pId }?.customName
                            )
                        } else null
                    }
                }
            }

            val finalStops = mutableListOf<TransitStop>()
            val seenIds = mutableSetOf<String>()

            for (saved in matchedSavedStops) {
                val existing = rawRows.firstOrNull { it.stop.stopId == saved.stopId }?.stop
                val resolved = existing?.copy(customName = saved.customName) ?: loadParent(saved.stopId)?.copy(customName = saved.customName)
                if (resolved != null && seenIds.add(resolved.stopId)) {
                    finalStops.add(resolved)
                }
            }

            val isExactChildCodeSearch = raw.all { it.isDigit() } &&
                rawRows.any { it.parentId.isNotBlank() && it.stop.stopCode == raw } &&
                rawRows.none { it.parentId.isBlank() && it.stop.stopCode == raw }

            for (row in rawRows) {
                val resolvedStop = if (row.parentId.isNotBlank() && !isExactChildCodeSearch) {
                    loadParent(row.parentId) ?: row.stop
                } else {
                    row.stop
                }

                if (seenIds.add(resolvedStop.stopId)) {
                    finalStops.add(resolvedStop)
                }
                if (finalStops.size >= limit) break
            }

            finalStops.take(limit)
        } finally {
            db.close()
        }
    }

    fun getPlatformsForStop(stopId: String): List<StopPlatform> {
        if (stopId.isBlank()) return emptyList()
        val db = databaseProvider.openReadOnly() ?: return emptyList()

        return try {
            val schema = getStopsSchemaInfo(db)
            if (!schema.hasParentStation) return emptyList()

            val platformColSql = if (schema.hasPlatformCode) "COALESCE(platform_code, '')" else "''"
            val descColSql = if (schema.hasStopDesc) "COALESCE(stop_desc, '')" else "''"

            val descPlatformRegex = Regex("רציף:\\s*([^\\s\\n\\r]+)")
            val namePlatformRegex = Regex("רציף\\s+([^\\s/\\-]+)")

            val platforms = db.rawQuery(
                """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, ''),
                    $platformColSql AS p_code,
                    $descColSql AS s_desc
                FROM stops
                WHERE parent_station = ?
                """.trimIndent(),
                arrayOf(stopId)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sId = cursor.getString(0) ?: continue
                        val sCode = cursor.getString(1) ?: sId
                        val sName = cursor.getString(2) ?: ""
                        val pCodeCol = cursor.getString(3)?.trim().orEmpty()
                        val sDesc = cursor.getString(4).orEmpty()

                        val pFromDesc = descPlatformRegex.find(sDesc)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val pFromName = namePlatformRegex.find(sName)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val platformCode = pCodeCol.ifBlank { pFromDesc }.ifBlank { pFromName }

                        val label = when {
                            platformCode.isNotBlank() -> "רציף $platformCode"
                            sName.isNotBlank() -> sName
                            else -> "רציף ($sCode)"
                        }

                        add(
                            StopPlatform(
                                stopId = sId,
                                stopCode = sCode,
                                name = sName,
                                platformCode = platformCode,
                                displayLabel = label
                            )
                        )
                    }
                }
            }

            platforms.sortedWith(
                compareBy<StopPlatform>(
                    { it.platformCode.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.platformCode },
                    { it.stopCode }
                )
            )
        } catch (_: Exception) {
            emptyList()
        } finally {
            db.close()
        }
    }

    fun getStopById(
        stopId: String,
        savedStops: List<SavedStopRef> = emptyList()
    ): TransitStop? {
        if (stopId.isBlank()) return null
        val db = databaseProvider.openReadOnly() ?: return null
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
                WHERE stop_id = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(stopId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val sId = cursor.getString(0) ?: stopId
                    TransitStop(
                        stopId = sId,
                        stopCode = cursor.getString(1) ?: sId,
                        name = cursor.getString(2) ?: "",
                        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        customName = savedStops.firstOrNull { it.stopId == sId }?.customName
                    )
                } else null
            }
        } finally {
            db.close()
        }
    }

    fun getStopCity(stopId: String): String {
        if (stopId.isBlank()) return ""
        val db = databaseProvider.openReadOnly() ?: return ""
        return try {
            val schema = getStopsSchemaInfo(db)
            if (!schema.hasStopDesc) return ""

            val cityRegex = Regex("עיר:\\s*([^\\n\\r]+?)(?=\\s+(?:רציף|קומה|רחוב|שכונה|אזור):|$)")

            fun extractFromDesc(desc: String?): String {
                if (desc.isNullOrBlank()) return ""
                return cityRegex.find(desc)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }

            val sql = if (schema.hasParentStation) {
                "SELECT COALESCE(stop_desc, ''), COALESCE(parent_station, '') FROM stops WHERE stop_id = ? LIMIT 1"
            } else {
                "SELECT COALESCE(stop_desc, ''), '' FROM stops WHERE stop_id = ? LIMIT 1"
            }

            var parentStation = ""
            db.rawQuery(sql, arrayOf(stopId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val city = extractFromDesc(cursor.getString(0))
                    if (city.isNotBlank()) return city
                    parentStation = cursor.getString(1) ?: ""
                }
            }

            if (schema.hasParentStation && parentStation.isNotBlank()) {
                db.rawQuery(
                    "SELECT COALESCE(stop_desc, '') FROM stops WHERE stop_id = ? LIMIT 1",
                    arrayOf(parentStation)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val city = extractFromDesc(cursor.getString(0))
                        if (city.isNotBlank()) return city
                    }
                }
            }

            ""
        } catch (_: Exception) {
            ""
        } finally {
            db.close()
        }
    }

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
            }.take(limit)
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
                    rps.stop_sequence,
                    NULL AS arrival_time,
                    NULL AS departure_time,
                    COALESCE(NULLIF(s.stop_code, ''), s.stop_id) AS real_stop_code
                FROM routes r
                JOIN route_patterns rp ON rp.route_idx = r.route_idx
                JOIN route_pattern_stops rps ON rps.pattern_id = rp.pattern_id
                JOIN stops s ON s.stop_idx = rps.stop_idx
                WHERE r.route_id = ?
                  AND rp.pattern_id = (
                      SELECT rp2.pattern_id
                      FROM route_patterns rp2
                      WHERE rp2.route_idx = r.route_idx
                      ORDER BY rp2.stop_count DESC, rp2.pattern_id
                      LIMIT 1
                  )
                ORDER BY rps.stop_sequence
                LIMIT ?
                """.trimIndent(),
                arrayOf(
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
            val schema = getStopsSchemaInfo(db)
            val stopFilterSql = if (schema.hasParentStation) {
                "(target.stop_id = ? OR target.parent_station = ?)"
            } else {
                "target.stop_id = ?"
            }

            val queryArgs = if (schema.hasParentStation) {
                arrayOf(stopId, stopId, limit.coerceIn(1, 500).toString())
            } else {
                arrayOf(stopId, limit.coerceIn(1, 500).toString())
            }

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
                FROM stops target
                JOIN route_pattern_stops rps ON rps.stop_idx = target.stop_idx
                JOIN route_patterns rp ON rp.pattern_id = rps.pattern_id
                JOIN routes r ON r.route_idx = rp.route_idx
                LEFT JOIN agency a ON a.agency_id = r.agency_id
                WHERE $stopFilterSql
                LIMIT ?
                """.trimIndent(),
                queryArgs
            ).use { cursor ->
                val lines = mutableListOf<TransitLine>()
                while (cursor.moveToNext()) {
                    val longName = cursor.getString(2) ?: ""
                    val routeDesc = cursor.getString(6) ?: ""
                    val parsed = parseRouteLongName(longName, routeDesc)

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
                            routeDesc = routeDesc
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
