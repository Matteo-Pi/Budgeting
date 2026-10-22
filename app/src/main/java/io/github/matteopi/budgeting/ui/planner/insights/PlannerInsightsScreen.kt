package io.github.matteopi.budgeting.ui.planner.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.domain.planner.InsightsRange
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.components.charts.StackedBarChart
import io.github.matteopi.budgeting.ui.components.charts.StackedValueGroup
import io.github.matteopi.budgeting.ui.components.charts.StackedValueSegment
import io.github.matteopi.budgeting.ui.components.charts.rememberChartColors
import io.github.matteopi.budgeting.ui.planner.components.PlannerTabTopBar

/** Test tags of the Insights tab. */
object PlannerInsightsTags {
    const val TIME = "insights-time"
    const val STUDY = "insights-study"
    const val PLANNED = "insights-planned"
    const val FOCUS = "insights-focus"
    const val HABITS = "insights-habits"
    const val DEADLINES = "insights-deadlines"
    const val TIPS = "insights-tips"
    const val PREVIOUS = "insights-previous"
}

/**
 * The Planner's Insights tab (docs/PLAN.md §12.10, §13.9): for a week or a month, where time went (bars by day, then
 * each category with its change), study against the targets, planned against done, focus by time of day, the habits,
 * the deadlines and the tips; then the weekly review.
 */
@Composable
fun PlannerInsightsScreen(
    onOpenSettings: () -> Unit,
    onOpenWeeklyReview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerInsightsViewModel = viewModel(factory = PlannerInsightsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = { PlannerTabTopBar(title = "Insights", onOpenSettings = onOpenSettings) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "range") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.range == InsightsRange.WEEK, onClick = { viewModel.setRange(InsightsRange.WEEK) }, label = { Text("Week") })
                    FilterChip(selected = state.range == InsightsRange.MONTH, onClick = { viewModel.setRange(InsightsRange.MONTH) }, label = { Text("Month") })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { viewModel.step(-1) }, modifier = Modifier.testTag(PlannerInsightsTags.PREVIOUS)) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = if (state.range == InsightsRange.WEEK) "Week before" else "Month before")
                    }
                    Text(state.ui?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { viewModel.step(1) }, enabled = !state.isCurrent) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = if (state.range == InsightsRange.WEEK) "Week after" else "Month after")
                    }
                }
            }
            val ui = state.ui ?: return@LazyColumn
            item(key = "time") { TimeCard(ui) }
            if (ui.modules.isNotEmpty()) item(key = "study") { StudyCard(ui) }
            if (ui.plannedText != null) item(key = "planned") { PlannedCard(ui) }
            if (ui.focus.isNotEmpty()) item(key = "focus") { FocusCard(ui) }
            if (ui.habits.isNotEmpty()) item(key = "habits") { HabitsCard(ui) }
            if (ui.deadlines.isNotEmpty()) item(key = "deadlines") { DeadlinesCard(ui) }
            item(key = "tips") { TipsCard(ui) }
            item(key = "review") {
                Button(onClick = onOpenWeeklyReview, modifier = Modifier.fillMaxWidth()) { Text("Weekly review") }
            }
        }
    }
}

@Composable
private fun TimeCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Where time went", modifier = Modifier.testTag(PlannerInsightsTags.TIME)) {
        if (!ui.hasRecord) {
            Text("Nothing recorded in this period yet. The day record on Today fills this in.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(ui.totalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var selected by remember(ui.days) { mutableStateOf<Int?>(null) }
        val other = rememberChartColors().secondaryInk
        StackedBarChart(
            groups = ui.days.map { day ->
                StackedValueGroup(day.label, day.parts.map { StackedValueSegment(it.name, it.colorArgb?.let { c -> Color(c.toInt()) } ?: other, it.minutes) })
            },
            axisLabel = { PlannerText.duration(it.toInt()) },
            minimumTop = 60L,
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            modifier = Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Time recorded by day: " + ui.days.joinToString("; ") { it.description } },
        )
        // The tapped day's numbers, so no part is read from its colour alone.
        selected?.let { i -> ui.days.getOrNull(i)?.let { Text(it.description, style = MaterialTheme.typography.bodySmall) } }
        Spacer(Modifier.height(8.dp))
        ui.time.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(row.colorArgb.toInt())))
                Spacer(Modifier.width(8.dp))
                Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(row.minutesText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    row.changeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

/** A bar out of a whole, in the theme's primary colour on its own lighter track. */
@Composable
private fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(primary.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(primary))
    }
}

@Composable
private fun StudyCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Study against targets", modifier = Modifier.testTag(PlannerInsightsTags.STUDY)) {
        ui.modules.forEach { row ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row {
                    Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(row.text, style = MaterialTheme.typography.bodyMedium)
                }
                row.fraction?.let { Meter(it, Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun PlannedCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Planned against done", modifier = Modifier.testTag(PlannerInsightsTags.PLANNED)) {
        Text(ui.plannedText.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        ui.plannedDetail.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun FocusCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Focus", modifier = Modifier.testTag(PlannerInsightsTags.FOCUS)) {
        Text("By time of day", style = MaterialTheme.typography.labelLarge)
        ui.focus.forEach { FocusLine(it) }
        if (ui.focusByLength.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("By length", style = MaterialTheme.typography.labelLarge)
            ui.focusByLength.forEach { FocusLine(it) }
        }
    }
}

@Composable
private fun FocusLine(row: FocusRowUi) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(row.text, style = MaterialTheme.typography.bodyMedium)
        }
        Meter(row.fraction, Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun HabitsCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Habits", modifier = Modifier.testTag(PlannerInsightsTags.HABITS)) {
        ui.habits.forEach { row ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row {
                    Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(row.rateText, style = MaterialTheme.typography.bodyMedium)
                }
                if (row.streakText.isNotBlank()) Text(row.streakText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.missedText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun DeadlinesCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Deadlines", modifier = Modifier.testTag(PlannerInsightsTags.DEADLINES)) {
        ui.deadlines.forEach { row ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.riskText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TipsCard(ui: PlannerInsightsUi) {
    SectionCard(title = "Tips", modifier = Modifier.testTag(PlannerInsightsTags.TIPS)) {
        if (ui.tips.isEmpty()) {
            Text("No tips for the last four weeks: they come when the record shows a pattern worth changing.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        ui.tips.forEach { tip ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(tip.text, style = MaterialTheme.typography.bodyMedium)
                Text(tip.numbers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
