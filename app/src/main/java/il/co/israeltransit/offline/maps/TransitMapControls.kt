package il.co.israeltransit.offline.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.maps.MapLibreMap

@Composable
fun TransitMapControls(
    map: MapLibreMap?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(
            onClick = {
                map?.let {
                    it.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.zoomIn()
                    )
                }
            },
            enabled = map != null,
            modifier = Modifier.size(48.dp)
        ) {
            Text(
                text = "+",
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        FilledTonalIconButton(
            onClick = {
                map?.let {
                    it.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.zoomOut()
                    )
                }
            },
            enabled = map != null,
            modifier = Modifier.size(48.dp)
        ) {
            Text(
                text = "−",
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
