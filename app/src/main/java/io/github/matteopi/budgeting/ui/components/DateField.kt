package io.github.matteopi.budgeting.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Locale.ENGLISH: en-GB CLDR data abbreviates September as "Sept".
private val DISPLAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** How a date is shown in date fields, e.g. "Mon 28 Sep 2026". */
fun formatDisplayDate(date: LocalDate): String = date.format(DISPLAY_DATE_FORMAT)

/**
 * A read-only field that opens a Material 3 date picker dialog when tapped. [maxDate], if set, disables
 * later dates (e.g. a transaction can't be dated in the future).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    maxDate: LocalDate? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    PickerField(
        value = formatDisplayDate(date),
        label = label,
        onClick = { showPicker = true },
        modifier = modifier,
        trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
    )

    if (showPicker) {
        val initialMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = maxDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val selectableDates = remember(maxMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    maxMillis == null || utcTimeMillis <= maxMillis
            }
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
