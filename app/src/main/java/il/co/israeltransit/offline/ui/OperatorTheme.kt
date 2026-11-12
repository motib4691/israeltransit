package il.co.israeltransit.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object OperatorTheme {

    const val ICON_ROUTE_FROM = "📍"
    const val ICON_ROUTE_TO = "🏁"

    /**
     * רשימת כל מפעילי התחבורה הציבורית בישראל לבחירה מהירה בסינון
     */
    val ALL_OPERATORS = listOf(
        "אגד",
        "דן",
        "מטרופולין",
        "קווים",
        "אלקטרה אפיקים",
        "סופרבוס",
        "תנופה",
        "רכבת ישראל",
        "כפיר (רכבת קלה ירושלים)",
        "דנקל (תבל מטרו ת\"א)",
        "דן בדרום",
        "דן באר שבע",
        "בית שמש אקספרס",
        "נתיב אקספרס",
        "גלים",
        "ש.א.מ (נצרת)",
        "נסיעות ותיירות נצרת"
    )

    fun getOperatorColor(agencyName: String): Color {
        val name = agencyName.trim()
        return when {
            name.contains("אגד") && !name.contains("תעבורה") -> Color(0xFF1B5E20)
            name.contains("דן") && !name.contains("בדרום") && !name.contains("באר שבע") -> Color(0xFF0D47A1)
            name.contains("קווים") -> Color(0xFF0277BD)
            name.contains("מטרופולין") -> Color(0xFFE65100)
            name.contains("סופרבוס") -> Color(0xFF00838F)
            name.contains("אלקטרה") || name.contains("אפיקים") -> Color(0xFF6A1B9A)
            name.contains("תנופה") -> Color(0xFFF57F17)
            name.contains("רכבת ישראל") -> Color(0xFF01579B)
            name.contains("כפיר") || name.contains("הקלה בירושלים") -> Color(0xFFB71C1C)
            name.contains("תבל") || name.contains("דנקל") -> Color(0xFF00695C)
            name.contains("בית שמש") -> Color(0xFF880E4F)
            name.contains("נתיב אקספרס") -> Color(0xFF37474F)
            name.contains("דן בדרום") || name.contains("דן באר שבע") -> Color(0xFF1976D2)
            else -> Color(0xFF1E5B94)
        }
    }

    fun getTransitIcon(routeType: String, agencyName: String): String {
        return when {
            routeType == "2" || agencyName.contains("רכבת ישראל") -> "🚆"
            routeType == "0" || agencyName.contains("כפיר") || agencyName.contains("דנקל") -> "🚊"
            else -> "🚌"
        }
    }

    fun normalizeHebrew(text: String): String {
        return text.replace("[\"'״׳\\-–\\s]".toRegex(), "").trim().lowercase()
    }
}

@Composable
fun TransitLineBadge(
    lineNumber: String,
    agencyName: String,
    routeType: String = "3",
    modifier: Modifier = Modifier
) {
    val color = OperatorTheme.getOperatorColor(agencyName)
    val icon = OperatorTheme.getTransitIcon(routeType, agencyName)

    Box(
        modifier = modifier
            .border(width = 1.3.dp, color = color, shape = RoundedCornerShape(8.dp))
            .background(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = lineNumber,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = color
            )
        }
    }
}

/**
 * כפתור אחיד ומעוצב עבור "מסלול מכאן" בכל רחבי האפליקציה
 */
@Composable
fun RouteFromButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(OperatorTheme.ICON_ROUTE_FROM, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("מסלול מכאן", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * כפתור אחיד ומעוצב עבור "מסלול לכאן" בכל רחבי האפליקציה
 */
@Composable
fun RouteToButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(OperatorTheme.ICON_ROUTE_TO, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("מסלול לכאן", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
