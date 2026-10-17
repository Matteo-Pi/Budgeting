package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeStepsTest {

    private fun kinds(body: String) = RecipeSteps.paragraphs(body).map { it.kind }

    @Test
    fun `plain, cue and rescue paragraphs`() {
        val body = "Onion straight in.\n\nReady when: the onion is soft and see-through.\n\nIf the base goes darker than chestnut brown: splash in about 50 ml of the stock and scrape."
        val paragraphs = RecipeSteps.paragraphs(body)
        assertEquals(listOf(ParagraphKind.PLAIN, ParagraphKind.CUE, ParagraphKind.RESCUE), paragraphs.map { it.kind })
        assertEquals("Onion straight in.", paragraphs[0].text)
        assertEquals("", paragraphs[0].lead)
        assertEquals("Onion straight in.", paragraphs[0].rest)
        assertEquals("Ready when", paragraphs[1].lead)
        assertEquals("the onion is soft and see-through.", paragraphs[1].rest)
        assertEquals("If the base goes darker than chestnut brown", paragraphs[2].lead)
        assertEquals("splash in about 50 ml of the stock and scrape.", paragraphs[2].rest)
        assertEquals("Ready when: the onion is soft and see-through.", paragraphs[1].text)
    }

    @Test
    fun `a cue starts Ready when and ignores case`() {
        assertEquals(listOf(ParagraphKind.CUE), kinds("ready WHEN: golden"))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("Ready when golden"))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("Not ready when: golden"))
    }

    @Test
    fun `a rescue starts If and has a colon, with no sentence before it`() {
        assertEquals(listOf(ParagraphKind.RESCUE), kinds("If it smells acrid or looks black: go straight to the next step."))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("If you like, add a pinch of chilli."))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("If it sticks. Then: add water."))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("Eating tonight? Start the rice now: 100 g rice to about 180 ml water."))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds("Iffy results: try again."))
    }

    @Test
    fun `paragraphs split at blank lines, however the blank line is written`() {
        assertEquals(2, RecipeSteps.paragraphs("One.\n\nTwo.").size)
        assertEquals(2, RecipeSteps.paragraphs("One.\n  \nTwo.").size)
        assertEquals(2, RecipeSteps.paragraphs("One.\r\n\r\nTwo.").size)
        assertEquals(2, RecipeSteps.paragraphs("One.\n\n\n\nTwo.").size)
        assertEquals(1, RecipeSteps.paragraphs("One.\nStill one.").size)
        assertEquals(listOf("One.", "Two."), RecipeSteps.splitParagraphs("  One.\n\nTwo.\n\n"))
    }

    @Test
    fun `an empty body has no paragraphs`() {
        assertTrue(RecipeSteps.paragraphs("").isEmpty())
        assertTrue(RecipeSteps.paragraphs("  \n\n ").isEmpty())
    }

    @Test
    fun `the chilli's steps carry cues and rescues`() {
        val chilli = StarterBook.recipe("chilli")
        val byTitle = chilli.method.associateBy { it.title }
        assertEquals(listOf(ParagraphKind.PLAIN, ParagraphKind.CUE), kinds(byTitle.getValue("Brown the mince").body))
        assertEquals(
            listOf(ParagraphKind.PLAIN, ParagraphKind.CUE, ParagraphKind.RESCUE),
            kinds(byTitle.getValue("Veg in").body),
        )
        assertEquals(
            listOf(ParagraphKind.PLAIN, ParagraphKind.CUE, ParagraphKind.RESCUE),
            kinds(byTitle.getValue("Bloom the spices").body),
        )
        assertEquals(
            listOf(ParagraphKind.PLAIN, ParagraphKind.CUE, ParagraphKind.PLAIN),
            kinds(byTitle.getValue("Simmer").body),
            "\"Eating tonight? …\" is a plain paragraph",
        )
        assertEquals(listOf(ParagraphKind.PLAIN, ParagraphKind.CUE), kinds(byTitle.getValue("Beans in").body))
        assertEquals(listOf(ParagraphKind.PLAIN), kinds(byTitle.getValue("Taste and finish").body))
    }
}
