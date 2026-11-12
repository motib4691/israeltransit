package il.co.israeltransit.offline.maps

import android.graphics.PointF
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

object TransitMapClusterController {

    private const val CLUSTER_LAYER_ID =
        "transit-stops-clusters"

    private const val SOURCE_ID =
        "transit-stops-source"

    private const val MAX_ZOOM = 22.0

    /**
     * Handles a tap on a station cluster.
     *
     * Returns true when a cluster was found and the map
     * was instructed to zoom into it.
     */
    fun handleClusterTap(
        map: MapLibreMap,
        style: Style,
        screenPoint: PointF
    ): Boolean {

        return try {
            val features = map.queryRenderedFeatures(
                screenPoint,
                CLUSTER_LAYER_ID
            )

            val cluster = features.firstOrNull()
                ?: return false

            if (!cluster.hasProperty("cluster_id")) {
                return false
            }

            val clusterPoint =
                cluster.geometry() as? Point
                    ?: return false

            val source =
                style.getSource(SOURCE_ID) as? GeoJsonSource
                    ?: return false

            val expansionZoom =
                source.getClusterExpansionZoom(cluster)

            val targetZoom = minOf(
                expansionZoom.toDouble() + 0.5,
                MAX_ZOOM
            )

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                clusterPoint.latitude(),
                                clusterPoint.longitude()
                            )
                        )
                        .zoom(targetZoom)
                        .build()
                )
            )

            true

        } catch (_: Exception) {
            // A stale cluster must not interfere with map interaction.
            false
        }
    }
}
