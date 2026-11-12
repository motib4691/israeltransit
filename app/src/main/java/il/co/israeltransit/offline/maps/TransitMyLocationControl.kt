package il.co.israeltransit.offline.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TransitMyLocationControl(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    fun hasLocationProvider(): Boolean {
        return try {
            locationManager.getProviders(true).any {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getCurrentLocation(): Result<Location> {
        return try {
            if (!hasLocationProvider()) {
                throw IllegalStateException("במכשיר זה לא זמינים שירותי מיקום.")
            }

            if (!hasLocationPermission()) {
                throw SecurityException("לא ניתנה הרשאת מיקום.")
            }

            val location = withTimeout(15_000L) {
                requestLocation()
            }

            require(
                location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
            ) {
                "התקבל מיקום לא תקין"
            }

            Result.success(location)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun moveToCurrentLocation(
        map: MapLibreMap
    ): Result<Location> {
        val locationResult = getCurrentLocation()
        if (locationResult.isFailure) {
            return locationResult
        }

        val location = locationResult.getOrThrow()

        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(15.0)
                    .build()
            )
        )

        addLocationMarker(
            map = map,
            latitude = location.latitude,
            longitude = location.longitude
        )

        return Result.success(location)
    }

    private fun addLocationMarker(
        map: MapLibreMap,
        latitude: Double,
        longitude: Double
    ) {
        map.getStyle { style ->
            try {
                val feature = Feature.fromGeometry(
                    Point.fromLngLat(longitude, latitude)
                )

                val collection = FeatureCollection.fromFeatures(arrayOf(feature))

                val existingSource = style.getSource(SOURCE_ID) as? GeoJsonSource
                if (existingSource != null) {
                    existingSource.setGeoJson(collection)
                } else {
                    style.addSource(GeoJsonSource(SOURCE_ID, collection))
                }

                if (style.getLayer(LAYER_ID) == null) {
                    style.addLayer(
                        CircleLayer(LAYER_ID, SOURCE_ID)
                            .withProperties(
                                circleColor("#1976D2"),
                                circleRadius(9f),
                                circleStrokeColor("#FFFFFF"),
                                circleStrokeWidth(3f)
                            )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun requestLocation(): Location =
        suspendCancellableCoroutine { continuation ->

            val fineGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val providers = try {
                locationManager.getProviders(true)
            } catch (_: Exception) {
                emptyList()
            }

            val provider = when {
                fineGranted && LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: throw IllegalStateException("לא נמצא ספק מיקום המתאים להרשאות שניתנו")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                    try {
                        locationManager.removeUpdates(this)
                    } catch (_: SecurityException) {
                    }
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }

            continuation.invokeOnCancellation {
                try {
                    locationManager.removeUpdates(listener)
                } catch (_: SecurityException) {
                }
            }

            try {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (exception: Exception) {
                try {
                    locationManager.removeUpdates(listener)
                } catch (_: SecurityException) {
                }

                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }

    private companion object {
        const val SOURCE_ID = "transit-current-location-source"
        const val LAYER_ID = "transit-current-location-layer"
    }
}
