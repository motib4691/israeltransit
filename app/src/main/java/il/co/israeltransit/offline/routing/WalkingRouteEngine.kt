
package il.co.israeltransit.offline.routing

import java.util.PriorityQueue
import kotlin.math.*

/**
 * Walking network routing engine.
 *
 * The graph is directed: one WalkingEdge represents movement from
 * one node to another. OSM import must create reverse edges where
 * walking is permitted in both directions.
 */
data class WalkingNode(
    val id: Long,
    val latitude: Double,
    val longitude: Double
)

data class WalkingEdge(
    val id: Long,
    val fromNodeId: Long,
    val toNodeId: Long,
    val lengthMeters: Double,
    val name: String? = null,
    val highway: String? = null,
    val surface: String? = null,
    val crossing: String? = null,
    val bridge: Boolean = false,
    val tunnel: Boolean = false,
    val steps: Boolean = false,
    val stepCount: Int? = null,
    val footAccess: String = "yes",
    val inclinePercent: Double? = null
)

data class WalkingOptions(
    val walkingSpeedMetersPerSecond: Double = 1.25,
    val allowStairs: Boolean = true,
    val avoidUnpavedSurfaces: Boolean = false,
    val maximumDistanceMeters: Double = 20_000.0
)

data class WalkingPoint(
    val latitude: Double,
    val longitude: Double
)

data class WalkingInstruction(
    val text: String,
    val distanceMeters: Double,
    val streetName: String? = null
)

data class WalkingRoute(
    val nodeIds: List<Long>,
    val points: List<WalkingPoint>,
    val instructions: List<WalkingInstruction>,
    val distanceMeters: Double,
    val estimatedSeconds: Int
)

sealed class WalkingRouteResult {
    data class Success(val route: WalkingRoute) : WalkingRouteResult()
    data object NoRoute : WalkingRouteResult()
    data class InvalidInput(val reason: String) : WalkingRouteResult()
}

/**
 * A* walking route search.
 *
 * Edge costs are measured in seconds. The heuristic uses straight-line
 * distance divided by walking speed, so it does not overestimate the
 * remaining travel time.
 */
class WalkingRouteEngine {

    private data class QueueEntry(
        val nodeId: Long,
        val estimatedTotalCost: Double,
        val actualCost: Double
    )

    private data class PreviousStep(
        val previousNodeId: Long,
        val edge: WalkingEdge
    )

    fun findRoute(
        startNodeId: Long,
        destinationNodeId: Long,
        nodes: Map<Long, WalkingNode>,
        edges: Collection<WalkingEdge>,
        options: WalkingOptions = WalkingOptions()
    ): WalkingRouteResult {

        if (startNodeId == destinationNodeId) {
            return WalkingRouteResult.InvalidInput(
                "נקודת המוצא והיעד זהות"
            )
        }

        val start = nodes[startNodeId]
            ?: return WalkingRouteResult.InvalidInput(
                "נקודת המוצא אינה קיימת ברשת ההליכה"
            )

        val destination = nodes[destinationNodeId]
            ?: return WalkingRouteResult.InvalidInput(
                "נקודת היעד אינה קיימת ברשת ההליכה"
            )

        if (options.walkingSpeedMetersPerSecond <= 0.0 ||
            !options.walkingSpeedMetersPerSecond.isFinite()
        ) {
            return WalkingRouteResult.InvalidInput(
                "מהירות ההליכה אינה תקינה"
            )
        }

        if (options.maximumDistanceMeters <= 0.0 ||
            !options.maximumDistanceMeters.isFinite()
        ) {
            return WalkingRouteResult.InvalidInput(
                "מרחק ההליכה המרבי אינו תקין"
            )
        }

        if (!isValidCoordinate(start) || !isValidCoordinate(destination)) {
            return WalkingRouteResult.InvalidInput(
                "קואורדינטות נקודת המוצא או היעד אינן תקינות"
            )
        }

        val adjacency = buildAdjacency(nodes, edges, options)

        val openQueue = PriorityQueue<QueueEntry>(
            compareBy<QueueEntry> { it.estimatedTotalCost }
                .thenBy { it.actualCost }
        )

        val bestCosts = HashMap<Long, Double>()
        val previousSteps = HashMap<Long, PreviousStep>()

        bestCosts[startNodeId] = 0.0

        openQueue.add(
            QueueEntry(
                nodeId = startNodeId,
                estimatedTotalCost = heuristic(
                    start,
                    destination,
                    options.walkingSpeedMetersPerSecond
                ),
                actualCost = 0.0
            )
        )

        while (openQueue.isNotEmpty()) {
            val current = openQueue.poll()

            val knownCost = bestCosts[current.nodeId] ?: continue

            // Ignore outdated queue entries.
            if (current.actualCost > knownCost + COST_EPSILON) {
                continue
            }

            if (current.nodeId == destinationNodeId) {
                return reconstructRoute(
                    startNodeId = startNodeId,
                    destinationNodeId = destinationNodeId,
                    nodes = nodes,
                    previousSteps = previousSteps,
                    totalCostSeconds = knownCost
                )
            }

            val outgoing = adjacency[current.nodeId] ?: continue

            for (edge in outgoing) {
                val nextNode = nodes[edge.toNodeId] ?: continue

                val edgeCost = calculateEdgeCost(edge, options)
                    ?: continue

                val newCost = knownCost + edgeCost

                if (newCost > options.maximumDistanceMeters /
                    options.walkingSpeedMetersPerSecond * 4.0
                ) {
                    continue
                }

                val oldCost = bestCosts[edge.toNodeId]

                if (oldCost == null ||
                    newCost + COST_EPSILON < oldCost
                ) {
                    bestCosts[edge.toNodeId] = newCost

                    previousSteps[edge.toNodeId] = PreviousStep(
                        previousNodeId = current.nodeId,
                        edge = edge
                    )

                    val estimate = newCost + heuristic(
                        nextNode,
                        destination,
                        options.walkingSpeedMetersPerSecond
                    )

                    openQueue.add(
                        QueueEntry(
                            nodeId = edge.toNodeId,
                            estimatedTotalCost = estimate,
                            actualCost = newCost
                        )
                    )
                }
            }
        }

        return WalkingRouteResult.NoRoute
    }

    private fun buildAdjacency(
        nodes: Map<Long, WalkingNode>,
        edges: Collection<WalkingEdge>,
        options: WalkingOptions
    ): Map<Long, List<WalkingEdge>> {

        val result = HashMap<Long, MutableList<WalkingEdge>>()

        for (edge in edges) {
            if (edge.fromNodeId !in nodes ||
                edge.toNodeId !in nodes
            ) {
                continue
            }

            if (!edge.lengthMeters.isFinite() ||
                edge.lengthMeters <= 0.0 ||
                edge.lengthMeters > options.maximumDistanceMeters
            ) {
                continue
            }

            if (calculateEdgeCost(edge, options) == null) {
                continue
            }

            result.getOrPut(edge.fromNodeId) {
                ArrayList()
            }.add(edge)
        }

        return result
    }

    private fun calculateEdgeCost(
        edge: WalkingEdge,
        options: WalkingOptions
    ): Double? {

        val access = edge.footAccess.lowercase()

        if (access in setOf("no", "private", "military", "restricted")) {
            return null
        }

        if (edge.steps && !options.allowStairs) {
            return null
        }

        val speed = options.walkingSpeedMetersPerSecond
        var cost = edge.lengthMeters / speed

        // Stairs require additional time.
        if (edge.steps) {
            val steps = edge.stepCount?.coerceAtLeast(0) ?: 10
            cost += 8.0 + steps * 0.65
        }

        // Crossing a road adds time.
        when (edge.crossing?.lowercase()) {
            "traffic_signals" -> cost += 12.0
            "uncontrolled" -> cost += 8.0
            "marked" -> cost += 5.0
            "unmarked" -> cost += 12.0
        }

        // Penalize difficult surfaces when requested.
        if (options.avoidUnpavedSurfaces) {
            when (edge.surface?.lowercase()) {
                "sand", "mud", "ground", "dirt", "gravel",
                "grass", "unpaved" -> cost *= 1.35
            }
        }

        // Uphill walking is slower.
        val incline = edge.inclinePercent
        if (incline != null && incline > 0.0) {
            cost *= 1.0 + (incline.coerceAtMost(20.0) / 100.0)
        }

        return cost.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun reconstructRoute(
        startNodeId: Long,
        destinationNodeId: Long,
        nodes: Map<Long, WalkingNode>,
        previousSteps: Map<Long, PreviousStep>,
        totalCostSeconds: Double
    ): WalkingRouteResult {

        val reversedNodeIds = ArrayList<Long>()
        val reversedEdges = ArrayList<WalkingEdge>()

        var currentId = destinationNodeId
        reversedNodeIds.add(currentId)

        while (currentId != startNodeId) {
            val previous = previousSteps[currentId]
                ?: return WalkingRouteResult.NoRoute

            reversedEdges.add(previous.edge)
            currentId = previous.previousNodeId
            reversedNodeIds.add(currentId)

            if (reversedNodeIds.size > nodes.size) {
                return WalkingRouteResult.NoRoute
            }
        }

        val nodeIds = reversedNodeIds.asReversed()
        val routeEdges = reversedEdges.asReversed()

        val points = nodeIds.mapNotNull { id ->
            nodes[id]?.let {
                WalkingPoint(it.latitude, it.longitude)
            }
        }

        val instructions = buildInstructions(routeEdges)

        val distance = routeEdges.sumOf { it.lengthMeters }

        return WalkingRouteResult.Success(
            WalkingRoute(
                nodeIds = nodeIds,
                points = points,
                instructions = instructions,
                distanceMeters = distance,
                estimatedSeconds = totalCostSeconds
                    .roundToInt()
                    .coerceAtLeast(1)
            )
        )
    }

    private fun buildInstructions(
        edges: List<WalkingEdge>
    ): List<WalkingInstruction> {

        if (edges.isEmpty()) return emptyList()

        val instructions = ArrayList<WalkingInstruction>()

        for (edge in edges) {
            val text = when {
                edge.steps -> "עלה או רד במדרגות"
                edge.crossing != null -> "חצה את הכביש במעבר החצייה"
                edge.bridge -> "המשך על הגשר"
                edge.tunnel -> "המשך במעבר התת-קרקעי"
                edge.highway == "footway" -> "המשך בשביל להולכי רגל"
                edge.highway == "pedestrian" -> "המשך במדרחוב"
                edge.highway == "steps" -> "המשך במדרגות"
                else -> "המשך בדרך"
            }

            val previous = instructions.lastOrNull()

            if (previous != null &&
                previous.text == text &&
                previous.streetName == edge.name
            ) {
                instructions[instructions.lastIndex] =
                    previous.copy(
                        distanceMeters =
                            previous.distanceMeters + edge.lengthMeters
                    )
            } else {
                instructions.add(
                    WalkingInstruction(
                        text = text,
                        distanceMeters = edge.lengthMeters,
                        streetName = edge.name
                    )
                )
            }
        }

        return instructions
    }

    private fun heuristic(
        from: WalkingNode,
        to: WalkingNode,
        speed: Double
    ): Double {
        return haversineMeters(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude
        ) / speed
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {

        val earthRadius = 6_371_000.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)

        return earthRadius * 2.0 *
            atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun isValidCoordinate(node: WalkingNode): Boolean {
        return node.latitude.isFinite() &&
            node.longitude.isFinite() &&
            node.latitude in -90.0..90.0 &&
            node.longitude in -180.0..180.0
    }

    companion object {
        private const val COST_EPSILON = 0.000001
    }
}
