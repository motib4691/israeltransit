package il.co.israeltransit.offline.routing

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Instruction
import il.co.israeltransit.offline.maps.OfflineMapStorage
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class GraphHopperWalkingRouter(context: Context) {

    private val mapStorage = OfflineMapStorage(context.applicationContext)

    companion object {
        private val lock = Any()

        @Volatile
        private var cachedHopper: GraphHopper? = null
        private var cachedGraphPath: String? = null
        private var cachedPropertiesTimestamp: Long = 0L
    }

    fun isAvailable(): Boolean {
        return mapStorage.isWalkingGraphAvailable()
    }

    private fun getOrLoadHopper(): GraphHopper? {
        if (!mapStorage.isWalkingGraphAvailable()) return null
        val graphDir = mapStorage.getGraphHopperDirectory()
        val propertiesFile = File(graphDir, "properties")
        val propsModified = if (propertiesFile.isFile) propertiesFile.lastModified() else 0L
        val path = graphDir.absolutePath

        synchronized(lock) {
            val existing = cachedHopper
            if (existing != null &&
                cachedGraphPath == path &&
                cachedPropertiesTimestamp == propsModified
            ) {
                return existing
            }

            try {
                existing?.close()
            } catch (_: Exception) {
            }
            cachedHopper = null

            return try {
                val footModel = CustomModel().apply {
                    setDistanceInfluence(70.0)
                }

                val footProfile = Profile("foot").apply {
                    setVehicle("foot")
                    setWeighting("custom")
                    setCustomModel(footModel)
                }

                val hopper = GraphHopper().apply {
                    setGraphHopperLocation(path)
                    setAllowWrites(false)
                    setProfiles(footProfile)
                    chPreparationHandler.setCHProfiles(CHProfile("foot"))
                }

                hopper.importOrLoad()
                cachedHopper = hopper
                cachedGraphPath = path
                cachedPropertiesTimestamp = propsModified
                hopper
            } catch (_: Throwable) {
                cachedHopper = null
                null
            }
        }
    }

    fun findRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): WalkingRouteResult {
        if (!fromLat.isFinite() || !fromLon.isFinite() ||
            !toLat.isFinite() || !toLon.isFinite()
        ) {
            return WalkingRouteResult.InvalidInput("קואורדינטות לא תקינות לחישוב הליכה")
        }

        if (fromLat !in -90.0..90.0 || toLat !in -90.0..90.0 ||
            fromLon !in -180.0..180.0 || toLon !in -180.0..180.0
        ) {
            return WalkingRouteResult.InvalidInput("קואורדינטות מחוץ לטווח התקין")
        }

        val hopper = getOrLoadHopper()
            ?: return WalkingRouteResult.NoRoute

        return try {
            val hebrewLocale = Locale.forLanguageTag("he")
            val request = GHRequest(fromLat, fromLon, toLat, toLon).apply {
                profile = "foot"
                locale = hebrewLocale
            }

            val response = hopper.route(request)
            if (response.hasErrors()) {
                return WalkingRouteResult.NoRoute
            }

            val bestPath = response.best
            val pointList = bestPath.points
            val points = ArrayList<WalkingPoint>(pointList.size())
            for (i in 0 until pointList.size()) {
                points.add(
                    WalkingPoint(
                        latitude = pointList.getLat(i),
                        longitude = pointList.getLon(i)
                    )
                )
            }

            val translation = hopper.translationMap.getWithFallBack(hebrewLocale)
            val instructions = ArrayList<WalkingInstruction>()
            for (instruction in bestPath.instructions) {
                val description = instruction.getTurnDescription(translation)?.trim().orEmpty()
                val streetName = instruction.name?.trim()?.takeIf { it.isNotEmpty() }

                if (description.isNotEmpty() || instruction.sign == Instruction.FINISH) {
                    val finalText = when {
                        instruction.sign == Instruction.FINISH && description.isEmpty() -> "הגעת ליעד"
                        else -> description
                    }
                    instructions.add(
                        WalkingInstruction(
                            text = finalText,
                            distanceMeters = instruction.distance,
                            streetName = streetName
                        )
                    )
                }
            }

            val distanceMeters = bestPath.distance
            val estimatedSeconds = (bestPath.time / 1000.0).roundToInt().coerceAtLeast(1)

            WalkingRouteResult.Success(
                WalkingRoute(
                    nodeIds = emptyList(),
                    points = points,
                    instructions = instructions,
                    distanceMeters = distanceMeters,
                    estimatedSeconds = estimatedSeconds
                )
            )
        } catch (_: Throwable) {
            WalkingRouteResult.NoRoute
        }
    }
}
