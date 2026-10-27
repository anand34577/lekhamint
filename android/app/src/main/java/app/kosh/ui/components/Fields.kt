package app.kosh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kosh.core.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A labelled date that opens the Material date picker. [max] stops future dates where they make no sense. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, max: LocalDate? = null) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.CalendarMonth, null)
            Text("  ${Dates.medium(value)}", Modifier.weight(1f), textAlign = TextAlign.Start)
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    max == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(max)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** A labelled time of day that opens the Material time picker, in the phone's 12- or 24-hour style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: LocalTime, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Schedule, null)
            Text("  ${Dates.time(value, h24)}", Modifier.weight(1f), textAlign = TextAlign.Start)
        }
    }
    if (open) {
        val state = rememberTimePickerState(value.hour, value.minute, is24Hour = h24)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("OK") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(LocalTime.now().withSecond(0).withNano(0)); open = false }) { Text("Now") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * When money moved: the date and the time of day side by side. [max] limits the date; a time later
 * than now on today is shown as an error, since these record things that already happened.
 */
@Composable
fun WhenFields(dateLabel: String, date: LocalDate, onDate: (LocalDate) -> Unit, time: LocalTime, onTime: (LocalTime) -> Unit, max: LocalDate? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(dateLabel, date, onDate, Modifier.weight(1.25f), max = max)
            TimeField("Time", time, onTime, Modifier.weight(1f))
        }
        if (isFuture(date, time)) ErrorText("That time has not come yet today")
    }
}

/** [time] on [date] is still to come. */
fun isFuture(date: LocalDate, time: LocalTime): Boolean = date == Dates.today() && time.isAfter(LocalTime.now())

/** The current time to the minute, the default for "when". */
fun nowMinute(): LocalTime = LocalTime.now().withSecond(0).withNano(0)

/** A label on the left and a value on the right, for key facts. */
@Composable
fun FactRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

/** A short explanation of how a figure is worked out. */
@Composable
fun AssumptionNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An error line inside a dialog. */
@Composable
fun ErrorText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
