package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.recipes.MergeChoice
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.ui.food.components.BundleImporter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importing a recipe file the way *Import recipes (JSON)* and *Add the starter recipes* do (docs/PLAN.md §11.7),
 * without the file picker: a good file adds its recipes, a broken one adds nothing and says why, and a second
 * import asks which ingredients to reuse.
 */
@RunWith(AndroidJUnit4::class)
class RecipeBundleImportTest {
    private val container get() = DataTestSupport.container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun importer() = BundleImporter(container.recipeRepository, scope)

    private suspend fun recipeCount(): Int = container.recipeRepository.observeAll().first().size

    private suspend fun ingredientCount(): Int = container.ingredientRepository.observeAll().first().size

    @Test
    fun aRecipeFileIsReadAndItsRecipesAdded() = runBlocking {
        val importer = importer()
        importer.offerText(RecipeBundle.encode(RecipeBundle.starter()))
        val done = withTimeout(DataTestSupport.WAIT_MS) { importer.state.first { it.message != null } }
        assertEquals("Added 4 recipes and 40 new ingredients", done.message)
        assertEquals(4, recipeCount())
        assertEquals(40, ingredientCount())
    }

    @Test
    fun aBrokenFileAddsNothingAndSaysWhy() = runBlocking {
        val importer = importer()
        importer.offerText("{\"format\": \"something-else\", \"version\": 1}")
        val failed = withTimeout(DataTestSupport.WAIT_MS) { importer.state.first { it.problems.isNotEmpty() } }
        assertTrue(failed.problems.isNotEmpty())
        assertNull(failed.prompt)
        assertEquals(0, recipeCount())
    }

    @Test
    fun aSecondImportAsksWhichIngredientsToReuse() = runBlocking {
        container.recipeRepository.importBundle(RecipeBundle.starter())
        val importer = importer()
        importer.offerStarter()
        val asked = withTimeout(DataTestSupport.WAIT_MS) { importer.state.first { it.prompt != null } }
        val prompt = assertNotNull(asked.prompt)
        assertEquals(40, prompt.matches.size)
        assertTrue(prompt.choices.values.all { it == MergeChoice.USE_EXISTING })

        importer.confirm()
        val done = withTimeout(DataTestSupport.WAIT_MS) { importer.state.first { it.message != null } }
        assertEquals("Added 4 recipes using 40 ingredients you already had", done.message)
        assertEquals(8, recipeCount())
        assertEquals(40, ingredientCount())
    }
}
