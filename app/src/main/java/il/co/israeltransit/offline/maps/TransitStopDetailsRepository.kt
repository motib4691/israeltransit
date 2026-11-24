package il.co.israeltransit.offline.maps

import android.content.Context
import il.co.israeltransit.offline.database.TransitDatabase

data class TransitStopLine(
    val routeId: String,
    val lineNumber: String,
    val lineName: String
)

class TransitStopDetailsRepository(context: Context) {

    private val database = TransitDatabase(context.applicationContext)

    fun getLinesForStop(stopId: String): List<TransitStopLine> {
        require(stopId.isNotBlank()) { "מספר התחנה אינו תקין" }

        val db = database.openReadOnly()
            ?: throw IllegalStateException("לא ניתן לפתוח את מאגר התחבורה")

        return db.use { readableDatabase ->
            val result = mutableListOf<TransitStopLine>()

            val sql = """
                SELECT DISTINCT
                    r.route_id,
                    COALESCE(NULLIF(r.route_short_name, ''), r.route_id) AS line_number,
                    COALESCE(r.route_long_name, '') AS line_name
                FROM stops AS s
                INNER JOIN route_pattern_stops AS rps
                    ON rps.stop_idx = s.stop_idx
                INNER JOIN route_patterns AS rp
                    ON rp.pattern_id = rps.pattern_id
                INNER JOIN routes AS r
                    ON r.route_idx = rp.route_idx
                WHERE s.stop_id = ? OR s.parent_station = ?
                ORDER BY line_number, line_name
                LIMIT 100
            """.trimIndent()

            readableDatabase.rawQuery(sql, arrayOf(stopId, stopId)).use { cursor ->
                val routeIdIndex = cursor.getColumnIndexOrThrow("route_id")
                val lineNumberIndex = cursor.getColumnIndexOrThrow("line_number")
                val lineNameIndex = cursor.getColumnIndexOrThrow("line_name")

                while (cursor.moveToNext()) {
                    result.add(
                        TransitStopLine(
                            routeId = cursor.getString(routeIdIndex),
                            lineNumber = cursor.getString(lineNumberIndex),
                            lineName = cursor.getString(lineNameIndex)
                        )
                    )
                }
            }

            result
        }
    }
}
