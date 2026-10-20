package io.github.matteopi.budgeting.ui.planner.day

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DaySource
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.day.Snap
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.ui.planner.components.ColourDot
import io.github.matteopi.budgeting.ui.planner.components.ModulePicker
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Test tags of the day editor. */
object DayTags {
    const val SEGMENT = "day-segment"
    const val BOUNDARY = "day-boundary"
    const val SHEET = "day-sheet"
    const val BANNER = "day-banner"
    const val START = "chunk-start"
    const val END = "chunk-end"
    const val NOTE = "chunk-note"
    const val SAVE = "chunk-save"
}

/** 64 dp an hour, so 15 minutes is 16 dp (docs/PLAN.md §13.9). */
private val HOUR_HEIGHT: Dp = 64.dp
private val GUTTER: Dp = 48.dp
private val STRIP: Dp = 6.dp
private val HANDLE: Dp = 48.dp

/** What the sheet is open on. */
private sealed interface SheetTarget {
    data class Segment(val ui: SegmentUi, val at: Instant) : SheetTarget

    data class Form(val form: ChunkForm) : SheetTarget
}

/**
 * The day editor (docs/PLAN.md §12.8, §13.9): the day as a scrolling strip of chunks at 64 dp an hour. Tap a chunk
 * to label, split, merge, copy or delete it; drag a boundary in 15-minute steps; confirm what was planned. With
 * [review] it opens for the end-of-day review, with the gaps and chunks to confirm marked and *Finish day*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    epochDay: Long,
    review: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DayViewModel = viewModel(factory = DayViewModel.factory(LocalDate.ofEpochDay(epochDay), review)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val minutePx = with(density) { HOUR_HEIGHT.toPx() } / 60f

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = if (event.undo != null) "Undo" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
            }
        }
    }
    // Opens at now, or at the start of the review's first stretch, or the day's start.
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (state.loading || scrolled) return@LaunchedEffect
        val target = (if (review) state.segments.firstOrNull { it.needsReview }?.startMinute else null) ?: state.nowMinute ?: 0
        scroll.scrollTo(((target - 60).coerceAtLeast(0) * minutePx).roundToInt())
        scrolled = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        Text(
                            if (state.finished) "Finished" else if (review) "End-of-day review" else "Day record",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.finished) {
                        TextButton(onClick = viewModel::reopenDay) { Text("Reopen") }
                    } else if (!state.loading) {
                        TextButton(onClick = { finishing = true }) { Text("Finish day") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.loading) return@Column
            if (review) {
                ReviewBanner(
                    banner = state.banner,
                    onNext = {
                        val current = (scroll.value / minutePx).roundToInt() + 60
                        val todo = state.segments.filter { it.needsReview }
                        val next = todo.firstOrNull { it.startMinute > current } ?: todo.firstOrNull()
                        if (next != null) scope.launch { scroll.animateScrollTo(((next.startMinute - 60).coerceAtLeast(0) * minutePx).roundToInt()) }
                    },
                    onConfirmAll = viewModel::confirmAll,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                DayCanvas(
                    state = state,
                    minutePx = minutePx,
                    onTap = { ui, at ->
                        val m = state.model ?: return@DayCanvas
                        sheet = when (ui.state) {
                            ChunkState.NOT_RECORDED, ChunkState.FREE -> SheetTarget.Form(newForm(ui, at, m.zone))
                            else -> SheetTarget.Segment(ui, at)
                        }
                    },
                    onMove = viewModel::moveBoundary,
                    onNudge = viewModel::nudge,
                )
            }
        }
    }

    val model = state.model
    when (val target = sheet) {
        null -> Unit
        is SheetTarget.Segment -> if (model != null) {
            ModalBottomSheet(onDismissRequest = { sheet = null }) {
                Column(modifier = Modifier.fillMaxWidth().testTag(DayTags.SHEET).padding(horizontal = 24.dp, vertical = 8.dp)) {
                    SegmentActions(
                        ui = target.ui,
                        segment = viewModel.segmentOf(target.ui),
                        onEdit = { form -> sheet = SheetTarget.Form(form) },
                        onSplit = {
                            viewModel.split(target.ui, target.at)
                            sheet = null
                        },
                        onMerge = { withNext ->
                            viewModel.merge(target.ui, withNext)
                            sheet = null
                        },
                        onDelete = {
                            viewModel.delete(target.ui)
                            sheet = null
                        },
                        onConfirm = {
                            viewModel.confirm(target.ui)
                            sheet = null
                        },
                        onChange = { viewModel.changeForm(target.ui)?.let { sheet = SheetTarget.Form(it) } },
                        onDidntHappen = {
                            viewModel.didntHappen(target.ui)
                            sheet = null
                        },
                        formOf = { ui ->
                            viewModel.recordOf(ui.recordId)?.let { ChunkForm.of(it, model.zone) }
                                ?: viewModel.planOf(ui.planId)?.let { ChunkForm.of(it, model.zone) }
                        },
                        zone = model.zone,
                    )
                }
            }
        }
        is SheetTarget.Form -> if (model != null) {
            ModalBottomSheet(onDismissRequest = { sheet = null }) {
                ChunkEditor(
                    form = target.form,
                    categories = model.categories.filter { !it.archived || it.id == target.form.categoryId },
                    modules = state.modules,
                    tasks = state.tasks,
                    window = model.window,
                    zone = model.zone,
                    replaces = viewModel.replaces(target.form),
                    onChange = { sheet = SheetTarget.Form(it) },
                    onSave = {
                        viewModel.save(target.form)
                        sheet = null
                    },
                )
            }
        }
    }

    if (finishing) {
        FinishDialog(
            onDismiss = { finishing = false },
            onFinish = { rating, note ->
                viewModel.finishDay(rating, note)
                finishing = false
            },
        )
    }
}

/** A new chunk where the user tapped a gap: the whole gap up to two hours, else the hour around the tap. */
private fun newForm(ui: SegmentUi, at: Instant, zone: ZoneId): ChunkForm {
    val gap = Interval(ui.start, ui.end)
    if (gap.minutes <= 120) return ChunkForm.new(gap.start, gap.end, zone)
    val start = maxOf(gap.start, Snap.snap(at.minus(Duration.ofMinutes(30)), zone))
    val end = minOf(gap.end, start.plus(Duration.ofMinutes(60)))
    return ChunkForm.new(start, end, zone)
}

@Composable
private fun ReviewBanner(banner: String?, onNext: () -> Unit, onConfirmAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp)
            .testTag(DayTags.BANNER),
    ) {
        Text(banner ?: "Everything is recorded. Finish the day when you're ready.", style = MaterialTheme.typography.bodyMedium)
        if (banner != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNext) { Text("Next") }
                TextButton(onClick = onConfirmAll) { Text("Confirm all as planned") }
            }
        }
    }
}

@Composable
private fun DayCanvas(
    state: DayUiState,
    minutePx: Float,
    onTap: (SegmentUi, Instant) -> Unit,
    onMove: (BoundaryUi, Instant) -> Unit,
    onNudge: (BoundaryUi, Int) -> Unit,
) {
    val model = state.model ?: return
    val minuteDp = HOUR_HEIGHT / 60f
    val lineColour = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = Modifier.fillMaxWidth().height(minuteDp * state.minutes + 24.dp)) {
        state.hours.forEach { hour ->
            Box(
                modifier = Modifier
                    .offset(y = minuteDp * hour.minute)
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(start = GUTTER)
                    .background(lineColour),
            )
            Text(
                hour.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = 6.dp, y = minuteDp * hour.minute - 6.dp),
            )
        }
        state.strip.forEach { s ->
            Box(
                modifier = Modifier
                    .offset(x = GUTTER, y = minuteDp * s.startMinute)
                    .width(STRIP)
                    .height(minuteDp * (s.endMinute - s.startMinute))
                    .background(s.colorArgb?.let { Color(it.toInt()).copy(alpha = 0.7f) } ?: MaterialTheme.colorScheme.outline),
            )
        }
        state.segments.forEach { ui -> SegmentBlock(ui, minuteDp, minutePx, model.window, onTap) }
        state.nowMinute?.let { now ->
            Box(
                modifier = Modifier
                    .offset(y = minuteDp * now)
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(start = GUTTER)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
        state.boundaries.forEach { b -> BoundaryHandle(b, minutePx, model.zone, onMove, onNudge) }
    }
}

@Composable
private fun SegmentBlock(ui: SegmentUi, minuteDp: Dp, minutePx: Float, window: Interval, onTap: (SegmentUi, Instant) -> Unit) {
    // The tap handler outlives recompositions (it is keyed by the segment), so it reads the latest of both.
    val current by rememberUpdatedState(ui)
    val tap by rememberUpdatedState(onTap)
    val colour = ui.colorArgb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    val reviewColour = MaterialTheme.colorScheme.tertiary
    val height = minuteDp * (ui.endMinute - ui.startMinute)
    val background = when (ui.state) {
        ChunkState.DONE -> colour.copy(alpha = 0.32f)
        ChunkState.TO_CONFIRM -> colour.copy(alpha = 0.14f)
        ChunkState.PLANNED -> colour.copy(alpha = 0.10f)
        ChunkState.NOT_RECORDED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ChunkState.FREE -> Color.Transparent
    }
    val middle = DayMapping.instantAt(window, (ui.startMinute + ui.endMinute) / 2)
    Box(
        modifier = Modifier
            .padding(start = GUTTER + STRIP + 4.dp, end = HANDLE + 4.dp)
            .offset(y = minuteDp * ui.startMinute)
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .drawBehind {
                val corner = CornerRadius(6.dp.toPx())
                when {
                    ui.needsReview -> drawRoundRect(reviewColour, cornerRadius = corner, style = Stroke(width = 2.dp.toPx()))
                    ui.state == ChunkState.TO_CONFIRM -> drawRoundRect(
                        colour,
                        cornerRadius = corner,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                    ui.state == ChunkState.PLANNED -> drawRoundRect(colour, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
                    ui.state == ChunkState.DONE -> drawRect(colour, size = size.copy(width = 4.dp.toPx()))
                    else -> Unit
                }
            }
            .pointerInput(ui.key) {
                detectTapGestures { offset -> tap(current, DayMapping.instantAt(window, current.startMinute + (offset.y / minutePx).roundToInt())) }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = ui.description
                onClick {
                    onTap(ui, middle)
                    true
                }
            }
            .testTag(DayTags.SEGMENT)
            .padding(start = 8.dp, end = 4.dp),
    ) {
        if (height >= 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (ui.state == ChunkState.FREE) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  ${ui.timeText}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BoundaryHandle(b: BoundaryUi, minutePx: Float, zone: ZoneId, onMove: (BoundaryUi, Instant) -> Unit, onNudge: (BoundaryUi, Int) -> Unit) {
    var dragPx by remember(b.key, b.minute) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val stepPx = minutePx * Snap.STEP_MINUTES
    val steps = (dragPx / stepPx).roundToInt()
    LaunchedEffect(steps) { if (dragging && steps != 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val handlePx = with(LocalDensity.current) { HANDLE.toPx() }
    val target = b.at.plus(Duration.ofMinutes(steps.toLong() * Snap.STEP_MINUTES))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, (b.minute * minutePx + dragPx - handlePx / 2).roundToInt()) }
            .height(HANDLE),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (dragging) {
            Text(
                PlannerText.time(target.atZone(zone).toLocalTime()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(end = HANDLE + 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(HANDLE)
                .draggable(
                    state = rememberDraggableState { delta -> dragPx += delta },
                    orientation = Orientation.Vertical,
                    onDragStarted = { dragging = true },
                    onDragStopped = {
                        dragging = false
                        val moved = (dragPx / stepPx).roundToInt()
                        if (moved != 0) onMove(b, b.at.plus(Duration.ofMinutes(moved.toLong() * Snap.STEP_MINUTES)))
                        dragPx = 0f
                    },
                )
                .semantics {
                    contentDescription = "Boundary at ${b.label}"
                    customActions = listOf(
                        CustomAccessibilityAction("Move earlier by 15 minutes") {
                            onNudge(b, -Snap.STEP_MINUTES)
                            true
                        },
                        CustomAccessibilityAction("Move later by 15 minutes") {
                            onNudge(b, Snap.STEP_MINUTES)
                            true
                        },
                    )
                }
                .testTag(DayTags.BOUNDARY),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.6f)),
            )
        }
    }
}

/** The sheet's buttons for a chunk: edit and tidy a recorded or planned one, or confirm a waiting one. */
@Composable
private fun SegmentActions(
    ui: SegmentUi,
    segment: DaySegment?,
    onEdit: (ChunkForm) -> Unit,
    onSplit: () -> Unit,
    onMerge: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onChange: () -> Unit,
    onDidntHappen: () -> Unit,
    formOf: (SegmentUi) -> ChunkForm?,
    zone: ZoneId,
) {
    Text(ui.title, style = MaterialTheme.typography.titleLarge)
    Text(listOf(ui.timeText, ui.detail).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    Text(DayMapping.stateLabel(ui.state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (segment) {
        is DaySegment.Waiting -> {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Happened as planned") }
            OutlinedButton(onClick = onChange, modifier = Modifier.fillMaxWidth()) { Text("Change") }
            if (segment.source !is DaySource.Event) {
                TextButton(onClick = onDidntHappen, modifier = Modifier.fillMaxWidth()) { Text("Didn't happen") }
            }
        }
        is DaySegment.Recorded, is DaySegment.Planned -> {
            val form = formOf(ui)
            if (form != null) {
                Button(onClick = { onEdit(form) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Edit") }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onSplit) { Text("Split here") }
                    TextButton(onClick = { onMerge(false) }) { Text("Merge with previous") }
                    TextButton(onClick = { onMerge(true) }) { Text("Merge with next") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onEdit(form.copy(record = null, plan = null)) }) { Text("Copy to…") }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            } else {
                // A class or event ahead: plan something over it if the day changes.
                OutlinedButton(onClick = { onEdit(ChunkForm.new(ui.start, ui.end, zone)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Plan something here")
                }
            }
        }
        else -> Unit
    }
    Box(modifier = Modifier.height(24.dp))
}

/** The chunk form: what it was (one tap), module and task, focus, exact times and a note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChunkEditor(
    form: ChunkForm,
    categories: List<TimeCategory>,
    modules: List<Module>,
    tasks: List<Task>,
    window: Interval,
    zone: ZoneId,
    replaces: String?,
    onChange: (ChunkForm) -> Unit,
    onSave: () -> Unit,
) {
    val errors = form.errors(window, zone, categories)
    val category = categories.firstOrNull { it.id == form.categoryId }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(DayTags.SHEET).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (form.isNew) "Add to the day" else "Edit", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { c ->
                FilterChip(
                    selected = c.id == form.categoryId,
                    onClick = { onChange(form.copy(categoryId = c.id, focus = if (c.ratesFocus) form.focus else null)) },
                    label = { Text(c.name) },
                    leadingIcon = { ColourDot(c.colorArgb) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("Start", form.start, { t -> onChange(form.copy(start = t)) }, Modifier.weight(1f), DayTags.START)
            TimeField("End", form.end, { t -> onChange(form.copy(end = t)) }, Modifier.weight(1f), DayTags.END)
        }
        ModulePicker(modules, form.moduleId, { id -> onChange(form.copy(moduleId = id, taskId = null)) })
        TaskPicker(tasks.filter { form.moduleId == null || it.moduleId == form.moduleId }, form.taskId) { id -> onChange(form.copy(taskId = id)) }
        if (category?.ratesFocus == true) {
            Text("Focus", style = MaterialTheme.typography.labelLarge)
            Row {
                (1..5).forEach { n ->
                    TextButton(onClick = { onChange(form.copy(focus = if (form.focus == n) null else n)) }) {
                        Text(if ((form.focus ?: 0) >= n) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        OutlinedTextField(
            value = form.note,
            onValueChange = { onChange(form.copy(note = it)) },
            label = { Text("Note") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(DayTags.NOTE),
        )
        replaces?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        errors.values.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onSave, enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().testTag(DayTags.SAVE)) { Text("Save") }
        Box(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TaskPicker(tasks: List<Task>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    if (tasks.isEmpty() && selectedId == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Task: " + (tasks.firstOrNull { it.id == selectedId }?.title ?: "none"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No task") }, onClick = {
                onSelect(null)
                open = false
            })
            tasks.take(30).forEach { t ->
                DropdownMenuItem(text = { Text(t.title) }, onClick = {
                    onSelect(t.id)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun FinishDialog(onDismiss: () -> Unit, onFinish: (Int?, String) -> Unit) {
    var rating by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish the day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How was it? (optional)")
                Row {
                    (1..5).forEach { n ->
                        TextButton(onClick = { rating = if (rating == n) null else n }) {
                            Text(if ((rating ?: 0) >= n) "★" else "☆", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onFinish(rating, note) }) { Text("Finish") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
