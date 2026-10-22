package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the recipe filters know: food groups, cooking methods and meat-free (docs/PLAN.md §11.11). */
class RecipeFacetsTest {

    private fun guess(name: String, veg: VegKind = VegKind.NONE) = FoodGroups.of(Ingredient(name = name, vegKind = veg))

    @Test
    fun `proteins are guessed from the name`() {
        assertEquals(FoodGroup.CHICKEN, guess("Chicken thigh fillets (boneless, skinless)"))
        assertEquals(FoodGroup.CHICKEN, guess("Chicken sausages"), "chicken before sausage")
        assertEquals(FoodGroup.TURKEY, guess("Turkey mince"))
        assertEquals(FoodGroup.BEEF, guess("Lean beef mince (5% fat)"), "the bracket's \"fat\" is not oil")
        assertEquals(FoodGroup.BEEF, guess("Beef sausages"))
        assertEquals(FoodGroup.BEEF, guess("Mince"))
        assertEquals(FoodGroup.BEEF, guess("Sirloin steak"))
        assertEquals(FoodGroup.PORK, guess("Smoked streaky bacon"))
        assertEquals(FoodGroup.PORK, guess("Cooking chorizo"))
        assertEquals(FoodGroup.LAMB, guess("Lamb leg steaks"), "lamb before steak")
        assertEquals(FoodGroup.PORK, guess("Gammon steaks"), "gammon before steak")
        assertEquals(FoodGroup.PORK, guess("Pork loin steaks"), "pork before steak")
        assertEquals(FoodGroup.PORK, guess("Pepperoni slices"))
        assertEquals(FoodGroup.FISH, guess("Salmon fillets"))
        assertEquals(FoodGroup.FISH, guess("Tuna chunks in spring water"))
        assertEquals(FoodGroup.SEAFOOD, guess("King prawns"))
        assertEquals(FoodGroup.EGGS, guess("Free range eggs"))
        assertEquals(FoodGroup.MEAT_FREE, guess("Firm tofu"))
        assertEquals(FoodGroup.MEAT_FREE, guess("Quorn mince"), "meat-free before mince")
        assertEquals(FoodGroup.MEAT_FREE, guess("Vegetarian sausages"))
        assertEquals(FoodGroup.MEAT_FREE, guess("Meat-free sausages"), "meat-free before sausage")
        assertEquals(FoodGroup.MEAT_FREE, guess("Meat free mince"))
        assertEquals(FoodGroup.MEAT_FREE, guess("Veggie burgers"))
        assertEquals(FoodGroup.BEANS_LENTILS, guess("Red split lentils (dried)", VegKind.PULSE))
        assertEquals(FoodGroup.BEANS_LENTILS, guess("Chickpeas", VegKind.PULSE))
    }

    @Test
    fun `carbs are guessed from the name`() {
        assertEquals(FoodGroup.RICE, guess("Long-grain white rice"))
        assertEquals(FoodGroup.NOODLES, guess("Rice noodles"), "noodles before rice")
        assertEquals(FoodGroup.NOODLES, guess("Egg noodles"), "noodles before eggs")
        assertEquals(FoodGroup.PASTA, guess("Wholewheat spaghetti"))
        assertEquals(FoodGroup.PASTA, guess("Penne"))
        assertEquals(FoodGroup.POTATOES, guess("Baking potatoes"))
        assertEquals(FoodGroup.POTATOES, guess("Sweet potato"))
        assertEquals(FoodGroup.BREAD, guess("Plain tortilla wraps"))
        assertEquals(FoodGroup.BREAD, guess("Wholemeal pitta"))
        assertEquals(FoodGroup.GRAINS, guess("Couscous"))
    }

    @Test
    fun `stocks, sauces, oils and the rest are neither`() {
        for (name in listOf(
            "Chicken stock cubes", "Oyster sauce", "Fish sauce", "Rice vinegar", "Rice wine", "Toasted sesame oil", "Garlic powder",
            "Beef gravy granules", "Cornflour", "Potato starch", "Broccoli", "Fine green beans", "Salt",
        )) {
            assertNull(guess(name), name)
        }
        assertNull(guess("Fine green beans", VegKind.VEG), "green beans are veg, not pulses")
    }

    @Test
    fun `the person's choice wins over the guess`() {
        assertEquals(FoodGroup.PORK, FoodGroups.of(Ingredient(name = "Mince", foodGroup = FoodGroup.PORK)))
        assertNull(FoodGroups.of(Ingredient(name = "Chicken breast", foodGroup = FoodGroup.NONE)))
        assertEquals(FoodGroup.CHICKEN, FoodGroups.guess(Ingredient(name = "Chicken breast", foodGroup = FoodGroup.NONE)))
    }

    @Test
    fun `meat or fish in any form, stock included`() {
        assertTrue(FoodGroups.isAnimal(Ingredient(name = "Chicken stock cubes")))
        assertTrue(FoodGroups.isAnimal(Ingredient(name = "Fish sauce")))
        assertTrue(FoodGroups.isAnimal(Ingredient(name = "Black pudding", shelf = Shelf.MEAT)))
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Vegetable stock cubes")))
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Free range eggs")))
        assertTrue(FoodGroups.isAnimal(Ingredient(name = "Beef meatballs")))
        assertTrue(FoodGroups.isAnimal(Ingredient(name = "Gammon steaks")))
        // Meat-free stays meat-free, whatever its name copies.
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Vegetarian sausages")))
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Meat-free burgers")))
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Quorn chicken-style pieces")))
        assertFalse(FoodGroups.isAnimal(Ingredient(name = "Soya mince", shelf = Shelf.MEAT)))
    }

    @Test
    fun `the starter recipes' facets`() {
        fun facets(key: String) = RecipeFacets.of(StarterBook.recipe(key), StarterBook.ingredientById)
        assertEquals(RecipeFacets(setOf(FoodGroup.CHICKEN), setOf(FoodGroup.RICE), setOf(CookMethod.HOB), meatFree = false), facets("stir-fry"))
        assertEquals(
            RecipeFacets(setOf(FoodGroup.CHICKEN), setOf(FoodGroup.POTATOES), setOf(CookMethod.OVEN), meatFree = false),
            facets("traybake"),
        )
        assertEquals(setOf(FoodGroup.CHICKEN, FoodGroup.PORK), facets("braise").proteins)
        assertEquals(setOf(FoodGroup.BEEF, FoodGroup.BEANS_LENTILS), facets("chilli").proteins)
        assertEquals(setOf(CookMethod.HOB), facets("chilli").methods)
    }

    @Test
    fun `optional lines give no protein and a line without quantity gives none either`() {
        val ingredients = mapOf(
            1L to Ingredient(id = 1, name = "King prawns"),
            2L to Ingredient(id = 2, name = "Rice"),
            3L to Ingredient(id = 3, name = "Firm tofu"),
        )
        val recipe = Recipe(
            title = "Fried rice", style = "Wok",
            lines = listOf(
                RecipeLine(text = "100 g prawns (optional)", ingredientId = 1, quantity = 100.0, optional = true),
                RecipeLine(text = "Rice", ingredientId = 2),
                RecipeLine(text = "200 g tofu", ingredientId = 3, quantity = 200.0),
            ),
        )
        val facets = RecipeFacets.of(recipe, ingredients)
        assertEquals(setOf(FoodGroup.MEAT_FREE), facets.proteins)
        assertTrue(facets.carbs.isEmpty())
        assertFalse(facets.meatFree, "the optional prawns still make it not meat-free")
        assertEquals(setOf(CookMethod.HOB), facets.methods)
    }

    @Test
    fun `methods come from the style first`() {
        fun methods(style: String?, equipment: String = "", body: String = "") = CookMethods.of(
            Recipe(
                title = "T", style = style,
                steps = listOf(RecipeStep(StepKind.EQUIPMENT, body = equipment), RecipeStep(StepKind.STEP, body = body)),
            ),
        )
        assertEquals(setOf(CookMethod.OVEN), methods("Oven · slow, dry heat", body = "Fry the onions in a pan"))
        assertEquals(setOf(CookMethod.HOB), methods("Hob · one pot"))
        assertEquals(setOf(CookMethod.AIR_FRYER), methods("Air fryer · 20 min"), "an air fryer is not a frying pan")
        assertEquals(setOf(CookMethod.SLOW_COOKER), methods("Slow cooker · 8 hours"))
        assertEquals(setOf(CookMethod.NO_COOK), methods("No-cook · 10 minutes"))
        assertEquals(setOf(CookMethod.HOB, CookMethod.OVEN), methods("Hob and oven"))
    }

    @Test
    fun `without a method in the style, the kit and steps decide`() {
        fun methods(equipment: String = "", body: String = "") = CookMethods.of(
            Recipe(title = "T", steps = listOf(RecipeStep(StepKind.EQUIPMENT, body = equipment), RecipeStep(StepKind.STEP, body = body))),
        )
        assertEquals(setOf(CookMethod.OVEN), methods(body = "Heat the oven to 200°C. Roast for 25 min."))
        assertEquals(setOf(CookMethod.OVEN), methods(body = "Bake at 180C fan for 20 minutes"))
        assertEquals(setOf(CookMethod.HOB), methods(equipment = "Large frying pan", body = "Simmer for 10 min"))
        assertEquals(setOf(CookMethod.AIR_FRYER), methods(body = "Air fry at 200 for 12 minutes"))
        assertEquals(setOf(CookMethod.MICROWAVE, CookMethod.HOB), methods(body = "Microwave the rice, then fry the egg"))
        assertEquals(setOf(CookMethod.GRILL), methods(body = "Grill for 4 minutes a side"))
        assertTrue(methods(body = "Mix and serve.").isEmpty())
    }
}
