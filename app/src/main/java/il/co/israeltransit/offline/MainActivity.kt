package il.co.israeltransit.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import il.co.israeltransit.offline.database.TransitDatabase
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.maps.OfflineMapScreen

sealed class AppScreen {
    data object Home : AppScreen()
    data class StopSearch(val initialStop: TransitRepository.TransitStop? = null) : AppScreen()
    data class LineSearch(val initialLineNumber: String? = null) : AppScreen()
    data class RoutePlanner(
        val initialOrigin: TransitRepository.TransitStop? = null,
        val initialDestination: TransitRepository.TransitStop? = null,
        val focusDestination: Boolean = false
    ) : AppScreen()
    data object SavedStops : AppScreen()
    data object OfflineMap : AppScreen()
    data object UpdateData : AppScreen()
    data object Settings : AppScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TransitApp()
        }
    }
}

@Composable
fun TransitApp() {
    val context = LocalContext.current
    var settingsVersion by remember { mutableIntStateOf(0) }
    val settings = remember(context, settingsVersion) { TransitSettings(context) }
    val database = remember(context) { TransitDatabase(context) }

    val isDark = when (settings.themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorTheme = settings.colorTheme
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = colorTheme.primary,
            secondary = colorTheme.secondary,
            background = colorTheme.backgroundDark,
            surface = colorTheme.surfaceDark,
            surfaceVariant = colorTheme.accentSoftDark
        )
    } else {
        lightColorScheme(
            primary = colorTheme.primary,
            secondary = colorTheme.secondary,
            background = colorTheme.backgroundLight,
            surface = colorTheme.surfaceLight,
            surfaceVariant = colorTheme.accentSoftLight
        )
    }

    val backStack = remember { mutableStateListOf<AppScreen>(AppScreen.Home) }
    val currentScreen = backStack.lastOrNull() ?: AppScreen.Home
    val stateHolder = rememberSaveableStateHolder()

    fun navigateTo(screen: AppScreen) {
        backStack.add(screen)
    }

    // כפתור אנדרואיד חוזר מסך אחד אחורה
    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // כפתור מובנה במסך מחזיר ישירות למסך הבית
    fun navigateHome() {
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateBack()
    }

    // בדיקת התראת החלפת שעון קיץ / חורף
    var clockAlert by remember {
        mutableStateOf(settings.checkClockChangeAlert(database.getLastUpdateDate()))
    }

    clockAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { clockAlert = null },
            title = {
                Text(
                    text = alert.title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clockAlert = null
                        navigateTo(AppScreen.UpdateData)
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("עדכון נתונים")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            settings.dismissedClockChangeId = alert.transitionId
                            clockAlert = null
                        }
                    ) {
                        Text("אל תראה שוב")
                    }
                    TextButton(
                        onClick = { clockAlert = null }
                    ) {
                        Text("סגור")
                    }
                }
            }
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                stateHolder.SaveableStateProvider(currentScreen.javaClass.name) {
                    when (val screen = currentScreen) {
                        is AppScreen.Home -> HomeScreen(onNavigate = { navigateTo(it) })

                        is AppScreen.StopSearch -> StopSearchScreen(
                            initialStop = screen.initialStop,
                            onBack = { navigateHome() },
                            onNavigateToLine = { lineNumber ->
                                navigateTo(AppScreen.LineSearch(lineNumber))
                            },
                            onPlanRouteFrom = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = stop,
                                        initialDestination = null,
                                        focusDestination = true
                                    )
                                )
                            },
                            onPlanRouteTo = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = null,
                                        initialDestination = stop,
                                        focusDestination = false
                                    )
                                )
                            }
                        )

                        is AppScreen.LineSearch -> LineSearchScreen(
                            initialLine = screen.initialLineNumber,
                            onBack = { navigateHome() },
                            onNavigateToStop = { stop ->
                                navigateTo(AppScreen.StopSearch(initialStop = stop))
                            },
                            onPlanRouteFrom = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = stop,
                                        initialDestination = null,
                                        focusDestination = true
                                    )
                                )
                            },
                            onPlanRouteTo = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = null,
                                        initialDestination = stop,
                                        focusDestination = false
                                    )
                                )
                            }
                        )

                        is AppScreen.RoutePlanner -> RoutePlannerScreen(
                            onBack = { navigateHome() },
                            initialOrigin = screen.initialOrigin,
                            initialDestination = screen.initialDestination,
                            focusDestinationInitially = screen.focusDestination
                        )

                        is AppScreen.SavedStops -> SavedStopsScreen(
                            onBack = { navigateHome() },
                            onPlanRouteFrom = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = stop,
                                        initialDestination = null,
                                        focusDestination = true
                                    )
                                )
                            },
                            onPlanRouteTo = { stop ->
                                navigateTo(
                                    AppScreen.RoutePlanner(
                                        initialOrigin = null,
                                        initialDestination = stop,
                                        focusDestination = false
                                    )
                                )
                            }
                        )

                        is AppScreen.OfflineMap -> OfflineMapScreen(
                            onBack = { navigateHome() },
                            onOpenStopDetails = { stop ->
                                navigateTo(AppScreen.StopSearch(initialStop = stop))
                            }
                        )

                        is AppScreen.UpdateData -> UpdateDataScreen(
                            onBack = { navigateHome() }
                        )

                        is AppScreen.Settings -> SettingsScreen(
                            onBack = { navigateHome() },
                            onSettingsChanged = { settingsVersion++ }
                        )
                    }
                }
            }
        }
    }
}

private data class HomeMenuItemData(
    val title: String,
    val description: String,
    val screen: AppScreen,
    val iconSymbol: String,
    val badgeColor: Color? = null
)

@Composable
private fun HomeScreen(onNavigate: (AppScreen) -> Unit) {
    val items = listOf(
        HomeMenuItemData("תכנון מסלול", "מציאת מסלול נסיעה בין שתי נקודות", AppScreen.RoutePlanner(), "🧭"),
        HomeMenuItemData("חיפוש קו", "חיפוש קווים לפי מספר, עיר ומפעיל", AppScreen.LineSearch(), "🚌"),
        HomeMenuItemData("חיפוש תחנה", "איתור תחנות וקווים העוברים בהן", AppScreen.StopSearch(), "🚏"),
        HomeMenuItemData("מפה לא מקוונת", "מפה אינטראקטיבית, איתור תחנות וערי ישראל", AppScreen.OfflineMap, "🗺️"),
        HomeMenuItemData("התחנות שלי", "התחנות ששמרת לשימוש מהיר", AppScreen.SavedStops, "⭐"),
        HomeMenuItemData("עדכון נתונים", "ייבוא ועדכון מאגר התחבורה וקובץ המפה", AppScreen.UpdateData, "🔄"),
        HomeMenuItemData("הגדרות", "התאמת החלפות, מרווחים, רטט בירידה ומראה", AppScreen.Settings, "⚙️")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "IsraelOfflineTransit",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "תחבורה ציבורית לא-מקוונת בישראל",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        items.forEach { item ->
            Card(
                onClick = { onNavigate(item.screen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = item.iconSymbol, fontSize = 24.sp)
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "←",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
