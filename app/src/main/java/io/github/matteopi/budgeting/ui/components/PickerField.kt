package io.github.matteopi.budgeting.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * A read-only text field that opens something (a date picker, a drop-down menu) when tapped.
 *
 * `Modifier.clickable` on an `OutlinedTextField` never fires: the text field's own pointer
 * handling consumes the tap even when it is read-only. So the tap target is a transparent box
 * drawn over the field. Put a `DropdownMenu` next to this inside the same parent `Box` to anchor
 * it to the field.
 */
@Composable
fun PickerField(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable () -> Unit = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClick = onClick),
        )
    }
}
