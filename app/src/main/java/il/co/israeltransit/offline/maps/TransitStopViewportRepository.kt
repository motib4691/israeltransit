package il.co.israeltransit.offline.maps

import android.content.Context
import il.co.israeltransit.offline.database.TransitDatabase

data class TransitMapStop(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)

class TransitStopViewportRepository(context: Context) {

    private val database = TransitDatabase(context.applicationContext)

    companion object {
        private const val DEFAULT_LIMIT = 10_000
        private const val MAX_LIMIT = 25_000
    }

    /**
     * Returns only the stops inside the currently visible map area.
     *
     * Supports areas crossing the international date line.
     */
    fun getStopsInBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        limit: Int = DEFAULT_LIMIT
    ): List<TransitMapStop> {

        require(west.isFinite() && east.isFinite()) {
            "גבולות האורך אינם תקינים"
        }

        require(south.isFinite() && north.isFinite()) {
            "גבולות הרוחב אינם תקינים"
        }

        require(west in -180.0..180.0 && east in -180.0..180.0) {
            "ערכי האורך מחוץ לטווח התקין"
        }

        require(south in -90.0..90.0 && north in -90.0..90.0) {
            "ערכי הרוחב מחוץ לטווח התקין"
        }

        require(south <= north) {
            "גבולות הרוחב הפוכים"
        }

        require(limit in 1..MAX_LIMIT) {
            "מגבלת מספר התחנות אינה תקינה"
        }

        val longitudeCondition = if (west <= east) {
            "stop_lon >= ? AND stop_lon <= ?"
        } else {
            "(stop_lon >= ? OR stop_lon <= ?)"
        }

        val sql = """
            SELECT
                stop_id,
                COALESCE(stop_name, '') AS stop_name,
                stop_lat,
                stop_lon
            FROM stops
            WHERE
                stop_lat IS NOT NULL
                AND stop_lon IS NOT NULL
                AND stop_lat >= ?
                AND stop_lat <= ?
                AND $longitudeCondition
                AND stop_lat BETWEEN -90 AND 90
                AND stop_lon BETWEEN -180 AND 180
            ORDER BY stop_id
            LIMIT ?
        """.trimIndent()

        val arguments = arrayOf(
            south.toString(),
            north.toString(),
            west.toString(),
            east.toString(),
            limit.toString()
        )

        val db = database.openReadOnly()
            ?: throw IllegalStateException(
                "לא ניתן לפתוח את מאגר התחבורה"
            )

        return db.use { readableDatabase ->
            readableDatabase.rawQuery(sql, arguments).use { cursor ->

                val idIndex = cursor.getColumnIndexOrThrow("stop_id")
                val nameIndex = cursor.getColumnIndexOrThrow("stop_name")
                val latitudeIndex = cursor.getColumnIndexOrThrow("stop_lat")
                val longitudeIndex = cursor.getColumnIndexOrThrow("stop_lon")

                val stops = ArrayList<TransitMapStop>()

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val latitude = cursor.getDouble(latitudeIndex)
                    val longitude = cursor.getDouble(longitudeIndex)

                    if (id.isBlank() ||
                        !latitude.isFinite() ||
                        !longitude.isFinite()
                    ) {
                        continue
                    }

                    stops.add(
                        TransitMapStop(
                            id = id,
                            name = cursor.getString(nameIndex) ?: "",
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                }

                stops
            }
        }
    }
}
