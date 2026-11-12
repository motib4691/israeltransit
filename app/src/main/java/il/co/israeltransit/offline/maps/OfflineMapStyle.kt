
package il.co.israeltransit.offline.maps

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Minimal offline map style.
 *
 * PMTiles: loaded from the user's selected storage.
 * Glyphs: bundled inside the APK assets.
 */
object OfflineMapStyle {

    private const val SOURCE_ID = "israel-roads"
    private const val SOURCE_LAYER = "roads"
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
            .put(
                roadLayer(
                    "main-roads",
                    "highway",
                    "#777777",
                    2.4
                )
            )
            .put(
                roadLayer(
                    "major-roads",
                    "major_road",
                    "#858585",
                    1.9
                )
            )
            .put(
                roadLayer(
                    "local-roads",
                    "minor_road",
                    "#999999",
                    1.35
                )
            )
            .put(
                roadLayer(
                    "walking-paths",
                    "path",
                    "#777777",
                    1.15
                )
            )
            .put(streetLabelsLayer())

        return JSONObject()
            .put("version", 8)
            .put("name", "Israel Minimal Offline Map")
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
                    .put("background-color", "#F5F5F2")
            )
    }

    private fun roadLayer(
        id: String,
        kind: String,
        color: String,
        width: Double
    ): JSONObject {
        val filter = JSONArray()
            .put("==")
            .put(
                JSONArray()
                    .put("get")
                    .put("kind")
            )
            .put(kind)

        val layout = JSONObject()
            .put("line-cap", "round")
            .put("line-join", "round")

        val paint = JSONObject()
            .put("line-color", color)
            .put("line-width", width)
            .put("line-opacity", 0.95)

        return JSONObject()
            .put("id", id)
            .put("type", "line")
            .put("source", SOURCE_ID)
            .put("source-layer", SOURCE_LAYER)
            .put("filter", filter)
            .put("layout", layout)
            .put("paint", paint)
    }

    private fun streetLabelsLayer(): JSONObject {
        val nameExpression = JSONArray()
            .put("coalesce")
            .put(
                JSONArray()
                    .put("get")
                    .put("name:he")
            )
            .put(
                JSONArray()
                    .put("get")
                    .put("name")
            )
            .put(
                JSONArray()
                    .put("get")
                    .put("name:en")
            )

        val layout = JSONObject()
            .put("symbol-placement", "line")
            .put("text-field", nameExpression)
            .put(
                "text-font",
                JSONArray().put(FONT_STACK)
            )
            .put("text-size", 11)
            .put("text-max-width", 8)
            .put("text-rotation-alignment", "map")
            .put("text-pitch-alignment", "viewport")
            .put("text-allow-overlap", false)
            .put("text-ignore-placement", false)

        val paint = JSONObject()
            .put("text-color", "#454545")
            .put("text-halo-color", "#F5F5F2")
            .put("text-halo-width", 1.2)

        return JSONObject()
            .put("id", "street-labels")
            .put("type", "symbol")
            .put("source", SOURCE_ID)
            .put("source-layer", SOURCE_LAYER)
            .put("minzoom", 13)
            .put("layout", layout)
            .put("paint", paint)
    }
}
