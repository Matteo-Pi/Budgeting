package io.github.matteopi.budgeting.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors

private const val SEARCH_RESULT_LIMIT = 8
private const val ALL_ITEMS_LIMIT = 20

/**
 * Insights → Items (docs/PLAN.md §10.4): the products that took most of the money in the selected
 * period, the biggest unit-price moves, and a search / "All items" list leading to an item's
 * price history. The caller shows it only when [ItemsCardState.hasReceipts].
 */
@Composable
fun ItemsCard(state: ItemsCardState, onOpenItem: (Long) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }

    SectionCard(title = "Items", modifier = modifier) {
        SubHeading("Most bought")
        if (state.mostBought.isEmpty()) {
            MutedText("Nothing bought with a recognised item in this period.")
        }
        state.mostBought.forEach { row ->
            ItemRow(
                name = row.name,
                onClick = { onOpenItem(row.productId) },
            ) {
                Text("${row.quantity} bought · ${row.spend.format()}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SubHeading("Biggest price changes")
        if (state.priceChanges.isEmpty()) {
            MutedText("No price changes in this period yet.")
        }
        state.priceChanges.forEach { row ->
            val colour = if (row.rose) MaterialTheme.colorScheme.error else LocalMoneyColors.current.income
            ItemRow(
                name = row.name,
                onClick = { onOpenItem(row.productId) },
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        ItemInsights.changeText(row.percent, row.rose),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colour,
                    )
                    Text(
                        "${row.previous.format()} → ${row.latest.format()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SubHeading("Find an item")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search items") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val matches = ItemInsights.search(state.products, query, SEARCH_RESULT_LIMIT)
        if (query.isNotBlank() && matches.isEmpty()) {
            MutedText("No item matches \"${query.trim()}\".")
        }
        matches.forEach { option -> ItemRow(name = option.name, onClick = { onOpenItem(option.productId) }) {} }

        if (query.isBlank() && state.products.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Hide all items" else "All items (${state.products.size})")
            }
            if (showAll) {
                state.products.take(ALL_ITEMS_LIMIT).forEach { option ->
                    ItemRow(name = option.name, onClick = { onOpenItem(option.productId) }) {}
                }
                if (state.products.size > ALL_ITEMS_LIMIT) {
                    MutedText("Showing the first $ALL_ITEMS_LIMIT of ${state.products.size}. Search to find the rest.")
                }
            }
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun MutedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A tappable row: the item name on the left and [trailing] content on the right. */
@Composable
private fun ItemRow(name: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        trailing()
    }
}
