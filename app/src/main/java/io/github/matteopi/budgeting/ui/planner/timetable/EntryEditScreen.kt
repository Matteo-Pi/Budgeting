package io.github.matteopi.budgeting.ui.planner.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.timetable.EntryField
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.OptionalDateField
import io.github.matteopi.budgeting.ui.planner.components.ModulePicker
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import io.github.matteopi.budgeting.ui.planner.settings.MODULE_COLOURS
import io.github.matteopi.budgeting.ui.planner.task.ChipRow
import java.time.format.TextStyle
import java.util.Locale

/** Test tags of the timetable entry editor. */
object EntryEditTags {
    const val TITLE = "entry-edit-title"
    const val START = "entry-edit-start"
    const val END = "entry-edit-end"
    const val WEEKS = "entry-edit-weeks"
    const val ROOM = "entry-edit-room"
    const val PREVIEW = "entry-edit-preview"
    const val SAVE = "entry-edit-save"
}

/**
 * Adds or edits a timetable entry (docs/PLAN.md §12.4): what, when, which weeks, where. It previews the dates the
 * weeks give and warns about clashes as the user types. A plain scrolling column so every field stays composed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    entryId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryEditViewModel = viewModel(factory = EntryEditViewModel.factory(entryId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    LaunchedEffect(state.savedId, state.deleted) { if (state.savedId != null || state.deleted) onSaved() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Add to timetable" else "Edit timetable entry") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (!state.isNew) IconButton(onClick = viewModel::requestDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) return@Scaffold
        val errors = state.errors
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.title,
                onValueChange = { v -> viewModel.update { it.copy(title = v) } },
                label = { Text("Title") },
                placeholder = { Text("EC101 Lecture") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(EntryEditTags.TITLE),
            )
            ChipRow(EntryKind.entries, form.kind, TimetableMapping::kindLabel) { k -> viewModel.update { it.copy(kind = k) } }
            ModulePicker(state.modules, form.moduleId, { id -> viewModel.update { it.copy(moduleId = id) } })

            Text("When", style = MaterialTheme.typography.labelLarge)
            ChipRow(
                options = PatternChoice.entries,
                selected = form.pattern,
                label = { p ->
                    when (p) {
                        PatternChoice.EVERY_WEEK -> "Every week"
                        PatternChoice.CHOSEN_WEEKS -> "Chosen weeks"
                        PatternChoice.ODD_WEEKS -> "Odd weeks"
                        PatternChoice.EVEN_WEEKS -> "Even weeks"
                        PatternChoice.ONCE -> "Once"
                    }
                },
                onSelect = { p -> viewModel.update { it.copy(pattern = p) } },
            )
            if (form.pattern == PatternChoice.ONCE) {
                OptionalDateField(
                    label = "Date",
                    date = form.onceDate,
                    onDateChange = { d -> viewModel.update { it.copy(onceDate = d) } },
                    initialDate = state.today,
                )
            } else {
                ChipRow(TimetableMapping.WEEKDAYS, form.weekday, { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }) { d ->
                    viewModel.update { it.copy(weekday = d) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Start", form.start, { t -> viewModel.update { it.copy(start = t) } }, Modifier.weight(1f), EntryEditTags.START)
                TimeField("End", form.end, { t -> viewModel.update { it.copy(end = t) } }, Modifier.weight(1f), EntryEditTags.END)
            }
            if (form.pattern == PatternChoice.CHOSEN_WEEKS) {
                OutlinedTextField(
                    value = form.weeksText,
                    onValueChange = { v -> viewModel.update { it.copy(weeksText = v) } },
                    label = { Text("Teaching weeks") },
                    placeholder = { Text("1-5, 7-11") },
                    isError = errors[EntryField.WEEKS] != null && form.weeksText.isNotBlank(),
                    supportingText = { Text(errors[EntryField.WEEKS]?.takeIf { form.weeksText.isNotBlank() } ?: "Week 1 is the week the term starts in.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(EntryEditTags.WEEKS),
                )
            }
            if (form.pattern != PatternChoice.ONCE && state.terms.isNotEmpty()) {
                ChipRow(
                    options = listOf<Long?>(null) + state.terms.map { it.id },
                    selected = form.termId,
                    label = { id -> if (id == null) "Every term" else state.terms.first { it.id == id }.name },
                    onSelect = { id -> viewModel.update { it.copy(termId = id) } },
                )
            }
            if (form.pattern == PatternChoice.EVERY_WEEK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In the holidays too", modifier = Modifier.weight(1f))
                    Switch(checked = form.inHolidays, onCheckedChange = { v -> viewModel.update { it.copy(inHolidays = v) } })
                }
            }
            state.preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(EntryEditTags.PREVIEW)) }
            if (state.needsTermDates) {
                Text(
                    "Chosen, odd and even weeks count from your term dates: add them in Planner settings → Term dates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.clashes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Text("Where", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = form.location,
                onValueChange = { v -> viewModel.update { it.copy(location = v) } },
                label = { Text("Place (e.g. Campus)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.room,
                onValueChange = { v -> viewModel.update { it.copy(room = v) } },
                label = { Text("Room") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(EntryEditTags.ROOM),
            )
            OutlinedTextField(
                value = form.contact,
                onValueChange = { v -> viewModel.update { it.copy(contact = v) } },
                label = { Text("Lecturer or contact") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Colour (else the module's)", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Long?>(null) + MODULE_COLOURS).forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(argb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant)
                            .border(if (argb == form.colorArgb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable(role = Role.RadioButton) { viewModel.update { it.copy(colorArgb = argb) } },
                    )
                }
            }
            OutlinedTextField(
                value = form.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            val firstError = EntryField.entries.firstNotNullOfOrNull { errors[it] }
            if (firstError != null) Text(firstError, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::save, enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().testTag(EntryEditTags.SAVE)) {
                Text("Save")
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = "Delete ${form.title}?",
            text = "It goes from every week of the timetable.",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            confirmLabel = "Delete",
        )
    }
}
