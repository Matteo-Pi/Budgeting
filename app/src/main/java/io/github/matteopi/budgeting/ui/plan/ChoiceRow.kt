package io.github.matteopi.budgeting.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A row of buttons for a small fixed choice (direction, frequency, posting mode, ...); the
 * selected option is filled, the rest outlined. Used by the Plan and Predictions editors.
 */
@Composable
internal fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            if (option == selected) {
                Button(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) {
                    Text(label(option), maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) {
                    Text(label(option), maxLines = 1)
                }
            }
        }
    }
}
