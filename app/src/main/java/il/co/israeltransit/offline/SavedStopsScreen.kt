package il.co.israeltransit.offline

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.ui.RouteFromButton
import il.co.israeltransit.offline.ui.RouteToButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class SavedStop(
    val stopId: String,
    val stopName: String,
    val customName: String,
    val stopCode: String = stopId
)

private class SavedStopsStorage(context: Context) {
    private val preferences = context.getSharedPreferences(
        "saved_transit_stops",
        Context.MODE_PRIVATE
    )

    fun load(): List<SavedStop> {
        val json = preferences.getString("stops", "[]") ?: "[]"

        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val sId = item.optString("stopId")
                    val sCode = item.optString("stopCode").ifBlank { sId }
                    add(
                        SavedStop(
                            stopId = sId,
                            stopName = item.optString("stopName"),
                            customName = item.optString("customName"),
                            stopCode = sCode
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(stops: List<SavedStop>) {
        val array = JSONArray()

        stops.forEach { stop ->
            array.put(
                JSONObject().apply {
                    put("stopId", stop.stopId)
                    put("stopName", stop.stopName)
                    put("customName", stop.customName)
                    put("stopCode", stop.stopCode)
                }
            )
        }

        preferences.edit()
            .putString("stops", array.toString())
            .apply()
    }
}

@Composable
fun SavedStopsScreen(
    onBack: () -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current
    val database = remember(context) { TransitDatabase(context) }
    val repository = remember(database) { TransitRepository(database) }
    val storage = remember(context) { SavedStopsStorage(context) }

    var savedStops by remember { mutableStateOf(storage.load()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember {
        mutableStateOf(emptyList<TransitRepository.TransitStop>())
    }
    var searching by remember { mutableStateOf(false) }

    var dialogStop by remember { mutableStateOf<TransitRepository.TransitStop?>(null) }
    var editingStop by remember { mutableStateOf<SavedStop?>(null) }
    var customName by remember { mutableStateOf("") }

    val savedRefs = remember(savedStops) {
        savedStops.map {
            TransitRepository.SavedStopRef(
                stopId = it.stopId,
                customName = it.customName,
                stopCode = it.stopCode
            )
        }
    }

    fun persist(stops: List<SavedStop>) {
        savedStops = stops
        storage.save(stops)
    }

    LaunchedEffect(query) {
        val value = query.trim()

        if (value.isEmpty()) {
            searchResults = emptyList()
            searching = false
        } else {
            delay(250)
            searching = true
            searchResults = withContext(Dispatchers.IO) {
                repository.searchStops(value, 50, savedRefs)
            }
            searching = false
        }
    }

    if (dialogStop != null || editingStop != null) {
        val isEditing = editingStop != null
        val stopName = editingStop?.stopName ?: dialogStop?.name.orEmpty()
        val stopId = editingStop?.stopId ?: dialogStop?.stopId.orEmpty()
        val stopCode = editingStop?.stopCode ?: dialogStop?.stopCode ?: stopId

        AlertDialog(
            onDismissRequest = {
                dialogStop = null
                editingStop = null
            },
            title = {
                Text(
                    text = if (isEditing) "שינוי שם התחנה" else "שמירת תחנה",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stopName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "מספר תחנה (מק\"ט): $stopCode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("שם אישי, למשל בית או עבודה") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = customName.trim()
                        if (name.isNotEmpty()) {
                            val updated = if (isEditing) {
                                savedStops.map {
                                    if (it.stopId == stopId) it.copy(customName = name) else it
                                }
                            } else {
                                savedStops.filterNot { it.stopId == stopId } + SavedStop(
                                    stopId = stopId,
                                    stopName = stopName,
                                    customName = name,
                                    stopCode = stopCode
                                )
                            }
                            persist(updated)
                        }
                        dialogStop = null
                        editingStop = null
                    },
                    shape = RoundedCornerShape(20.dp),
                    enabled = customName.trim().isNotEmpty()
                ) {
                    Text("שמירה")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dialogStop = null
                        editingStop = null
                    }
                ) {
                    Text("ביטול")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // כותרת עליונה + כפתור חזרה למסך הבית
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "התחנות שלי",
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
            text = "שמור תחנות חשובות עם שם אישי כדי למצוא אותן ולתכנן אליהן מסלולים במהירות.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("חיפוש תחנה להוספה (לפי שם או מק\"ט)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) { Text("✕") }
                }
            }
        )

        if (!database.isDatabaseAvailable()) {
            Text("יש להתקין תחילה מאגר נתונים במסך עדכון נתונים.")
        } else if (searching) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else if (query.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Text("לא נמצאו תחנות מתאימות.")
            }

            searchResults.forEach { stop ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stop.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        Text(
                            "מספר תחנה (מק\"ט): ${stop.stopCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        val alreadySaved = savedStops.any { it.stopId == stop.stopId }

                        Button(
                            onClick = {
                                customName = stop.customName ?: stop.name
                                dialogStop = stop
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (alreadySaved) "עדכון השם האישי" else "⭐ הוספה לתחנות שלי")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "התחנות השמורות",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (savedStops.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "עדיין לא שמרת תחנות. חפש תחנה בשדה למעלה והוסף אותה לרשימה.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        savedStops.forEach { stop ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(2.dp)
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
                            text = "⭐ ${stop.customName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "תחנה ${stop.stopCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Text(
                        text = stop.stopName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val transitStop = TransitRepository.TransitStop(
                        stopId = stop.stopId,
                        name = stop.stopName,
                        latitude = null,
                        longitude = null,
                        stopCode = stop.stopCode,
                        customName = stop.customName
                    )

                    // שורת כפתורי ניווט אחידים עם האייקונים הקבועים (📍 ו-🏁)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPlanRouteFrom != null) {
                            RouteFromButton(
                                onClick = { onPlanRouteFrom(transitStop) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (onPlanRouteTo != null) {
                            RouteToButton(
                                onClick = { onPlanRouteTo(transitStop) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // שורת פעולות ניהול
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                editingStop = stop
                                customName = stop.customName
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("שינוי שם", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                persist(savedStops.filterNot { it.stopId == stop.stopId })
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("הסרה", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
