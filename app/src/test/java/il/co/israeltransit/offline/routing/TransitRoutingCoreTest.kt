package il.co.israeltransit.offline.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TransitRoutingCoreTest {

    private val core = TransitRoutingCore()

    private fun time(value: String): LocalDateTime =
        LocalDateTime.parse("2026-11-06T$value")

    private fun stop(
        id: String,
        name: String,
        arrival: String,
        departure: String = arrival
    ) = TransitRoutingCore.StopTime(
        stopId = id,
        stopName = name,
        arrivalAt = time(arrival),
        departureAt = time(departure)
    )

    private fun trip(
        id: String,
        stops: List<TransitRoutingCore.StopTime>
    ) = TransitRoutingCore.ScheduledTrip(
        tripId = id,
        routeId = "route-$id",
        lineNumber = id,
        lineName = "קו $id",
        agencyName = "מפעיל בדיקה",
        headsign = "יעד",
        stops = stops
    )

    @Test
    fun laterDepartingTripCanArriveEarlier() {
        val trips = listOf(
            trip(
                "early",
                listOf(
                    stop("A", "מוצא", "08:00", "08:00"),
                    stop("B", "יעד", "08:50")
                )
            ),
            trip(
                "fast",
                listOf(
                    stop("A", "מוצא", "08:10", "08:10"),
                    stop("B", "יעד", "08:30")
                )
            )
        )

        val results = core.findJourneys(
            originStopId = "A",
            destinationStopId = "B",
            departureTime = time("07:55"),
            trips = trips,
            maxTransfers = 0
        )

        assertEquals(1, results.size)
        assertEquals("fast", results.first().legs.first().tripId)
        assertEquals(time("08:30"), results.first().arrivalAt)
    }

    @Test
    fun impossibleTransferIsRejected() {
        val trips = listOf(
            trip(
                "first",
                listOf(
                    stop("A", "מוצא", "08:00", "08:00"),
                    stop("X", "החלפה", "08:20")
                )
            ),
            trip(
                "second",
                listOf(
                    stop("X", "החלפה", "08:22", "08:22"),
                    stop("B", "יעד", "08:40")
                )
            )
        )

        val results = core.findJourneys(
            originStopId = "A",
            destinationStopId = "B",
            departureTime = time("07:55"),
            trips = trips,
            maxTransfers = 1,
            minimumTransferMinutes = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun feasibleTransferIsAccepted() {
        val trips = listOf(
            trip(
                "first",
                listOf(
                    stop("A", "מוצא", "08:00", "08:00"),
                    stop("X", "החלפה", "08:20")
                )
            ),
            trip(
                "second",
                listOf(
                    stop("X", "החלפה", "08:25", "08:25"),
                    stop("B", "יעד", "08:45")
                )
            )
        )

        val results = core.findJourneys(
            originStopId = "A",
            destinationStopId = "B",
            departureTime = time("07:55"),
            trips = trips,
            maxTransfers = 1,
            minimumTransferMinutes = 5
        )

        assertEquals(1, results.size)
        assertEquals(1, results.first().transfers)
        assertEquals(2, results.first().legs.size)
        assertEquals(time("08:45"), results.first().arrivalAt)
    }

    @Test
    fun directJourneyIsPreservedSeparatelyFromTransferJourney() {
        val trips = listOf(
            trip(
                "direct",
                listOf(
                    stop("A", "מוצא", "08:00", "08:00"),
                    stop("B", "יעד", "09:00")
                )
            ),
            trip(
                "first",
                listOf(
                    stop("A", "מוצא", "08:00", "08:00"),
                    stop("X", "החלפה", "08:20")
                )
            ),
            trip(
                "second",
                listOf(
                    stop("X", "החלפה", "08:25", "08:25"),
                    stop("B", "יעד", "08:40")
                )
            )
        )

        val results = core.findJourneys(
            originStopId = "A",
            destinationStopId = "B",
            departureTime = time("07:55"),
            trips = trips,
            maxTransfers = 1,
            minimumTransferMinutes = 5
        )

        assertEquals(2, results.size)
        assertTrue(results.any { it.transfers == 0 })
        assertTrue(results.any { it.transfers == 1 })
    }
}
