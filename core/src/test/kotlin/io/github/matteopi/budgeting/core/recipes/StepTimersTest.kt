package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timers a step offers (docs/PLAN.md §11.11). */
class StepTimersTest {

    private fun seconds(text: String) = StepTimers.durations(text).map { it.seconds }

    @Test
    fun `the forms of a duration`() {
        assertEquals(listOf(30), seconds("about 30 sec"))
        assertEquals(listOf(45), seconds("45 seconds"))
        assertEquals(listOf(120), seconds("2 min"))
        assertEquals(listOf(1200), seconds("about 20 minutes"))
        assertEquals(listOf(600), seconds("~10 mins"))
        assertEquals(listOf(4500), seconds("1 hr 15 min"))
        assertEquals(listOf(5400), seconds("1½ hours"))
        assertEquals(listOf(5400), seconds("1 hour and 30 minutes"))
        assertEquals(listOf(7200), seconds("2h"))
        assertEquals(listOf(150), seconds("2.5 min"))
    }

    @Test
    fun `a range starts at its lower end and keeps its upper end`() {
        assertEquals(listOf(StepTimer("10–12 min", 600, 720)), StepTimers.durations("lowest heat for 10–12 min"))
        assertEquals(listOf(StepTimer("30–45 sec", 30, 45)), StepTimers.durations("30–45 sec"))
        assertEquals(listOf(StepTimer("3-4 minutes", 180, 240)), StepTimers.durations("3-4 minutes a side"))
        assertEquals(listOf(StepTimer("1 to 2 hours", 3600, 7200)), StepTimers.durations("1 to 2 hours"))
    }

    @Test
    fun `reminders and other units are not timers`() {
        assertEquals(emptyList(), seconds("Stir every 5 min, scraping the bottom"))
        assertEquals(emptyList(), seconds("500 g mince, 5 ml oil, 2 slices of bread, 4 servings, 15 cm pan, 200C"))
        assertEquals(emptyList(), seconds("0 min"))
        assertEquals(emptyList(), seconds("30 hours"), "longer than a day")
    }

    @Test
    fun `a step offers its own time first, then the body's in reading order without repeats`() {
        val step = RecipeStep(
            title = "Braise gently",
            time = "about 25 min",
            body = "Lid on for 25 minutes. About 15 minutes in, start the rice: lowest heat 10–12 min, then rest 5–10 min.",
        )
        assertEquals(listOf(1500, 900, 600, 300), StepTimers.of(step).map { it.seconds })
        assertEquals("about 2 min, then steam".let { StepTimers.of(RecipeStep(time = it, body = "")) }.single().seconds, 120)
        assertTrue(StepTimers.of(RecipeStep(body = "Serve straight away.")).isEmpty())
    }

    @Test
    fun `the starter recipes' timers`() {
        val stirFry = StarterBook.recipe("stir-fry").method
        assertEquals(listOf(StepTimer("30 sec", 30)), StepTimers.of(stirFry.first { it.title == "Aromatics" }))
        val chilli = StarterBook.recipe("chilli").method
        assertEquals(listOf(1500), StepTimers.of(chilli.first { it.title == "Simmer" }).map { it.seconds }, "stirring every 5 min is no timer")
        assertEquals(StepTimer("8–10 min", 480, 600), StepTimers.of(chilli.first { it.title == "Veg in" }).single())
        for (key in StarterBook.models.recipeKeys.keys) {
            for (step in StarterBook.recipe(key).method) {
                if (step.time?.any { it.isDigit() } == true) assertTrue(StepTimers.of(step).isNotEmpty(), "$key: ${step.title}")
            }
        }
    }

    @Test
    fun `durations in words and on a clock`() {
        assertEquals("30 sec", StepTimers.format(30))
        assertEquals("2 min", StepTimers.format(120))
        assertEquals("2 min 30 sec", StepTimers.format(150))
        assertEquals("1 hr", StepTimers.format(3600))
        assertEquals("1 hr 15 min", StepTimers.format(4500))
        assertEquals("0:45", StepTimers.clock(45))
        assertEquals("12:05", StepTimers.clock(725))
        assertEquals("1:02:03", StepTimers.clock(3723))
        assertEquals("0:00", StepTimers.clock(-3))
    }
}
