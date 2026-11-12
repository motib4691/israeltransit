package il.co.israeltransit.offline.maps

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline vector map style for Israel.
 *
 * PMTiles: loaded from the user's selected storage.
 * Glyphs: bundled inside the APK assets.
 */
object OfflineMapStyle {

    private const val SOURCE_ID = "israel-basemap"
    private const val FONT_STACK = "Noto Sans Regular"

    private const val GLYPHS_URL =
        "asset://glyphs/{fontstack}/{range}.pbf"

    fun create(pmtilesFile: File): String {
        require(pmtilesFile.exists() && pmtilesFile.isFile) {
            "Map archive does not exist or is not a file"
        }
        require(pmtilesFile.canRead() && pmtilesFile.length() > 0L) {
            "Map archive is empty or unreadable"
        }

        val pmtilesUrl =
            "pmtiles://${Uri.fromFile(pmtilesFile)}"

        val source = JSONObject()
            .put("type", "vector")
            .put("url", pmtilesUrl)
            .put(
                "attribution",
                "© OpenStreetMap contributors"
            )

        val sources = JSONObject()
            .put(SOURCE_ID, source)

        val layers = JSONArray()
            .put(backgroundLayer())
            .put(oceanLayer())
            .put(inlandWaterLayer())
            .put(landuseLayer())
            .put(waterwayLayer())
            .put(railwayLayer())
            .put(walkingPathsLayer())
            .put(serviceRoadsLayer())
            .put(minorRoadsCasingLayer())
            .put(majorRoadsCasingLayer())
            .put(highwaysCasingLayer())
            .put(minorRoadsFillLayer())
            .put(majorRoadsFillLayer())
            .put(highwaysFillLayer())
            .put(highwayShieldsLayer())
            .put(streetLabelsLayer())
            .put(stationLabelsLayer())
            .put(neighbourhoodLabelsLayer())
            .put(villageLabelsLayer())
            .put(cityLabelsLayer())

        return JSONObject()
            .put("version", 8)
            .put("name", "Israel Offline Vector Map")
            .put("glyphs", GLYPHS_URL)
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun backgroundLayer(): JSONObject {
        return JSONObject()
            .put("id", "map-background")
            .put("type", "background")
            .put(
                "paint",
                JSONObject()
                    .put("background-color", "#F3F2EE")
            )
    }

    private fun oceanLayer(): JSONObject {
        return JSONObject()
            .put("id", "ocean-water")
            .put("type", "fill")
            .put("source", SOURCE_ID)
            .put("source-layer", "ocean")
            .put(
                "paint",
                JSONObject()
                    .put("fill-color", "#CBE6F7")
            )
    }

    private fun inlandWaterLayer(): JSONObject {
        return JSONObject()
            .put("id", "inland-water")
            .put("type", "fill")
            .put("source", SOURCE_ID)
            .put("source-layer", "water")
            .put(
                "paint",
                JSONObject()
                    .put("fill-color", "#CBE6F7")
            )
    }

    private fun landuseLayer(): JSONObject {
        val colorExpr = JSONArray()
            .put("match")
            .put(JSONArray().put("get").put("kind"))
            .put("forest")
            .put("#D4E8CE")
            .put("#E1EFE0")

        return JSONObject()
            .put("id", "landuse-green")
            .put("type", "fill")
            .put("source", SOURCE_ID)
            .put("source-layer", "landuse")
            .put(
                "paint",
                JSONObject()
                    .put("fill-color", colorExpr)
                    .put("fill-opacity", 0.9)
            )
    }

    private fun waterwayLayer(): JSONObject {
        val widthExpr = zoomWidth(
            9 to 0.9,
            13 to 2.0,
            16 to 3.5
        )

        return JSONObject()
            .put("id", "waterways")
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", "waterway")
            .put(
                "layout",
                JSONObject()
                    .put("line-cap", "round")
                    .put("line-join", "round")
            )
            .put(
                "paint",
                JSONObject()
                    .put("line-color", "#A5D5F2")
                    .put("line-width", widthExpr)
            )
    }

    private fun railwayLayer(): JSONObject {
        val colorExpr = JSONArray()
            .put("match")
            .put(JSONArray().put("get").put("kind"))
            .put("light_rail")
            .put("#7E57C2")
            .put("tram")
            .put("#7E57C2")
            .put("#8A8A8A")

        val widthExpr = zoomWidth(
            8 to 0.9,
            13 to 1.8,
            16 to 2.8
        )

        return JSONObject()
            .put("id", "railways")
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", "railway")
            .put(
                "paint",
                JSONObject()
                    .put("line-color", colorExpr)
                    .put("line-width", widthExpr)
                    .put("line-dasharray", JSONArray().put(3).put(2))
                    .put("line-opacity", 0.85)
            )
    }

    private fun walkingPathsLayer(): JSONObject {
        return JSONObject()
            .put("id", "walking-paths")
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", "roads")
            .put("minzoom", 13)
            .put("filter", kindEquals("path"))
            .put(
                "layout",
                JSONObject()
                    .put("line-cap", "round")
                    .put("line-join", "round")
            )
            .put(
                "paint",
                JSONObject()
                    .put("line-color", "#8D6E63")
                    .put("line-width", zoomWidth(13 to 1.1, 16 to 2.2))
                    .put("line-dasharray", JSONArray().put(2.0).put(1.8))
                    .put("line-opacity", 0.9)
            )
    }

    private fun serviceRoadsLayer(): JSONObject {
        return JSONObject()
            .put("id", "service-roads")
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", "roads")
            .put("minzoom", 13)
            .put("filter", kindEquals("service_road"))
            .put(
                "layout",
                JSONObject()
                    .put("line-cap", "round")
                    .put("line-join", "round")
            )
            .put(
                "paint",
                JSONObject()
                    .put("line-color", "#E6E4DF")
                    .put("line-width", zoomWidth(13 to 1.2, 16 to 3.5))
            )
    }

    private fun minorRoadsCasingLayer(): JSONObject {
        return roadLayer(
            id = "minor-roads-casing",
            kind = "minor_road",
            color = "#D4D2CC",
            widthExpr = zoomWidth(11 to 1.4, 13 to 4.2, 16 to 10.5),
            minZoom = 11
        )
    }

    private fun minorRoadsFillLayer(): JSONObject {
        return roadLayer(
            id = "minor-roads-fill",
            kind = "minor_road",
            color = "#FFFFFF",
            widthExpr = zoomWidth(11 to 0.8, 13 to 2.8, 16 to 8.5),
            minZoom = 11
        )
    }

    private fun majorRoadsCasingLayer(): JSONObject {
        return roadLayer(
            id = "major-roads-casing",
            kind = "major_road",
            color = "#DDB86B",
            widthExpr = zoomWidth(9 to 1.6, 13 to 5.6, 16 to 13.0),
            minZoom = 9
        )
    }

    private fun majorRoadsFillLayer(): JSONObject {
        return roadLayer(
            id = "major-roads-fill",
            kind = "major_road",
            color = "#FFF4CC",
            widthExpr = zoomWidth(9 to 1.0, 13 to 4.0, 16 to 10.5),
            minZoom = 9
        )
    }

    private fun highwaysCasingLayer(): JSONObject {
        return roadLayer(
            id = "highways-casing",
            kind = "highway",
            color = "#D9822B",
            widthExpr = zoomWidth(6 to 1.5, 10 to 3.2, 14 to 7.8, 16 to 15.5),
            minZoom = 6
        )
    }

    private fun highwaysFillLayer(): JSONObject {
        return roadLayer(
            id = "highways-fill",
            kind = "highway",
            color = "#FCD68A",
            widthExpr = zoomWidth(6 to 0.9, 10 to 2.0, 14 to 5.8, 16 to 12.5),
            minZoom = 6
        )
    }

    private fun roadLayer(
        id: String,
        kind: String,
        color: String,
        widthExpr: Any,
        minZoom: Int
    ): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", "roads")
            .put("minzoom", minZoom)
            .put("filter", kindEquals(kind))
            .put(
                "layout",
                JSONObject()
                    .put("line-cap", "round")
                    .put("line-join", "round")
            )
            .put(
                "paint",
                JSONObject()
                    .put("line-color", color)
                    .put("line-width", widthExpr)
            )
    }

    private fun highwayShieldsLayer(): JSONObject {
        val filter = JSONArray()
            .put("all")
            .put(kindEquals("highway"))
            .put(JSONArray().put("has").put("ref"))

        return JSONObject()
            .put("id", "highway-shields")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "roads")
            .put("minzoom", 8)
            .put("maxzoom", 15)
            .put("filter", filter)
            .put(
                "layout",
                JSONObject()
                    .put("symbol-placement", "line")
                    .put("text-field", JSONArray().put("get").put("ref"))
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", 10.5)
                    .put("symbol-spacing", 350)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#FFFFFF")
                    .put("text-halo-color", "#C62828")
                    .put("text-halo-width", 3.0)
            )
    }

    private fun streetLabelsLayer(): JSONObject {
        val nameExpression = JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("name"))
            .put(JSONArray().put("get").put("name:he"))
            .put(JSONArray().put("get").put("name:en"))

        return JSONObject()
            .put("id", "street-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "roads")
            .put("minzoom", 13)
            .put(
                "layout",
                JSONObject()
                    .put("symbol-placement", "line")
                    .put("text-field", nameExpression)
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", zoomWidth(13 to 10.5, 16 to 13.0))
                    .put("text-max-width", 8)
                    .put("text-rotation-alignment", "map")
                    .put("text-pitch-alignment", "viewport")
                    .put("text-allow-overlap", false)
                    .put("text-ignore-placement", false)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#333333")
                    .put("text-halo-color", "#FFFFFF")
                    .put("text-halo-width", 1.5)
            )
    }

    private fun stationLabelsLayer(): JSONObject {
        val filter = JSONArray()
            .put("any")
            .put(kindEquals("train_station"))
            .put(kindEquals("bus_station"))

        return JSONObject()
            .put("id", "station-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "places")
            .put("minzoom", 11)
            .put("filter", filter)
            .put(
                "layout",
                JSONObject()
                    .put("text-field", JSONArray().put("get").put("name"))
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", 11.0)
                    .put("text-max-width", 9)
                    .put("text-allow-overlap", false)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#0D47A1")
                    .put("text-halo-color", "#FFFFFF")
                    .put("text-halo-width", 1.8)
            )
    }

    private fun neighbourhoodLabelsLayer(): JSONObject {
        return JSONObject()
            .put("id", "neighbourhood-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "places")
            .put("minzoom", 12)
            .put("filter", kindEquals("neighbourhood"))
            .put(
                "layout",
                JSONObject()
                    .put("text-field", JSONArray().put("get").put("name"))
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", 11.5)
                    .put("text-max-width", 8)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#5E5E5E")
                    .put("text-halo-color", "#FFFFFF")
                    .put("text-halo-width", 1.5)
            )
    }

    private fun villageLabelsLayer(): JSONObject {
        return JSONObject()
            .put("id", "village-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "places")
            .put("minzoom", 10)
            .put("filter", kindEquals("village"))
            .put(
                "layout",
                JSONObject()
                    .put("text-field", JSONArray().put("get").put("name"))
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", 12.0)
                    .put("text-max-width", 8)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#37474F")
                    .put("text-halo-color", "#FFFFFF")
                    .put("text-halo-width", 1.6)
            )
    }

    private fun cityLabelsLayer(): JSONObject {
        val filter = JSONArray()
            .put("any")
            .put(kindEquals("city"))
            .put(kindEquals("town"))

        val sizeExpr = JSONArray()
            .put("match")
            .put(JSONArray().put("get").put("kind"))
            .put("city")
            .put(15.0)
            .put(13.0)

        return JSONObject()
            .put("id", "city-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", "places")
            .put("minzoom", 6)
            .put("filter", filter)
            .put(
                "layout",
                JSONObject()
                    .put("text-field", JSONArray().put("get").put("name"))
                    .put("text-font", JSONArray().put(FONT_STACK))
                    .put("text-size", sizeExpr)
                    .put("text-max-width", 9)
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "#1E293B")
                    .put("text-halo-color", "#FFFFFF")
                    .put("text-halo-width", 2.0)
            )
    }

    private fun kindEquals(kind: String): JSONArray {
        return JSONArray()
            .put("==")
            .put(JSONArray().put("get").put("kind"))
            .put(kind)
    }

    private fun zoomWidth(vararg stops: Pair<Int, Double>): JSONArray {
        val expr = JSONArray()
            .put("interpolate")
            .put(JSONArray().put("linear"))
            .put(JSONArray().put("zoom"))
        for ((zoom, width) in stops) {
            expr.put(zoom)
            expr.put(width)
        }
        return expr
    }
}
