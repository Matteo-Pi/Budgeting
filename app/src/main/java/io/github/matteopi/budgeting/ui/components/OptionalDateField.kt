package io.github.matteopi.budgeting.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.LocalDate

/** What an [OptionalDateField] shows while no date has been picked. */
const val PICK_A_DATE = "Pick a date"

/**
 * Like [DateField], but the date may not be known yet: while [date] is null the field reads
 * "Pick a date", and the picker opens on [initialDate]. Use it where a made-up default date
 * would be wrong — the caller must check for null before saving.
 */
@Composable
fun OptionalDateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    initialDate: LocalDate = LocalDate.now(),
    maxDate: LocalDate? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    PickerField(
        value = date?.let { formatDisplayDate(it) } ?: PICK_A_DATE,
        label = label,
        onClick = { showPicker = true },
        modifier = modifier,
        trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
    )

    if (showPicker) {
        DatePickerDialogFor(
            initialDate = date ?: initialDate,
            maxDate = maxDate,
            onDateChange = onDateChange,
            onDismiss = { showPicker = false },
        )
    }
}
