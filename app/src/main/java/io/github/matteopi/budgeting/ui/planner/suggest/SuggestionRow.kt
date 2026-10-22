package io.github.matteopi.budgeting.ui.planner.suggest

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Test tags of suggestions. */
object SuggestionTags {
    const val ROW = "suggestion-row"
}

/** A suggestion, outlined as a proposal: its time, what for and why, with *Accept*, *Move* (when given) and *Another time*. */
@Composable
fun SuggestionRow(
    row: SuggestionRowUi,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showDay: Boolean = false,
    onMove: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .testTag(SuggestionTags.ROW)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            if (showDay) "${row.dayText} · ${row.timeText}" else row.timeText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = row.description },
        )
        Text(row.title, style = MaterialTheme.typography.bodyLarge)
        Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAccept) { Text("Accept") }
            if (onMove != null) TextButton(onClick = onMove) { Text("Move") }
            TextButton(onClick = onDismiss) { Text("Another time") }
        }
    }
}
