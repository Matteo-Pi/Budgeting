package io.github.matteopi.budgeting.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSectionTest {
    @Test
    fun `every section parses from its name`() {
        AppSection.entries.forEach { assertEquals(it, AppSection.parse(it.name)) }
    }

    @Test
    fun `missing or unknown text reads as Budgeting`() {
        assertEquals(AppSection.BUDGETING, AppSection.parse(null))
        assertEquals(AppSection.BUDGETING, AppSection.parse(""))
        assertEquals(AppSection.BUDGETING, AppSection.parse("COOKING"))
        assertEquals(AppSection.BUDGETING, AppSection.parse("recipes"))
    }

    @Test
    fun `there are three sections, Budgeting first`() {
        assertEquals(listOf(AppSection.BUDGETING, AppSection.RECIPES, AppSection.PLANNER), AppSection.entries)
        assertEquals(AppSection.BUDGETING, AppSection.DEFAULT)
    }
}
