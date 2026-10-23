@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.matteopi.budgeting.ui.food.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.nutrition.DiaryMaths
import io.github.matteopi.budgeting.core.nutrition.IntakeKey
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.TargetProgress
import io.github.matteopi.budgeting.core.nutrition.TargetState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** One nutrient of the day: its progress and the rows that gave the most of it. */
data class NutrientLine(val progress: TargetProgress, val sources: List<Pair<LogItem, Double>>)

data class DayNutrientsUiState(val loading: Boolean = true, val title: String = "", val lines: List<NutrientLine> = emptyList(), val empty: Boolean = true)

class DayNutrientsViewModel(container: AppContainer, date: LocalDate) : ViewModel() {
    val uiState: StateFlow<DayNutrientsUiState> = combine(
        container.diaryRepository.observeDay(date),
        container.diaryRepository.observeFoodDay(date),
        container.settingsStore.foodPrefs,
    ) { items, day, prefs ->
        val summary = DiaryMaths.day(items, day.waterMl, prefs.targets)
        DayNutrientsUiState(
            loading = false,
            title = dayTitle(date, container.clock.today()),
            lines = IntakeKey.entries.map { NutrientLine(summary.of(it), DiaryMaths.topSources(items, it, 2)) },
            empty = items.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayNutrientsUiState())

    companion object {
        fun factory(epochDay: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                DayNutrientsViewModel(app.container, LocalDate.ofEpochDay(epochDay))
            }
        }
    }
}

/**
 * *All nutrients* (docs/PLAN.md §14.5): every nutrient of the day against its target (the macros) or its reference
 * (the vitamins and minerals), "at least" where something eaten didn't know it, and the foods that gave the most.
 */
@Composable
fun DayNutrientsScreen(
    epochDay: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DayNutrientsViewModel = viewModel(factory = DayNutrientsViewModel.factory(epochDay)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("All nutrients") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Vitamins and minerals are against the daily reference on UK labels (vitamin D: the NHS's 10 µg). " +
                            "\"At least\" means something you ate doesn't say how much it has.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.loading && state.empty) Text("Nothing logged on this day.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.lines, key = { it.progress.key.name }) { line ->
                Card(Modifier.fillMaxWidth().testTag(DiaryTags.nutrient(line.progress.key.name))) {
                    Column(Modifier.padding(12.dp)) {
                        NutrientBar(line.progress.key.label, line.progress)
                        val state2 = line.progress.state
                        val percent = "${Math.round(line.progress.fraction * 100)}%"
                        Text(
                            "$percent · ${state2.label}" + if (line.progress.atLeast) " · at least" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state2 == TargetState.OVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (line.sources.isNotEmpty()) {
                            Text(
                                "Most from " + line.sources.joinToString(", ") { (row, share) -> "${row.name} (${Math.round(share * 100)}%)" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
