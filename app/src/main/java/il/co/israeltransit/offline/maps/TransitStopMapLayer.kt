package il.co.israeltransit.offline.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import il.co.israeltransit.offline.database.TransitDatabase
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

object TransitStopMapLayer {

    private const val SOURCE_ID = "transit-stops-source"
    private const val CLUSTER_LAYER_ID = "transit-stops-clusters"
    private const val CLUSTER_COUNT_LAYER_ID = "transit-stops-cluster-count"
    private const val STOP_LAYER_ID = "transit-stops-individual"
    private const val BUS_ICON_ID = "transit-bus-icon"

    private const val MAX_CLUSTER_ZOOM = 13
    private const val CLUSTER_RADIUS = 45

    private const val LATITUDE_PADDING_FACTOR = 0.15
    private const val LONGITUDE_PADDING_FACTOR = 0.15

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraListeners =
        WeakHashMap<MapLibreMap, MapLibreMap.OnCameraIdleListener>()

    private val requestSequences =
        WeakHashMap<MapLibreMap, AtomicLong>()

    private data class ViewBounds(
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double
    )

    fun install(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onComplete: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val appContext = context.applicationContext

        try {
            // הוספת אייקון אוטובוס מעוצב ישירות ל-Style של המפה
            if (style.getImage(BUS_ICON_ID) == null) {
                style.addImage(BUS_ICON_ID, createBusStopBitmap(appContext))
            }

            if (style.getSource(SOURCE_ID) == null) {
                installLayers(style)
            }

            registerCameraListener(
                context = appContext,
                style = style,
                map = map,
                onError = onError
            )

            onComplete(0)

            // רענון מיידי עם גיבוי למרכז המצלמה
            refreshViewport(
                context = appContext,
                style = style,
                map = map,
                onError = onError
            )

            // רענון נוסף קצר לאחר השלמת ה-Layout של המסך
            mainHandler.postDelayed({
                refreshViewport(
                    context = appContext,
                    style = style,
                    map = map,
                    onError = onError
                )
            }, 400L)

        } catch (exception: Exception) {
            onError(exception.message ?: "לא ניתן להתקין את שכבת התחנות")
        }
    }

    private fun createBusStopBitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(44)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // רקע מעגלי כחול-תחבורה
        paint.color = Color.parseColor("#1769AA")
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius - 1.5f * density, paint)

        // מסגרת לבנה עדינה
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(radius, radius, radius - 1.5f * density, paint)

        // ציור סילואט אוטובוס לבן
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val busLeft = size * 0.28f
        val busTop = size * 0.24f
        val busRight = size * 0.72f
        val busBottom = size * 0.72f
        val corner = 2.5f * density
        canvas.drawRoundRect(busLeft, busTop, busRight, busBottom, corner, corner, paint)

        // שמשה כחולה
        paint.color = Color.parseColor("#1769AA")
        val wLeft = size * 0.34f
        val wTop = size * 0.30f
        val wRight = size * 0.66f
        val wBottom = size * 0.46f
        canvas.drawRoundRect(wLeft, wTop, wRight, wBottom, 1.2f * density, 1.2f * density, paint)

        // גלגלים
        paint.color = Color.WHITE
        canvas.drawCircle(size * 0.37f, size * 0.76f, 1.8f * density, paint)
        canvas.drawCircle(size * 0.63f, size * 0.76f, 1.8f * density, paint)

        return bitmap
    }

    private fun registerCameraListener(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onError: (String) -> Unit
    ) {
        synchronized(cameraListeners) {
            if (cameraListeners.containsKey(map)) {
                return
            }

            val mapReference = WeakReference(map)
            val listener = object : MapLibreMap.OnCameraIdleListener {
                override fun onCameraIdle() {
                    val currentMap = mapReference.get() ?: return
                    refreshViewport(
                        context = context,
                        style = style,
                        map = currentMap,
                        onError = onError
                    )
                }
            }

            cameraListeners[map] = listener
            map.addOnCameraIdleListener(listener)
        }
    }

    private fun getRequestSequence(map: MapLibreMap): AtomicLong {
        synchronized(requestSequences) {
            return requestSequences.getOrPut(map) { AtomicLong(0) }
        }
    }

    private fun refreshViewport(
        context: Context,
        style: Style,
        map: MapLibreMap,
        onError: (String) -> Unit
    ) {
        val bounds = try {
            val visibleBounds = map.projection.visibleRegion.latLngBounds
            val target = map.cameraPosition.target

            // אם המסך טרם רונדר ואין גבולות תקינים – נשתמש במרכז המצלמה עם רדיוס ביטחון
            if (visibleBounds.latitudeNorth == visibleBounds.latitudeSouth ||
                visibleBounds.latitudeNorth == 0.0
            ) {
                val lat = target?.latitude ?: 31.7683
                val lon = target?.longitude ?: 35.2137
                ViewBounds(
                    south = lat - 0.08,
                    north = lat + 0.08,
                    west = lon - 0.08,
                    east = lon + 0.08
                )
            } else {
                createPaddedBounds(
                    doubleArrayOf(
                        visibleBounds.latitudeNorth,
                        visibleBounds.longitudeEast,
                        visibleBounds.latitudeSouth,
                        visibleBounds.longitudeWest
                    )
                )
            }
        } catch (exception: Exception) {
            val target = map.cameraPosition.target
            val lat = target?.latitude ?: 31.7683
            val lon = target?.longitude ?: 35.2137
            ViewBounds(lat - 0.08, lat + 0.08, lon - 0.08, lon + 0.08)
        }

        val sequence = getRequestSequence(map)
        val requestId = sequence.incrementAndGet()

        executor.execute {
            try {
                val features = loadStopFeatures(
                    context = context,
                    bounds = bounds,
                    sequence = sequence,
                    requestId = requestId
                ) ?: return@execute

                if (sequence.get() != requestId) return@execute

                val collection = FeatureCollection.fromFeatures(features.toTypedArray())

                mainHandler.post {
                    if (sequence.get() != requestId) return@post
                    try {
                        val source = style.getSource(SOURCE_ID) as? GeoJsonSource
                            ?: return@post
                        source.setGeoJson(collection)
                    } catch (exception: Exception) {
                        if (sequence.get() == requestId) {
                            onError(exception.message ?: "לא ניתן לעדכן את התחנות במפה")
                        }
                    }
                }
            } catch (exception: Exception) {
                if (sequence.get() == requestId) {
                    mainHandler.post {
                        if (sequence.get() == requestId) {
                            onError(exception.message ?: "טעינת התחנות נכשלה")
                        }
                    }
                }
            }
        }
    }

    private fun createPaddedBounds(coordinates: DoubleArray): ViewBounds {
        val north = coordinates[0]
        val east = coordinates[1]
        val south = coordinates[2]
        val west = coordinates[3]

        val latitudeSpan = abs(north - south).coerceAtLeast(0.02)
        val latitudePadding = latitudeSpan * LATITUDE_PADDING_FACTOR

        val paddedNorth = (maxOf(north, south) + latitudePadding).coerceAtMost(90.0)
        val paddedSouth = (minOf(north, south) - latitudePadding).coerceAtLeast(-90.0)

        val longitudeSpan = if (east >= west) east - west else east + 360.0 - west
        val effectiveSpan = longitudeSpan.coerceAtLeast(0.02)
        val padding = effectiveSpan * LONGITUDE_PADDING_FACTOR
        val paddedSpan = min(360.0, effectiveSpan + padding * 2.0)

        val center = west + effectiveSpan / 2.0
        val resultWest = center - paddedSpan / 2.0
        val resultEast = center + paddedSpan / 2.0

        return ViewBounds(
            south = paddedSouth,
            north = paddedNorth,
            west = resultWest,
            east = resultEast
        )
    }

    private fun loadStopFeatures(
        context: Context,
        bounds: ViewBounds,
        sequence: AtomicLong,
        requestId: Long
    ): List<Feature>? {

        val databaseProvider = TransitDatabase(context)
        val database: SQLiteDatabase = databaseProvider.openReadOnly() ?: return null

        return database.use { db ->
            val longitudeCondition = if (bounds.west <= bounds.east) {
                "stop_lon BETWEEN ? AND ?"
            } else {
                "(stop_lon >= ? OR stop_lon <= ?)"
            }

            // שליפת stop_code האמיתי מהשלט יחד עם שם התחנה
            val sql = """
                SELECT
                    stop_id,
                    COALESCE(NULLIF(stop_code, ''), stop_id) AS real_stop_code,
                    COALESCE(stop_name, '') AS stop_name,
                    stop_lat,
                    stop_lon
                FROM stops
                WHERE
                    stop_lat BETWEEN ? AND ?
                    AND $longitudeCondition
                    AND stop_lat IS NOT NULL
                    AND stop_lon IS NOT NULL
                    AND stop_lat BETWEEN -90 AND 90
                    AND stop_lon BETWEEN -180 AND 180
                LIMIT 800
            """.trimIndent()

            val arguments = arrayOf(
                bounds.south.toString(),
                bounds.north.toString(),
                bounds.west.toString(),
                bounds.east.toString()
            )

            db.rawQuery(sql, arguments).use { cursor ->
                val stopIdCol = cursor.getColumnIndexOrThrow("stop_id")
                val stopCodeCol = cursor.getColumnIndexOrThrow("real_stop_code")
                val stopNameCol = cursor.getColumnIndexOrThrow("stop_name")
                val latCol = cursor.getColumnIndexOrThrow("stop_lat")
                val lonCol = cursor.getColumnIndexOrThrow("stop_lon")

                val features = ArrayList<Feature>()

                while (cursor.moveToNext()) {
                    if (sequence.get() != requestId) return@use null

                    if (cursor.isNull(latCol) || cursor.isNull(lonCol)) continue

                    val latitude = cursor.getDouble(latCol)
                    val longitude = cursor.getDouble(lonCol)

                    if (!latitude.isFinite() || !longitude.isFinite()) continue

                    val stopId = cursor.getString(stopIdCol) ?: continue
                    val stopCode = cursor.getString(stopCodeCol) ?: stopId
                    val stopName = cursor.getString(stopNameCol) ?: ""

                    val properties = JsonObject().apply {
                        addProperty("stop_id", stopId)
                        addProperty("stop_code", stopCode)
                        addProperty("stop_name", stopName)
                    }

                    features.add(
                        Feature.fromGeometry(
                            Point.fromLngLat(longitude, latitude),
                            properties
                        )
                    )
                }

                features
            }
        }
    }

    private fun installLayers(style: Style) {
        check(style.getSource(SOURCE_ID) == null) {
            "מקור התחנות כבר קיים במפה"
        }

        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterRadius(CLUSTER_RADIUS)
            .withClusterMaxZoom(MAX_CLUSTER_ZOOM)

        style.addSource(
            GeoJsonSource(
                SOURCE_ID,
                FeatureCollection.fromFeatures(emptyArray<Feature>()),
                options
            )
        )

        // שכבת אשכולות תחנות כשמרוחקים
        val clusterLayer = CircleLayer(
            CLUSTER_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.has("point_count")
        ).withProperties(
            circleColor("#245B85"),
            circleRadius(16f),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(2.5f)
        )
        style.addLayer(clusterLayer)

        val clusterCountLayer = SymbolLayer(
            CLUSTER_COUNT_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.has("point_count")
        ).withProperties(
            textField(Expression.get("point_count_abbreviated")),
            textSize(11f),
            textColor("#FFFFFF"),
            textHaloColor("#245B85"),
            textHaloWidth(0.5f)
        )
        style.addLayer(clusterCountLayer)

        // שכבת תחנות בודדות: אייקון אוטובוס גרפי יפהפה עם שם התחנה
        val individualStopsLayer = SymbolLayer(
            STOP_LAYER_ID,
            SOURCE_ID
        ).withFilter(
            Expression.not(Expression.has("point_count"))
        ).withProperties(
            iconImage(BUS_ICON_ID),
            iconSize(1.0f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            textField(Expression.get("stop_name")),
            textSize(10.5f),
            textColor("#1A2B4C"),
            textHaloColor("#FFFFFF"),
            textHaloWidth(1.8f),
            textOffset(arrayOf(0f, 1.35f)),
            textAllowOverlap(false)
        )
        individualStopsLayer.setMinZoom(13.0f)

        style.addLayer(individualStopsLayer)
    }
}
