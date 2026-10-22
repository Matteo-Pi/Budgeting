package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built-in food list (docs/PLAN.md §11.12). */
class FoodDatabaseTest {

    private val foods = FoodDatabase.all()
    private val sources = FoodDatabase.bundle.ingredients.associateBy { it.key }

    private fun matched(line: String): String? = FoodDatabase.match(IngredientLines.parse(line).name, line)?.key

    @Test
    fun `it decodes with about 550 foods and no recipes`() {
        assertTrue(foods.size in 520..600, "${foods.size} foods")
        assertTrue(FoodDatabase.bundle.recipes.isEmpty())
        assertEquals(foods.size, foods.map { it.key }.toSet().size, "keys are unique")
        assertEquals(foods.size, foods.map { it.name.lowercase() }.toSet().size, "names are unique")
    }

    @Test
    fun `every food has a pack, a dated Tesco price and all eight label values`() {
        for (food in foods) {
            val i = food.ingredient
            assertTrue(i.packSize > 0 && i.packLabel.isNotBlank(), "${food.key} pack")
            assertNotNull(i.price, "${food.key} price")
            assertTrue(i.price!!.pence > 0, "${food.key} price")
            assertNotNull(i.priceDate, "${food.key} date")
            assertTrue(i.priceDate!! in LocalDate.of(2026, 10, 15)..FoodDatabase.PRICES_FOUND, "${food.key} date")
            assertEquals("Tesco", i.store, food.key)
            assertTrue(!i.productName.isNullOrBlank(), "${food.key} product")
            assertFalse(i.per100.hasUnknown, "${food.key} nutrition")
            assertNotNull(i.per100.addedSugars, "${food.key} added sugars")
            assertEquals(0L, i.id, food.key)
            assertTrue(i.note.isNotBlank(), "${food.key} note")
        }
    }

    @Test
    fun `the foods this list adds are typical values for the food as weighed`() {
        val starter = RecipeBundle.starter().ingredients.map { it.key }.toSet()
        val added = foods.filter { it.key !in starter }
        assertTrue(added.size >= 480, "${added.size} added")
        for (food in added) {
            assertEquals(NutritionSource.ESTIMATE, food.ingredient.nutritionSource, food.key)
            assertEquals(FoodDatabase.PRICES_FOUND, food.ingredient.priceDate, food.key)
        }
    }

    @Test
    fun `no part is more than its whole`() {
        for (food in foods) {
            val n = food.ingredient.per100
            assertTrue(n.saturates!! <= n.fat!!, "${food.key} saturates")
            assertTrue(n.sugars!! <= n.carbs!!, "${food.key} sugars")
            assertTrue(n.addedSugars!! <= n.sugars!!, "${food.key} added sugars")
            assertTrue(n.salt!! <= 100.0, "${food.key} salt")
            val grams = n.fat!! + n.carbs!! + n.fibre!! + n.protein!! + n.salt!!
            assertTrue(grams <= 101.0, "${food.key}: $grams g in 100")
        }
    }

    /** Vinegars' energy is acetic acid and vanilla extract's and rice wine's alcohol, which labels count at 3.6 and 7 kcal/g and these factors don't. */
    private val acidAndAlcohol = setOf(
        "vinegar_white_wine", "red_wine_vinegar", "cider_vinegar", "malt_vinegar", "rice_vinegar", "vanilla_extract", "shaoxing",
    )

    @Test
    fun `energy agrees with the fat, carbs, protein and fibre`() {
        for (food in foods) {
            if (food.key in acidAndAlcohol) continue
            val n = food.ingredient.per100
            val worked = 4 * n.carbs!! + 4 * n.protein!! + 9 * n.fat!! + 2 * n.fibre!!
            assertTrue(abs(worked - n.kcal!!) <= 0.15 * n.kcal!! + 10, "${food.key}: ${n.kcal} kcal, worked out $worked")
        }
    }

    @Test
    fun `sugar, honey, jam and sweet sauces are the foods with added sugars`() {
        val sweet = foods.filter { it.ingredient.per100.addedSugars!! > 0 }.map { it.key }.toSet()
        for (key in listOf(
            "sugar_granulated", "sugar_brown", "honey", "jam", "ketchup", "sweet_chilli", "hoisin", "baked_beans", "caster_sugar",
            "icing_sugar", "golden_syrup", "maple_syrup", "condensed_milk", "dark_chocolate", "sweet_sour_sauce",
        )) {
            assertTrue(key in sweet, key)
        }
        for (key in listOf(
            "milk_semi", "apples", "bananas", "sultanas", "rice", "oat_drink", "chickpeas", "dates", "dried_apricots", "peaches_tin",
            "evaporated_milk", "milk_skimmed",
        )) {
            assertFalse(key in sweet, key)
        }
    }

    @Test
    fun `the starter foods are the starter book's`() {
        for (starter in RecipeBundle.starter().ingredients) {
            val food = assertNotNull(sources[starter.key], starter.key)
            assertEquals(starter, food.copy(aliases = emptyList()), starter.key)
        }
    }

    @Test
    fun `files the app writes have no other names`() {
        assertFalse(RecipeBundle.encode(RecipeBundle.starter()).contains("aliases"))
        assertTrue(RecipeBundle.encode(FoodDatabase.bundle).contains("aliases"))
    }

    @Test
    fun `names and other names belong to one food`() {
        // Each name and other name, with its describing words ("chopped") but not its bracketed ones, is one food's of
        // those weighed in grams and one of those in millilitres: "chicken stock" is the cubes and the stock made with one.
        val owner = HashMap<String, String>()
        for (source in FoodDatabase.bundle.ingredients) {
            for (name in (listOf(source.name) + source.aliases).map { FoodDatabase.words(it).sorted().joinToString(" ") }.toSet()) {
                val key = "${source.unit} $name"
                val before = owner.put(key, source.key)
                assertTrue(before == null, "\"$key\" is a name of both $before and ${source.key}")
            }
        }
        // "Chopped tomatoes (tin)" and "Tomatoes (round)" are both "tomato" to the matching; the line's own words decide.
        assertEquals("tomatoes", matched("4 tomatoes"))
        assertEquals("tomatoes", matched("2 ripe tomatoes"))
        assertEquals("tomatoes_chopped", matched("400 g chopped tomatoes"))
        assertEquals("tomatoes_chopped", matched("1 tin of tomatoes"))
    }

    @Test
    fun `pulses are pulses and counted lines have portions`() {
        for (key in listOf(
            "chickpeas", "black_beans", "butter_beans", "cannellini", "green_lentils", "kidney_beans", "red_lentils", "mixed_beans",
            "green_lentils_tin", "chickpeas_dried", "split_peas", "edamame", "mushy_peas",
        )) {
            assertEquals(VegKind.PULSE, FoodDatabase.byKey(key)!!.ingredient.vegKind, key)
        }
        assertEquals(240.0, Quantities.portion(FoodDatabase.byKey("chickpeas")!!.ingredient, "tins")?.amount)
        assertEquals(50.0, Quantities.portion(FoodDatabase.byKey("eggs")!!.ingredient, "each")?.amount)
        assertEquals(10.0, Quantities.portion(FoodDatabase.byKey("butter")!!.ingredient, "knob")?.amount)
        assertEquals(VegKind.NONE, FoodDatabase.byKey("potatoes_white")!!.ingredient.vegKind, "potatoes don't count")
        assertEquals(FoodGroup.NONE, FoodDatabase.byKey("oat_drink")!!.ingredient.foodGroup, "oat drink isn't a grain")
        assertNull(FoodGroups.of(FoodDatabase.byKey("oat_drink")!!.ingredient))
        assertEquals(FoodGroup.NOODLES, FoodGroups.of(FoodDatabase.byKey("egg_noodles")!!.ingredient))
        assertEquals(FoodGroup.BEANS_LENTILS, FoodGroups.of(FoodDatabase.byKey("chickpeas")!!.ingredient))
        assertNull(FoodGroups.of(FoodDatabase.byKey("stock_cube_beef")!!.ingredient))
    }

    @Test
    fun `each food counts as the protein or carb it is`() {
        fun group(key: String) = FoodGroups.of(FoodDatabase.byKey(key)!!.ingredient)
        fun animal(key: String) = FoodGroups.isAnimal(FoodDatabase.byKey(key)!!.ingredient)
        val expected = mapOf(
            "gammon_steaks" to FoodGroup.PORK, "pork_loin_steaks" to FoodGroup.PORK, "rump_steak" to FoodGroup.BEEF,
            "turkey_steaks" to FoodGroup.TURKEY, "pepperoni" to FoodGroup.PORK, "chipolatas" to FoodGroup.PORK,
            "beef_meatballs" to FoodGroup.BEEF, "turkey_meatballs" to FoodGroup.TURKEY, "sardines_tin" to FoodGroup.FISH,
            "prawns_raw" to FoodGroup.SEAFOOD, "tortelloni" to FoodGroup.PASTA, "fresh_pasta" to FoodGroup.PASTA,
            "rice_arborio" to FoodGroup.RICE, "udon" to FoodGroup.NOODLES, "english_muffins" to FoodGroup.BREAD,
            "brioche_buns" to FoodGroup.BREAD, "hash_browns" to FoodGroup.POTATOES, "bulgur" to FoodGroup.GRAINS,
            "quorn_pieces" to FoodGroup.MEAT_FREE, "veggie_sausages" to FoodGroup.MEAT_FREE, "veggie_burgers" to FoodGroup.MEAT_FREE,
            "plant_mince" to FoodGroup.MEAT_FREE, "falafel" to FoodGroup.MEAT_FREE, "edamame" to FoodGroup.BEANS_LENTILS,
        )
        for ((key, group) in expected) assertEquals(group, group(key), key)
        for (key in listOf("tortilla_chips", "soya_drink", "pasta_sauce", "chicken_stock", "stock_pot_chicken", "flour_bread", "breadcrumbs")) {
            assertNull(group(key), key)
        }
        for (key in listOf("chicken_stock", "beef_stock", "stock_pot_chicken", "anchovies", "lardons", "beef_burgers", "chicken_nuggets")) {
            assertTrue(animal(key), key)
        }
        for (key in listOf("veg_stock", "quorn_pieces", "veggie_sausages", "veggie_burgers", "plant_mince", "brioche_buns", "hot_dog_rolls")) {
            assertFalse(animal(key), key)
        }
    }

    @Test
    fun `recipe lines find their food`() {
        assertEquals("chickpeas", matched("2 × 400 g tins chickpeas"))
        assertEquals("red_onions", matched("1 red onion, finely chopped"))
        assertEquals("penne", matched("200 g penne"))
        assertEquals("butter", matched("a knob of butter"))
        assertEquals("chickpeas", matched("1 tin garbanzo beans"))
        assertEquals("onion", matched("2 onions"))
        assertEquals("eggs", matched("3 large eggs"))
        assertEquals("tomatoes_chopped", matched("400 g tin chopped tomatoes"))
        assertEquals("veg_oil", matched("1 tbsp oil"))
        assertEquals("veg_stock", matched("500 ml vegetable stock"))
        assertEquals("black_pepper", matched("black pepper"))
        assertEquals("greek_yogurt", matched("150 g Greek yoghurt"))
        assertEquals("yogurt_greek_0", matched("150 g fat free Greek yogurt"))
        assertEquals("creme_fraiche", matched("2 tbsp crème fraîche"))
        assertEquals("coriander_fresh", matched("a handful of fresh coriander"))
    }

    @Test
    fun `stock is the liquid, the cubes or the pots as the line says`() {
        assertEquals("chicken_stock", matched("300 ml chicken stock (1 cube)"))
        assertEquals("chicken_stock", matched("chicken stock"))
        assertEquals("stock_cube", matched("1 chicken stock cube"))
        assertEquals("stock_cube", matched("2 stock cubes"))
        assertEquals("veg_stock", matched("1 litre vegetable stock"))
        assertEquals("stock_cube_veg", matched("1 vegetable stock cube"))
        assertEquals("beef_stock", matched("500 ml beef stock"))
        assertEquals("stock_cube_beef", matched("1 beef stock cube"))
        assertEquals("stock_pot_chicken", matched("1 chicken stock pot"))
        assertEquals("fish_stock", matched("500 ml fish stock"))
        assertEquals("stock_cube_fish", matched("1 fish stock cube"))
        assertEquals("lamb_stock", matched("300 ml lamb stock"))
        assertEquals("stock_pot_beef", matched("1 beef stock pot"))
        assertEquals("bouillon", matched("1 tsp vegetable bouillon powder"))
        // A litre of stock costs a cube's share, not a kilo of cubes.
        val stock = FoodDatabase.byKey("chicken_stock")!!.ingredient
        assertEquals(BaseUnit.ML, stock.unit)
        assertEquals(Money(110), stock.price)
        assertEquals(5000.0, stock.packSize)
        assertEquals(0.84, stock.per100.salt!!, 1e-9)
    }

    @Test
    fun `cuts, formats and kinds find their own food`() {
        val lines = mapOf(
            "500 g beef mince" to "beef_mince_20",
            "250 g steak mince" to "beef_mince_15",
            "500 g lean beef mince" to "beef_mince_5",
            "500 g chicken mince" to "chicken_mince",
            "500 g minced chicken" to "chicken_mince",
            "12 beef meatballs" to "beef_meatballs",
            "4 pork chops" to "pork_chops",
            "2 gammon steaks" to "gammon_steaks",
            "8 chipolatas" to "chipolatas",
            "500 g lamb" to "lamb_diced",
            "2 chicken breasts" to "chicken_breast",
            "500 g diced chicken breast" to "chicken_pieces",
            "4 chicken thighs" to "chicken_thigh",
            "6 bone-in chicken thighs" to "chicken_thighs_bone",
            "200 g raw king prawns" to "prawns_raw",
            "200 g king prawns" to "prawns_cooked",
            "1 tin salmon" to "salmon_tin",
            "2 salmon fillets" to "salmon_fillets",
            "100 g smoked salmon" to "smoked_salmon",
            "2 sprigs of thyme" to "thyme_fresh",
            "1 tsp thyme" to "thyme",
            "1 sprig rosemary" to "rosemary_fresh",
            "100 g grated mozzarella" to "mozzarella_grated",
            "1 ball of mozzarella" to "mozzarella",
            "300 g fresh egg noodles" to "noodles_ready",
            "200 g egg noodles" to "egg_noodles",
            "250 g fresh tagliatelle" to "fresh_pasta",
            "250 g tagliatelle" to "tagliatelle",
            "200 g macaroni" to "macaroni",
            "200 g pasta" to "penne",
            "1 tin green lentils" to "green_lentils_tin",
            "100 g green lentils" to "green_lentils",
            "200 g dried chickpeas" to "chickpeas_dried",
            "1 tin of peaches" to "peaches_tin",
            "1 sachet fast-action yeast" to "yeast",
            "50 g sliced almonds" to "flaked_almonds",
            "50 g ground almonds" to "ground_almonds",
            "250 g cooked rice" to "rice_microwave",
            "200 g risotto rice" to "rice_arborio",
            "2 tbsp sun-dried tomatoes" to "sun_dried_tomatoes",
            "1 red cabbage" to "red_cabbage",
            "1 cabbage" to "white_cabbage",
            "500 g new potatoes" to "new_potatoes",
            "1 kg potatoes" to "potatoes_white",
            "100 g caster sugar" to "caster_sugar",
            "1 tbsp sugar" to "sugar_granulated",
            "1 tin coconut milk" to "coconut_milk",
            "1 tin light coconut milk" to "coconut_milk_light",
            "100 ml soured cream" to "sour_cream",
            "200 g houmous" to "houmous",
            "a handful of rocket" to "rocket",
            "1 tbsp hummus" to "houmous",
            "1 tsp paprika" to "paprika",
            "1 tsp smoked paprika" to "smoked_paprika",
            "2 tbsp fish sauce" to "fish_sauce",
            "1 tbsp dark soy sauce" to "soy_dark",
            "2 tbsp soy sauce" to "soy_light",
            "1 tbsp red wine vinegar" to "red_wine_vinegar",
            "1 tbsp white wine vinegar" to "vinegar_white_wine",
            "1 tbsp wholegrain mustard" to "mustard_wholegrain",
            "1 tsp dijon mustard" to "dijon",
            "1 tsp ground ginger" to "ginger_ground",
            "1 tbsp grated ginger" to "ginger",
            "1 tsp cumin seeds" to "cumin_seeds",
            "1 tsp ground cumin" to "cumin",
            "200 g cherries" to "cherries",
            "250 g cherry tomatoes" to "cherry_tomatoes",
            "1 tin cherry tomatoes" to "cherry_tomatoes_tin",
            "2 tbsp miso paste" to "miso",
            "1 tbsp gochujang" to "gochujang",
            "1 jar korma sauce" to "korma_sauce",
            "1 jar bolognese sauce" to "bolognese_sauce",
            "500 g tomato pasta sauce" to "pasta_sauce",
            "8 sprays of spray oil" to "spray_oil",
            "2 tbsp tahini" to "tahini",
            "2 chicken kievs" to "chicken_kievs",
            "100 g chicken livers" to "chicken_livers",
            "6 slices of prosciutto" to "prosciutto",
            "200 g squid rings" to "squid_rings",
            "1 tin pinto beans" to "pinto_beans",
            "400 ml coconut cream" to "coconut_cream",
            "150 ml whipping cream" to "whipping_cream",
            "1 tbsp half fat creme fraiche" to "creme_fraiche_light",
            "100 g smoked tofu" to "tofu_smoked",
            "200 g firm tofu" to "tofu",
            "1 litre orange juice" to "orange_juice",
        )
        val wrong = lines.filter { (line, key) -> matched(line) != key }.map { (line, key) -> "$line: ${matched(line)}, not $key" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("; "))
    }

    @Test
    fun `a food it doesn't have is no match`() {
        assertNull(matched("1 dragon fruit"))
        assertNull(matched("200 g venison"))
        // A close name for something else: the line hasn't the food's last word.
        assertNull(matched("300 ml red wine"), "not red wine vinegar")
        assertNull(matched("200 g lychees"), "not cherry tomatoes or cherries")
        assertNull(matched("2 lamb kidneys"), "not kidney beans")
        assertNull(matched("300 ml water"), "nothing is called water")
        assertNull(FoodDatabase.match(""))
    }

    @Test
    fun `search starts with the names that start with the text`() {
        assertEquals("chickpeas", FoodDatabase.search("chickpea").first().key)
        assertTrue(FoodDatabase.search("chick").take(8).all { it.name.startsWith("Chick") })
        assertEquals("chickpeas", FoodDatabase.search("garbanzo").first().key)
        assertEquals("creme_fraiche", FoodDatabase.search("creme").first().key)
        assertTrue(FoodDatabase.search("rice").take(4).all { it.ingredient.name.contains("rice", ignoreCase = true) })
        assertTrue(FoodDatabase.search("zzz").isEmpty())
        assertEquals(foods.size, FoodDatabase.search("  ").size)
    }

    @Test
    fun `a food becomes the user's ingredient with its price, nutrition and portions`() {
        val chickpeas = FoodDatabase.byKey("chickpeas")!!.toIngredient(nowMillis = 1234L)
        assertEquals(0L, chickpeas.id)
        assertEquals(1234L, chickpeas.createdAtEpochMillis)
        assertEquals("Chickpeas (tin, drained)", chickpeas.name)
        assertEquals(Money(41), chickpeas.price)
        assertEquals(240.0, chickpeas.packSize)
        assertEquals(121.0, chickpeas.per100.kcal)
        assertEquals(listOf(Portion("tin", 240.0)), chickpeas.portions)
        assertEquals(Shelf.CUPBOARD, chickpeas.shelf)
    }
}
