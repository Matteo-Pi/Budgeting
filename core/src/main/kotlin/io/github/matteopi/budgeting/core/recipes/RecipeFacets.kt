package io.github.matteopi.budgeting.core.recipes

/*
 * What the Recipes tab filters on (docs/PLAN.md §11.11): each recipe's proteins and carbs (from its ingredients'
 * food groups), its cooking methods (from its style, else its kit and steps) and whether it has no meat or fish.
 */

/** Guessing an ingredient's [FoodGroup] from its name, unless the person chose one. */
object FoodGroups {

    private fun stems(vararg words: String): Set<String> = words.map { Quantities.stem(it) }.toSet()

    private val NOT_A_FOOD = stems(
        "stock", "cube", "gravy", "bouillon", "sauce", "paste", "powder", "seasoning", "oil", "vinegar", "wine",
        "flour", "starch", "extract", "granules", "flavouring",
    )
    private val MEAT_FREE = stems("tofu", "tempeh", "quorn", "seitan", "paneer", "halloumi", "vegetarian", "vegan", "plant", "soya")
    private val NOODLES = stems("noodle", "ramen", "udon", "soba", "vermicelli")
    private val RICE = stems("rice", "risotto", "arborio", "basmati", "jasmine")
    private val PASTA = stems(
        "pasta", "spaghetti", "penne", "fusilli", "linguine", "tagliatelle", "macaroni", "lasagne", "lasagna", "rigatoni",
        "farfalle", "orzo", "conchiglie", "gnocchi", "ravioli", "tortellini", "pappardelle", "orecchiette",
    )
    private val POTATOES = stems("potato", "chips", "wedges", "hash")
    private val BREAD = stems(
        "bread", "baguette", "ciabatta", "focaccia", "pitta", "pita", "naan", "bun", "bagel", "tortilla", "wrap",
        "flatbread", "chapati", "toast", "crumpet", "sourdough", "brioche",
    )
    private val GRAINS = stems("couscous", "bulgur", "bulghur", "quinoa", "barley", "freekeh", "polenta", "oats")
    private val FISH = stems(
        "fish", "salmon", "cod", "haddock", "tuna", "mackerel", "trout", "bass", "hake", "pollock", "sardine", "anchovy",
        "plaice", "basa", "tilapia", "kipper", "sole", "monkfish", "coley",
    )
    private val SEAFOOD = stems("prawn", "shrimp", "mussel", "squid", "calamari", "crab", "scallop", "lobster", "clam", "seafood")
    private val CHICKEN = stems("chicken")
    private val TURKEY = stems("turkey")
    private val LAMB = stems("lamb", "mutton")
    private val BEEF = stems("beef", "steak", "brisket")
    private val PORK = stems(
        "pork", "bacon", "ham", "gammon", "sausage", "chorizo", "pancetta", "lardon", "salami", "prosciutto",
    )
    private val MINCE = stems("mince", "meatball", "burger")
    private val EGG = stems("egg")

    /** Words that mean meat or fish in any form ("chicken stock" too), for *No meat or fish*. */
    private val ANIMAL = CHICKEN + TURKEY + LAMB + BEEF + PORK + FISH + SEAFOOD + MINCE

    /** The name's words as stems, without anything in brackets. */
    private fun nameStems(name: String): Set<String> =
        Quantities.words(name.replace(Regex("""\([^)]*\)"""), " ")).map { Quantities.stem(it) }.toSet()

    /**
     * What [ingredient] counts as: the person's choice, else the guess from its name (docs/PLAN.md §11.11). Null
     * for an ingredient that is neither a protein nor a carb.
     */
    fun of(ingredient: Ingredient): FoodGroup? = when (val chosen = ingredient.foodGroup) {
        null -> guess(ingredient)
        FoodGroup.NONE -> null
        else -> chosen
    }

    /** The guess alone, first match wins: not-a-food words, meat-free, carbs, fish, meats, eggs, then pulses. */
    fun guess(ingredient: Ingredient): FoodGroup? {
        val words = nameStems(ingredient.name)
        fun has(set: Set<String>) = words.any { it in set }
        return when {
            has(NOT_A_FOOD) -> null
            has(MEAT_FREE) -> FoodGroup.MEAT_FREE
            has(NOODLES) -> FoodGroup.NOODLES
            has(RICE) -> FoodGroup.RICE
            has(PASTA) -> FoodGroup.PASTA
            has(POTATOES) -> FoodGroup.POTATOES
            has(BREAD) -> FoodGroup.BREAD
            has(GRAINS) -> FoodGroup.GRAINS
            has(SEAFOOD) -> FoodGroup.SEAFOOD
            has(FISH) -> FoodGroup.FISH
            has(CHICKEN) -> FoodGroup.CHICKEN
            has(TURKEY) -> FoodGroup.TURKEY
            has(LAMB) -> FoodGroup.LAMB
            has(BEEF) -> FoodGroup.BEEF
            has(PORK) -> FoodGroup.PORK
            has(MINCE) -> FoodGroup.BEEF
            has(EGG) -> FoodGroup.EGGS
            ingredient.vegKind == VegKind.PULSE -> FoodGroup.BEANS_LENTILS
            else -> null
        }
    }

    /** True when [ingredient] is meat or fish in any form: its shelf, its group or a word of its name. */
    fun isAnimal(ingredient: Ingredient): Boolean =
        ingredient.shelf == Shelf.MEAT || of(ingredient)?.isMeatOrFish == true || nameStems(ingredient.name).any { it in ANIMAL }
}

/** How a recipe is cooked. */
enum class CookMethod(val label: String) {
    HOB("Hob"),
    OVEN("Oven"),
    AIR_FRYER("Air fryer"),
    SLOW_COOKER("Slow cooker"),
    PRESSURE_COOKER("Pressure cooker"),
    MICROWAVE("Microwave"),
    GRILL("Grill"),
    NO_COOK("No cook"),
}

/** Finding a recipe's [CookMethod]s: from its style line first, else from its kit and steps (docs/PLAN.md §11.11). */
object CookMethods {

    private val NAMED: List<Pair<CookMethod, Regex>> = listOf(
        CookMethod.AIR_FRYER to Regex("""\bair[\s-]?fr(y|yer|ied|ying)\b"""),
        CookMethod.SLOW_COOKER to Regex("""\b(slow[\s-]?cooker|crock[\s-]?pot)\b"""),
        CookMethod.PRESSURE_COOKER to Regex("""\b(pressure[\s-]?cooker|instant[\s-]?pot|instapot)\b"""),
        CookMethod.MICROWAVE to Regex("""\bmicrowav"""),
        CookMethod.GRILL to Regex("""\bgrill(s|ed|ing)?\b"""),
        CookMethod.NO_COOK to Regex("""\bno[\s-]?(cook|cooking|heat)\b"""),
    )
    private val STYLE_OVEN = Regex("""\b(oven|traybake|tray[\s-]?bake|roast(ed|ing)?|bake[sd]?)\b""")
    private val STYLE_HOB = Regex("""\b(hob|stove|stovetop|pan|wok|one[\s-]pot|stir[\s-]?fry)\b""")
    private val TEXT_OVEN = Regex(
        """\b(oven|traybake|tray[\s-]?bake|roast|roasting|bake|bakes|gas mark)\b|°\s?c\b|\b(1\d\d|2[0-5]\d)\s?c\b""",
    )
    private val TEXT_HOB = Regex(
        """\b(hob|stove|frying pan|saucepan|pan|wok|simmer\w*|boil\w*|sauté\w*|saute\w*|sear\w*|stir[\s-]?fr\w*)\b|(?<!air[\s-])\bfr(y|ying|ied)\b""",
    )

    private fun found(text: String, oven: Regex, hob: Regex): Set<CookMethod> {
        val t = text.lowercase()
        val methods = LinkedHashSet<CookMethod>()
        for ((method, pattern) in NAMED) if (pattern.containsMatchIn(t)) methods += method
        // "Air fryer" is not a frying pan.
        val withoutAirFryer = NAMED.first().second.replace(t, " ")
        if (oven.containsMatchIn(withoutAirFryer)) methods += CookMethod.OVEN
        if (hob.containsMatchIn(withoutAirFryer)) methods += CookMethod.HOB
        return methods
    }

    /** The methods [recipe] uses, in [CookMethod] order. */
    fun of(recipe: Recipe): Set<CookMethod> {
        val fromStyle = recipe.style?.let { found(it, STYLE_OVEN, STYLE_HOB) }.orEmpty()
        val methods = fromStyle.ifEmpty {
            val text = (recipe.equipment + recipe.method).joinToString("\n") { listOfNotNull(it.title, it.body).joinToString(" ") }
            found(text, TEXT_OVEN, TEXT_HOB)
        }
        return CookMethod.entries.filter { it in methods }.toSet()
    }
}

/** What the filters know about one recipe. */
data class RecipeFacets(
    val proteins: Set<FoodGroup>,
    val carbs: Set<FoodGroup>,
    val methods: Set<CookMethod>,
    /** No line is meat or fish in any form ([FoodGroups.isAnimal]). */
    val meatFree: Boolean,
) {
    companion object {
        /**
         * [recipe]'s facets. Proteins and carbs come from its counted, non-optional lines (a line pointing at one of
         * [ingredients] with a quantity); *No meat or fish* looks at every line with an ingredient, optional ones too.
         */
        fun of(recipe: Recipe, ingredients: Map<Long, Ingredient>): RecipeFacets {
            val proteins = LinkedHashSet<FoodGroup>()
            val carbs = LinkedHashSet<FoodGroup>()
            var meatFree = true
            for (line in recipe.lines) {
                val ingredient = line.ingredientId?.let { ingredients[it] } ?: continue
                if (FoodGroups.isAnimal(ingredient)) meatFree = false
                if (line.optional || line.quantity == null) continue
                val group = FoodGroups.of(ingredient) ?: continue
                when (group.kind) {
                    FoodGroupKind.PROTEIN -> proteins += group
                    FoodGroupKind.CARB -> carbs += group
                    null -> Unit
                }
            }
            return RecipeFacets(
                proteins = FoodGroup.entries.filter { it in proteins }.toSet(),
                carbs = FoodGroup.entries.filter { it in carbs }.toSet(),
                methods = CookMethods.of(recipe),
                meatFree = meatFree,
            )
        }
    }
}
