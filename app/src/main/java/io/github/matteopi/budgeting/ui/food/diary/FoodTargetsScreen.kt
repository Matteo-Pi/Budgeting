@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.matteopi.budgeting.core.nutrition.Activity
import io.github.matteopi.budgeting.core.nutrition.BodyProfile
import io.github.matteopi.budgeting.core.nutrition.BodySex
import io.github.matteopi.budgeting.core.nutrition.FoodPrefs
import io.github.matteopi.budgeting.core.nutrition.FoodTargets
import io.github.matteopi.budgeting.core.nutrition.TargetCalculator
import io.github.matteopi.budgeting.core.nutrition.WeightGoal
import io.github.matteopi.budgeting.core.recipes.Quantities
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Test tags of the targets screen. */
object TargetTags {
    const val SAVE = "targets-save"
    const val WORK_OUT = "targets-work-out"
    const val USE = "targets-use"
    const val RESET = "targets-reset"

    fun field(name: String) = "targets-$name"
}

class FoodTargetsViewModel(private val container: AppContainer) : ViewModel() {
    val prefs: StateFlow<FoodPrefs?> = container.settingsStore.foodPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun save(targets: FoodTargets, profile: BodyProfile?, onDone: () -> Unit) {
        viewModelScope.launch {
            container.settingsStore.updateFoodPrefs { it.copy(targets = targets, profile = profile) }
            onDone()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                FoodTargetsViewModel(app.container)
            }
        }
    }
}

/** The fields of the targets, by name, as text. */
private val TARGET_FIELDS = listOf(
    "kcal" to "Energy (kcal)", "protein" to "Protein (g, at least)", "carbs" to "Carbs (g)", "fat" to "Fat (g)",
    "fibre" to "Fibre (g, at least)", "sugars" to "Sugars (g, limit)", "saturates" to "Saturates (g, limit)",
    "salt" to "Salt (g, limit)", "addedSugars" to "Added sugars (g, limit)",
)

private fun FoodTargets.asTexts(): Map<String, String> = mapOf(
    "kcal" to kcal, "protein" to protein, "carbs" to carbs, "fat" to fat, "fibre" to fibre, "sugars" to sugars,
    "saturates" to saturates, "salt" to salt, "addedSugars" to addedSugars,
).mapValues { Quantities.plain(it.value, 1) }

private fun targetsOf(texts: Map<String, String>, fallback: FoodTargets): FoodTargets {
    fun v(name: String, old: Double) = texts[name]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 } ?: old
    return FoodTargets(
        kcal = v("kcal", fallback.kcal), protein = v("protein", fallback.protein), carbs = v("carbs", fallback.carbs),
        fat = v("fat", fallback.fat), saturates = v("saturates", fallback.saturates), sugars = v("sugars", fallback.sugars),
        addedSugars = v("addedSugars", fallback.addedSugars), fibre = v("fibre", fallback.fibre), salt = v("salt", fallback.salt),
    )
}

/**
 * The daily targets (docs/PLAN.md §14.5): typed, reset to the UK reference intakes, or worked out from age, height,
 * weight, activity and goal by [TargetCalculator] (a guide, not advice).
 */
@Composable
fun FoodTargetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodTargetsViewModel = viewModel(factory = FoodTargetsViewModel.Factory),
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var texts by remember { mutableStateOf<Map<String, String>?>(null) }
    var profile by remember { mutableStateOf<BodyProfile?>(null) }
    var calculating by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) {
        val loaded = prefs ?: return@LaunchedEffect
        if (texts == null) {
            texts = loaded.targets.asTexts()
            profile = loaded.profile
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Daily targets") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { innerPadding ->
        val current = texts ?: return@Scaffold
        Column(
            Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Start from the UK reference intakes for an adult, type your own, or work them out. A guide, not medical advice.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TARGET_FIELDS.forEach { (name, label) ->
                OutlinedTextField(
                    value = current[name].orEmpty(),
                    onValueChange = { value -> texts = current + (name to value) },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(TargetTags.field(name)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { calculating = !calculating }, modifier = Modifier.testTag(TargetTags.WORK_OUT)) { Text("Work them out") }
                TextButton(onClick = { texts = FoodTargets().asTexts(); profile = null }, modifier = Modifier.testTag(TargetTags.RESET)) {
                    Text("Reference intakes")
                }
            }
            if (calculating) {
                Calculator(profile ?: BodyProfile()) { worked, used ->
                    texts = worked.asTexts()
                    profile = used
                    calculating = false
                }
            }
            Button(
                onClick = { viewModel.save(targetsOf(current, prefs?.targets ?: FoodTargets()), profile, onBack) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TargetTags.SAVE),
            ) { Text("Save targets") }
        }
    }
}

@Composable
private fun Calculator(start: BodyProfile, onUse: (FoodTargets, BodyProfile) -> Unit) {
    var sex by remember { mutableStateOf(start.sex) }
    var age by remember { mutableStateOf(start.age.toString()) }
    var height by remember { mutableStateOf(Quantities.plain(start.heightCm, 0)) }
    var weight by remember { mutableStateOf(Quantities.plain(start.weightKg, 1)) }
    var activity by remember { mutableStateOf(start.activity) }
    var goal by remember { mutableStateOf(start.goal) }
    var strength by remember { mutableStateOf(start.strength) }
    val profile = BodyProfile(
        sex = sex,
        age = age.toIntOrNull()?.coerceIn(14, 100) ?: start.age,
        heightCm = height.replace(',', '.').toDoubleOrNull()?.coerceIn(100.0, 250.0) ?: start.heightCm,
        weightKg = weight.replace(',', '.').toDoubleOrNull()?.coerceIn(30.0, 300.0) ?: start.weightKg,
        activity = activity,
        goal = goal,
        strength = strength,
    )
    val worked = TargetCalculator.targets(profile)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodySex.entries.forEach { FilterChip(selected = sex == it, onClick = { sex = it }, label = { Text(it.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(age, { age = it }, label = { Text("Age") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TargetTags.field("age")),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(height, { height = it }, label = { Text("Height cm") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TargetTags.field("height")),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(weight, { weight = it }, label = { Text("Weight kg") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TargetTags.field("weight")),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Text("How active", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Activity.entries.forEach { FilterChip(selected = activity == it, onClick = { activity = it }, label = { Text(it.label) }) }
        }
        Text("Goal", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightGoal.entries.forEach { FilterChip(selected = goal == it, onClick = { goal = it }, label = { Text(it.label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Strength training (more protein)", modifier = Modifier.weight(1f))
            Switch(checked = strength, onCheckedChange = { strength = it })
        }
        Text(
            "About ${Quantities.roundHalfUp(worked.kcal)} kcal, ${Quantities.plain(worked.protein, 0)} g protein, " +
                "${Quantities.plain(worked.carbs, 0)} g carbs and ${Quantities.plain(worked.fat, 0)} g fat a day.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { onUse(worked, profile) }, modifier = Modifier.testTag(TargetTags.USE)) { Text("Use these") }
    }
}
