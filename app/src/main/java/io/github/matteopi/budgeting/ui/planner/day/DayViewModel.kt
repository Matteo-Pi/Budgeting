package io.github.matteopi.budgeting.ui.planner.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.day.DayLayer
import io.github.matteopi.budgeting.core.planner.day.DaySegment
import io.github.matteopi.budgeting.core.planner.day.DaySource
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.data.repo.planner.DayRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TaskRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.domain.planner.DayModel
import io.github.matteopi.budgeting.domain.planner.PlannerService
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayUiState(
    val loading: Boolean = true,
    val date: LocalDate,
    /** "Tuesday 20 October". */
    val title: String = "",
    val review: Boolean = false,
    val model: DayModel? = null,
    val segments: List<SegmentUi> = emptyList(),
    val boundaries: List<BoundaryUi> = emptyList(),
    val hours: List<HourUi> = emptyList(),
    val strip: List<StripUi> = emptyList(),
    /** The canvas's height in minutes: the window's length. */
    val minutes: Int = 0,
    /** Minutes from the window's start to now, or null when now is outside the day. */
    val nowMinute: Int? = null,
    val banner: String? = null,
    val modules: List<Module> = emptyList(),
    val tasks: List<Task> = emptyList(),
) {
    val finished: Boolean get() = model?.finished == true
}

/** Something that happened, for the snackbar; [undo] reverses it when it can. */
data class DayEvent(val message: String, val undo: (suspend () -> Unit)? = null)

/** The day editor (docs/PLAN.md §12.8, §13.9): every edit is one core edit plan, applied in one go, with *Undo*. */
class DayViewModel(
    private val date: LocalDate,
    private val review: Boolean,
    plannerService: PlannerService,
    private val dayRepository: DayRepository,
    private val timetableRepository: TimetableRepository,
    moduleRepository: ModuleRepository,
    taskRepository: TaskRepository,
) : ViewModel() {
    private val events = Channel<DayEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DayEvent> = events.receiveAsFlow()

    val state: StateFlow<DayUiState> = combine(
        plannerService.dayFlow(date),
        moduleRepository.observeModules(),
        taskRepository.observeOpenTasks(),
    ) { model, modules, tasks ->
        DayUiState(
            loading = false,
            date = date,
            title = date.format(TITLE),
            review = review,
            model = model,
            segments = DayMapping.segments(model, modules, review),
            boundaries = DayMapping.boundaries(model),
            hours = DayMapping.hours(model.window, model.zone),
            strip = DayMapping.plannedStrip(model, modules),
            minutes = DayMapping.minuteOf(model.window, model.window.end),
            nowMinute = if (model.now in model.window) DayMapping.minuteOf(model.window, model.now) else null,
            banner = if (review) DayMapping.reviewBanner(model) else null,
            modules = modules.filter { !it.archived },
            tasks = tasks,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(date = date, review = review))

    private val model: DayModel? get() = state.value.model

    /** The core segment behind [ui]. */
    fun segmentOf(ui: SegmentUi): DaySegment? = model?.segments?.firstOrNull { DayMapping.keyOf(it) == ui.key }

    fun recordOf(id: Long?): RecordChunk? = id?.let { i -> model?.records?.firstOrNull { it.id == i } }

    fun planOf(id: Long?): PlanBlock? = id?.let { i -> model?.plans?.firstOrNull { it.id == i } }

    private fun records(plan: EditPlan<RecordChunk>?, message: String) {
        if (plan == null || plan.isEmpty) {
            events.trySend(DayEvent("Nothing to change there"))
            return
        }
        viewModelScope.launch {
            val applied = dayRepository.applyRecords(plan)
            events.send(DayEvent(message) { dayRepository.applyRecords(applied.inverse()) })
        }
    }

    private fun plans(plan: EditPlan<PlanBlock>?, message: String) {
        if (plan == null || plan.isEmpty) {
            events.trySend(DayEvent("Nothing to change there"))
            return
        }
        viewModelScope.launch {
            val applied = dayRepository.applyPlans(plan)
            events.send(DayEvent(message) { dayRepository.applyPlans(applied.inverse()) })
        }
    }

    /** Saves the sheet: an edited chunk stays in its layer; a new one is a record before now and a plan from now. */
    fun save(form: ChunkForm) {
        val m = model ?: return
        val existingRecord = form.record
        val existingPlan = form.plan
        val layer = when {
            existingRecord != null -> DayLayer.RECORD
            existingPlan != null -> DayLayer.PLAN
            else -> DayEdits.layerOf(form.startInstant(m.window, m.zone) ?: return, m.now)
        }
        when (layer) {
            DayLayer.RECORD -> {
                val draft = form.toRecord(m.window, m.zone) ?: return
                records(DayEdits.place(m.records, draft, m.window), if (form.isNew) "Recorded ${timeOf(draft.start, draft.end)}" else "Saved")
            }
            DayLayer.PLAN -> {
                val draft = form.toPlan(m.window, m.zone) ?: return
                plans(DayEdits.place(m.plans, draft, m.window), if (form.isNew) "Planned ${timeOf(draft.start, draft.end)}" else "Saved")
            }
        }
    }

    /** What saving [form] would replace: "This replaces Social 18:00–18:30". */
    fun replaces(form: ChunkForm): String? {
        val m = model ?: return null
        val start = form.startInstant(m.window, m.zone) ?: return null
        val names = m.categoriesById
        val overlapped = when {
            form.record != null || (form.plan == null && DayEdits.layerOf(start, m.now) == DayLayer.RECORD) ->
                form.toRecord(m.window, m.zone)?.let { d -> DayEdits.overlapped(m.records, d).map { names[it.categoryId]?.name to timeOf(it.start, it.end) } }
            else -> form.toPlan(m.window, m.zone)?.let { d -> DayEdits.overlapped(m.plans, d).map { names[it.categoryId]?.name to timeOf(it.start, it.end) } }
        } ?: return null
        if (overlapped.isEmpty()) return null
        return "This replaces " + overlapped.joinToString(", ") { (name, time) -> "${name ?: "a chunk"} $time" }
    }

    fun split(ui: SegmentUi, at: Instant) {
        val m = model ?: return
        recordOf(ui.recordId)?.let { return records(DayEdits.split(it, at, m.zone), "Split") }
        planOf(ui.planId)?.let { plans(DayEdits.split(it, at, m.zone), "Split") }
    }

    /** *Merge with previous* ([withNext] false) or *next*, in the chunk's own layer. */
    fun merge(ui: SegmentUi, withNext: Boolean) {
        val m = model ?: return
        val record = recordOf(ui.recordId)
        val plan = planOf(ui.planId)
        if (record != null) {
            val sorted = m.records.sortedBy { it.start }
            val other = sorted.getOrNull(sorted.indexOfFirst { it.id == record.id } + if (withNext) 1 else -1)
            if (other == null) {
                events.trySend(DayEvent("Nothing to merge with"))
                return
            }
            records(if (withNext) DayEdits.merge(m.records, record, other) else DayEdits.merge(m.records, other, record), "Merged")
        } else if (plan != null) {
            val sorted = m.plans.sortedBy { it.start }
            val other = sorted.getOrNull(sorted.indexOfFirst { it.id == plan.id } + if (withNext) 1 else -1)
            if (other == null) {
                events.trySend(DayEvent("Nothing to merge with"))
                return
            }
            plans(if (withNext) DayEdits.merge(m.plans, plan, other) else DayEdits.merge(m.plans, other, plan), "Merged")
        }
    }

    fun delete(ui: SegmentUi) {
        recordOf(ui.recordId)?.let { return records(DayEdits.delete(it), "Deleted") }
        planOf(ui.planId)?.let { plans(DayEdits.delete(it), "Deleted") }
    }

    fun moveBoundary(boundary: BoundaryUi, to: Instant) {
        val m = model ?: return
        when (boundary.layer) {
            DayLayer.RECORD -> {
                // Records stay in the past.
                val limit = Interval(m.window.start, maxOf(m.window.start, minOf(m.window.end, m.now)))
                records(DayEdits.moveBoundary(m.records, recordOf(boundary.leftId), recordOf(boundary.rightId), to, limit, m.zone), "Moved")
            }
            DayLayer.PLAN -> {
                val limit = Interval(minOf(m.window.end, maxOf(m.window.start, m.now)), m.window.end)
                plans(DayEdits.moveBoundary(m.plans, planOf(boundary.leftId), planOf(boundary.rightId), to, limit, m.zone), "Moved")
            }
        }
    }

    /** The boundary's accessibility actions: 15 minutes earlier or later. */
    fun nudge(boundary: BoundaryUi, minutes: Int) = moveBoundary(boundary, boundary.at.plus(Duration.ofMinutes(minutes.toLong())))

    /** *Happened as planned* for a waiting stretch. */
    fun confirm(ui: SegmentUi) {
        val m = model ?: return
        val waiting = segmentOf(ui) as? DaySegment.Waiting ?: return
        records(DayEdits.confirm(m.records, waiting, m.categories, m.now), "Confirmed")
    }

    fun confirmAll() {
        val m = model ?: return
        records(DayEdits.confirmAll(m.records, m.segments, m.categories, m.now), "Confirmed all as planned")
    }

    /** The sheet for *Change*: a new record over the waiting stretch, starting from what it expected. */
    fun changeForm(ui: SegmentUi): ChunkForm? {
        val m = model ?: return null
        val waiting = segmentOf(ui) as? DaySegment.Waiting ?: return null
        val template = DayEdits.recordFor(waiting.source, m.categories)
        return ChunkForm.new(waiting.interval.start, minOf(waiting.interval.end, m.now), m.zone, template?.categoryId, template?.moduleId, template?.taskId)
            .copy(planBlockId = template?.planBlockId, sourceKey = template?.sourceKey)
    }

    /** *Didn't happen*: a plan block is marked skipped; a class is cancelled for that date. */
    fun didntHappen(ui: SegmentUi) {
        val waiting = segmentOf(ui) as? DaySegment.Waiting ?: return
        when (val source = waiting.source) {
            is DaySource.Plan -> plans(EditPlan(updates = listOf(source.block to source.block.copy(outcomeOverride = Outcome.SKIPPED))), "Marked as skipped")
            is DaySource.Class -> viewModelScope.launch {
                val o = source.occurrence
                timetableRepository.cancel(o.entryId, o.date)
                events.send(DayEvent("${o.title} marked as cancelled") { timetableRepository.restoreDate(o.entryId, o.date) })
            }
            is DaySource.Event -> Unit
        }
    }

    fun finishDay(rating: Int?, note: String) {
        viewModelScope.launch {
            dayRepository.finishDay(date, rating, note)
            events.send(DayEvent("Day finished") { dayRepository.reopenDay(date) })
        }
    }

    fun reopenDay() {
        viewModelScope.launch { dayRepository.reopenDay(date) }
    }

    fun undo(event: DayEvent) {
        val undo = event.undo ?: return
        viewModelScope.launch { undo() }
    }

    private fun timeOf(start: Instant, end: Instant): String {
        val zone = model?.zone ?: return ""
        return DayMapping.range(start, end, zone)
    }

    companion object {
        private val TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

        fun factory(date: LocalDate, review: Boolean): ViewModelProvider.Factory = budgetViewModelFactory {
            DayViewModel(date, review, it.plannerService, it.dayRepository, it.timetableRepository, it.moduleRepository, it.taskRepository)
        }
    }
}
