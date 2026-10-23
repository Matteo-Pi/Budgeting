package io.github.matteopi.budgeting.core.nutrition

import io.github.matteopi.budgeting.core.recipes.FoodDatabase
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.StarterBook
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diary's day, the kitchen's batches, sides, meal times, quick food, targets and trends (docs/PLAN.md §14.4). */
class DiaryAndKitchenTest {

    private val today = LocalDate.of(2026, 10, 23)

    private fun near(expected: Double, actual: Double, tolerance: Double = 1e-6, message: String = "") =
        assertTrue(Math.abs(expected - actual) <= tolerance, "$message expected $expected, was $actual")

    private fun row(id: Long, meal: MealSlot, intake: Intake, cost: Double? = 50.0, kind: LogKind = LogKind.FOOD, sideOf: Long? = null, date: LocalDate = today, name: String = "Row $id", recipeId: Long? = null, ingredientId: Long? = null, amount: Double = 1.0) =
        LogItem(id = id, date = date, meal = meal, kind = kind, name = name, intake = intake, costPence = cost, sideOf = sideOf, eatenAtEpochMillis = id, recipeId = recipeId, ingredientId = ingredientId, amount = amount)

    private fun kcal(kcal: Double, protein: Double = 0.0, veg: Double = 0.0, pulses: Double = 0.0, salt: Double = 0.0) =
        Intake.of(mapOf(IntakeKey.KCAL to kcal, IntakeKey.PROTEIN to protein, IntakeKey.SALT to salt), vegGrams = veg, pulseGrams = pulses)

    // ---- DiaryMaths ----

    @Test
    fun `a day adds up overall and per meal, with the pulses capped once for the day`() {
        val items = listOf(
            row(1, MealSlot.BREAKFAST, kcal(300.0, 10.0)),
            row(2, MealSlot.LUNCH, kcal(600.0, 30.0, veg = 160.0, pulses = 100.0)),
            row(3, MealSlot.DINNER, kcal(700.0, 40.0, veg = 80.0, pulses = 100.0), cost = null),
            row(4, MealSlot.DINNER, Intakes.quick(200.0), cost = null, kind = LogKind.QUICK),
        )
        val day = DiaryMaths.day(items, waterMl = 750)
        near(1800.0, day.kcal)
        near(900.0, day.byMeal.getValue(MealSlot.DINNER).kcal)
        near(0.0, day.byMeal.getValue(MealSlot.SNACKS).kcal)
        near(3.0 + 1.0, day.vegPortions, message = "240 g veg is 3, 200 g pulses count once")
        near(100.0, day.costPence)
        assertEquals(1, day.unpriced, "a quick entry has no price to miss")
        assertTrue(day.of(IntakeKey.CARBS).atLeast, "the quick entry's carbs are unknown")
        assertFalse(day.of(IntakeKey.KCAL).atLeast)
        assertEquals(750, day.waterMl)
    }

    @Test
    fun `targets are under, on track or over by their kind`() {
        val targets = FoodTargets()
        fun state(key: IntakeKey, amount: Double) = DiaryMaths.progress(key, Intake.of(mapOf(key to amount)), targets).state
        assertEquals(TargetState.UNDER, state(IntakeKey.KCAL, 1700.0))
        assertEquals(TargetState.ON_TRACK, state(IntakeKey.KCAL, 2100.0))
        assertEquals(TargetState.OVER, state(IntakeKey.KCAL, 2300.0))
        assertEquals(TargetState.ON_TRACK, state(IntakeKey.PROTEIN, 120.0), "protein is at least")
        assertEquals(TargetState.UNDER, state(IntakeKey.FIBRE, 20.0))
        assertEquals(TargetState.ON_TRACK, state(IntakeKey.SALT, 6.0))
        assertEquals(TargetState.OVER, state(IntakeKey.SALT, 6.1), "salt is a limit")
        assertEquals(TargetState.UNDER, state(IntakeKey.IRON, 5.0), "against its reference")
        val progress = DiaryMaths.progress(IntakeKey.KCAL, kcal(1500.0), targets)
        near(500.0, progress.left)
        near(0.75, progress.fraction)
    }

    @Test
    fun `rows are ordered by meal with each portion's sides under it, and the top sources found`() {
        val items = listOf(
            row(5, MealSlot.DINNER, kcal(300.0), sideOf = 3, name = "Rice"),
            row(3, MealSlot.DINNER, kcal(500.0), name = "Chilli"),
            row(1, MealSlot.BREAKFAST, kcal(200.0), name = "Toast"),
            row(9, MealSlot.SNACKS, kcal(100.0), sideOf = 99, name = "Orphan side"),
        )
        assertEquals(listOf(1L, 3L, 5L, 9L), DiaryMaths.ordered(items).map { it.id })
        val top = DiaryMaths.topSources(items, IntakeKey.KCAL, 2)
        assertEquals(listOf("Chilli", "Rice"), top.map { it.first.name })
        near(500.0 / 1100.0, top.first().second)
    }

    // ---- Batches ----

    private val portion = Eaten(kcal(550.0, 40.0), 120.0)

    private fun chilli() = Batches.make("Chilli", 4, today, servings = 8.0, portions = 6.0, freezer = 2.0, perPortion = portion, nowMillis = 1)

    @Test
    fun `a batch splits into the fridge and the freezer with the fridge's eat-by`() {
        val batch = chilli()
        assertEquals(4.0, batch.fridgeLeft)
        assertEquals(2.0, batch.freezerLeft)
        assertEquals(today, batch.frozenOn)
        assertEquals(today.plusDays(2), batch.eatBy)
        assertEquals(120.0, batch.costPence)
        assertEquals(550.0, batch.intake.kcal)
        assertNull(Batches.make("Soup", null, today, 4.0, 4.0, 0.0, portion).frozenOn)
        assertEquals(3.0, Batches.make("Soup", null, today, 4.0, 3.0, 9.0, portion).freezerLeft, "never more frozen than made")
    }

    @Test
    fun `eating takes from the fridge first, then the freezer, in halves, never below zero`() {
        var batch = chilli()
        val half = Batches.eat(batch, 0.5)
        assertEquals(3.5, half.batch.fridgeLeft)
        assertEquals(Taken(0.5, 0.0), half.taken)
        batch = Batches.eat(batch, 4.5).let {
            assertEquals(Taken(4.0, 0.5), it.taken)
            it.batch
        }
        assertEquals(0.0, batch.fridgeLeft)
        assertEquals(1.5, batch.freezerLeft)
        val tooMuch = Batches.eat(batch, 5.0)
        assertEquals(1.5, tooMuch.taken.total)
        assertTrue(tooMuch.batch.finished)
        val fromFreezer = Batches.eat(chilli(), 1.0, Place.FREEZER)
        assertEquals(Taken(0.0, 1.0), fromFreezer.taken)
    }

    @Test
    fun `undo puts back exactly what was taken, and a deleted row goes back where portions are`() {
        val start = chilli()
        val change = Batches.eat(start, 5.0)
        assertEquals(start, Batches.putBack(change.batch, change.taken))
        val thrown = Batches.throwAway(start, 1.0)
        assertEquals(1.0, thrown.batch.wasted)
        assertEquals(start, Batches.putBack(thrown.batch, thrown.taken, wasThrownAway = true))
        val onlyFrozen = start.copy(fridgeLeft = 0.0)
        assertEquals(Place.FREEZER, Batches.returnPlace(onlyFrozen))
        assertEquals(Place.FRIDGE, Batches.returnPlace(start))
        assertEquals(3.0, Batches.putBack(onlyFrozen, 1.0, Place.FREEZER).freezerLeft)
    }

    @Test
    fun `moving freezes or defrosts, and defrosted portions keep a day`() {
        val start = Batches.make("Curry", 1, today, 4.0, 4.0, 0.0, portion)
        val frozen = Batches.move(start, 2.0, Place.FREEZER, today.plusDays(1))
        assertEquals(2.0, frozen.fridgeLeft)
        assertEquals(2.0, frozen.freezerLeft)
        assertEquals(today.plusDays(1), frozen.frozenOn)
        val eaten = Batches.eat(frozen, 2.0).batch
        val later = today.plusDays(20)
        val defrosted = Batches.move(eaten, 1.0, Place.FRIDGE, later)
        assertEquals(1.0, defrosted.fridgeLeft)
        assertEquals(later.plusDays(1), defrosted.eatBy)
        assertEquals(today.plusDays(1), defrosted.frozenOn, "one still frozen")
        assertNull(Batches.move(defrosted, 5.0, Place.FRIDGE, later).frozenOn, "all out of the freezer")
        assertEquals(2.0, Batches.move(defrosted, 5.0, Place.FRIDGE, later).fridgeLeft, "moves at most what is there")
    }

    @Test
    fun `statuses follow the eat-by and how long it has been frozen`() {
        val batch = chilli()
        assertEquals(BatchStatus.IN_FRIDGE, Batches.status(batch, today))
        assertEquals(BatchStatus.EAT_TOMORROW, Batches.status(batch, today.plusDays(1)))
        assertEquals(BatchStatus.EAT_TODAY, Batches.status(batch, today.plusDays(2)))
        assertEquals(BatchStatus.PAST_EAT_BY, Batches.status(batch, today.plusDays(3)))
        val frozen = batch.copy(fridgeLeft = 0.0)
        assertEquals(BatchStatus.FROZEN, Batches.status(frozen, today.plusMonths(2)))
        assertEquals(BatchStatus.FROZEN_LONG, Batches.status(frozen, today.plusMonths(3).plusDays(1)))
        assertEquals(BatchStatus.FINISHED, Batches.status(frozen.copy(freezerLeft = 0.0), today))
    }

    @Test
    fun `ready lists the fridge by eat-by then the freezer, and waste is costed`() {
        val a = chilli().copy(id = 1, eatBy = today.plusDays(2))
        val b = chilli().copy(id = 2, eatBy = today.plusDays(1))
        val c = chilli().copy(id = 3, fridgeLeft = 0.0)
        val done = chilli().copy(id = 4, fridgeLeft = 0.0, freezerLeft = 0.0, wasted = 2.0)
        assertEquals(listOf(2L, 1L, 3L), Batches.ready(listOf(a, b, c, done)).map { it.id })
        assertEquals(listOf(4L), Batches.recentlyFinished(listOf(a, done), today.plusDays(3)).map { it.id })
        assertTrue(Batches.recentlyFinished(listOf(done), today.plusDays(15)).isEmpty())
        near(240.0, Batches.wastedPence(listOf(a, done), today, today))
        assertEquals("1 portion", Batches.portionsText(1.0))
        assertEquals("½ portion", Batches.portionsText(0.5))
        assertEquals("1½ portions", Batches.portionsText(1.5))
    }

    // ---- Sides ----

    private val foods = FoodDatabase.all().associate { it.key to it.ingredient }
    private fun food(key: String, id: Long = 0) = foods.getValue(key).copy(id = id)

    @Test
    fun `default side amounts`() {
        assertEquals(75.0, Sides.defaultAmount(food("rice")).amount)
        assertEquals("75 g", Sides.defaultAmount(food("rice")).text)
        assertEquals(250.0, Sides.defaultAmount(food("rice_microwave")).amount)
        assertEquals("1 pouch", Sides.defaultAmount(food("rice_microwave")).text)
        assertEquals("2 slices", Sides.defaultAmount(food("bread_white")).text)
        assertEquals(80.0, Sides.defaultAmount(food("bread_white")).amount)
        assertEquals(130.0, Sides.defaultAmount(food("naan")).amount)
        assertEquals("1 wrap", Sides.defaultAmount(food("tortilla_wraps")).text)
        assertEquals(150.0, Sides.defaultAmount(food("oven_chips")).amount)
        assertEquals(200.0, Sides.defaultAmount(food("potatoes_white")).amount)
        assertEquals(75.0, Sides.defaultAmount(food("couscous")).amount)
        assertTrue(Sides.isCarb(food("pitta")))
        assertFalse(Sides.isCarb(food("broccoli")))
    }

    @Test
    fun `amounts read in portions when they fit`() {
        val bread = food("bread_white")
        assertEquals("3 slices", Sides.describe(bread, 120.0))
        assertEquals("½ slice", Sides.describe(bread, 20.0))
        assertEquals("50 g", Sides.describe(bread, 50.0))
        assertEquals("2 (120 g)", Sides.describe(food("bread_rolls"), 120.0))
        assertEquals("2 pouches", Sides.describe(food("rice_microwave"), 500.0))
        assertEquals("leaves", Sides.plural("leaf"))
    }

    @Test
    fun `suggestions come from served with, then the sides last eaten with it, then the most logged carbs`() {
        val rice = food("rice", 1)
        val naan = food("naan", 2)
        val pitta = food("pitta", 3)
        val bread = food("bread_white", 4)
        val mine = listOf(rice, naan, pitta, bread)
        val curry = Recipe(id = 10, title = "Curry", servedWith = "Basmati rice or naan bread")
        val history = listOf(
            row(1, MealSlot.DINNER, kcal(500.0), recipeId = 10, kind = LogKind.BATCH),
            row(2, MealSlot.DINNER, kcal(200.0), sideOf = 1, ingredientId = 3, amount = 110.0),
            row(3, MealSlot.LUNCH, kcal(200.0), ingredientId = 4),
            row(4, MealSlot.LUNCH, kcal(200.0), ingredientId = 4),
        )
        val got = Sides.suggestions(curry, mine, history)
        val names = got.map { it.ingredient.name }
        assertEquals(naan.name, names[1], "$names")
        assertEquals("Basmati rice", names[0], "not mine yet: from the food list")
        assertNotNull(got[0].foodKey)
        assertEquals(pitta.name, names[2])
        assertEquals(110.0, got[2].amount.amount, "the amount last eaten")
        assertEquals(bread.name, names[3])
        val included = Recipe(id = 11, title = "Chilli", servedWith = "Served with rice (included in the numbers).")
        assertTrue(Sides.suggestions(included, mine, emptyList()).none { it.ingredient.name == rice.name })
    }

    // ---- MealSlots, QuickFood ----

    @Test
    fun `meal slots by the time of day`() {
        assertEquals(MealSlot.BREAKFAST, MealSlots.at(LocalTime.of(7, 0)))
        assertEquals(MealSlot.LUNCH, MealSlots.at(LocalTime.of(10, 30)))
        assertEquals(MealSlot.SNACKS, MealSlots.at(LocalTime.of(15, 30)))
        assertEquals(MealSlot.DINNER, MealSlots.at(LocalTime.of(19, 0)))
        assertEquals(MealSlot.SNACKS, MealSlots.at(LocalTime.of(23, 0)))
        assertEquals(MealSlot.DINNER, MealSlots.at(LocalTime.of(16, 0), FoodPrefs(snacksUntil = 16 * 60)))
    }

    @Test
    fun `three slices of toast, 200 g of yoghurt and an apple`() {
        val bread = Ingredient(id = 5, name = "Hovis white bread", per100 = Nutrition(kcal = 235.0), portions = listOf(Portion("slice", 38.0)))
        val toast = assertNotNull(QuickFood.read("3 slices of toast", listOf(bread)))
        assertEquals(5L, toast.ingredient.id, "toast is bread, and the user's own bread is close enough")
        assertEquals(114.0, toast.amount.amount)
        assertEquals("3 slices", toast.amount.text)
        assertTrue(toast.amountGiven)
        val starterOnly = assertNotNull(QuickFood.read("3 slices of toast", StarterBook.ingredients))
        assertEquals("bread_white", starterOnly.foodKey, "white mushrooms are not white bread")
        val listBread = assertNotNull(QuickFood.read("3 slices of toast", emptyList()))
        assertEquals("bread_white", listBread.foodKey)
        assertEquals(120.0, listBread.amount.amount)
        val mine = Ingredient(id = 6, name = "White bread", per100 = Nutrition(kcal = 235.0), portions = listOf(Portion("slice", 38.0)))
        val own = assertNotNull(QuickFood.read("2 slices toast", listOf(mine)))
        assertEquals(6L, own.ingredient.id)
        assertNull(own.foodKey)
        assertEquals(76.0, own.amount.amount)
        val yoghurt = assertNotNull(QuickFood.read("200 g greek yoghurt", emptyList()))
        assertEquals(200.0, yoghurt.amount.amount)
        assertNotNull(yoghurt.foodKey)
        val apple = assertNotNull(QuickFood.read("an apple", emptyList()))
        assertEquals("apples", apple.foodKey)
        assertEquals(150.0, apple.amount.amount)
        val noAmount = assertNotNull(QuickFood.read("naan", emptyList()))
        assertFalse(noAmount.amountGiven)
        assertEquals(130.0, noAmount.amount.amount)
        assertNull(QuickFood.read("   ", emptyList()))
    }

    // ---- TargetCalculator, prefs, insights ----

    @Test
    fun `targets worked out by Mifflin St Jeor with floors`() {
        val woman = BodyProfile(BodySex.FEMALE, age = 20, heightCm = 165.0, weightKg = 60.0, activity = Activity.LIGHT)
        near(10 * 60 + 6.25 * 165 - 5 * 20 - 161.0, TargetCalculator.restingKcal(woman))
        val t = TargetCalculator.targets(woman)
        assertEquals(Math.round(TargetCalculator.restingKcal(woman) * 1.375 / 10.0) * 10.0, t.kcal)
        assertEquals(45.0, t.protein)
        assertEquals(Math.round(t.kcal * 0.3 / 9).toDouble(), t.fat)
        assertTrue(Math.abs(t.protein * 4 + t.fat * 9 + t.carbs * 4 - t.kcal) < 10)
        assertEquals(30.0, t.fibre)
        assertEquals(96.0, TargetCalculator.targets(woman.copy(strength = true)).protein)
        val tiny = BodyProfile(BodySex.FEMALE, age = 60, heightCm = 150.0, weightKg = 40.0, activity = Activity.SITTING, goal = WeightGoal.LOSE_HALF)
        assertEquals(1200.0, TargetCalculator.targets(tiny).kcal)
        assertEquals(1500.0, TargetCalculator.targets(tiny.copy(sex = BodySex.MALE)).kcal)
    }

    @Test
    fun `food prefs round trip and fall back to the defaults`() {
        val prefs = FoodPrefs(targets = FoodTargets(kcal = 2400.0), fridgeDays = 3, profile = BodyProfile(age = 21))
        assertEquals(prefs, FoodPrefs.decode(prefs.encode()))
        assertEquals(FoodPrefs(), FoodPrefs.decode(null))
        assertEquals(FoodPrefs(), FoodPrefs.decode("not json"))
        assertEquals(2000, FoodPrefs.decode("""{"glassMl": 300}""").waterGoalMl)
    }

    @Test
    fun `insights average over logged days and flag low micros`() {
        val iron = Intake.of(mapOf(IntakeKey.KCAL to 2000.0, IntakeKey.IRON to 3.0, IntakeKey.VITAMIN_C to 100.0))
        val items = listOf(
            row(1, MealSlot.DINNER, iron, name = "Pasta"),
            row(2, MealSlot.DINNER, iron * 0.5, date = today.minusDays(2), name = "Soup", cost = 100.0),
            row(3, MealSlot.DINNER, iron, date = today.minusDays(30), name = "Old"),
        )
        val waste = chilli().copy(wasted = 1.0)
        val insights = NutritionInsights.of(items, today.minusDays(6), today, days = listOf(FoodDay(today, 1500)), batches = listOf(waste))
        assertEquals(7, insights.days.size)
        assertEquals(2, insights.loggedDays)
        near(1500.0, insights.average.kcal)
        assertTrue(Micro.IRON in insights.lowMicros)
        assertFalse(Micro.VITAMIN_C in insights.lowMicros)
        assertEquals(listOf("Pasta", "Soup"), insights.topKcal.map { it.name })
        assertEquals("Soup", insights.topCost.first().name)
        near(75.0, insights.costPerDay)
        assertEquals(1, insights.daysOnTarget)
        near(750.0, insights.waterPerDay, message = "over the days with something")
        near(120.0, insights.wastedPence)
    }
}
