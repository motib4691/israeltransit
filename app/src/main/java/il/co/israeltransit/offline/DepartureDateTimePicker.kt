package il.co.israeltransit.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val departureDateFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val departureTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

enum class RouteTimeMode {
    NOW,
    DEPART_AT,
    ARRIVE_BY
}

@Composable
fun DepartureDateTimePicker(
    mode: RouteTimeMode,
    value: LocalDateTime,
    onModeChange: (RouteTimeMode) -> Unit,
    onValueChange: (LocalDateTime) -> Unit,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val options = listOf(
                    RouteTimeMode.NOW to "עכשיו",
                    RouteTimeMode.DEPART_AT to "שעת יציאה",
                    RouteTimeMode.ARRIVE_BY to "שעת הגעה"
                )

                options.forEachIndexed { index, (itemMode, label) ->
                    val selected = mode == itemMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable {
                                if (itemMode == RouteTimeMode.NOW) {
                                    onRefreshNow()
                                } else {
                                    if (mode == RouteTimeMode.NOW) {
                                        onValueChange(LocalDateTime.now())
                                    }
                                    onModeChange(itemMode)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    if (index < options.lastIndex) {
                        VerticalDivider(
                            modifier = Modifier.height(22.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        val prefixLabel = if (mode == RouteTimeMode.ARRIVE_BY) {
            "מועד הגעה"
        } else {
            "מועד יציאה"
        }

        Text(
            text = "$prefixLabel: ${value.format(departureDateFormatter)} בשעה " +
                value.format(departureTimeFormatter),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        AnimatedVisibility(visible = mode == RouteTimeMode.DEPART_AT || mode == RouteTimeMode.ARRIVE_BY) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                onValueChange(
                                    value.withYear(year)
                                        .withMonth(month + 1)
                                        .withDayOfMonth(day)
                                )
                            },
                            value.year,
                            value.monthValue - 1,
                            value.dayOfMonth
                        ).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ui_221d72),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                onValueChange(
                                    value.withHour(hour)
                                        .withMinute(minute)
                                        .withSecond(0)
                                        .withNano(0)
                                )
                            },
                            value.hour,
                            value.minute,
                            true
                        ).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ui_69c99e),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
