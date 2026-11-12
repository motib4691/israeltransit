package il.co.israeltransit.offline.routing

import java.time.Duration
import java.time.LocalDateTime

/**
 * ליבת חישוב עצמאית, ללא תלות ב-Android או ב-SQLite.
 *
 * כל זמני הנסיעות מועברים כזמנים מוחלטים.
 * כל נסיעה נבדקת בנפרד, ולא רק הנסיעה שיוצאת ראשונה.
 */
class TransitRoutingCore {

    data class StopTime(
        val stopId: String,
        val stopName: String,
        val arrivalAt: LocalDateTime,
        val departureAt: LocalDateTime
    )

    data class ScheduledTrip(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val stops: List<StopTime>
    )

    data class Leg(
        val tripId: String,
        val routeId: String,
        val lineNumber: String,
        val lineName: String,
        val agencyName: String,
        val headsign: String,
        val fromStopId: String,
        val fromStopName: String,
        val toStopId: String,
        val toStopName: String,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime
    )

    data class Journey(
        val legs: List<Leg>,
        val departureAt: LocalDateTime,
        val arrivalAt: LocalDateTime,
        val durationMinutes: Long,
        val transfers: Int
    )

    private data class Label(
        val stopId: String,
        val arrivalAt: LocalDateTime,
        val previous: Label?,
        val leg: Leg?
    )

    /**
     * מחפש מסלולים לפי מספר הנסיעות.
     *
     * בכל סבב נשמרת תוצאה נפרדת לכל תחנה.
     * כך מסלול עם פחות החלפות אינו נדרס
     * על ידי מסלול מוקדם יותר עם יותר החלפות.
     */
    fun findJourneys(
        originStopId: String,
        destinationStopId: String,
        departureTime: LocalDateTime,
        trips: List<ScheduledTrip>,
        maxTransfers: Int = 3,
        minimumTransferMinutes: Int = 0,
        maxResults: Int = 5
    ): List<Journey> {

        require(originStopId.isNotBlank())
        require(destinationStopId.isNotBlank())
        require(originStopId != destinationStopId)
        require(maxTransfers in 0..5)
        require(minimumTransferMinutes >= 0)
        require(maxResults > 0)

        val start = Label(
            stopId = originStopId,
            arrivalAt = departureTime,
            previous = null,
            leg = null
        )

        // מפתח: מספר הנסיעות שבוצעו.
        // בכל סבב נשמרת התווית הטובה ביותר לכל תחנה.
        var previousRound = mapOf(originStopId to start)

        val journeys = mutableListOf<Journey>()

        for (round in 1..(maxTransfers + 1)) {

            val nextRound = mutableMapOf<String, Label>()

            for (trip in trips) {
                if (trip.stops.size < 2) continue

                for (boardIndex in 0 until trip.stops.lastIndex) {

                    val boardStop = trip.stops[boardIndex]
                    val previousLabel =
                        previousRound[boardStop.stopId] ?: continue

                    val requiredTransfer = if (round == 1) {
                        0L
                    } else {
                        minimumTransferMinutes.toLong()
                    }

                    val earliestBoarding = previousLabel.arrivalAt
                        .plusMinutes(requiredTransfer)

                    if (trip.stops[boardIndex].departureAt
                        .isBefore(earliestBoarding)
                    ) {
                        continue
                    }

                    val departureAt =
                        trip.stops[boardIndex].departureAt

                    for (alightIndex in (boardIndex + 1)..trip.stops.lastIndex) {

                        val alightStop = trip.stops[alightIndex]
                        val arrivalAt = alightStop.arrivalAt

                        if (arrivalAt.isBefore(departureAt)) continue

                        val leg = Leg(
                            tripId = trip.tripId,
                            routeId = trip.routeId,
                            lineNumber = trip.lineNumber,
                            lineName = trip.lineName,
                            agencyName = trip.agencyName,
                            headsign = trip.headsign,
                            fromStopId = boardStop.stopId,
                            fromStopName = boardStop.stopName,
                            toStopId = alightStop.stopId,
                            toStopName = alightStop.stopName,
                            departureAt = departureAt,
                            arrivalAt = arrivalAt
                        )

                        val candidate = Label(
                            stopId = alightStop.stopId,
                            arrivalAt = arrivalAt,
                            previous = previousLabel,
                            leg = leg
                        )

                        val current =
                            nextRound[alightStop.stopId]

                        // באותו מספר נסיעות, הגעה מוקדמת יותר
                        // שולטת בהגעה מאוחרת יותר.
                        if (current == null ||
                            arrivalAt.isBefore(current.arrivalAt)
                        ) {
                            nextRound[alightStop.stopId] = candidate
                        }
                    }
                }
            }

            val destinationLabel =
                nextRound[destinationStopId]

            if (destinationLabel != null) {
                journeys.add(buildJourney(destinationLabel))
            }

            if (nextRound.isEmpty()) break

            previousRound = nextRound
        }

        return journeys
            .distinctBy { journey ->
                journey.legs.joinToString("|") { it.tripId }
            }
            .sortedWith(
                compareBy<Journey> { it.arrivalAt }
                    .thenBy { it.transfers }
            )
            .take(maxResults)
    }

    private fun buildJourney(destination: Label): Journey {
        val legs = mutableListOf<Leg>()
        var current: Label? = destination

        while (current != null) {
            current.leg?.let { legs.add(it) }
            current = current.previous
        }

        legs.reverse()

        val first = legs.first()
        val last = legs.last()

        return Journey(
            legs = legs,
            departureAt = first.departureAt,
            arrivalAt = last.arrivalAt,
            durationMinutes = Duration.between(
                first.departureAt,
                last.arrivalAt
            ).toMinutes(),
            transfers = (legs.size - 1).coerceAtLeast(0)
        )
    }
}
