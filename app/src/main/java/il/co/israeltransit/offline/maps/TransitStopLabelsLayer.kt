package il.co.israeltransit.offline.maps

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer

object TransitStopLabelsLayer {

    private const val SOURCE_ID = "transit-stops-source"
    private const val LAYER_ID = "transit-stops-labels"

    fun install(style: Style) {
        if (style.getLayer(LAYER_ID) != null) {
            return
        }

        check(style.getSource(SOURCE_ID) != null) {
            "מקור התחנות אינו קיים"
        }

        val layer = SymbolLayer(
            LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.not(
                Expression.has("point_count")
            )
        ).withProperties(
            textField(
                Expression.get("stop_name")
            ),
            textSize(11f),
            textColor("#263746"),
            textHaloColor("#FFFFFF"),
            textHaloWidth(1.5f)
        )

        layer.setMinZoom(15f)

        style.addLayer(layer)
    }
}
