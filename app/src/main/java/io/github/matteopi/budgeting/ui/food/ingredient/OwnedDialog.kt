@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.ingredient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Pantry
import io.github.matteopi.budgeting.core.recipes.Quantities

/** Test tags of the *How much I have* dialog. */
object OwnedTags {
    const val AMOUNT = "owned-amount"
    const val SAVE = "owned-save"
    const val SOME = "owned-some"
    const val NONE_LEFT = "owned-none-left"

    fun unit(index: Int) = "owned-unit-$index"
}

/**
 * *How much I have* for [ingredient] (docs/PLAN.md §11.11): a number in g or ml, kg or l, packs or one of its
 * portions; *Some (not measured)* for salt, oil and the like; *None left*. [onSave] gets base units, or null for
 * "some"; zero means none left.
 */
@Composable
fun OwnedDialog(ingredient: Ingredient, current: OwnedItem?, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
    val units = remember(ingredient) { Pantry.units(ingredient) }
    var unitIndex by rememberSaveable { mutableIntStateOf(0) }
    var amount by rememberSaveable {
        mutableStateOf(current?.quantity?.let { Quantities.plain(it, 2) }.orEmpty())
    }
    val unit = units[unitIndex.coerceIn(0, units.lastIndex)]
    val base = IngredientFormRules.parseNumber(amount)?.let { Pantry.toBase(it, unit) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How much I have") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ingredient.name, style = MaterialTheme.typography.titleMedium)
                current?.let {
                    Text(
                        "Now: ${Pantry.describe(it, ingredient)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FoodNumberField(
                    label = "Amount",
                    value = amount,
                    onValueChange = { amount = it },
                    modifier = Modifier.fillMaxWidth().testTag(OwnedTags.AMOUNT),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEachIndexed { index, option ->
                        FilterChip(
                            selected = index == unitIndex,
                            onClick = { unitIndex = index },
                            label = { Text(option.label) },
                            modifier = Modifier.testTag(OwnedTags.unit(index)),
                        )
                    }
                }
                if (base != null && unit.factor != 1.0) {
                    Text(
                        "= ${Quantities.format(base, ingredient.unit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSave(null) }, modifier = Modifier.testTag(OwnedTags.SOME)) { Text("Some (not measured)") }
                    if (current != null) {
                        TextButton(onClick = { onSave(0.0) }, modifier = Modifier.testTag(OwnedTags.NONE_LEFT)) { Text("None left") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(base) }, enabled = base != null, modifier = Modifier.testTag(OwnedTags.SAVE)) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
