
package il.co.israeltransit.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val departureDateFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val departureTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun DepartureDateTimePicker(
    value: LocalDateTime,
    onValueChange: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("מועד יציאה")

        Text(
            "${value.format(departureDateFormatter)} בשעה " +
                value.format(departureTimeFormatter)
        )

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
                modifier = Modifier.weight(1f)
            ) {
                Text("בחירת תאריך")
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
                modifier = Modifier.weight(1f)
            ) {
                Text("בחירת שעה")
            }
        }

        Button(
            onClick = {
                onValueChange(LocalDateTime.now())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("יציאה עכשיו")
        }
    }
}
