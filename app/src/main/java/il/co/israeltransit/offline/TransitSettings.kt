package il.co.israeltransit.offline

import android.content.Context
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class TransitModePreference {
    ALL,
    PREFER_TRAIN,
    BUS_ONLY
}

enum class AppColorTheme(
    val title: String,
    val primary: Color,
    val secondary: Color,
    val backgroundLight: Color = Color(0xFFF1F5F9),
    val backgroundDark: Color = Color(0xFF111827),
    val surfaceLight: Color = Color(0xFFFFFFFF),
    val surfaceDark: Color = Color(0xFF1F2937),
    val cardBorderLight: Color = Color(0xFFCBD5E1),
    val cardBorderDark: Color = Color(0xFF374151),
    val accentSoftLight: Color = Color(0xFFE2E8F0),
    val accentSoftDark: Color = Color(0xFF263238)
) {
    BLUE(
        title = "כחול תחבורה",
        primary = Color(0xFF1565C0),
        secondary = Color(0xFF00897B),
        backgroundLight = Color(0xFFF0F4F8),
        backgroundDark = Color(0xFF0F172A),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceDark = Color(0xFF1E293B),
        cardBorderLight = Color(0xFFBFDBFE),
        cardBorderDark = Color(0xFF334155),
        accentSoftLight = Color(0xFFE0EFFF),
        accentSoftDark = Color(0xFF1E3A5F)
    ),
    TEAL(
        title = "טורקיז ירוק",
        primary = Color(0xFF00796B),
        secondary = Color(0xFF0288D1),
        backgroundLight = Color(0xFFF0F7F6),
        backgroundDark = Color(0xFF0C1F1C),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceDark = Color(0xFF142E2A),
        cardBorderLight = Color(0xFFB2DFDB),
        cardBorderDark = Color(0xFF1D4A43),
        accentSoftLight = Color(0xFFE0F2F1),
        accentSoftDark = Color(0xFF194D44)
    ),
    INDIGO(
        title = "אינדיגו עמוק",
        primary = Color(0xFF3F51B5),
        secondary = Color(0xFF7E57C2),
        backgroundLight = Color(0xFFF3F3FA),
        backgroundDark = Color(0xFF121426),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceDark = Color(0xFF1C1E38),
        cardBorderLight = Color(0xFFC5CAE9),
        cardBorderDark = Color(0xFF2E3258),
        accentSoftLight = Color(0xFFE8EAF6),
        accentSoftDark = Color(0xFF282B54)
    ),
    AMBER(
        title = "כתום חם",
        primary = Color(0xFFD97706),
        secondary = Color(0xFFB45309),
        backgroundLight = Color(0xFFFDF8F3),
        backgroundDark = Color(0xFF1C160C),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceDark = Color(0xFF2A2114),
        cardBorderLight = Color(0xFFFDE68A),
        cardBorderDark = Color(0xFF45361F),
        accentSoftLight = Color(0xFFFEF3C7),
        accentSoftDark = Color(0xFF3D2D16)
    )
}

data class ClockChangeAlert(
    val transitionId: String,
    val isSummerTime: Boolean,
    val transitionEpochMillis: Long,
    val title: String,
    val message: String
)

class TransitSettings(context: Context) {

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFERENCES_NAME = "israel_transit_settings"
        private const val KEY_MAX_TRANSFERS = "max_transfers"
        private const val KEY_MIN_TRANSFER_MINUTES = "min_transfer_minutes"
        private const val KEY_ALLOW_WALKING_TRANSFERS = "allow_walking_transfers"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_THEME = "color_theme"
        private const val KEY_TRANSIT_MODE = "transit_mode_preference"
        private const val KEY_ALIGHT_VIBRATION = "alight_vibration"
        private const val KEY_DISMISSED_CLOCK_CHANGE = "dismissed_clock_change_transition"

        const val DEFAULT_MAX_TRANSFERS = 3
        const val DEFAULT_MIN_TRANSFER_MINUTES = 2
        const val DEFAULT_ALLOW_WALKING_TRANSFERS = true
    }

    var maxTransfers: Int
        get() = preferences.getInt(KEY_MAX_TRANSFERS, DEFAULT_MAX_TRANSFERS).coerceIn(0, 5)
        set(value) { preferences.edit().putInt(KEY_MAX_TRANSFERS, value.coerceIn(0, 5)).apply() }

    var minTransferMinutes: Int
        get() = preferences.getInt(KEY_MIN_TRANSFER_MINUTES, DEFAULT_MIN_TRANSFER_MINUTES).coerceIn(1, 15)
        set(value) { preferences.edit().putInt(KEY_MIN_TRANSFER_MINUTES, value.coerceIn(1, 15)).apply() }

    var allowWalkingTransfers: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_WALKING_TRANSFERS, DEFAULT_ALLOW_WALKING_TRANSFERS)
        set(value) { preferences.edit().putBoolean(KEY_ALLOW_WALKING_TRANSFERS, value).apply() }

    var transitModePreference: TransitModePreference
        get() {
            val name = preferences.getString(KEY_TRANSIT_MODE, TransitModePreference.ALL.name)
            return try { TransitModePreference.valueOf(name ?: TransitModePreference.ALL.name) } catch (_: Exception) { TransitModePreference.ALL }
        }
        set(value) { preferences.edit().putString(KEY_TRANSIT_MODE, value.name).apply() }

    var alightVibrationEnabled: Boolean
        get() = preferences.getBoolean(KEY_ALIGHT_VIBRATION, false)
        set(value) { preferences.edit().putBoolean(KEY_ALIGHT_VIBRATION, value).apply() }

    var themeMode: AppThemeMode
        get() {
            val name = preferences.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            return try { AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name) } catch (_: Exception) { AppThemeMode.SYSTEM }
        }
        set(value) { preferences.edit().putString(KEY_THEME_MODE, value.name).apply() }

    var colorTheme: AppColorTheme
        get() {
            val name = preferences.getString(KEY_COLOR_THEME, AppColorTheme.BLUE.name)
            return try { AppColorTheme.valueOf(name ?: AppColorTheme.BLUE.name) } catch (_: Exception) { AppColorTheme.BLUE }
        }
        set(value) { preferences.edit().putString(KEY_COLOR_THEME, value.name).apply() }

    var dismissedClockChangeId: String?
        get() = preferences.getString(KEY_DISMISSED_CLOCK_CHANGE, null)
        set(value) { preferences.edit().putString(KEY_DISMISSED_CLOCK_CHANGE, value).apply() }

    /**
     * בדיקה האם התרחשה החלפת שעון בישראל (קיץ/חורף) לאחר שהותקן מאגר הנתונים הנוכחי,
     * והאם המשתמש טרם ביטל את ההודעה עבור החלפה ספציפית זו.
     */
    fun checkClockChangeAlert(lastDatabaseUpdateMillis: Long): ClockChangeAlert? {
        if (lastDatabaseUpdateMillis <= 0L) return null

        return try {
            val zoneRules = ZoneId.of("Asia/Jerusalem").rules
            val nowInstant = Instant.now()
            val previousTransition = zoneRules.previousTransition(nowInstant) ?: return null

            val transitionMillis = previousTransition.instant.toEpochMilli()

            // אם מאגר הנתונים עודכן לפני מועד החלפת השעון האחרון
            if (lastDatabaseUpdateMillis < transitionMillis) {
                val isSummer = previousTransition.isGap // במעבר לשעון קיץ מדלגים על שעה
                val transitionId = "${transitionMillis}_${if (isSummer) "SUMMER" else "WINTER"}"

                // אם המשתמש כבר בחר 'אל תראה לי שוב' עבור החלפה זו - לא נציג
                if (dismissedClockChangeId == transitionId) {
                    return null
                }

                val title = if (isSummer) "תזכורת: מעבר לשעון קיץ בישראל" else "תזכורת: מעבר לשעון חורף בישראל"
                val clockName = if (isSummer) "שעון קיץ" else "שעון חורף"
                val message = "חל מעבר ל$clockName בישראל. מאגר הנתונים במכשיר עודכן לפני החלפת השעון, ולכן ייתכנו אי-דיוקים בלוחות הזמנים של הקווים. מומלץ לייבא קובץ נתונים מעודכן במסך 'עדכון נתונים'."

                ClockChangeAlert(
                    transitionId = transitionId,
                    isSummerTime = isSummer,
                    transitionEpochMillis = transitionMillis,
                    title = title,
                    message = message
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
