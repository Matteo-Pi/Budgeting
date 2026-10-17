package io.github.matteopi.budgeting.ui.food

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
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
)

/** Building, searching and ordering the cards of the Recipes tab. Pure, so it is unit-tested on the JVM. */
object RecipeListMapping {

    /** One card per recipe that is not archived, each worked out at the recipe's own servings. */
    fun cards(book: FoodBook): List<RecipeCard> = book.liveRecipes.map { recipe ->
        val numbers = book.analyse(recipe)
        val nutrition = numbers.nutrition
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
        )
    }

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
    fun filter(cards: List<RecipeCard>, query: String, tags: Set<String>): List<RecipeCard> {
        val words = query.lowercase(Locale.ROOT).split(' ', '\t', '\n').filter { it.isNotEmpty() }
        return cards.filter { card ->
            words.all { it in card.searchText } && tags.all { tag -> card.tags.any { it.equals(tag, ignoreCase = true) } }
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
