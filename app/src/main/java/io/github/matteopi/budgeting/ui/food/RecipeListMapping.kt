package io.github.matteopi.budgeting.ui.food

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.CookMethod
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroupKind
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.RecipeFacets
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import java.util.Locale

/** How the Recipes tab orders its cards (docs/PLAN.md §11.8). Each has one direction: the one that puts the best first. */
enum class RecipeSort(val label: String) {
    /** The most recently added first. */
    NEWEST("Newest"),

    /** The cheapest portion first; recipes with ingredients that have no price last. */
    COST("Cost per portion"),

    /** The most protein first. */
    PROTEIN("Protein"),

    /** The fewest kcal first. */
    KCAL("Kcal"),

    /** The shortest total time first; recipes with no time last. */
    TIME("Total time"),

    /** The best rated first; unrated recipes last. */
    RATING("Rating"),
}

/** One recipe on the Recipes tab, worked out at its own servings. */
data class RecipeCard(
    val id: Long,
    /** The short title, else the title. */
    val title: String,
    val style: String?,
    val tags: List<String>,
    /** Cost of one portion. With [costAtLeast] it leaves out the ingredients that have no price. */
    val cost: Money,
    val costAtLeast: Boolean,
    val kcal: Double,
    val protein: Double,
    val salt: Double,
    val saltLight: Light,
    val veg: Double,
    val totalMins: Int?,
    val rating: Int,
    val createdAtEpochMillis: Long,
    /** Lower case title, short title and tags, for the search box. */
    val searchText: String,
    /** What the filters know (docs/PLAN.md §11.11): proteins and carbs from the ingredients, methods from the style. */
    val proteins: Set<FoodGroup> = emptySet(),
    val carbs: Set<FoodGroup> = emptySet(),
    val methods: Set<CookMethod> = emptySet(),
    val meatFree: Boolean = false,
    /** Distinct ingredients and how many of them the kitchen doesn't have enough of. */
    val ingredientCount: Int = 0,
    val missingCount: Int = 0,
) {
    /** "You have 8 of 10 ingredients". */
    val haveText: String get() = "You have ${ingredientCount - missingCount} of $ingredientCount ingredients"
}

/** *What I have*: every recipe, those the kitchen has everything for, or those missing two at most. */
enum class HaveFilter(val label: String, val maxMissing: Int?) {
    ANY("Any", null),
    EVERYTHING("I have everything", 0),
    MISSING_TWO("Missing 2 at most", 2),
}

/**
 * The Recipes tab's filters (docs/PLAN.md §11.11). Each one left at its default keeps every recipe; together they
 * all have to match. [meatFree] is the *No meat or fish* choice of the protein menu, so it goes without [protein].
 */
data class RecipeFilters(
    val protein: FoodGroup? = null,
    val meatFree: Boolean = false,
    val carb: FoodGroup? = null,
    val method: CookMethod? = null,
    /** Total time (prep + cook) at most this many minutes. */
    val maxMins: Int? = null,
    val have: HaveFilter = HaveFilter.ANY,
    val tags: Set<String> = emptySet(),
) {
    val active: Boolean get() = this != RecipeFilters()

    companion object {
        /** The *Time* menu: at most this many minutes in total. */
        val TIME_CHOICES = listOf(20, 30, 45, 60, 90, 120)
    }
}

/** Building, searching and ordering the cards of the Recipes tab. Pure, so it is unit-tested on the JVM. */
object RecipeListMapping {

    /** One card per recipe that is not archived, each worked out at the recipe's own servings. */
    fun cards(book: FoodBook): List<RecipeCard> = book.liveRecipes.map { recipe ->
        val numbers = book.analyse(recipe)
        val nutrition = numbers.nutrition
        val facets = RecipeFacets.of(recipe, book.ingredients)
        val coverage = book.coverage(recipe)
        RecipeCard(
            id = recipe.id,
            title = recipe.shortTitle?.takeIf { it.isNotBlank() } ?: recipe.title,
            style = recipe.style?.takeIf { it.isNotBlank() },
            tags = recipe.tags,
            cost = numbers.cost.perPortion,
            costAtLeast = numbers.cost.atLeast,
            kcal = nutrition.perPortion.kcal,
            protein = nutrition.perPortion.protein,
            salt = nutrition.perPortion.salt,
            saltLight = nutrition.lights[Nutrient.SALT] ?: Light.GREEN,
            veg = numbers.veg,
            totalMins = recipe.totalMins,
            rating = recipe.rating,
            createdAtEpochMillis = recipe.createdAtEpochMillis,
            searchText = (listOfNotNull(recipe.title, recipe.shortTitle) + recipe.tags).joinToString(" ").lowercase(Locale.ROOT),
            proteins = facets.proteins,
            carbs = facets.carbs,
            methods = facets.methods,
            meatFree = facets.meatFree,
            ingredientCount = coverage.needed,
            missingCount = coverage.missingCount,
        )
    }

    /** The food groups of [kind] on [cards] with how many recipes have each, in [FoodGroup] order. */
    fun groupChoices(cards: List<RecipeCard>, kind: FoodGroupKind): List<Pair<FoodGroup, Int>> =
        FoodGroup.entries.filter { it.kind == kind }.mapNotNull { group ->
            val count = cards.count { group in (if (kind == FoodGroupKind.PROTEIN) it.proteins else it.carbs) }
            if (count == 0) null else group to count
        }

    /** The cooking methods on [cards] with how many recipes use each. */
    fun methodChoices(cards: List<RecipeCard>): List<Pair<CookMethod, Int>> =
        CookMethod.entries.mapNotNull { method -> cards.count { method in it.methods }.takeIf { it > 0 }?.let { method to it } }

    /** How many of [cards] match [filters] (and [query]). */
    fun count(cards: List<RecipeCard>, query: String, filters: RecipeFilters): Int = filter(cards, query, filters).size

    /** Every tag on [cards] (spellings that differ only in case count as one), the most used first, then alphabetically. */
    fun allTags(cards: List<RecipeCard>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (card in cards) {
            val tags = card.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
            for (tag in tags) {
                val key = tag.lowercase(Locale.ROOT)
                counts.merge(key, 1, Int::plus)
                spelling.putIfAbsent(key, tag)
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { spelling.getValue(it.key) }
    }

    /**
     * The cards whose title, short title or tags contain every word of [query] (ignoring case), and that have
     * every tag in [tags]. A blank query and no tags keep everything.
     */
    fun filter(cards: List<RecipeCard>, query: String, tags: Set<String>): List<RecipeCard> =
        filter(cards, query, RecipeFilters(tags = tags))

    /**
     * The cards that match [query] (every word in the title, short title or tags) and every one of [filters]:
     * the protein (or no meat or fish), the carb, the method, the total time (a recipe with no time doesn't match
     * a time), what the kitchen has, and every tag.
     */
    fun filter(cards: List<RecipeCard>, query: String, filters: RecipeFilters): List<RecipeCard> {
        val words = query.lowercase(Locale.ROOT).split(' ', '\t', '\n').filter { it.isNotEmpty() }
        return cards.filter { card ->
            words.all { it in card.searchText } &&
                filters.tags.all { tag -> card.tags.any { it.equals(tag, ignoreCase = true) } } &&
                (filters.protein == null || filters.protein in card.proteins) &&
                (!filters.meatFree || card.meatFree) &&
                (filters.carb == null || filters.carb in card.carbs) &&
                (filters.method == null || filters.method in card.methods) &&
                (filters.maxMins == null || (card.totalMins != null && card.totalMins <= filters.maxMins)) &&
                (filters.have.maxMissing == null || card.missingCount <= filters.have.maxMissing)
        }
    }

    /** [cards] in the order of [sort]; ties and missing values fall back to the title. */
    fun sorted(cards: List<RecipeCard>, sort: RecipeSort): List<RecipeCard> {
        val byTitle = compareBy<RecipeCard>({ it.title.lowercase(Locale.ROOT) }, { it.id })
        val order: Comparator<RecipeCard> = when (sort) {
            RecipeSort.NEWEST -> compareByDescending<RecipeCard> { it.createdAtEpochMillis }
            // A cost that leaves out ingredients with no price is only a lower bound, so it must not win.
            RecipeSort.COST -> compareBy<RecipeCard>({ it.costAtLeast }, { it.cost.pence })
            RecipeSort.PROTEIN -> compareByDescending<RecipeCard> { it.protein }
            RecipeSort.KCAL -> compareBy<RecipeCard> { it.kcal }
            RecipeSort.TIME -> compareBy<RecipeCard> { it.totalMins ?: Int.MAX_VALUE }
            RecipeSort.RATING -> compareByDescending<RecipeCard> { it.rating }
        }
        return cards.sortedWith(order.then(byTitle))
    }
}
