package io.github.matteopi.budgeting.ui.planner.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.AppIcons
import io.github.matteopi.budgeting.ui.components.PickerField
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton
import java.time.LocalTime

/** Test tags of the Planner's screens (docs/PLAN.md §13.11). */
object PlannerTags {
    const val QUICK_ADD = "planner-quick-add"
    const val QUICK_ADD_PREVIEW = "planner-quick-add-preview"
    const val TASK_ROW = "planner-task-row"
    const val TASK_TITLE = "planner-task-title"
    const val DEADLINE_STATUS = "planner-deadline-status"
    const val STEP_ROW = "planner-step-row"
    const val DONE_ROW = "planner-done-row"
}

/**
 * The top bar of a Planner tab: the section chooser, [title], [actions], and the gear that opens the Planner's
 * settings (docs/PLAN.md §12.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerTabTopBar(
    title: String,
    onOpenSettings: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { SectionSwitchButton(AppSection.PLANNER) },
        actions = {
            actions()
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Planner settings")
            }
        },
    )
}

/** What a task row shows (docs/PLAN.md §13.9): built by `TodoMapping`, drawn by [TaskRow]. */
@Immutable
data class TaskRowUi(
    val id: Long,
    val title: String,
    val subtitle: String,
    val isDeadline: Boolean,
    val overdue: Boolean,
    val done: Boolean = false,
    val repeats: Boolean = false,
    val highPriority: Boolean = false,
    val moduleColor: Long? = null,
    /** A deadline's work left and countdown ("6 h left · due in 3 days"), or null. */
    val status: String? = null,
)

/** A task: a tick box, its title and details; a tap opens it and a long press offers [menu]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    row: TaskRowUi,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PlannerTags.TASK_ROW)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onOpen,
                    onLongClick = if (menu.isNotEmpty()) ({ menuOpen = true }) else null,
                    onLongClickLabel = if (menu.isNotEmpty()) "More options" else null,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = row.done, onCheckedChange = onToggle)
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.moduleColor != null) ColourDot(row.moduleColor)
                    if (row.isDeadline) {
                        Icon(AppIcons.Flag, contentDescription = "Deadline", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (row.highPriority) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = if (row.done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag(PlannerTags.TASK_TITLE),
                    )
                    if (row.repeats) {
                        Icon(AppIcons.Repeat, contentDescription = "Repeats", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.subtitle.isNotEmpty()) {
                    Text(
                        row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.status != null) {
                    Text(
                        row.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(PlannerTags.DEADLINE_STATUS),
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    menuOpen = false
                    action()
                })
            }
        }
    }
}

/** A small round swatch of a module's colour. */
@Composable
fun ColourDot(argb: Long, size: Int = 10) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(argb.toInt())),
    )
}

/**
 * A typed time of day (docs/PLAN.md §13.9): "15:30", "1530" or "3pm". It keeps what was typed while it doesn't read
 * as a time, says so, and reports null for an empty field.
 */
@Composable
fun TimeField(
    label: String,
    time: LocalTime?,
    onTimeChange: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    var text by remember { mutableStateOf(time?.let(PlannerText::time) ?: "") }
    // A change from outside (a quick chip, a reset) replaces the text unless it already says that time.
    LaunchedEffect(time) {
        if (PlannerText.parseTime(text) != time) text = time?.let(PlannerText::time) ?: ""
    }
    val invalid = text.isNotBlank() && PlannerText.parseTime(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            if (value.isBlank()) onTimeChange(null) else PlannerText.parseTime(value)?.let(onTimeChange)
        },
        label = { Text(label) },
        placeholder = { Text("e.g. 15:30") },
        isError = invalid,
        supportingText = if (invalid) ({ Text("Type a time such as 15:30 or 3pm.") }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

/** A typed duration ("90", "1h30", "2h"), reported in minutes; null for an empty field. */
@Composable
fun DurationField(
    label: String,
    minutes: Int?,
    onMinutesChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    var text by remember { mutableStateOf(minutes?.let(PlannerText::duration)?.replace(" ", "") ?: "") }
    LaunchedEffect(minutes) {
        if (PlannerText.parseDuration(text) != minutes) text = minutes?.let(PlannerText::duration)?.replace(" ", "") ?: ""
    }
    val invalid = text.isNotBlank() && PlannerText.parseDuration(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            if (value.isBlank()) onMinutesChange(null) else PlannerText.parseDuration(value)?.let(onMinutesChange)
        },
        label = { Text(label) },
        placeholder = { Text("e.g. 1h30") },
        isError = invalid,
        supportingText = if (invalid) ({ Text("Type a length such as 45m, 2h or 1h30.") }) else null,
        singleLine = true,
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

/** A tap-to-choose field for a module, with *No module*. */
@Composable
fun ModulePicker(
    modules: List<Module>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = modules.firstOrNull { it.id == selectedId }
    Box(modifier = modifier) {
        PickerField(
            value = selected?.label ?: "No module",
            label = "Module",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("No module") }, onClick = {
                onSelect(null)
                expanded = false
            })
            modules.filter { !it.archived || it.id == selectedId }.forEach { module ->
                val argb = module.colorArgb
                DropdownMenuItem(
                    text = { Text(module.label) },
                    leadingIcon = if (argb != null) ({ ColourDot(argb, 12) }) else null,
                    onClick = {
                        onSelect(module.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
