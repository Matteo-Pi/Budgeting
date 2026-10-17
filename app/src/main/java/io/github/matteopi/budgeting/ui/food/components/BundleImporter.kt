package io.github.matteopi.budgeting.ui.food.components

import io.github.matteopi.budgeting.core.recipes.BundleDecodeResult
import io.github.matteopi.budgeting.core.recipes.IngredientMerge
import io.github.matteopi.budgeting.core.recipes.MergeChoice
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.data.repo.RecipeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The question asked before a bundle is imported when some of its ingredients match ones the user already
 * has (docs/PLAN.md §11.7): for each, *Use existing* (the default) or *Import as new*. [choices] is keyed by the
 * ingredient's key in the bundle file.
 */
data class MergePrompt(
    val bundle: RecipeBundle,
    val matches: List<IngredientMerge>,
    val choices: Map<String, MergeChoice>,
)

/** What a screen shows of a running import. */
data class BundleImportState(
    /** Reading or importing: the screen can show a progress bar. */
    val working: Boolean = false,
    /** The merge question, while it is waiting for an answer. */
    val prompt: MergePrompt? = null,
    /** A one-off line for the snackbar: the count after an import, or what went wrong. Cleared by [BundleImporter.messageShown]. */
    val message: String? = null,
    /** Why a bundle file could not be read. Cleared by [BundleImporter.dismissProblems]. */
    val problems: List<String> = emptyList(),
)

/**
 * Adds a recipe bundle (the starter book, or a JSON file) through [RecipeRepository.importBundle], asking the
 * merge question first when some ingredients match ones the user already has; with nothing to ask it imports
 * straight away. It works on [scope] (the app's, so an import started on a screen still completes if the user leaves),
 * and screens show [state].
 */
class BundleImporter(
    private val recipes: RecipeRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(BundleImportState())
    val state: StateFlow<BundleImportState> = mutableState.asStateFlow()

    /** Offers the starter recipe book that ships with the app. */
    fun offerStarter() {
        scope.launch(Dispatchers.Default) {
            val bundle = try {
                RecipeBundle.starter()
            } catch (e: IllegalStateException) {
                mutableState.update { it.copy(message = "The starter recipes couldn't be read.") }
                return@launch
            }
            offer(bundle)
        }
    }

    /** Offers the bundle in [text], a JSON file's contents: it is read first, and what is wrong with it is put in [BundleImportState.problems]. */
    fun offerText(text: String) {
        scope.launch(Dispatchers.Default) {
            when (val result = RecipeBundle.decode(text)) {
                is BundleDecodeResult.Success -> offer(result.bundle)
                is BundleDecodeResult.Failure -> mutableState.update { it.copy(problems = result.problems) }
            }
        }
    }

    /** Imports [bundle], asking the merge question first if some of its ingredients match the user's. */
    fun offer(bundle: RecipeBundle) {
        if (mutableState.value.working || mutableState.value.prompt != null) return
        mutableState.update { it.copy(working = true) }
        scope.launch {
            try {
                val matches = recipes.findMatches(bundle)
                if (matches.isEmpty()) {
                    runImport(bundle, emptyMap())
                } else {
                    val choices = matches.associate { it.key to MergeChoice.USE_EXISTING }
                    mutableState.update { it.copy(working = false, prompt = MergePrompt(bundle, matches, choices)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Sets the answer for one ingredient of the open question. */
    fun choose(key: String, choice: MergeChoice) {
        mutableState.update { state ->
            val prompt = state.prompt ?: return@update state
            state.copy(prompt = prompt.copy(choices = prompt.choices + (key to choice)))
        }
    }

    /** Gives every ingredient of the open question the same answer. */
    fun chooseAll(choice: MergeChoice) {
        mutableState.update { state ->
            val prompt = state.prompt ?: return@update state
            state.copy(prompt = prompt.copy(choices = prompt.matches.associate { it.key to choice }))
        }
    }

    /** Imports with the answers given. */
    fun confirm() {
        val prompt = mutableState.value.prompt ?: return
        mutableState.update { it.copy(working = true, prompt = null) }
        scope.launch {
            try {
                runImport(prompt.bundle, prompt.choices)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Closes the question without importing anything. */
    fun cancel() {
        mutableState.update { it.copy(prompt = null, working = false) }
    }

    fun messageShown() {
        mutableState.update { it.copy(message = null) }
    }

    fun dismissProblems() {
        mutableState.update { it.copy(problems = emptyList()) }
    }

    private suspend fun runImport(bundle: RecipeBundle, choices: Map<String, MergeChoice>) {
        val result = recipes.importBundle(bundle, choices)
        mutableState.update { it.copy(working = false, prompt = null, message = FoodFormat.importSummary(result)) }
    }

    private fun failed(e: Exception) {
        mutableState.update {
            it.copy(working = false, prompt = null, message = "Couldn't add the recipes: ${e.message ?: "something went wrong"}")
        }
    }
}
