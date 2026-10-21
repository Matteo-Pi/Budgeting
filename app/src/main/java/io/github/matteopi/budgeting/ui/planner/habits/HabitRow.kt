package io.github.matteopi.budgeting.ui.planner.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.model.GoalUnit
import io.github.matteopi.budgeting.ui.planner.components.ColourDot
import io.github.matteopi.budgeting.ui.planner.components.DurationField

/** Test tags of the habit screens. */
object HabitTags {
    const val ROW = "habit-row"
    const val TICK = "habit-tick"
    const val ADD = "habit-add"
    const val AMOUNT = "habit-amount"
    const val NAME = "habit-edit-name"
    const val TARGET = "habit-edit-target"
    const val MIN_MINUTES = "habit-edit-min-minutes"
    const val REMINDER = "habit-edit-reminder"
    const val SAVE = "habit-edit-save"
    const val CELL = "habit-cell"
    const val STREAK = "habit-streak"
    const val PROGRESS = "habit-progress"
}

/**
 * A habit (docs/PLAN.md §12.9): a tick box (or *Add* for amounts), its name, goal and progress, the streak, and this
 * week's days. A tap opens its page; the menu has *Skip today*.
 */
@Composable
fun HabitRow(
    row: HabitRowUi,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onAdd: (Int) -> Unit,
    onSkip: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HabitTags.ROW)
            .clickable(role = Role.Button, onClickLabel = "Open ${row.name}", onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            row.tickable -> Checkbox(
                checked = row.ticked,
                onCheckedChange = { onToggle() },
                enabled = row.activeToday && !row.skipped,
                modifier = Modifier.testTag(HabitTags.TICK).semantics { contentDescription = "Done today: ${row.name}" },
            )
            else -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { ColourDot(row.colorArgb, 14) }
        }
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.tickable) ColourDot(row.colorArgb)
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "${row.goal} · ${row.progress}",
                style = MaterialTheme.typography.bodySmall,
                color = if (row.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(HabitTags.PROGRESS),
            )
            if (row.streak != null) {
                Text(row.streak, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(HabitTags.STREAK))
            }
            WeekStrip(row.week, modifier = Modifier.padding(top = 4.dp))
        }
        if (row.addsAmount && row.activeToday && !row.skipped) {
            TextButton(onClick = { adding = true }, modifier = Modifier.testTag(HabitTags.ADD)) { Text("Add") }
        }
        if (row.activeToday) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${row.name}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (row.skipped) "Don't skip today" else "Skip today") },
                        onClick = {
                            menuOpen = false
                            onSkip(!row.skipped)
                        },
                    )
                }
            }
        }
    }
    if (adding) {
        AmountDialog(
            name = row.name,
            unit = row.unit,
            onAdd = { amount ->
                adding = false
                onAdd(amount)
            },
            onDismiss = { adding = false },
        )
    }
}

/** Monday to Sunday as small circles: filled when done, outlined when missed, faint otherwise; today ringed. */
@Composable
fun WeekStrip(days: List<HabitDotUi>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DayMark(day.state, day.isToday, size = 12)
            }
        }
    }
}

/** How a day went, as a small circle. */
@Composable
fun DayMark(state: HabitDayState?, isToday: Boolean, size: Int) {
    val scheme = MaterialTheme.colorScheme
    val fill = when (state) {
        HabitDayState.DONE -> scheme.primary
        HabitDayState.MISSED -> Color.Transparent
        HabitDayState.SKIPPED, HabitDayState.PAUSED, HabitDayState.REST -> scheme.surfaceVariant
        else -> Color.Transparent
    }
    val ring = when {
        state == HabitDayState.MISSED -> scheme.error
        isToday -> scheme.primary
        else -> scheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(fill)
            .border(width = if (isToday || state == HabitDayState.MISSED) 2.dp else 1.dp, color = ring, shape = CircleShape),
    )
}

/** Adds minutes ("45m", "1h") or a number to today's amount. */
@Composable
private fun AmountDialog(name: String, unit: GoalUnit, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf<Int?>(null) }
    var text by remember { mutableStateOf("") }
    val amount = if (unit == GoalUnit.MINUTES) minutes else text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to $name") },
        text = {
            if (unit == GoalUnit.MINUTES) {
                DurationField(label = "How long", minutes = minutes, onMinutesChange = { minutes = it }, tag = HabitTags.AMOUNT)
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter { it.isDigit() }.take(5) },
                    label = { Text(if (unit == GoalUnit.TIMES) "How many times" else "How many") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(HabitTags.AMOUNT),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let(onAdd) }, enabled = amount != null && amount > 0) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
