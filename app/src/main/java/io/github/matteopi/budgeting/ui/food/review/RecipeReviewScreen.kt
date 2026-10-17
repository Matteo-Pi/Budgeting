package io.github.matteopi.budgeting.ui.food.review

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.food.ingredient.FoodSaveBar

/**
 * Test tags of the add/edit recipe screens. A line's parts are `review-line-<key>-<part>` with keys counted from 1
 * in reading order (lines first, then steps); suggestions are `…-suggestion-<ingredient id>`.
 */
object ReviewTags {
    const val PASTE_TEXT = "review-paste-text"
    const val PASTE = "review-paste"
    const val READ = "review-read"
    const val TYPE_INSTEAD = "review-type-instead"
    const val SAVE = "review-save"
    const val ADD_LINE = "review-add-line"
    const val UNMATCHED = "review-unmatched"
    const val COST = "review-cost"
    const val KCAL = "review-kcal"
    const val PROTEIN = "review-protein"
    const val SALT = "review-salt"
    const val VEG = "review-veg"
    const val CHOOSER_SEARCH = "review-chooser-search"
    const val CREATE_NAME = "review-create-name"
    const val CREATE_UNIT_G = "review-create-unit-g"
    const val CREATE_UNIT_ML = "review-create-unit-ml"
    const val CREATE_PACK_SIZE = "review-create-pack-size"
    const val CREATE_PACK_LABEL = "review-create-pack-label"
    const val CREATE_PRICE = "review-create-price"
    const val CREATE_SUBMIT = "review-create-submit"
    const val CREATE_CANCEL = "review-create-cancel"

    fun line(key: Long, part: String) = "review-line-$key-$part"

    fun chooserRow(ingredientId: Long) = "review-chooser-row-$ingredientId"

    fun step(key: Long, part: String) = "review-step-$key-$part"

    fun add(kind: StepKind) = "review-add-${kind.name.lowercase()}"
}

/**
 * Add a recipe (docs/PLAN.md §11.8): a paste box with *Paste*, *Read recipe* and *Type it instead*, then the
 * review, which is the same view model's next step. When [textPath] is set (text shared into the app) the file is
 * read and the review opens straight away. Saving opens the new recipe through [onSaved].
 */
@Composable
fun AddRecipeScreen(
    textPath: String?,
    onBack: () -> Unit,
    onSaved: (recipeId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeReviewViewModel = viewModel(factory = RecipeReviewViewModel.factory(recipeId = null, textPath = textPath)),
) {
    RecipeReviewScreen(
        title = "Add recipe",
        saveLabel = "Save recipe",
        viewModel = viewModel,
        onBack = onBack,
        onSaved = onSaved,
        modifier = modifier,
    )
}

/** Edit a saved recipe (docs/PLAN.md §11.8): the review again, prefilled. Saving replaces the recipe and goes back. */
@Composable
fun EditRecipeScreen(
    recipeId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecipeReviewViewModel = viewModel(factory = RecipeReviewViewModel.factory(recipeId = recipeId, textPath = null)),
) {
    RecipeReviewScreen(
        title = "Edit recipe",
        saveLabel = "Save changes",
        viewModel = viewModel,
        onBack = onBack,
        onSaved = { onSaved() },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeReviewScreen(
    title: String,
    saveLabel: String,
    viewModel: RecipeReviewViewModel,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val derived by viewModel.derived.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    var askDiscard by remember { mutableStateOf(false) }

    // Leaving a review that has been changed asks first: a long review is a lot to retype.
    val dirty = phase == ReviewPhase.REVIEW && !saving && viewModel.isDirty(edit)
    BackHandler(enabled = dirty) { askDiscard = true }
    val leave: () -> Unit = { if (dirty) askDiscard = true else onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (phase == ReviewPhase.REVIEW) {
                FoodSaveBar(
                    label = saveLabel,
                    problem = derived?.problems?.firstOrNull(),
                    error = saveError,
                    enabled = derived?.canSave == true,
                    saving = saving,
                    onSave = { viewModel.save(onSaved) },
                    tag = ReviewTags.SAVE,
                )
            }
        },
    ) { innerPadding ->
        when (phase) {
            ReviewPhase.LOADING -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            ReviewPhase.MISSING -> EmptyState(
                message = "This recipe is no longer there.",
                modifier = Modifier.padding(innerPadding),
            )
            ReviewPhase.PASTE -> PasteStep(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            ReviewPhase.REVIEW -> ReviewBody(viewModel = viewModel, modifier = Modifier.fillMaxSize().padding(innerPadding))
        }
    }

    if (askDiscard) {
        ConfirmDialog(
            title = "Discard your changes?",
            text = "The recipe hasn't been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onConfirm = {
                askDiscard = false
                onBack()
            },
            onDismiss = { askDiscard = false },
        )
    }
}

/** The paste step: a large text field, *Paste* from the clipboard, *Read recipe* and *Type it instead*. */
@Composable
private fun PasteStep(viewModel: RecipeReviewViewModel, modifier: Modifier = Modifier) {
    val text by viewModel.pasteText.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Paste a recipe, or type one: a title, an Ingredients list and a Method. Recipes in the paste " +
                "format work best, and plain recipes are read too. You can fix everything on the next screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::setPasteText,
            label = { Text("Recipe text") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth().testTag(ReviewTags.PASTE_TEXT),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val pasted = clipboardText(context)
                    if (pasted.isNullOrBlank()) {
                        Toast.makeText(context.applicationContext, "There's no text on the clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.setPasteText(pasted)
                    }
                },
                modifier = Modifier.testTag(ReviewTags.PASTE),
            ) { Text("Paste") }
            Button(
                onClick = viewModel::readRecipe,
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(ReviewTags.READ),
            ) { Text("Read recipe") }
        }
        TextButton(onClick = viewModel::typeInstead, modifier = Modifier.testTag(ReviewTags.TYPE_INSTEAD)) {
            Text("Type it instead")
        }
    }
}

/** The text on the clipboard, or null when there is none. Reading happens on a tap, while the app is in front. */
private fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
