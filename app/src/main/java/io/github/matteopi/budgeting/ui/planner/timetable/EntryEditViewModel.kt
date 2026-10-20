package io.github.matteopi.budgeting.ui.planner.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.timetable.ClashKind
import io.github.matteopi.budgeting.core.planner.timetable.Clashes
import io.github.matteopi.budgeting.core.planner.timetable.EntryField
import io.github.matteopi.budgeting.core.planner.timetable.EntryRules
import io.github.matteopi.budgeting.core.planner.timetable.Timetable
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.planner.ModuleRepository
import io.github.matteopi.budgeting.data.repo.planner.TimetableRepository
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.settings.budgetViewModelFactory
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EntryEditUiState(
    val loading: Boolean = true,
    val form: EntryForm = EntryForm(),
    val modules: List<Module> = emptyList(),
    val terms: List<TermPeriod> = emptyList(),
    val others: List<TimetableEntry> = emptyList(),
    val today: LocalDate,
    val savedId: Long? = null,
    val deleted: Boolean = false,
    val confirmDelete: Boolean = false,
    val errors: Map<EntryField, String> = emptyMap(),
    /** Where it lands this term ("10 dates in Autumn: …"). */
    val preview: String? = null,
    val needsTermDates: Boolean = false,
    /** Clashes with the rest of the timetable over the next 8 weeks: "Overlaps EC102 Seminar on Mon 26 Oct and 6 more dates". */
    val clashes: List<String> = emptyList(),
) {
    val isNew: Boolean get() = form.id == 0L

    /** This state with [errors], [preview], [needsTermDates] and [clashes] worked out for its form, once per change. */
    fun derived(zone: ZoneId): EntryEditUiState {
        val errors = form.errors
        val entry = form.toEntry()
        return copy(
            errors = errors,
            preview = form.preview(terms, today),
            needsTermDates = EntryRules.needsTermDates(entry, terms),
            clashes = if (errors.isEmpty()) clashesOf(entry, zone) else emptyList(),
        )
    }

    private fun clashesOf(entry: TimetableEntry, zone: ZoneId): List<String> {
        val me = entry.copy(id = if (entry.id == 0L) -1 else entry.id)
        val occurrences = Timetable.occurrences(others.filter { it.id != entry.id } + me, emptyList(), terms, today, today.plusWeeks(8), zone)
        return Clashes.find(occurrences)
            .filter { it.first.entryId == me.id || it.second.entryId == me.id }
            // One line per other entry and kind: the first date, and how many more.
            .groupBy { c -> (if (c.first.entryId == me.id) c.second else c.first).entryId to c.kind }
            .values
            .take(3)
            .map { group ->
                val c = group.first()
                val other = if (c.first.entryId == me.id) c.second else c.first
                val day = PlannerText.date(c.first.date, today)
                val more = when (val n = group.size - 1) {
                    0 -> ""
                    1 -> " and 1 more date"
                    else -> " and $n more dates"
                }
                when (c.kind) {
                    ClashKind.OVERLAP -> "Overlaps ${other.title} on $day$more"
                    ClashKind.TIGHT -> "Only ${c.gapMinutes} min to get to or from ${other.title} on $day$more"
                }
            }
    }
}

/** Adds or edits a timetable entry (docs/PLAN.md §12.4). */
class EntryEditViewModel(
    private val entryId: Long?,
    private val timetableRepository: TimetableRepository,
    moduleRepository: ModuleRepository,
    planRepository: PlanRepository,
    clock: Clock,
) : ViewModel() {
    private val zone = clock.zone()
    private val _state = MutableStateFlow(EntryEditUiState(today = clock.today()))
    val state: StateFlow<EntryEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            moduleRepository.observeModules().collect { modules -> _state.update { it.copy(modules = modules) } }
        }
        viewModelScope.launch {
            val terms = planRepository.observeTermPeriods().first()
            val entries = timetableRepository.getEntries()
            val form = entryId?.let { id -> entries.firstOrNull { it.id == id } }?.let(EntryForm::from) ?: EntryForm()
            _state.update { it.copy(loading = false, form = form, terms = terms, others = entries.filter { e -> !e.archived }).derived(zone) }
        }
    }

    fun update(change: (EntryForm) -> EntryForm) = _state.update { it.copy(form = change(it.form)).derived(zone) }

    fun save() {
        val current = _state.value
        if (current.loading || current.errors.isNotEmpty()) return
        viewModelScope.launch {
            val id = timetableRepository.save(current.form.toEntry())
            _state.update { it.copy(savedId = id) }
        }
    }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        val id = _state.value.form.id.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            timetableRepository.delete(id)
            _state.update { it.copy(deleted = true, confirmDelete = false) }
        }
    }

    companion object {
        fun factory(entryId: Long?): ViewModelProvider.Factory = budgetViewModelFactory {
            EntryEditViewModel(entryId, it.timetableRepository, it.moduleRepository, it.planRepository, it.clock)
        }
    }
}
