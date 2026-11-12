package il.co.israeltransit.offline.routing

import kotlin.math.roundToInt

data class FareResult(
    val priceNis: Double,
    val distanceKm: Double,
    val isFreeTransfer: Boolean,
    val description: String
)

object FareCalculator {
    fun calculateFare(
        totalDistanceMeters: Double,
        isTrainIncluded: Boolean = false,
        durationMinutes: Long = 0
    ): FareResult {
        val distKm = totalDistanceMeters / 1000.0

        val price = when {
            distKm <= 15.0 -> if (isTrainIncluded) 9.50 else 6.00
            distKm <= 40.0 -> if (isTrainIncluded) 13.00 else 12.50
            distKm <= 120.0 -> if (isTrainIncluded) 25.50 else 17.00
            distKm <= 225.0 -> if (isTrainIncluded) 33.00 else 28.50
            else -> 68.50
        }

        val freeTransfer = distKm <= 15.0 && durationMinutes <= 90

        val desc = buildString {
            append("מרחק משוער: ${distKm.roundToInt()} ק\"מ\n")
            if (freeTransfer) {
                append("זכאי למעבר חופשי של 90 דקות ללא חיוב נוסף (עד 15 ק\"מ).")
            } else if (distKm <= 15.0) {
                append("הנסיעה מעל 90 דקות - ייתכן חיוב עבור מעבר נוסף.")
            } else {
                append("נסיעה בין-אזורית (מעל 15 ק\"מ) - ללא מעבר עירוני חופשי.")
            }
        }

        return FareResult(price, distKm, freeTransfer, desc)
    }
}
