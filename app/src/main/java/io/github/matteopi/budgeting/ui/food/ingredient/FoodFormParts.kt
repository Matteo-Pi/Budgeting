package io.github.matteopi.budgeting.ui.food.ingredient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.ui.components.PickerField

/*
 * Small form parts shared by the Recipes section's screens that this package builds: the ingredient
 * editor, the recipe review and its inline "create ingredient" form.
 */

/** A read-only field that opens a drop-down of [options]; [optionName] words each one. */
@Composable
fun <T> OptionPicker(
    label: String,
    selected: T,
    options: List<T>,
    optionName: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        PickerField(
            value = optionName(selected),
            label = label,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionName(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A single-line field for a number (decimal keyboard), red while [isError]. */
@Composable
fun FoodNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    integersOnly: Boolean = false,
    prefix: String? = null,
) {
    val prefixContent: (@Composable () -> Unit)? = if (prefix == null) null else ({ Text(prefix) })
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = prefixContent,
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (integersOnly) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** The bar under a form: what is wrong (or the error from the last try) above a full-width Save button. */
@Composable
fun FoodSaveBar(
    label: String,
    problem: String?,
    error: String?,
    enabled: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    tag: String,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (problem != null) {
                Text(problem, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSave,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth().testTag(tag),
            ) {
                Text(if (saving) "Saving…" else label)
            }
        }
    }
}
