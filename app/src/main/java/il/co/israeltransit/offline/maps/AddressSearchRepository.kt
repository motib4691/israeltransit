package il.co.israeltransit.offline.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos

class AddressSearchRepository(context: Context) {

    private val mapStorage = OfflineMapStorage(context.applicationContext)

    enum class ResultKind {
        CITY,
        PLACE,
        STREET,
        ADDRESS
    }

    data class AddressResult(
        val title: String,
        val subtitle: String,
        val latitude: Double,
        val longitude: Double,
        val kind: ResultKind
    )

    private fun openReadOnly(): SQLiteDatabase? {
        val file = mapStorage.getAddressesFile()
        if (!mapStorage.isAddressesAvailable()) return null
        return try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        } catch (_: Exception) {
            null
        }
    }

    fun search(rawQuery: String, limit: Int = 25): List<AddressResult> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return emptyList()

        val db = openReadOnly() ?: return emptyList()
        val results = mutableListOf<AddressResult>()
        val safeLimit = limit.coerceIn(1, 60)

        try {
            // הפרדה לפי פסיק אם המשתמש הקליד "רחוב, עיר"
            val commaParts = trimmed.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val firstPart = commaParts.firstOrNull().orEmpty()
            val secondPart = commaParts.getOrNull(1).orEmpty()

            // זיהוי מספר בית (למשל "הרצל 15" או "הרצל 15א")
            val tokens = firstPart.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val houseNumberToken = if (tokens.size >= 2) {
                tokens.lastOrNull { token ->
                    token.any { it.isDigit() } && token.length <= 6
                }
            } else null

            val streetWords = if (houseNumberToken != null) {
                tokens.filterNot { it == houseNumberToken }.joinToString(" ")
            } else {
                firstPart
            }

            // 1. חיפוש כתובת מדויקת עם מספר בית אם הוזן מספר
            if (houseNumberToken != null && streetWords.isNotBlank()) {
                val sql = if (secondPart.isNotEmpty()) {
                    """
                    SELECT s.city, s.street_name, a.house_number, a.lat, a.lon
                    FROM addresses a
                    JOIN streets s ON s.street_id = a.street_id
                    WHERE s.street_name LIKE ? AND a.house_number = ? AND s.city LIKE ?
                    ORDER BY CASE WHEN s.street_name = ? THEN 0 ELSE 1 END, s.city, s.street_name
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    SELECT s.city, s.street_name, a.house_number, a.lat, a.lon
                    FROM addresses a
                    JOIN streets s ON s.street_id = a.street_id
                    WHERE s.street_name LIKE ? AND a.house_number = ?
                    ORDER BY CASE WHEN s.street_name = ? THEN 0 ELSE 1 END, s.city, s.street_name
                    LIMIT ?
                    """.trimIndent()
                }

                val args = if (secondPart.isNotEmpty()) {
                    arrayOf(
                        "%$streetWords%",
                        houseNumberToken,
                        "%$secondPart%",
                        streetWords,
                        (safeLimit / 2).coerceAtLeast(8).toString()
                    )
                } else {
                    arrayOf(
                        "%$streetWords%",
                        houseNumberToken,
                        streetWords,
                        (safeLimit / 2).coerceAtLeast(8).toString()
                    )
                }

                db.rawQuery(sql, args).use { cursor ->
                    while (cursor.moveToNext()) {
                        val city = cursor.getString(0) ?: ""
                        val street = cursor.getString(1) ?: ""
                        val house = cursor.getString(2) ?: ""
                        val lat = cursor.getDouble(3)
                        val lon = cursor.getDouble(4)

                        results.add(
                            AddressResult(
                                title = "$street $house",
                                subtitle = city.ifBlank { "כתובת" },
                                latitude = lat,
                                longitude = lon,
                                kind = ResultKind.ADDRESS
                            )
                        )
                    }
                }
            }

            // 2. חיפוש ערים, יישובים, שכונות ומוסדות/אתרים מרכזיים (רק אם לא הוזן מספר בית או פסיק)
            if (houseNumberToken == null && secondPart.isEmpty()) {
                db.rawQuery(
                    """
                    SELECT name, kind, lat, lon
                    FROM places
                    WHERE name LIKE ?
                    ORDER BY
                        CASE
                            WHEN name = ? THEN 0
                            WHEN name LIKE ? THEN 1
                            ELSE 2
                        END,
                        CASE kind
                            WHEN 'city' THEN 0
                            WHEN 'town' THEN 1
                            WHEN 'hospital' THEN 2
                            WHEN 'university' THEN 3
                            WHEN 'airport' THEN 4
                            WHEN 'train_station' THEN 5
                            WHEN 'bus_station' THEN 6
                            WHEN 'mall' THEN 7
                            WHEN 'suburb' THEN 8
                            WHEN 'quarter' THEN 9
                            WHEN 'neighbourhood' THEN 10
                            WHEN 'village' THEN 11
                            ELSE 12
                        END,
                        name
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        "%$trimmed%",
                        trimmed,
                        "$trimmed%",
                        14.toString()
                    )
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        val kindStr = cursor.getString(1) ?: ""
                        val lat = cursor.getDouble(2)
                        val lon = cursor.getDouble(3)

                        val isCity = kindStr == "city" || kindStr == "town" || kindStr == "village"
                        val subtitle = when (kindStr) {
                            "city" -> "עיר"
                            "town" -> "עיר / יישוב"
                            "village" -> "יישוב / כפר"
                            "suburb", "quarter", "neighbourhood" -> "שכונה / רובע"
                            "hospital" -> "בית חולים / מרכז רפואי"
                            "university" -> "אוניברסיטה / מכללה"
                            "mall" -> "קניון / מרכז קניות"
                            "airport" -> "שדה תעופה"
                            "train_station" -> "תחנת רכבת"
                            "bus_station" -> "מסוף / תחנה מרכזית"
                            "parking" -> "חניון חנה וסע"
                            "park" -> "פארק / שמורת טבע"
                            "poi" -> "מוזיאון / אתר מרכזי"
                            else -> "אתר / שכונה"
                        }

                        results.add(
                            AddressResult(
                                title = name,
                                subtitle = subtitle,
                                latitude = lat,
                                longitude = lon,
                                kind = if (isCity) ResultKind.CITY else ResultKind.PLACE
                            )
                        )
                    }
                }
            }

            // 3. חיפוש רחובות
            val streetQuery = streetWords.ifBlank { trimmed }
            val streetSql = if (secondPart.isNotEmpty()) {
                """
                SELECT city, street_name, lat, lon
                FROM streets
                WHERE street_name LIKE ? AND city LIKE ?
                ORDER BY
                    CASE WHEN street_name = ? THEN 0 WHEN street_name LIKE ? THEN 1 ELSE 2 END,
                    city, street_name
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT city, street_name, lat, lon
                FROM streets
                WHERE street_name LIKE ?
                ORDER BY
                    CASE WHEN street_name = ? THEN 0 WHEN street_name LIKE ? THEN 1 ELSE 2 END,
                    city, street_name
                LIMIT ?
                """.trimIndent()
            }

            val streetArgs = if (secondPart.isNotEmpty()) {
                arrayOf(
                    "%$streetQuery%",
                    "%$secondPart%",
                    streetQuery,
                    "$streetQuery%",
                    safeLimit.toString()
                )
            } else {
                arrayOf(
                    "%$streetQuery%",
                    streetQuery,
                    "$streetQuery%",
                    safeLimit.toString()
                )
            }

            db.rawQuery(streetSql, streetArgs).use { cursor ->
                while (cursor.moveToNext()) {
                    val city = cursor.getString(0) ?: ""
                    val street = cursor.getString(1) ?: continue
                    val lat = cursor.getDouble(2)
                    val lon = cursor.getDouble(3)

                    results.add(
                        AddressResult(
                            title = street,
                            subtitle = if (city.isNotBlank()) "רחוב • $city" else "רחוב",
                            latitude = lat,
                            longitude = lon,
                            kind = ResultKind.STREET
                        )
                    )
                }
            }

            // 4. אם המשתמש הקליד "שםרחוב שםעיר" בלי פסיק (למשל "הרצל רחובות")
            if (secondPart.isEmpty() && houseNumberToken == null && tokens.size >= 2) {
                val possibleStreet = tokens.dropLast(1).joinToString(" ")
                val possibleCity = tokens.last()
                db.rawQuery(
                    """
                    SELECT city, street_name, lat, lon
                    FROM streets
                    WHERE street_name LIKE ? AND city LIKE ?
                    ORDER BY CASE WHEN street_name = ? THEN 0 ELSE 1 END, city, street_name
                    LIMIT 12
                    """.trimIndent(),
                    arrayOf("%$possibleStreet%", "%$possibleCity%", possibleStreet)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val city = cursor.getString(0) ?: ""
                        val street = cursor.getString(1) ?: continue
                        val lat = cursor.getDouble(2)
                        val lon = cursor.getDouble(3)
                        results.add(
                            AddressResult(
                                title = street,
                                subtitle = if (city.isNotBlank()) "רחוב • $city" else "רחוב",
                                latitude = lat,
                                longitude = lon,
                                kind = ResultKind.STREET
                            )
                        )
                    }
                }
            }

        } catch (_: Exception) {
            // אם מאגר הכתובות טרם יובא או חלקי - מחזיר מה שנמצא בלי לקרוס
        } finally {
            db.close()
        }

        return results
            .distinctBy { "${it.kind}|${it.title}|${it.subtitle}" }
            .take(safeLimit)
    }

    /**
     * זיהוי הכתובת, הרחוב, השכונה או היישוב הקרוב ביותר לקואורדינטה (עבור לחיצה ארוכה על המפה)
     */
    fun findNearestPlaceLabel(latitude: Double, longitude: Double): String? {
        val db = openReadOnly() ?: return null
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)

        try {
            // 1. חיפוש כתובת/בניין קרוב ברדיוס של ~180 מטר
            val aLat = 0.0018
            val aLon = 0.0018 / cosLat

            var bestAddressLabel: String? = null
            var bestAddressDist = Double.MAX_VALUE

            db.rawQuery(
                """
                SELECT s.street_name, a.house_number, s.city, a.lat, a.lon
                FROM addresses a
                JOIN streets s ON s.street_id = a.street_id
                WHERE a.lat BETWEEN ? AND ? AND a.lon BETWEEN ? AND ?
                LIMIT 150
                """.trimIndent(),
                arrayOf(
                    (latitude - aLat).toString(),
                    (latitude + aLat).toString(),
                    (longitude - aLon).toString(),
                    (longitude + aLon).toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val street = cursor.getString(0) ?: continue
                    val house = cursor.getString(1) ?: ""
                    val city = cursor.getString(2) ?: ""
                    val pLat = cursor.getDouble(3)
                    val pLon = cursor.getDouble(4)
                    val d2 = (latitude - pLat) * (latitude - pLat) +
                        ((longitude - pLon) * cosLat) * ((longitude - pLon) * cosLat)
                    if (d2 < bestAddressDist) {
                        bestAddressDist = d2
                        val base = if (house.isNotBlank()) "$street $house" else street
                        bestAddressLabel = if (city.isNotBlank()) "$base, $city" else base
                    }
                }
            }

            if (bestAddressLabel != null) return bestAddressLabel

            // 2. חיפוש רחוב קרוב ברדיוס של ~900 מטר
            val dLat = 0.0085
            val dLon = 0.0085 / cosLat

            var bestStreet: String? = null
            var bestStreetDist = Double.MAX_VALUE

            db.rawQuery(
                """
                SELECT street_name, city, lat, lon
                FROM streets
                WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                LIMIT 200
                """.trimIndent(),
                arrayOf(
                    (latitude - dLat).toString(),
                    (latitude + dLat).toString(),
                    (longitude - dLon).toString(),
                    (longitude + dLon).toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val street = cursor.getString(0) ?: continue
                    val city = cursor.getString(1) ?: ""
                    val sLat = cursor.getDouble(2)
                    val sLon = cursor.getDouble(3)
                    val d2 = (latitude - sLat) * (latitude - sLat) +
                        ((longitude - sLon) * cosLat) * ((longitude - sLon) * cosLat)
                    if (d2 < bestStreetDist) {
                        bestStreetDist = d2
                        bestStreet = if (city.isNotBlank()) "$street, $city" else street
                    }
                }
            }

            if (bestStreet != null) return bestStreet

            // 3. גיבוי: עדיפות לשכונה/רובע בטווח של ~2.5 ק"מ על פני כפר/יישוב חיצוני סמוך
            val nDelta = 0.025
            val nLonDelta = 0.025 / cosLat
            var bestNeighbourhood: String? = null
            var bestNeighbourhoodDist = Double.MAX_VALUE

            db.rawQuery(
                """
                SELECT name, lat, lon
                FROM places
                WHERE kind IN ('suburb', 'quarter', 'neighbourhood')
                  AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                LIMIT 80
                """.trimIndent(),
                arrayOf(
                    (latitude - nDelta).toString(),
                    (latitude + nDelta).toString(),
                    (longitude - nLonDelta).toString(),
                    (longitude + nLonDelta).toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val pLat = cursor.getDouble(1)
                    val pLon = cursor.getDouble(2)
                    val d2 = (latitude - pLat) * (latitude - pLat) +
                        ((longitude - pLon) * cosLat) * ((longitude - pLon) * cosLat)
                    if (d2 < bestNeighbourhoodDist) {
                        bestNeighbourhoodDist = d2
                        bestNeighbourhood = name
                    }
                }
            }

            // חיפוש עיר/יישוב קרוב ברדיוס של ~10 ק"מ
            val pDelta = 0.1
            var bestCity: String? = null
            var bestCityDist = Double.MAX_VALUE
            var bestSettlement: String? = null
            var bestSettlementDist = Double.MAX_VALUE

            db.rawQuery(
                """
                SELECT name, kind, lat, lon
                FROM places
                WHERE kind IN ('city', 'town', 'village')
                  AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                LIMIT 80
                """.trimIndent(),
                arrayOf(
                    (latitude - pDelta).toString(),
                    (latitude + pDelta).toString(),
                    (longitude - pDelta).toString(),
                    (longitude + pDelta).toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val kind = cursor.getString(1) ?: ""
                    val pLat = cursor.getDouble(2)
                    val pLon = cursor.getDouble(3)
                    val d2 = (latitude - pLat) * (latitude - pLat) +
                        ((longitude - pLon) * cosLat) * ((longitude - pLon) * cosLat)
                    if ((kind == "city" || kind == "town") && d2 < bestCityDist) {
                        bestCityDist = d2
                        bestCity = name
                    }
                    if (d2 < bestSettlementDist) {
                        bestSettlementDist = d2
                        bestSettlement = name
                    }
                }
            }

            if (bestNeighbourhood != null) {
                return if (bestCity != null && bestNeighbourhood != bestCity) {
                    "$bestNeighbourhood, $bestCity"
                } else {
                    bestNeighbourhood
                }
            }

            return bestSettlement?.let { "סמוך ל$it" }
        } catch (_: Exception) {
            return null
        } finally {
            db.close()
        }
    }
}
