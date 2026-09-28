package io.github.matteopi.budgeting.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A numeric keypad: 1-9, ".", 0, backspace. Emits raw key presses ("0".."9", ".", "back") — the
 * amount logic itself is `core.entry.AmountEntry` (written in parallel by another work package).
 */
@Composable
fun AmountKeypad(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "back"),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { key ->
                    TextButton(
                        onClick = { onKey(key) },
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text(if (key == "back") "⌫" else key)
                    }
                }
            }
        }
    }
}
