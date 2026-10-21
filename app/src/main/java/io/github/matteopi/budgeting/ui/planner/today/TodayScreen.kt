package io.github.matteopi.budgeting.ui.planner.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.ui.components.DatePickerDialogFor
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.planner.components.ColourDot
import io.github.matteopi.budgeting.ui.planner.components.ModulePicker
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar
import io.github.matteopi.budgeting.ui.planner.components.TaskRow
import io.github.matteopi.budgeting.ui.planner.day.ChunkState
import io.github.matteopi.budgeting.ui.planner.day.DayMapping
import io.github.matteopi.budgeting.ui.planner.habits.HabitRow
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Test tags of the Today tab. */
object TodayTags {
    /** A row of the day's agenda. */
    const val ON_TODAY = "today-on"
    const val NOW = "today-now"
    const val FINISH_YESTERDAY = "today-finish-yesterday"
    const val TIMER = "today-timer"
}

/**
 * The Planner's first tab (docs/PLAN.md §12.2, §13.9): the Now card, *Finish yesterday*, the day as an agenda (tap it,
 * or *Edit day*, for the day record), today's and overdue tasks, and the timer button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onOpenTodo: () -> Unit,
    onOpenDay: (LocalDate, Boolean) -> Unit,
    onAddTask: () -> Unit,
    onOpenHabit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var startingTimer by remember { mutableStateOf(false) }
    var pickingDay by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.completed.collect { undo ->
            scope.launch {
                val result = snackbarHostState.showSnackbar("Done: ${undo.task.title}", actionLabel = "Undo")
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(undo)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.habitEventFlow.collect { event ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(event.message, actionLabel = "Undo")
                if (result == SnackbarResult.ActionPerformed) viewModel.undoHabit(event)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.timerEventFlow.collect { event ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(event.message, actionLabel = if (event.undo != null) "Undo" else null)
                if (result == SnackbarResult.ActionPerformed) viewModel.undoTimer(event)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            PlannerTabTopBar(title = state.title, onOpenSettings = onOpenSettings) {
                IconButton(onClick = { pickingDay = true }) { Icon(Icons.Default.DateRange, contentDescription = "Open another day") }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (state.timerRunning) viewModel.stopTimer() else startingTimer = true },
                // Material clears the extended button's text from accessibility, so the icon carries its name.
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = if (state.timerRunning) "Stop timer" else "Start timer") },
                text = { Text(if (state.timerRunning) "Stop timer" else "Start timer") },
                modifier = Modifier.testTag(TodayTags.TIMER),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            if (state.loading) return@LazyColumn
            item(key = "now") { NowCard(state) }
            if (state.finishYesterday) {
                item(key = "yesterday") {
                    SectionCard(modifier = Modifier.padding(top = 8.dp).testTag(TodayTags.FINISH_YESTERDAY)) {
                        Text("Yesterday isn't finished yet.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onOpenDay(state.day.minusDays(1), true) }) { Text("Finish yesterday") }
                    }
                }
            }
            item(key = "day") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Header("The day", modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpenDay(state.day, false) }) { Text("Edit day") }
                }
            }
            items(state.agenda, key = { it.key }) { row -> AgendaRow(row, onClick = { onOpenDay(state.day, false) }) }
            if (state.overdue.isNotEmpty()) {
                item(key = "overdue") { Header("Overdue") }
                items(state.overdue, key = { "task-${it.id}" }) { row ->
                    TaskRow(row = row, onToggle = { if (it) viewModel.complete(row.id) }, onOpen = { onOpenTask(row.id) })
                }
            }
            item(key = "today") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Header("Due today", modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddTask) { Text("Add task") }
                }
            }
            if (state.dueToday.isEmpty()) {
                item(key = "empty") { EmptyState("Nothing due today.") }
            }
            items(state.dueToday, key = { "task-${it.id}" }) { row ->
                TaskRow(row = row, onToggle = { if (it) viewModel.complete(row.id) }, onOpen = { onOpenTask(row.id) })
            }
            item(key = "todo") { TextButton(onClick = onOpenTodo) { Text("All tasks") } }
            if (state.habits.isNotEmpty()) {
                item(key = "habits") { Header("Habits") }
                items(state.habits, key = { "habit-${it.id}" }) { row ->
                    HabitRow(
                        row = row,
                        onOpen = { onOpenHabit(row.id) },
                        onToggle = { viewModel.toggleHabit(row.id) },
                        onAdd = { amount -> viewModel.addToHabit(row.id, amount) },
                        onSkip = { skipped -> viewModel.skipHabit(row.id, skipped) },
                    )
                }
            }
        }
    }

    if (startingTimer) {
        ModalBottomSheet(onDismissRequest = { startingTimer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TimerStartSheet(state, onStart = { categoryId, moduleId ->
                viewModel.startTimer(categoryId, moduleId)
                startingTimer = false
            })
        }
    }
    if (pickingDay) {
        DatePickerDialogFor(
            initialDate = state.day,
            maxDate = null,
            onDateChange = { date ->
                pickingDay = false
                onOpenDay(date, false)
            },
            onDismiss = { pickingDay = false },
        )
    }
}

@Composable
private fun NowCard(state: TodayUiState) {
    val now = state.now ?: return
    SectionCard(modifier = Modifier.testTag(TodayTags.NOW)) {
        Text(now.classLine ?: "No more classes today", style = MaterialTheme.typography.titleSmall)
        Text(now.freeLine + " · " + state.freeSummary, style = MaterialTheme.typography.bodyMedium)
        now.timerLine?.let { Text("Recording: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

/** What the timer records: one tap on a category, and a module for study. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerStartSheet(state: TodayUiState, onStart: (Long, Long?) -> Unit) {
    var categoryId by remember { mutableStateOf(state.categories.firstOrNull { it.systemKey == CategoryKey.STUDY }?.id ?: state.categories.firstOrNull()?.id) }
    var moduleId by remember { mutableStateOf<Long?>(null) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Start a timer", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.categories.forEach { c ->
                FilterChip(selected = c.id == categoryId, onClick = { categoryId = c.id }, label = { Text(c.name) }, leadingIcon = { ColourDot(c.colorArgb) })
            }
        }
        ModulePicker(state.modules, moduleId, { moduleId = it })
        Button(onClick = { categoryId?.let { onStart(it, moduleId) } }, enabled = categoryId != null, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
        Box(modifier = Modifier.height(16.dp))
    }
}

/** "09:00–10:00 · EC101 Lecture · LT1 · To confirm", with a bar in its colour. */
@Composable
private fun AgendaRow(row: AgendaRowUi, onClick: () -> Unit) {
    val faint = row.state == ChunkState.FREE || row.state == ChunkState.NOT_RECORDED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(TodayTags.ON_TODAY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .background(row.colorArgb?.let { Color(it.toInt()) } ?: if (faint) Color.Transparent else MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(row.timeText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(92.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (faint) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (row.detail.isNotBlank()) {
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(DayMapping.stateLabel(row.state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}
