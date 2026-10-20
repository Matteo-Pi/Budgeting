package io.github.matteopi.budgeting.ui.planner.timetable

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.domain.planner.CalendarState
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** Test tags of the timetable. */
object TimetableTags {
    const val ITEM = "timetable-item"
    const val ROW = "timetable-row"
    const val SHEET = "timetable-sheet"
}

private val HOUR_HEIGHT: Dp = 48.dp
private val GUTTER: Dp = 36.dp
private val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/**
 * The Timetable tab (docs/PLAN.md §12.4, §13.9): the week as a grid (classes, linked calendar events, exams), a day's
 * list, and the month. Tapping a class offers *Cancel this week*, *Change this week* and *Edit entry*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onOpenSettings: () -> Unit,
    onOpenCalendars: () -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimetableViewModel = viewModel(factory = TimetableViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<TimetableItemUi?>(null) }
    var changing by remember { mutableStateOf<TimetableItemUi?>(null) }
    var showClashes by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The calendar permission can change in Android's settings while the app is away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCalendar() }

    val clashes = state.week?.clashes.orEmpty()
    Scaffold(
        modifier = modifier,
        topBar = {
            PlannerTabTopBar(title = "Timetable", onOpenSettings = onOpenSettings) {
                if (clashes.isNotEmpty()) {
                    IconButton(onClick = { showClashes = true }) {
                        Icon(Icons.Default.Warning, contentDescription = "Clashes (${clashes.size})", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val from = state.copyFrom
                        val to = state.copyTo
                        if (from != null && to != null) {
                            DropdownMenuItem(
                                text = { Text("Copy ${from.name}'s timetable to ${to.name}") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.copyTerm { n ->
                                        scope.launch { snackbarHostState.showSnackbar(if (n == 1) "Copied 1 entry" else "Copied $n entries") }
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Calendars") }, onClick = {
                            menuOpen = false
                            onOpenCalendars()
                        })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddEntry,
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.Add, contentDescription = "Add to timetable") },
                text = { Text("Add to timetable") },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(TimetableMode.DAY to "Day", TimetableMode.WEEK to "Week", TimetableMode.MONTH to "Month").forEach { (mode, label) ->
                    FilterChip(selected = state.mode == mode, onClick = { viewModel.setMode(mode) }, label = { Text(label) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::goToToday) { Text("Now") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.move(-1) }) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous") }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (state.mode) {
                            TimetableMode.DAY -> state.focus.format(DAY_TITLE)
                            TimetableMode.WEEK -> state.week?.title.orEmpty()
                            TimetableMode.MONTH -> state.focus.format(MONTH_TITLE)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val label = if (state.mode == TimetableMode.WEEK) state.week?.termLabel.orEmpty() else ""
                    if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.move(1) }) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next") }
            }
            CalendarNote(state, onOpenCalendars)
            if (state.loading) return@Column
            when (state.mode) {
                TimetableMode.WEEK -> state.week?.let { week -> WeekGrid(week, onItem = { selected = it }, onDay = viewModel::openDay) }
                TimetableMode.DAY -> DayList(state, onItem = { selected = it })
                TimetableMode.MONTH -> MonthGrid(state, onDay = viewModel::openDay)
            }
        }
    }

    selected?.let { item ->
        ItemSheet(
            item = item,
            onDismiss = { selected = null },
            onCancel = {
                viewModel.cancel(item)
                selected = null
            },
            onRestore = {
                viewModel.restore(item)
                selected = null
            },
            onChange = {
                changing = item
                selected = null
            },
            onEdit = {
                selected = null
                item.entryId?.let(onEditEntry)
            },
            onOpenTask = {
                selected = null
                item.taskId?.let(onOpenTask)
            },
            onOpenEvent = {
                selected = null
                item.eventId?.let { id ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)))
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbarHostState.showSnackbar("No calendar app to open it in") }
                    }
                }
            },
        )
    }
    changing?.let { item ->
        ChangeDialog(item, onDismiss = { changing = null }) { start, end, room ->
            viewModel.change(item, start, end, room)
            changing = null
        }
    }
    if (showClashes) {
        AlertDialog(
            onDismissRequest = { showClashes = false },
            title = { Text("Clashes this week") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { clashes.forEach { Text(it) } } },
            confirmButton = { TextButton(onClick = { showClashes = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun CalendarNote(state: TimetableUiState, onOpenCalendars: () -> Unit) {
    val text = when {
        state.calendar is CalendarState.NeedsPermission -> "Calendar access is off, so your linked calendars can't show."
        state.calendar is CalendarState.Off && !state.hasEntries ->
            "Add your classes with the + button, or link your Google Calendar so its events show here too."
        else -> null
    } ?: return
    SectionCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpenCalendars) { Text("Calendars") }
    }
}

/** The colour an item is drawn in, softened for the block and full for its edge. */
private fun itemColour(item: TimetableItemUi, fallback: Color): Color = item.colorArgb?.let { Color(it.toInt()) } ?: fallback

private fun describe(item: TimetableItemUi): String {
    val day = item.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return listOf(item.title, "$day ${item.timeText}", item.detail).filter { it.isNotBlank() }.joinToString(", ")
}

@Composable
private fun WeekGrid(week: WeekUi, onItem: (TimetableItemUi) -> Unit, onDay: (LocalDate) -> Unit) {
    val hours = week.lastHour - week.firstHour
    val lineColour = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
            Spacer(Modifier.width(GUTTER))
            week.days.forEach { day ->
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = if (day.isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button) { onDay(day.date) }.padding(vertical = 4.dp),
                )
            }
        }
        if (week.days.any { it.allDay.isNotEmpty() }) {
            Row(modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
                Spacer(Modifier.width(GUTTER))
                week.days.forEach { day ->
                    Text(
                        day.allDay.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(1.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(end = 4.dp, bottom = 88.dp)
                .height(HOUR_HEIGHT * hours),
        ) {
            Box(modifier = Modifier.width(GUTTER).fillMaxHeight()) {
                for (h in 0 until hours) {
                    Text(
                        "%02d".format(week.firstHour + h),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(y = HOUR_HEIGHT * h).padding(start = 6.dp),
                    )
                }
            }
            week.days.forEach { day ->
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .drawBehind {
                            val step = size.height / hours
                            for (h in 0..hours) drawLine(lineColour, Offset(0f, step * h), Offset(size.width, step * h), 1f)
                            drawLine(lineColour, Offset(0f, 0f), Offset(0f, size.height), 1f)
                        },
                ) {
                    val columnWidth = maxWidth
                    day.items.forEach { item ->
                        val laneWidth = columnWidth / item.lanes
                        val top = HOUR_HEIGHT * ((item.startMinute - week.firstHour * 60) / 60f)
                        val height = (HOUR_HEIGHT * ((item.endMinute - item.startMinute) / 60f)).coerceAtLeast(18.dp)
                        val colour = itemColour(item, primary)
                        val cancelled = item.kind == ItemKind.CANCELLED
                        Box(
                            modifier = Modifier
                                .offset(x = laneWidth * item.lane, y = top)
                                .width(laneWidth)
                                .height(height)
                                .padding(1.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (cancelled) MaterialTheme.colorScheme.surfaceVariant else colour.copy(alpha = 0.22f))
                                .drawBehind { if (!cancelled) drawRect(colour, size = size.copy(width = 3.dp.toPx())) }
                                .testTag(TimetableTags.ITEM)
                                .semantics { contentDescription = describe(item) + if (cancelled) ", cancelled" else "" }
                                .clickable(role = Role.Button) { onItem(item) }
                                .padding(start = 5.dp, top = 1.dp, end = 1.dp),
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayList(state: TimetableUiState, onItem: (TimetableItemUi) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        items(state.dayAllDay, key = { "allday-$it" }) { title ->
            Text("All day: $title", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.day.isEmpty() && state.dayAllDay.isEmpty()) {
            item(key = "empty") { EmptyState("Nothing on this day.") }
        }
        items(state.day, key = { it.key }) { item ->
            val colour = itemColour(item, MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TimetableTags.ROW)
                    .clickable(role = Role.Button) { onItem(item) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(4.dp).height(36.dp).background(colour))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (item.kind == ItemKind.CANCELLED) TextDecoration.LineThrough else null,
                    )
                    Text(
                        listOf(item.timeText, item.detail).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(state: TimetableUiState, onDay: (LocalDate) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row {
            TimetableMapping.WEEKDAYS.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.month.chunked(7).forEach { week ->
            Row {
                week.forEach { day ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (day.isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(role = Role.Button) { onDay(day.date) }
                            .semantics { contentDescription = "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, ${day.count} items" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${day.date.dayOfMonth}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (day.inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            day.colours.forEach { argb -> Box(Modifier.size(6.dp).clip(CircleShape).background(Color(argb.toInt()))) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(
    item: TimetableItemUi,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onChange: () -> Unit,
    onEdit: () -> Unit,
    onOpenTask: () -> Unit,
    onOpenEvent: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().testTag(TimetableTags.SHEET).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${PlannerText.date(item.date, item.date)} · ${item.timeText}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            when (item.kind) {
                ItemKind.CLASS -> {
                    TextButton(onClick = onCancel) { Text("Cancel this week") }
                    TextButton(onClick = onChange) { Text("Change this week") }
                    if (item.changed) TextButton(onClick = onRestore) { Text("As usual this week") }
                    TextButton(onClick = onEdit) { Text("Edit entry") }
                }
                ItemKind.CANCELLED -> {
                    TextButton(onClick = onRestore) { Text("Back on this week") }
                    TextButton(onClick = onEdit) { Text("Edit entry") }
                }
                ItemKind.EVENT -> TextButton(onClick = onOpenEvent) { Text("Open in your calendar") }
                ItemKind.EXAM, ItemKind.TASK -> TextButton(onClick = onOpenTask) { Text("Open") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChangeDialog(item: TimetableItemUi, onDismiss: () -> Unit, onSave: (LocalTime, LocalTime, String?) -> Unit) {
    var start by remember { mutableStateOf<LocalTime?>(item.start) }
    var end by remember { mutableStateOf<LocalTime?>(item.end) }
    var room by remember { mutableStateOf(item.room) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change this week") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only ${PlannerText.date(item.date, item.date)} changes.", style = MaterialTheme.typography.bodyMedium)
                TimeField(label = "Start", time = start, onTimeChange = { start = it })
                TimeField(label = "End", time = end, onTimeChange = { end = it })
                OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Room") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            val s = start
            val e = end
            TextButton(enabled = s != null && e != null && s != e, onClick = { if (s != null && e != null) onSave(s, e, room.trim().ifEmpty { null }) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
