package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundleMergeTest {

    private val models = StarterBook.models

    /** The starter ingredients as if they were already saved: new ids, nothing else changed. */
    private val saved: List<Ingredient> = models.ingredients.map { it.copy(id = it.id + 1_000) }

    @Test
    fun `nothing matches in an empty cupboard`() {
        assertTrue(BundleMerge.matches(models, emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `the forty starter ingredients have forty different name keys`() {
        val keys = models.ingredients.map { IngredientMatching.nameKey(it.name) }
        assertEquals(40, keys.toSet().size, "name keys: $keys")
        assertTrue(keys.none { it.isBlank() })
    }

    @Test
    fun `importing the starter book again matches every ingredient to its own copy`() {
        val merges = BundleMerge.matches(models, saved, emptyMap())
        assertEquals(40, merges.size)
        for (merge in merges) {
            assertEquals(merge.bundle.id + 1_000, merge.existing.id, "${merge.key} (${merge.bundle.name}) matched ${merge.existing.name}")
            assertEquals(models.ingredientKeys.getValue(merge.key), merge.bundle.id)
        }
    }

    @Test
    fun `learned aliases of the starter names do not change who matches whom`() {
        val aliases = saved.associate { IngredientMatching.nameKey(it.name) to it.id }
        val merges = BundleMerge.matches(models, saved, aliases)
        assertEquals(40, merges.size)
        assertTrue(merges.all { it.existing.id == it.bundle.id + 1_000 })
    }

    private fun bundleOf(vararg names: String): BundleModels {
        val ingredients = names.mapIndexed { index, name ->
            """{"key": "k$index", "name": "$name", "unit": "G", "pack": {"size": 100}}"""
        }.joinToString(",")
        val text = """{"format": "budgeting-recipes", "version": 1, "ingredients": [$ingredients], "recipes": []}"""
        return assertIs<BundleDecodeResult.Success>(RecipeBundle.decode(text)).bundle.toModels()
    }

    @Test
    fun `a learned alias matches a differently named ingredient`() {
        val bundle = bundleOf("Cooking oil", "Salt")
        val existing = listOf(Ingredient(id = 7, name = "Rapeseed oil"), Ingredient(id = 8, name = "Sea salt"))
        assertTrue(BundleMerge.matches(bundle, existing, emptyMap()).isEmpty())

        val aliases = mapOf(IngredientMatching.nameKey("Cooking oil") to 7L)
        val merges = BundleMerge.matches(bundle, existing, aliases)
        assertEquals(listOf("k0"), merges.map { it.key })
        assertEquals(7L, merges.single().existing.id)
    }

    @Test
    fun `the same name in other words and case matches`() {
        val bundle = bundleOf("CHICKEN breast fillets", "Chopped Tomatoes")
        val existing = listOf(
            Ingredient(id = 1, name = "Chicken breast"),
            Ingredient(id = 2, name = "Tomatoes (chopped)"),
        )
        val merges = BundleMerge.matches(bundle, existing, emptyMap())
        assertEquals(mapOf("k0" to 1L, "k1" to 2L), merges.associate { it.key to it.existing.id })
    }

    @Test
    fun `a close name is not a match and neither is an archived ingredient`() {
        val bundle = bundleOf("Onion", "Garlic")
        val existing = listOf(
            Ingredient(id = 1, name = "Red onion"),
            Ingredient(id = 2, name = "Garlic", archived = true),
        )
        assertTrue(BundleMerge.matches(bundle, existing, emptyMap()).isEmpty())
    }

    @Test
    fun `an alias pointing at an archived ingredient is not a match`() {
        val bundle = bundleOf("Cooking oil")
        val existing = listOf(Ingredient(id = 7, name = "Rapeseed oil", archived = true))
        val aliases = mapOf(IngredientMatching.nameKey("Cooking oil") to 7L)
        assertTrue(BundleMerge.matches(bundle, existing, aliases).isEmpty())
    }
}
