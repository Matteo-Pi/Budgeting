package io.github.matteopi.budgeting.ui.food.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.nutrition.TargetKind
import io.github.matteopi.budgeting.core.nutrition.TargetProgress
import io.github.matteopi.budgeting.core.nutrition.TargetState
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.ui.components.DatePickerDialogFor
import io.github.matteopi.budgeting.ui.components.formatDisplayDate
import io.github.matteopi.budgeting.ui.food.components.FoodIcons
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tags shared by the diary's and the kitchen's screens. */
object DiaryTags {
    const val SUMMARY = "diary-summary"
    const val KCAL = "diary-kcal"
    const val WATER = "diary-water"
    const val ADD_GLASS = "diary-add-glass"
    const val DAY = "diary-day"
    const val ALL_NUTRIENTS = "diary-all-nutrients"
    const val BATCH_SHEET = "batch-sheet"
    const val BATCH_SAVE = "batch-save"
    const val LOG_SAVE = "log-save"
    const val LOG_TOTAL = "log-total"
    const val QUICK_TEXT = "log-quick-text"
    const val QUICK_NAME = "log-quick-name"
    const val QUICK_KCAL = "log-quick-kcal"
    const val QUICK_PROTEIN = "log-quick-protein"
    const val QUICK_CARBS = "log-quick-carbs"
    const val QUICK_FAT = "log-quick-fat"
    const val SEARCH = "log-search"
    const val AMOUNT = "log-amount"
    const val ROW_AMOUNT = "diary-row-amount"

    fun meal(name: String) = "diary-meal-$name"
    fun row(id: Long) = "diary-row-$id"
    fun ready(batchId: Long) = "diary-ready-$batchId"
    fun batch(batchId: Long) = "kitchen-batch-$batchId"
    fun frozen(batchId: Long) = "kitchen-frozen-$batchId"
    fun pick(key: String) = "log-pick-$key"
    fun side(key: String) = "log-side-$key"
    fun nutrient(key: String) = "nutrient-$key"
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "Today · Fri 23 Oct", "Yesterday · Thu 22 Oct", "Wed 21 Oct". */
fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today · ${date.format(DAY_FORMAT)}"
    today.minusDays(1) -> "Yesterday · ${date.format(DAY_FORMAT)}"
    today.plusDays(1) -> "Tomorrow · ${date.format(DAY_FORMAT)}"
    else -> date.format(DAY_FORMAT)
}

/** "Fri 23 Oct". */
fun shortDay(date: LocalDate): String = date.format(DAY_FORMAT)

/** Pence as money ("£1.20"), or "no price" for none. */
fun penceText(pence: Double?): String = pence?.let { Money(Quantities.roundHalfUp(it)).format() } ?: "no price"

/** "690 kcal". */
fun kcalText(kcal: Double): String = "${Quantities.roundHalfUp(kcal)} kcal"

/** "41 g", "4.5 g". */
fun gramsText(grams: Double): String = "${Quantities.plain(grams, if (grams < 10) 1 else 0)} g"

/** A whole or half number of portions: "3", "2½". */
fun countText(value: Double): String = Quantities.formatCount(value)

/**
 * A minus button, the amount in halves and a plus button. The buttons are described "Fewer [noun]" and "More
 * [noun]"; [min] and [max] bound it.
 */
@Composable
fun HalfStepper(
    value: Double,
    onChange: (Double) -> Unit,
    noun: String,
    modifier: Modifier = Modifier,
    step: Double = 0.5,
    min: Double = 0.5,
    max: Double = 99.0,
    text: String = countText(value),
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min + 1e-9) {
            Icon(FoodIcons.Remove, contentDescription = "Fewer $noun")
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).semantics { contentDescription = "$text $noun" },
        )
        IconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max - 1e-9) {
            Icon(Icons.Default.Add, contentDescription = "More $noun")
        }
    }
}

/** The colour of a target's bar: the primary colour, or the error colour once a limit or an aim is over. */
@Composable
fun progressColor(progress: TargetProgress): Color = when {
    progress.state == TargetState.OVER -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

/** "45 of 50 g", "at least 45 of 50 g". */
fun progressText(progress: TargetProgress): String {
    val amount = progress.key.format(progress.amount)
    val target = progress.key.format(progress.target)
    val prefix = if (progress.atLeast) "at least " else ""
    val of = when (progress.kind) {
        TargetKind.LIMIT -> "limit"
        TargetKind.AT_LEAST -> "aim at least"
        TargetKind.AIM -> "of"
    }
    return if (progress.kind == TargetKind.AIM) "$prefix$amount of $target" else "$prefix$amount · $of $target"
}

/** One nutrient: its name, "45 of 50 g" and a thin bar. */
@Composable
fun NutrientBar(label: String, progress: TargetProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(progressText(progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { progress.fraction.toFloat().coerceIn(0f, 1f) },
            color = progressColor(progress),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/**
 * *I cooked*: how much of [recipe] was cooked and how it was split, then [onSave] with the servings cooked, the
 * portions made, how many of them are frozen and the day it was made.
 */
@Composable
fun BatchSheet(
    recipe: Recipe,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (servings: Double, portions: Double, freezer: Double, madeOn: LocalDate) -> Unit,
) {
    var servings by remember(recipe.id) { mutableStateOf(recipe.serves.toDouble()) }
    var portions by remember(recipe.id) { mutableStateOf(recipe.serves.toDouble()) }
    var freezer by remember(recipe.id) { mutableStateOf(0.0) }
    var madeOn by remember(recipe.id) { mutableStateOf(today) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DiaryTags.BATCH_SHEET),
        title = { Text("I cooked ${recipe.shortTitle ?: recipe.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Servings of the recipe cooked", style = MaterialTheme.typography.bodyMedium)
                HalfStepper(servings, { servings = it; portions = it; freezer = freezer.coerceAtMost(it) }, "servings cooked", step = 1.0, min = 1.0)
                Text("Portions it made", style = MaterialTheme.typography.bodyMedium)
                HalfStepper(portions, { portions = it; freezer = freezer.coerceAtMost(it) }, "portions made", step = 1.0, min = 1.0)
                Text("Into the freezer", style = MaterialTheme.typography.bodyMedium)
                HalfStepper(freezer, { freezer = it.coerceAtMost(portions) }, "portions frozen", step = 1.0, min = 0.0, max = portions)
                Text(
                    "${countText(portions - freezer)} in the fridge · ${countText(freezer)} in the freezer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = madeOn == today, onClick = { madeOn = today }, label = { Text("Today") })
                    FilterChip(
                        selected = madeOn == today.minusDays(1),
                        onClick = { madeOn = today.minusDays(1) },
                        label = { Text("Yesterday") },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    TextButton(onClick = { picking = true }) { Text("Other day") }
                }
                Text("Made ${formatDisplayDate(madeOn)}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(servings, portions, freezer, madeOn) }, modifier = Modifier.testTag(DiaryTags.BATCH_SAVE)) {
                Text("Save to the kitchen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        DatePickerDialogFor(initialDate = madeOn, maxDate = today, onDateChange = { madeOn = it }, onDismiss = { picking = false })
    }
}
