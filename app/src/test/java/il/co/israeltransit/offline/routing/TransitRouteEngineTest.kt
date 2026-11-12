package il.co.israeltransit.offline.routing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import il.co.israeltransit.offline.database.TransitDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransitRouteEngineTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private lateinit var engine: TransitRouteEngine

    private data class TestStop(
        val id: String,
        val name: String,
        val arrival: Int,
        val departure: Int,
        val lat: Double = 31.77,
        val lon: Double = 35.21
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        val directory = File(context.filesDir, "transit")
        directory.mkdirs()

        databaseFile = File(directory, "israel-transit.sqlite")
        databaseFile.delete()

        createSchema()

        engine = TransitRouteEngine(
            TransitDatabase(context)
        )
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    private fun createSchema() {
        val db = SQLiteDatabase.openOrCreateDatabase(
            databaseFile,
            null
        )

        try {
            db.execSQL(
                """
                CREATE TABLE agency (
                    agency_id TEXT PRIMARY KEY,
                    agency_name TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE routes (
                    route_id TEXT PRIMARY KEY,
                    agency_id TEXT,
                    route_short_name TEXT,
                    route_long_name TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE trips (
                    trip_id TEXT PRIMARY KEY,
                    route_id TEXT,
                    service_id TEXT,
                    trip_headsign TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE calendar (
                    service_id TEXT PRIMARY KEY,
                    monday TEXT,
                    tuesday TEXT,
                    wednesday TEXT,
                    thursday TEXT,
                    friday TEXT,
                    saturday TEXT,
                    sunday TEXT,
                    start_date TEXT,
                    end_date TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE stops (
                    stop_id TEXT PRIMARY KEY,
                    stop_name TEXT,
                    stop_lat REAL,
                    stop_lon REAL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE stop_times (
                    trip_id TEXT,
                    arrival_time INTEGER,
                    departure_time INTEGER,
                    stop_id TEXT,
                    stop_sequence INTEGER
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE route_patterns (
                    pattern_id INTEGER PRIMARY KEY,
                    route_id TEXT,
                    direction_id TEXT,
                    stop_count INTEGER
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE route_pattern_stops (
                    pattern_id INTEGER,
                    stop_sequence INTEGER,
                    stop_id TEXT,
                    PRIMARY KEY (pattern_id, stop_sequence)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE trip_patterns (
                    trip_id TEXT,
                    pattern_id INTEGER,
                    service_id TEXT
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO agency (agency_id, agency_name)
                VALUES ('agency', 'מפעיל בדיקה')
                """
            )
        } finally {
            db.close()
        }
    }

    private fun addTrip(
        tripId: String,
        patternId: Int,
        routeId: String,
        stops: List<TestStop>
    ) {
        val db = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )

        try {
            val serviceId = "service_$tripId"

            val routeValues = ContentValues().apply {
                put("route_id", routeId)
                put("agency_id", "agency")
                put("route_short_name", routeId)
                put("route_long_name", "קו בדיקה")
            }

            db.insertWithOnConflict(
                "routes",
                null,
                routeValues,
                SQLiteDatabase.CONFLICT_IGNORE
            )

            val tripValues = ContentValues().apply {
                put("trip_id", tripId)
                put("route_id", routeId)
                put("service_id", serviceId)
                put("trip_headsign", "יעד בדיקה")
            }

            db.insert("trips", null, tripValues)

            val calendarValues = ContentValues().apply {
                put("service_id", serviceId)
                put("monday", "0")
                put("tuesday", "0")
                put("wednesday", "0")
                put("thursday", "0")
                put("friday", "1")
                put("saturday", "0")
                put("sunday", "0")
                put("start_date", "20261101")
                put("end_date", "20261130")
            }

            db.insert("calendar", null, calendarValues)

            val patternValues = ContentValues().apply {
                put("pattern_id", patternId)
                put("route_id", routeId)
                put("direction_id", "0")
                put("stop_count", stops.size)
            }

            db.insertWithOnConflict(
                "route_patterns",
                null,
                patternValues,
                SQLiteDatabase.CONFLICT_IGNORE
            )

            stops.forEachIndexed { index, stop ->
                val stopValues = ContentValues().apply {
                    put("stop_id", stop.id)
                    put("stop_name", stop.name)
                    put("stop_lat", stop.lat)
                    put("stop_lon", stop.lon)
                }

                db.insertWithOnConflict(
                    "stops",
                    null,
                    stopValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )

                val patternStopValues = ContentValues().apply {
                    put("pattern_id", patternId)
                    put("stop_sequence", index + 1)
                    put("stop_id", stop.id)
                }

                db.insertWithOnConflict(
                    "route_pattern_stops",
                    null,
                    patternStopValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )

                val stopTimeValues = ContentValues().apply {
                    put("trip_id", tripId)
                    put("arrival_time", stop.arrival)
                    put("departure_time", stop.departure)
                    put("stop_id", stop.id)
                    put("stop_sequence", index + 1)
                }

                db.insert("stop_times", null, stopTimeValues)
            }

            val tripPatternValues = ContentValues().apply {
                put("trip_id", tripId)
                put("pattern_id", patternId)
                put("service_id", serviceId)
            }

            db.insert("trip_patterns", null, tripPatternValues)

        } finally {
            db.close()
        }
    }

    private fun seconds(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 3600 +
            parts[1].toInt() * 60 +
            parts[2].toInt()
    }

    private fun stop(
        id: String,
        name: String,
        arrival: String,
        departure: String = arrival,
        lat: Double = 31.77,
        lon: Double = 35.21
    ) = TestStop(
        id = id,
        name = name,
        arrival = seconds(arrival),
        departure = seconds(departure),
        lat = lat,
        lon = lon
    )

    private fun search(
        origin: String,
        destination: String,
        time: String,
        transfers: Int = 3,
        minimumTransferMinutes: Long = 2L
    ) = engine.findJourneys(
        originStopId = origin,
        destinationStopId = destination,
        departureTime = LocalDateTime.parse(time),
        maxTransfers = transfers,
        minimumTransferMinutes = minimumTransferMinutes,
        maxResults = 5
    )

    @Test
    fun choosesFastestDirectTrip() {
        addTrip(
            "slow",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:00:00"),
                stop("B", "יעד", "08:50:00")
            )
        )

        addTrip(
            "fast",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:10:00"),
                stop("B", "יעד", "08:30:00")
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-06T07:55:00",
            transfers = 0
        )

        assertTrue(result.error ?: "", result.journeys.isNotEmpty())
        assertEquals("fast", result.journeys.first().legs.first().tripId)
        assertEquals(
            LocalDateTime.parse("2026-11-06T08:30:00"),
            result.journeys.first().arrivalAt
        )
    }

    @Test
    fun directOnlySettingRejectsTransferJourneys() {
        addTrip(
            "first",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:00:00"),
                stop("X", "תחנת החלפה", "08:20:00")
            )
        )

        addTrip(
            "second",
            2,
            "line2",
            listOf(
                stop("X", "תחנת החלפה", "08:25:00"),
                stop("B", "יעד", "08:45:00")
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-06T07:55:00",
            transfers = 0
        )

        assertTrue(result.journeys.isEmpty())
    }

    @Test
    fun acceptsTransferWithSufficientBuffer() {
        addTrip(
            "first",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:00:00"),
                stop("X", "תחנת החלפה", "08:20:00")
            )
        )

        addTrip(
            "second",
            2,
            "line2",
            listOf(
                stop("X", "תחנת החלפה", "08:23:00"),
                stop("B", "יעד", "08:40:00")
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-06T07:55:00",
            transfers = 1,
            minimumTransferMinutes = 2L
        )

        assertTrue(result.error ?: "", result.journeys.isNotEmpty())
        assertEquals(2, result.journeys.first().legs.size)
        assertEquals(1, result.journeys.first().transfers)
    }

    @Test
    fun rejectsTransferWhenBufferIsTooSmall() {
        addTrip(
            "first",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:00:00"),
                stop("X", "תחנת החלפה", "08:20:00")
            )
        )

        addTrip(
            "second",
            2,
            "line2",
            listOf(
                stop("X", "תחנת החלפה", "08:23:00"),
                stop("B", "יעד", "08:40:00")
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-06T07:55:00",
            transfers = 1,
            minimumTransferMinutes = 5L
        )

        assertTrue(result.journeys.isEmpty())
    }

    @Test
    fun supportsTransferBetweenNearbyStopsWithWalking() {
        // תחנה X1 במיקום מסוים
        addTrip(
            "first",
            1,
            "line1",
            listOf(
                stop("A", "מוצא", "08:00:00", lat = 31.7700, lon = 35.2100),
                stop("X1", "רציף ראשון", "08:20:00", lat = 31.7750, lon = 35.2150)
            )
        )

        // תחנה X2 במרחק כ-100 מטר מ-X1 (שינוי זעיר בקואורדינטות)
        addTrip(
            "second",
            2,
            "line2",
            listOf(
                stop("X2", "רציף שני סמוך", "08:30:00", lat = 31.7758, lon = 35.2155),
                stop("B", "יעד", "08:50:00", lat = 31.7800, lon = 35.2200)
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-06T07:55:00",
            transfers = 1,
            minimumTransferMinutes = 2L
        )

        assertTrue(result.error ?: "", result.journeys.isNotEmpty())
        val journey = result.journeys.first()
        assertEquals(2, journey.legs.size)
        assertEquals("X1", journey.legs[0].toStopId)
        assertEquals("X2", journey.legs[1].fromStopId)
    }

    @Test
    fun findsTripThatContinuesAfterMidnight() {
        addTrip(
            "overnight",
            1,
            "nightline",
            listOf(
                stop("A", "מוצא", "25:10:00"),
                stop("B", "יעד", "25:30:00")
            )
        )

        val result = search(
            "A",
            "B",
            "2026-11-07T01:00:00",
            transfers = 0
        )

        assertTrue(result.error ?: "", result.journeys.isNotEmpty())
        assertEquals(
            LocalDateTime.parse("2026-11-07T01:30:00"),
            result.journeys.first().arrivalAt
        )
    }
}
