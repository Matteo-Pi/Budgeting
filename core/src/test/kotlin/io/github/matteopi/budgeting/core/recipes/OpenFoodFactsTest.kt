package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mapping saved Open Food Facts answers to drafts, and the request details (docs/PLAN.md §11.5). */
class OpenFoodFactsTest {

    private val tomatoes = """
        {"code":"5000436589458","product":{"brands":"Tesco","product_name":"Chopped Tomatoes","quantity":"400 g",
        "product_quantity":"400","product_quantity_unit":"g","nutriments":{"energy-kcal_100g":21,"energy_100g":88,
        "fat_100g":0.1,"saturated-fat_100g":0,"carbohydrates_100g":3.5,"sugars_100g":3.4,"fiber_100g":0.9,
        "proteins_100g":1.1,"salt_100g":0.03}},"status":1,"status_verbose":"product found"}
    """.trimIndent()

    @Test
    fun aFoundProductBecomesADraft() {
        val result = OpenFoodFacts.toResult(200, tomatoes, "5000436589458")
        val draft = assertIs<OffResult.Found>(result).draft
        assertEquals("Tesco Chopped Tomatoes", draft.name)
        assertEquals(400.0, draft.packSize)
        assertEquals(BaseUnit.G, draft.unit)
        assertEquals("400 g", draft.quantityText)
        assertEquals(21.0, draft.per100.kcal)
        assertEquals(3.5, draft.per100.carbs)
        assertEquals(0.9, draft.per100.fibre)
        assertEquals(0.03, draft.per100.salt)
    }

    @Test
    fun missingValuesAreUnknownAndEnergyAndSodiumAreConverted() {
        val body = """
            {"status":1,"product":{"product_name":"Oat drink","quantity":"1 l",
            "nutriments":{"energy_100g":"192","sodium_100g":0.04,"sugars_100g":"4,0"}}}
        """.trimIndent()
        val draft = assertIs<OffResult.Found>(OpenFoodFacts.toResult(200, body, "12345670")).draft
        assertEquals("Oat drink", draft.name)
        assertEquals(1000.0, draft.packSize)
        assertEquals(BaseUnit.ML, draft.unit)
        assertEquals(192.0 / 4.184, draft.per100.kcal!!, 1e-9)
        assertEquals(0.1, draft.per100.salt!!, 1e-9)
        assertEquals(4.0, draft.per100.sugars)
        assertNull(draft.per100.fat)
        assertNull(draft.per100.protein)
    }

    @Test
    fun aNameAlreadyStartingWithTheBrandIsNotDoubled() {
        val body = """{"status":1,"product":{"brands":"Heinz, Kraft Heinz","product_name":"Heinz Baked Beans","quantity":"4 x 415 g"}}"""
        val draft = assertIs<OffResult.Found>(OpenFoodFacts.toResult(200, body, "5000157024671")).draft
        assertEquals("Heinz Baked Beans", draft.name)
        assertEquals(1660.0, draft.packSize)
        assertEquals(BaseUnit.G, draft.unit)
        assertTrue(draft.per100.kcal == null)
    }

    @Test
    fun notFoundAndBrokenAnswers() {
        assertEquals(OffResult.NotFound, OpenFoodFacts.toResult(404, """{"status":0,"status_verbose":"product not found"}""", "1"))
        assertEquals(OffResult.NotFound, OpenFoodFacts.toResult(200, """{"status":0,"status_verbose":"product not found"}""", "1"))
        assertIs<OffResult.Invalid>(OpenFoodFacts.toResult(200, "<html>", "1"))
        assertIs<OffResult.Invalid>(OpenFoodFacts.toResult(503, "", "1"))
    }

    @Test
    fun packSizesFromTheNumberOrTheText() {
        assertEquals(500.0 to BaseUnit.G, OpenFoodFacts.pack(0.5, "kg", null))
        assertEquals(330.0 to BaseUnit.ML, OpenFoodFacts.pack(33.0, "cl", null))
        assertEquals(1500.0 to BaseUnit.ML, OpenFoodFacts.pack(null, null, "1.5 L"))
        assertEquals(240.0 to BaseUnit.G, OpenFoodFacts.pack(null, null, "Drained weight 240g"))
        assertEquals(null to null, OpenFoodFacts.pack(null, null, "6 eggs"))
    }

    @Test
    fun barcodesAndTheRequest() {
        assertEquals("5000436589458", OpenFoodFacts.normaliseBarcode("5 000436 589458"))
        assertEquals("0036000291452", OpenFoodFacts.normaliseBarcode("036000291452"))
        assertEquals("96385074", OpenFoodFacts.normaliseBarcode("96385074"))
        assertNull(OpenFoodFacts.normaliseBarcode("12345"))
        assertEquals(
            "https://world.openfoodfacts.org/api/v2/product/5000436589458?fields=${OpenFoodFacts.FIELDS}",
            OpenFoodFacts.productUrl("5000436589458"),
        )
        assertEquals("Budgeting/0.1.0 (build 60) (https://github.com/matteo-pi/budgeting)", OpenFoodFacts.userAgent("0.1.0 (build 60)"))
    }
}
