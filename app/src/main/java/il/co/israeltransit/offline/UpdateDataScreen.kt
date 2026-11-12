package il.co.israeltransit.offline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitStorage
import il.co.israeltransit.offline.database.TransitStorageMigration
import il.co.israeltransit.offline.maps.OfflineMapStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val mapStorage = remember(context) { OfflineMapStorage(context) }
    val storage = remember(context) { TransitStorage(context) }
    val migration = remember(context) { TransitStorageMigration(context) }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("בחר קובץ נתונים או קובץ מפה להתקנה.") }
    var refreshVersion by remember { mutableIntStateOf(0) }

    val locations = remember(refreshVersion) { storage.availableLocations() }
    val selectedLocationId = remember(refreshVersion) {
        storage.getConfiguredLocationId() ?: storage.availableLocations().firstOrNull { !it.removable }?.id
    }
    val databaseAvailable = remember(refreshVersion) { database.isDatabaseAvailable() }
    val mapAvailable = remember(refreshVersion) { mapStorage.isMapAvailable() }
    val lastUpdate = remember(refreshVersion) { database.getLastUpdateDate() }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    val transitFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                statusMessage = "מתבצע ייבוא מהיר של מאגר התחבורה..."
                val result = withContext(Dispatchers.IO) { database.importDatabase(uri) }
                busy = false
                statusMessage = result.message
                refreshVersion++
            }
        }
    }

    val mapFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                statusMessage = "מתבצע ייבוא ואימות של קובץ המפה הלא-מקוונת..."
                val result = withContext(Dispatchers.IO) { mapStorage.importArchive(uri) }
                busy = false
                statusMessage = result.message
                refreshVersion++
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ייבוא ועדכון נתונים",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("חזרה למסך הבית")
            }
        }

        Text(
            text = "מרכז ניהול קובצי הנתונים והמפה של האפליקציה לפעילות מלאה ללא אינטרנט.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // כרטיס מאגר תחבורה GTFS
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("מאגר נתוני תחבורה (GTFS)", fontWeight = FontWeight.Bold)
                if (databaseAvailable) {
                    Text("מותקן במכשיר", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("עדכון אחרון: ${dateFormatter.format(Date(lastUpdate))}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("עדיין לא הותקן מאגר נתונים", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { transitFilePicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ייבוא קובץ נתוני תחבורה (israel-transit.sqlite.gz)")
                }
            }
        }

        // כרטיס מפה וקטורית PMTiles
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קובץ מפה לא מקוונת (PMTiles)", fontWeight = FontWeight.Bold)
                if (mapAvailable) {
                    Text("קובץ מפה מותקן ותקין", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else {
                    Text("עדיין לא הותקן קובץ מפה (אופציונלי)", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = { mapFilePicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ייבוא קובץ מפה (israel-basemap.pmtiles)")
                }
            }
        }

        // כרטיס בחירת אחסון
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("מיקום אחסון הקבצים", fontWeight = FontWeight.Bold)
                locations.forEach { loc ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLocationId == loc.id,
                            onClick = {
                                if (!busy && selectedLocationId != loc.id) {
                                    scope.launch {
                                        busy = true
                                        if (databaseAvailable || mapAvailable) {
                                            val result = withContext(Dispatchers.IO) { migration.moveTo(loc.id) }
                                            statusMessage = result.message
                                        } else {
                                            val success = storage.selectLocation(loc.id)
                                            statusMessage = if (success) {
                                                "מיקום האחסון הוגדר ל: ${loc.label}. הקבצים שייובאו יישמרו כאן."
                                            } else {
                                                "לא ניתן היה להגדיר את מיקום האחסון שנבחר."
                                            }
                                        }
                                        busy = false
                                        refreshVersion++
                                    }
                                }
                            }
                        )
                        Text(loc.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // צג התקדמות ייבוא, העברה ועדכון
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (busy) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            border = BorderStroke(
                1.dp,
                if (busy) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (busy) "⏳ מתבצע ייבוא / עדכון..." else "מצב המערכת:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
