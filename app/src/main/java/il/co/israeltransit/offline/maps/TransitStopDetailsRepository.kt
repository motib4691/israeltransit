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
                FROM stop_times AS st
                INNER JOIN trips AS t
                    ON t.trip_id = st.trip_id
                INNER JOIN routes AS r
                    ON r.route_id = t.route_id
                WHERE st.stop_id = ?
                ORDER BY line_number, line_name
                LIMIT 100
            """.trimIndent()

            readableDatabase.rawQuery(sql, arrayOf(stopId)).use { cursor ->
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
