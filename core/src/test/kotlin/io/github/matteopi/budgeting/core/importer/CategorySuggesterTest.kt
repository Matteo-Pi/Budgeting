package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.MerchantRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CategorySuggesterTest {

    private val groceries = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val eatingOut = Category(id = 2, name = "Eating out", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val transport = Category(id = 3, name = "Transport", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
    private val categories = listOf(groceries, eatingOut, transport)

    @Test
    fun `an exact merchant rule wins over keywords`() {
        val rule = MerchantRule(merchantKey = "TESCO STORES", categoryId = transport.id)
        val result = CategorySuggester.suggest("TESCO STORES", listOf(rule), categories)
        assertEquals(transport.id, result)
    }

    @Test
    fun `keyword match on groceries`() {
        assertEquals(groceries.id, CategorySuggester.suggest("TESCO STORES", emptyList(), categories))
        assertEquals(groceries.id, CategorySuggester.suggest("SAINSBURYS LOCAL", emptyList(), categories))
    }

    @Test
    fun `UBER EATS goes to Eating out, not Transport`() {
        assertEquals(eatingOut.id, CategorySuggester.suggest("UBER EATS", emptyList(), categories))
    }

    @Test
    fun `plain UBER goes to Transport`() {
        assertEquals(transport.id, CategorySuggester.suggest("UBER", emptyList(), categories))
        assertEquals(transport.id, CategorySuggester.suggest("UBER *TRIP HELP.UBER.COM", emptyList(), categories))
    }

    @Test
    fun `unknown merchant returns null`() {
        assertNull(CategorySuggester.suggest("SOME RANDOM SHOP", emptyList(), categories))
    }

    @Test
    fun `skips a keyword category that does not exist in the user's categories`() {
        assertNull(CategorySuggester.suggest("TESCO STORES", emptyList(), listOf(transport)))
    }

    @Test
    fun `skips an archived category`() {
        val archivedGroceries = groceries.copy(archived = true)
        assertNull(CategorySuggester.suggest("TESCO STORES", emptyList(), listOf(archivedGroceries)))
    }
}
