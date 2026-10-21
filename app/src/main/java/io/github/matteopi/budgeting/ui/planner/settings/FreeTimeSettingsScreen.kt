package io.github.matteopi.budgeting.ui.planner.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.freetime.MealSlot
import io.github.matteopi.budgeting.ui.planner.components.DurationField
import io.github.matteopi.budgeting.ui.planner.components.TimeField
import java.time.LocalTime

/**
 * Free time (docs/PLAN.md §12.6): fixed meals, the travel buffer and times from home by place, and the shortest gap
 * worth counting. Wake and sleep times are on *The day*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeTimeSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StudySettingsViewModel = viewModel(factory = StudySettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val free = state.free
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Free time") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) return@Column
            Text(
                "Free time is your waking day less classes, busy events, exams, meals and travel, without gaps too short to use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Meals", style = MaterialTheme.typography.titleMedium)
            free.meals.forEachIndexed { index, meal ->
                // Changes go to the stored meals, which a quick earlier tap may already have changed.
                fun change(update: (MutableList<MealSlot>) -> Unit) =
                    viewModel.setFree { f -> f.copy(meals = f.meals.toMutableList().also { if (index in it.indices) update(it) }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("From", timeOf(meal.startMinute), { t ->
                        t?.let { change { list -> list[index] = list[index].copy(startMinute = minuteOf(it)) } }
                    }, Modifier.weight(1f))
                    TimeField("To", timeOf(meal.endMinute), { t ->
                        t?.let { change { list -> list[index] = list[index].copy(endMinute = endOf(it)) } }
                    }, Modifier.weight(1f))
                    IconButton(onClick = { change { it.removeAt(index) } }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove the meal at ${PlannerText.time(timeOf(meal.startMinute))}")
                    }
                }
            }
            TextButton(onClick = { viewModel.setFree { it.copy(meals = it.meals + MealSlot(12 * 60 + 30, 13 * 60 + 15)) } }) { Text("Add a meal") }

            HorizontalDivider()
            Text("Travel", style = MaterialTheme.typography.titleMedium)
            DurationField("Before and after anything with a place", free.defaultBufferMinutes, { m -> viewModel.setFree { it.copy(defaultBufferMinutes = m ?: 0) } })
            Text("From home to a place (else the time above)", style = MaterialTheme.typography.labelLarge)
            free.travelMinutes.toSortedMap().forEach { (place, minutes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$place: ${PlannerText.duration(minutes)}", modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.setFree { it.copy(travelMinutes = it.travelMinutes - place) } }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove $place")
                    }
                }
            }
            AddPlace { place, minutes -> viewModel.setFree { it.copy(travelMinutes = it.travelMinutes + (place to minutes)) } }

            HorizontalDivider()
            DurationField("Shortest gap worth counting", free.minGapMinutes, { m -> viewModel.setFree { it.copy(minGapMinutes = m ?: 0) } })
        }
    }
}

@Composable
private fun AddPlace(onAdd: (String, Int) -> Unit) {
    var place by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf<Int?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = place, onValueChange = { place = it }, label = { Text("Place") }, singleLine = true, modifier = Modifier.weight(1f))
        DurationField("Time", minutes, { minutes = it }, Modifier.weight(1f))
    }
    TextButton(
        onClick = {
            minutes?.let { onAdd(place.trim(), it) }
            place = ""
            minutes = null
        },
        enabled = place.isNotBlank() && minutes != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add the place") }
}

private fun timeOf(minute: Int): LocalTime = if (minute >= 24 * 60) LocalTime.MIDNIGHT else LocalTime.of(minute / 60, minute % 60)

private fun minuteOf(time: LocalTime) = time.hour * 60 + time.minute

/** 00:00 as an end is midnight at the end of the day. */
private fun endOf(time: LocalTime): Int = if (time == LocalTime.MIDNIGHT) 24 * 60 else minuteOf(time)
