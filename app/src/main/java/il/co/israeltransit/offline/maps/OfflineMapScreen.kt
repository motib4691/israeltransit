package il.co.israeltransit.offline.maps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitRepository
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private data class CityInfo(val name: String, val lat: Double, val lon: Double)

private val ISRAEL_CITIES = listOf(
    CityInfo("ירושלים", 31.7683, 35.2137),
    CityInfo("תל אביב - יפו", 32.0853, 34.7818),
    CityInfo("חיפה", 32.7940, 34.9896),
    CityInfo("ראשון לציון", 31.9730, 34.7925),
    CityInfo("פתח תקווה", 32.0840, 34.8878),
    CityInfo("אשדוד", 31.8044, 34.6553),
    CityInfo("נתניה", 32.3215, 34.8532),
    CityInfo("בני ברק", 32.0833, 34.8333),
    CityInfo("באר שבע", 31.2530, 34.7915),
    CityInfo("חולון", 32.0158, 34.7874),
    CityInfo("רמת גן", 32.0684, 34.8248),
    CityInfo("אשקלון", 31.6688, 34.5743),
    CityInfo("רחובות", 31.8928, 34.8113),
    CityInfo("בת ים", 32.0167, 34.7500),
    CityInfo("בית שמש", 31.7470, 34.9881),
    CityInfo("כפר סבא", 32.1750, 34.9069),
    CityInfo("הרצליה", 32.1663, 34.8432),
    CityInfo("חדרה", 32.4340, 34.9197),
    CityInfo("מודיעין-מכבים-רעות", 31.8903, 35.0104),
    CityInfo("רעננה", 32.1848, 34.8708),
    CityInfo("מודיעין עילית", 31.9328, 35.0442),
    CityInfo("ביתר עילית", 31.6978, 35.1158),
    CityInfo("אלעד", 32.0522, 34.9511),
    CityInfo("טבריה", 32.7922, 35.5312),
    CityInfo("צפת", 32.9646, 35.4960),
    CityInfo("אילת", 29.5577, 34.9519)
)

@Composable
fun OfflineMapScreen(
    onBack: () -> Unit,
    onOpenStopDetails: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    var activeMapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var showCityDialog by remember { mutableStateOf(false) }
    var citySearchQuery by remember { mutableStateOf("") }

    if (!mapStorage.isMapAvailable()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.padding(24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text("קובץ המפה הלא-מקוונת אינו מותקן", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "כדי לצפות במפה ללא אינטרנט, ייבא את קובץ israel-basemap.pmtiles במסך 'עדכון נתונים'.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBack, shape = RoundedCornerShape(20.dp)) {
                        Text("חזרה למסך הבית")
                    }
                }
            }
        }
        return
    }

    if (showCityDialog) {
        AlertDialog(
            onDismissRequest = { showCityDialog = false },
            title = { Text("מעבר מהיר לעיר או יישוב", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = citySearchQuery,
                        onValueChange = { citySearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("הקלד שם עיר...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val filtered = ISRAEL_CITIES.filter { it.name.contains(citySearchQuery.trim()) }
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(filtered) { city ->
                            Text(
                                text = city.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeMapInstance?.animateCamera(
                                            CameraUpdateFactory.newCameraPosition(
                                                CameraPosition.Builder()
                                                    .target(LatLng(city.lat, city.lon))
                                                    .zoom(13.8)
                                                    .build()
                                            ),
                                            1200
                                        )
                                        showCityDialog = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 6.dp),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCityDialog = false }) { Text("סגירה") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TransitMapScreen(
            pmtilesFile = mapStorage.getMapFile(),
            modifier = Modifier.fillMaxSize(),
            onPlanRouteFrom = { stop -> onOpenStopDetails?.invoke(stop) },
            onPlanRouteTo = { stop -> onOpenStopDetails?.invoke(stop) }
        )

        // שורת כפתורים עליונה שקופה למחצה
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                )
            ) {
                Text("חזרה")
            }

            FilledTonalButton(
                onClick = {
                    citySearchQuery = ""
                    showCityDialog = true
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                )
            ) {
                Text("🏙️ מעבר לעיר")
            }
        }
    }
}
