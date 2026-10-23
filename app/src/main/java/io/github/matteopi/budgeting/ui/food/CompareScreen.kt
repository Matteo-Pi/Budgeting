package io.github.matteopi.budgeting.ui.food

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.food.components.FoodIcons
import io.github.matteopi.budgeting.ui.food.components.LightBadge
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton

/** Test tags of the Compare tab. */
object CompareTags {
    const val TABLE = "compare-table"

    fun row(recipeId: Long) = "compare-row-$recipeId"
}

private val TITLE_WIDTH = 108.dp
private val CELL_WIDTH = 92.dp
private val HEADER_HEIGHT = 56.dp

/**
 * Compare (docs/PLAN.md §11.8; opened from the Recipes tab's top bar since §14.1): a row per recipe at its own servings with cost, kcal, protein, fat, saturates,
 * carbs, sugars, fibre, salt, veg, time and rating. The recipe title stays put while the other columns scroll sideways
 * together (one shared scroll state for the heading and every row). A tap on a heading sorts by it, and again reverses;
 * the best value in each judged column carries a star; fat, saturates, sugars and salt carry their traffic light.
 * A tap on a row opens the recipe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    onOpenRecipe: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompareViewModel = viewModel(factory = CompareViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Compare") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.countOptionalExtras,
                        role = Role.Switch,
                        onValueChange = viewModel::setCountOptionalExtras,
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Count optional extras", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.countOptionalExtras, onCheckedChange = null)
            }
            when {
                state.loading -> Unit
                state.rows.isEmpty() -> EmptyState("No recipes to compare yet. Add some on the Recipes tab.")
                else -> {
                    Text(
                        text = "Per portion, at each recipe's own servings. Tap a heading to sort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    CompareTable(state = state, onSort = viewModel::sortBy, onOpenRecipe = onOpenRecipe)
                }
            }
        }
    }
}

@Composable
private fun CompareTable(state: CompareUiState, onSort: (CompareColumn) -> Unit, onOpenRecipe: (Long) -> Unit) {
    // One scroll state for the heading and every row, so the scrolling columns move together.
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().testTag(CompareTags.TABLE)) {
        Row(modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
            HeaderCell(CompareColumn.TITLE, state.sort, Modifier.width(TITLE_WIDTH), onSort)
            Row(modifier = Modifier.weight(1f).horizontalScroll(scroll)) {
                CompareColumn.scrolling.forEach { column ->
                    HeaderCell(column, state.sort, Modifier.width(CELL_WIDTH), onSort)
                }
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.id }) { row ->
                Column {
                    CompareRowView(row = row, best = state.best, scroll = scroll, onClick = { onOpenRecipe(row.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(column: CompareColumn, sort: CompareSort, modifier: Modifier, onSort: (CompareColumn) -> Unit) {
    val selected = sort.column == column
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = { onSort(column) })
            .padding(horizontal = if (column == CompareColumn.TITLE) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = column.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else null,
            maxLines = 1,
        )
        if (selected) {
            Icon(
                imageVector = if (sort.descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (sort.descending) "Sorted descending" else "Sorted ascending",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CompareRowView(
    row: CompareRow,
    best: Map<CompareColumn, Set<Long>>,
    scroll: ScrollState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .testTag(CompareTags.row(row.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(TITLE_WIDTH).padding(horizontal = 12.dp),
        )
        Row(modifier = Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            CompareColumn.scrolling.forEach { column ->
                val isBest = best[column]?.contains(row.id) == true
                CellView(CompareMapping.cell(row, column, isBest), Modifier.width(CELL_WIDTH))
            }
        }
    }
}

@Composable
private fun CellView(cell: CompareCell, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cell.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (cell.best) FontWeight.Bold else null,
                maxLines = 2,
            )
            if (cell.best) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = FoodIcons.Star,
                    contentDescription = "Best in this column",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        cell.light?.let { LightBadge(it) }
        cell.note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
