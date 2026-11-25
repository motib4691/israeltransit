package il.co.israeltransit.offline

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.time.format.DateTimeFormatter

internal val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreenTopHeader(
    title: String,
    onBack: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 148.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
        ) {
            Text(
                text = stringResource(R.string.ui_55a91f),
                maxLines = 1,
                softWrap = false,
                fontSize = 13.sp
            )
        }
    }
}

internal object SearchHistoryHelper {
    fun getHistory(context: Context, prefKey: String): List<String> {
        val prefs = context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
        val json = prefs.getString(prefKey, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addHistory(context: Context, prefKey: String, item: String) {
        val trimmed = item.trim()
        if (trimmed.isBlank()) return
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val array = JSONArray(current.take(8))
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }

    fun removeHistory(context: Context, prefKey: String, item: String) {
        val current = getHistory(context, prefKey).toMutableList()
        current.remove(item)
        val array = JSONArray(current)
        context.getSharedPreferences("transit_search_histories", Context.MODE_PRIVATE)
            .edit().putString(prefKey, array.toString()).apply()
    }
}
