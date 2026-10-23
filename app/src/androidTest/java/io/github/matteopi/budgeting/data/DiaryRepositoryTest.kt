package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.MealSlot
import io.github.matteopi.budgeting.core.nutrition.Place
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.testing.DataTestSupport
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The food diary and the kitchen against the real database (docs/PLAN.md §14.3): a batch of the chilli cooked into
 * the fridge and the freezer, eaten with a side and undone, a deleted portion going back, moving and throwing away.
 */
@RunWith(AndroidJUnit4::class)
class DiaryRepositoryTest {
    private val container get() = DataTestSupport.container
    private val diary get() = container.diaryRepository

    private suspend fun chilli(): Recipe {
        container.recipeRepository.importBundle(RecipeBundle.starter())
        return container.recipeRepository.observeAll().first().first { it.shortTitle == "Chilli" }
    }

    private fun near(expected: Double, actual: Double, message: String = "") =
        assertTrue(Math.abs(expected - actual) < 1e-6, "$message expected $expected, was $actual")

    @Test
    fun eatingTakesAPortionWithItsSidesAndUndoPutsItBack() = runBlocking<Unit> {
        val recipe = chilli()
        val today = DataTestSupport.today()
        val batchId = container.foodDiary.cook(recipe, servings = 4.0, portions = 6.0, freezer = 2.0, madeOn = today)
        val batch = assertNotNull(diary.batch(batchId))
        assertEquals(4.0, batch.fridgeLeft)
        assertEquals(2.0, batch.freezerLeft)
        assertEquals(today.plusDays(2), batch.eatBy)
        // The chilli's 4 servings split 6 ways: two thirds of a recipe portion each.
        val numbers = container.recipeRepository.observeAll().first().first { it.id == recipe.id }
        assertTrue(batch.intake.kcal > 300 && batch.intake.kcal < 700, "kcal a portion ${batch.intake.kcal} of ${numbers.title}")

        val rice = container.ingredientRepository.observeAll().first().first { it.name == "Long-grain white rice" }
        val book = container.foodDiary.book()
        val template = container.foodDiary.template(today, MealSlot.DINNER)
        val side = container.foodDiary.foodRow(template, rice, 75.0, book.prices[rice.id])
        val eaten = assertNotNull(diary.eatFromBatch(batchId, 1.0, template, listOf(side)))
        assertEquals(Place.FRIDGE, if (eaten.taken.fromFridge > 0) Place.FRIDGE else Place.FREEZER)
        assertEquals(3.0, diary.batch(batchId)!!.fridgeLeft)

        val rows = diary.observeDay(today).first()
        assertEquals(2, rows.size)
        val main = rows.single { it.sideOf == null }
        assertEquals(LogKind.BATCH, main.kind)
        assertEquals("Chilli", main.name)
        near(batch.intake.kcal, main.intake.kcal, "the portion's frozen numbers")
        assertEquals(main.id, rows.single { it.sideOf != null }.sideOf)
        near(270.0, rows.single { it.sideOf != null }.intake.kcal, "75 g of rice")

        // Undo: the rows go and the portion is back.
        diary.undoEat(eaten)
        assertTrue(diary.observeDay(today).first().isEmpty())
        assertEquals(4.0, diary.batch(batchId)!!.fridgeLeft)
    }

    @Test
    fun deletingARowPutsThePortionBackAndRestoringTakesItAgain() = runBlocking<Unit> {
        val recipe = chilli()
        val today = DataTestSupport.today()
        val batchId = container.foodDiary.cook(recipe, 4.0, 2.0, 0.0, today)
        val first = assertNotNull(container.foodDiary.ateOne(batchId))
        assertEquals(1.0, diary.batch(batchId)!!.fridgeLeft)
        assertTrue(first.message.startsWith("Logged Chilli"), first.message)

        val deleted = assertNotNull(diary.delete(first.eaten.rowId))
        assertEquals(2.0, diary.batch(batchId)!!.fridgeLeft)
        assertTrue(diary.observeDay(today).first().isEmpty())

        diary.restore(deleted)
        assertEquals(1.0, diary.batch(batchId)!!.fridgeLeft)
        assertEquals(listOf(first.eaten.rowId), diary.observeDay(today).first().map { it.id })

        // Eating more than is left takes what there is, then nothing.
        assertNotNull(container.foodDiary.ateOne(batchId))
        assertTrue(diary.batch(batchId)!!.finished)
        assertNull(container.foodDiary.ateOne(batchId))
    }

    @Test
    fun movingAndThrowingAwayChangeOnlyTheBatch() = runBlocking<Unit> {
        val recipe = chilli()
        val today = DataTestSupport.today()
        val batchId = container.foodDiary.cook(recipe, 4.0, 4.0, 0.0, today)
        diary.move(batchId, 2.0, Place.FREEZER)
        var batch = diary.batch(batchId)!!
        assertEquals(2.0, batch.fridgeLeft)
        assertEquals(2.0, batch.freezerLeft)
        assertEquals(today, batch.frozenOn)

        val taken = assertNotNull(diary.throwAway(batchId, 1.0))
        batch = diary.batch(batchId)!!
        assertEquals(1.0, batch.fridgeLeft)
        assertEquals(1.0, batch.wasted)
        diary.undoThrowAway(batchId, taken)
        batch = diary.batch(batchId)!!
        assertEquals(2.0, batch.fridgeLeft)
        assertEquals(0.0, batch.wasted)

        diary.addWater(today, 250)
        diary.addWater(today, 250)
        assertEquals(500, diary.observeFoodDay(today).first().waterMl)
        assertTrue(diary.observeDay(today).first().isEmpty(), "nothing was eaten")
    }
}
