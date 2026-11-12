
package il.co.israeltransit.offline.maps

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

@Composable
fun OfflineMapView(
    pmtilesFile: File,
    modifier: Modifier = Modifier,
    initialLatitude: Double = 31.7683,
    initialLongitude: Double = 35.2137,
    initialZoom: Double = 12.0,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
    onMapError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentFile = rememberUpdatedState(pmtilesFile)
    val currentLatitude = rememberUpdatedState(initialLatitude)
    val currentLongitude = rememberUpdatedState(initialLongitude)
    val currentZoom = rememberUpdatedState(initialZoom)
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnMapError = rememberUpdatedState(onMapError)

    val mapView = remember(
        context,
        pmtilesFile.absolutePath
    ) {
        createMapView(context)
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var started = false
        var resumed = false
        var disposed = false

        mapView.onCreate(Bundle())

        fun startMap() {
            if (!started) {
                mapView.onStart()
                started = true
            }
        }

        fun resumeMap() {
            startMap()

            if (!resumed) {
                mapView.onResume()
                resumed = true
            }
        }

        fun pauseMap() {
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
        }

        fun stopMap() {
            pauseMap()

            if (started) {
                mapView.onStop()
                started = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startMap()
                Lifecycle.Event.ON_RESUME -> resumeMap()
                Lifecycle.Event.ON_PAUSE -> pauseMap()
                Lifecycle.Event.ON_STOP -> stopMap()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        if (
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        ) {
            startMap()
        }

        if (
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        ) {
            resumeMap()
        }

        val file = currentFile.value

        if (!file.exists() || !file.isFile) {
            currentOnMapError.value?.invoke(
                "קובץ המפה אינו נמצא"
            )
        } else if (!file.canRead() || file.length() <= 0L) {
            currentOnMapError.value?.invoke(
                "קובץ המפה ריק או שאינו ניתן לקריאה"
            )
        } else {
            mapView.getMapAsync { map ->
                if (disposed) {
                    return@getMapAsync
                }

                try {
                    val styleJson = OfflineMapStyle.create(file)

                    map.setStyle(
                        Style.Builder().fromJson(styleJson)
                    ) {
                        if (disposed) {
                            return@setStyle
                        }

                        map.cameraPosition =
                            CameraPosition.Builder()
                                .target(
                                    LatLng(
                                        currentLatitude.value,
                                        currentLongitude.value
                                    )
                                )
                                .zoom(currentZoom.value)
                                .build()

                        map.uiSettings.apply {
                            isCompassEnabled = true
                            isRotateGesturesEnabled = true
                            isTiltGesturesEnabled = false
                            isZoomGesturesEnabled = true
                            isScrollGesturesEnabled = true
                        }

                        currentOnMapReady.value?.invoke(map)
                    }
                } catch (exception: Exception) {
                    if (!disposed) {
                        currentOnMapError.value?.invoke(
                            exception.message
                                ?: "שגיאה בטעינת המפה"
                        )
                    }
                }
            }
        }

        onDispose {
            disposed = true

            lifecycleOwner.lifecycle.removeObserver(observer)

            stopMap()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = {
            // Map initialization and style loading happen only once
            // inside DisposableEffect, not during Compose recompositions.
        }
    )
}

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context)
}
