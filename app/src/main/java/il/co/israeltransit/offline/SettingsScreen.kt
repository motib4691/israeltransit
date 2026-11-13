package il.co.israeltransit.offline

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val TRANSIT_DATA_URL =
    "https://github.com/motib4691/israel-offline-transit/releases/download/transit-data/israel-transit.sqlite.gz"

private const val OFFLINE_MAP_URL =
    "https://github.com/motib4691/israel-offline-transit/releases/download/offline-map/israel-map-pack.zip"

private data class CreditEntry(
    val title: String,
    val license: String,
    val description: String
)

private val APP_CREDITS_AND_LICENSES = listOf(
    CreditEntry(
        title = "© OpenStreetMap contributors",
        license = "רישיון: Open Database License (ODbL 1.0)",
        description = "נתוני המפה, גבולות הים והמים, הפארקים, השבילים, שמות הרחובות, הכתובות ורשת ההליכה בישראל מבוססים על נתוני OpenStreetMap."
    ),
    CreditEntry(
        title = "משרד התחבורה בישראל (GTFS)",
        license = "מאגר מידע ממשלתי פתוח",
        description = "רשימת התחנות, הקווים, המסלולים ולוחות הזמנים מבוססים על קובצי ה-GTFS הפתוחים של משרד התחבורה."
    ),
    CreditEntry(
        title = "MapLibre Native Android",
        license = "רישיון: BSD 2-Clause License",
        description = "מנוע תצוגת המפה הווקטורית הלא-מקוונת (Copyright © MapLibre contributors)."
    ),
    CreditEntry(
        title = "Protomaps / PMTiles & Tilemaker",
        license = "רישיון: BSD 3-Clause / FTWPL",
        description = "פורמט האריחים הווקטוריים הלא-מקוונים PMTiles (Copyright © Protomaps) וכלי יצירת האריחים Tilemaker."
    ),
    CreditEntry(
        title = "GraphHopper Routing Engine",
        license = "רישיון: Apache License 2.0",
        description = "מנוע חישוב מסלולי ההליכה והפקת הוראות הפנייה (Copyright © GraphHopper GmbH)."
    ),
    CreditEntry(
        title = "Google Noto Sans & Basemaps Fonts",
        license = "רישיון: SIL Open Font License (OFL) 1.1",
        description = "גופני הטקסט להצגת שמות ערים, רחובות ותחנות בעברית ובאנגלית על גבי המפה."
    ),
    CreditEntry(
        title = "AndroidX, Jetpack Compose, Kotlin & תלויות תשתית",
        license = "רישיון: Apache License 2.0 / MIT / BSD",
        description = "כולל Jetpack Compose, Material 3, AndroidX, Kotlin, Kotlinx Coroutines, JTS Topology Suite, Gson, HPPC, Jackson ו-SLF4J."
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val settings = remember(context) { TransitSettings(context) }

    var maxTransfers by remember { mutableIntStateOf(settings.maxTransfers) }
    var minTransferMinutes by remember { mutableIntStateOf(settings.minTransferMinutes) }
    var allowWalkingTransfers by remember { mutableStateOf(settings.allowWalkingTransfers) }
    var transitMode by remember { mutableStateOf(settings.transitModePreference) }
    var alightVibration by remember { mutableStateOf(settings.alightVibrationEnabled) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var colorTheme by remember { mutableStateOf(settings.colorTheme) }

    var transferSliderValue by remember { mutableFloatStateOf(maxTransfers.toFloat()) }
    var bufferSliderValue by remember { mutableFloatStateOf(minTransferMinutes.toFloat()) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = {
                Text(
                    text = "קרדיטים, מקורות מידע ורישיונות",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    APP_CREDITS_AND_LICENSES.forEachIndexed { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = item.license,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (index < APP_CREDITS_AND_LICENSES.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("סגירה")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "הגדרות",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("חזרה")
            }
        }

        Text(
            text = "התאמת אופן תכנון המסלול, הנחיות לקובצי הנתונים ונראות האפליקציה.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // כרטיס תכנון נסיעה והעדפות
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "תכנון נסיעה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // בורר אמצעי תחבורה מותאם אוטומטית לרוחב המסך
                Text(
                    text = "העדפת אמצעי תחבורה:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        TransitModePreference.ALL to "כל האמצעים",
                        TransitModePreference.PREFER_TRAIN to "העדפת רכבת",
                        TransitModePreference.BUS_ONLY to "אוטובוסים בלבד"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = transitMode == mode,
                            onClick = {
                                transitMode = mode
                                settings.transitModePreference = mode
                                onSettingsChanged()
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }

                Text(
                    text = "בעת בחירת 'העדפת רכבת', יוצגו חלופות אוטובוס מצוינות במידה ואין רכבת מתאימה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(2.dp))

                // כמות החלפות
                Text(
                    text = "כמות החלפות מרבית מבוקשת:",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = if (maxTransfers == 0) "נסיעה ישירה בלבד (ללא החלפות)"
                           else "$maxTransfers החלפות לכל היותר",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = transferSliderValue,
                    onValueChange = {
                        transferSliderValue = it
                        maxTransfers = it.roundToInt()
                    },
                    onValueChangeFinished = {
                        val rounded = transferSliderValue.roundToInt()
                        maxTransfers = rounded
                        settings.maxTransfers = rounded
                        onSettingsChanged()
                    },
                    valueRange = 0f..5f,
                    steps = 4
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ישיר", style = MaterialTheme.typography.bodySmall)
                    Text("1", style = MaterialTheme.typography.bodySmall)
                    Text("2", style = MaterialTheme.typography.bodySmall)
                    Text("3 (ברירת מחדל)", style = MaterialTheme.typography.bodySmall)
                    Text("4", style = MaterialTheme.typography.bodySmall)
                    Text("5", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(2.dp))

                // מרווח ביטחון
                Text(
                    text = "מרווח ביטחון מינימלי להחלפת קו:",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "$minTransferMinutes דקות המתנה רצויה",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = bufferSliderValue,
                    onValueChange = {
                        bufferSliderValue = it
                        minTransferMinutes = it.roundToInt()
                    },
                    onValueChangeFinished = {
                        val rounded = bufferSliderValue.roundToInt()
                        minTransferMinutes = rounded
                        settings.minTransferMinutes = rounded
                        onSettingsChanged()
                    },
                    valueRange = 1f..15f,
                    steps = 13
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 דק'", style = MaterialTheme.typography.bodySmall)
                    Text("2 (ברירת מחדל)", style = MaterialTheme.typography.bodySmall)
                    Text("15 דק'", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))

                // הליכה בין תחנות בהחלפה
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "אפשר הליכה בין תחנות בהחלפה",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = if (allowWalkingTransfers)
                                "מאפשר מעבר רגלי קצר (עד 350 מטר) בין תחנות סמוכות"
                            else
                                "יוצגו רק מסלולים ישירים או החלפות באותה תחנה בלבד",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = allowWalkingTransfers,
                        onCheckedChange = {
                            allowWalkingTransfers = it
                            settings.allowWalkingTransfers = it
                            onSettingsChanged()
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // מתג התראת ירידה ברטט
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "התראת רטט לפני תחנת ירידה",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "מרטיט את המכשיר בקרבת תחנת היעד (פעיל רק במכשירים הכוללים רכיב מיקום GPS פעיל)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = alightVibration,
                        onCheckedChange = {
                            alightVibration = it
                            settings.alightVibrationEnabled = it
                            onSettingsChanged()
                        }
                    )
                }
            }
        }

        // כרטיס הוראות הורדת הקבצים והקישורים הישירים
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "הורדת קובצי נתונים ומפה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "הנחיות לעדכון המאגרים:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "• חבילת המפה והניווט (israel-map-pack.zip): יציבה מאוד ומכילה את מפת הדרכים בישראל, אינדקס הכתובות ורשת ההליכה. אין צורך לעדכן אותה אלא אחת לתקופה ממושכת.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "• מאגר התחבורה (GTFS): מתעדכן בתחילת כל חודש עם לוחות זמנים ומסלולים חדשים. מומלץ לעדכן אותו אחת לחודש.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    text = "קישורים ישירים להורדה:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                // קישור GTFS
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "מאגר נתוני תחבורה (israel-transit.sqlite.gz)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = TRANSIT_DATA_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(TRANSIT_DATA_URL))
                                Toast.makeText(context, "קישור נתוני התחבורה הועתק ללוח", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("העתק קישור להורדה", fontSize = 12.sp)
                        }
                    }
                }

                // קישור חבילת המפה המאוחדת
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "חבילת מפה וניווט לא מקוונת (israel-map-pack.zip)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = OFFLINE_MAP_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        FilledTonalButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(OFFLINE_MAP_URL))
                                Toast.makeText(context, "קישור חבילת המפה הועתק ללוח", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("העתק קישור להורדה", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // כרטיס עיצוב ומראה
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "עיצוב ומראה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "מצב תצוגה:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        AppThemeMode.SYSTEM to "מערכת",
                        AppThemeMode.LIGHT to "בהיר",
                        AppThemeMode.DARK to "כהה"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = {
                                themeMode = mode
                                settings.themeMode = mode
                                onSettingsChanged()
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "צבע נושא ראשי:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppColorTheme.entries.forEach { theme ->
                        val isSelected = colorTheme == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    colorTheme = theme
                                    settings.colorTheme = theme
                                    onSettingsChanged()
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(theme.primary)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = theme.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // כרטיס אודות + קרדיטים ורישיונות
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "אודות האפליקציה",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IsraelOfflineTransit — גרסה 1.1.0",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Text(
                    text = "האפליקציה פועלת באופן לא-מקוון ומאפשרת שימוש מלא בתחבורה הציבורית בישראל ללא תלות ברשת או ב-GPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { showLicensesDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📜 רישיונות קוד פתוח ומקורות מידע", fontSize = 12.sp)
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("חזרה למסך הבית")
        }
    }
}
