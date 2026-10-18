@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.matteopi.budgeting.ui.food

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.food.components.BundleImportDialogs
import io.github.matteopi.budgeting.ui.food.components.BundleImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the recipe settings. */
object RecipeSettingsTags {
    const val COUNT_OPTIONAL = "recipe-settings-count-optional"
    const val LOOK_UP_BARCODES = "recipe-settings-look-up-barcodes"
    const val PHONE_TIMERS = "recipe-settings-phone-timers"
    const val ADD_STARTER = "recipe-settings-add-starter"
    const val IMPORT_JSON = "recipe-settings-import-json"
}

data class RecipeSettingsUiState(
    val countOptionalExtras: Boolean = true,
    val lookUpBarcodesOnline: Boolean = false,
    val phoneTimers: Boolean = true,
    /** How many recipes there are (not archived): adding the starter book asks first when there are some. */
    val recipeCount: Int = 0,
)

/** The recipe settings (docs/PLAN.md §11.8, §11.11): the switches, the starter book and importing a recipe file. */
class RecipeSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val importer = BundleImporter(container.recipeRepository, container.appScope)

    val uiState: StateFlow<RecipeSettingsUiState> = combine(
        container.settingsStore.countOptionalExtras,
        container.settingsStore.lookUpBarcodesOnline,
        container.settingsStore.phoneTimers,
        container.recipeRepository.observeAll(),
    ) { count, lookUp, phoneTimers, recipes ->
        RecipeSettingsUiState(count, lookUp, phoneTimers, recipes.count { !it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeSettingsUiState())

    fun setCountOptionalExtras(count: Boolean) {
        container.appScope.launch { container.settingsStore.setCountOptionalExtras(count) }
    }

    fun setLookUpBarcodesOnline(lookUp: Boolean) {
        container.appScope.launch { container.settingsStore.setLookUpBarcodesOnline(lookUp) }
    }

    fun setPhoneTimers(on: Boolean) {
        container.appScope.launch { container.settingsStore.setPhoneTimers(on) }
    }

    fun addStarterRecipes() = importer.offerStarter()

    /** Reads the picked file and offers its recipes; what is wrong with it goes to the importer's problems. */
    fun importFile(context: Context, uri: Uri) {
        val app = context.applicationContext
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) importer.offerText("") else importer.offerText(text)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BudgetApp
                RecipeSettingsViewModel(app.container)
            }
        }
    }
}

/** What the file picker offers: JSON, and the types some apps give a JSON file. */
private val RECIPE_FILE_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

@Composable
fun RecipeSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeSettingsViewModel = viewModel(factory = RecipeSettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importer.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmStarter by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(context, uri)
    }

    LaunchedEffect(importState.message) {
        val message = importState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.importer.messageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Recipe settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(title = "Numbers") {
                    SwitchRow(
                        label = "Count optional extras",
                        detail = "Optional ingredients count in a recipe's cost and nutrition.",
                        checked = state.countOptionalExtras,
                        onChange = viewModel::setCountOptionalExtras,
                        tag = RecipeSettingsTags.COUNT_OPTIONAL,
                    )
                }
                SectionCard(title = "Timers") {
                    SwitchRow(
                        label = "Start timers on the phone's clock",
                        detail = "A recipe timer also starts a timer in your clock app, so it rings even if this app " +
                            "is closed. Cancelling here doesn't stop the clock app's timer.",
                        checked = state.phoneTimers,
                        onChange = viewModel::setPhoneTimers,
                        tag = RecipeSettingsTags.PHONE_TIMERS,
                    )
                }
                SectionCard(title = "Barcodes") {
                    SwitchRow(
                        label = "Look up barcodes online",
                        detail = "When you scan a barcode the app doesn't know, it can ask Open Food Facts (a free, " +
                            "crowd-sourced food database) for the product's name, pack size and nutrition. Only the " +
                            "barcode number is sent, and only when you scan one with this on. Off by default; " +
                            "nothing else in the app uses the internet.",
                        checked = state.lookUpBarcodesOnline,
                        onChange = viewModel::setLookUpBarcodesOnline,
                        tag = RecipeSettingsTags.LOOK_UP_BARCODES,
                    )
                }
                SectionCard(title = "Recipes") {
                    Text(
                        "The starter book has four recipes (stir-fry, traybake, braise and chilli) with Tesco prices " +
                            "from October 2026.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { if (state.recipeCount > 0) confirmStarter = true else viewModel.addStarterRecipes() },
                        modifier = Modifier.padding(top = 8.dp).testTag(RecipeSettingsTags.ADD_STARTER),
                    ) { Text("Add the starter recipes") }
                    Text(
                        "A recipe file (.json) from Claude or a backup of your recipes adds its recipes and ingredients.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { pickFile.launch(RECIPE_FILE_TYPES) },
                        modifier = Modifier.padding(top = 8.dp).testTag(RecipeSettingsTags.IMPORT_JSON),
                    ) { Text("Import recipes (JSON)") }
                }
            }
            if (importState.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (confirmStarter) {
        ConfirmDialog(
            title = "Add the starter recipes again?",
            text = "You already have ${state.recipeCount} recipes. The four starter recipes are added alongside them.",
            confirmLabel = "Add them",
            onConfirm = {
                confirmStarter = false
                viewModel.addStarterRecipes()
            },
            onDismiss = { confirmStarter = false },
        )
    }
    BundleImportDialogs(state = importState, importer = viewModel.importer)
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
