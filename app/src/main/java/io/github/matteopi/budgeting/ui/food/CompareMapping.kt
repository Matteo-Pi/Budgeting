package io.github.matteopi.budgeting.ui.food

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.Light
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.NutrientTotals
import io.github.matteopi.budgeting.core.recipes.TrafficLights
import io.github.matteopi.budgeting.ui.food.components.FoodBook
import io.github.matteopi.budgeting.ui.food.components.FoodFormat
import java.util.Locale

/**
 * The columns of the Compare table (docs/PLAN.md §11.8), after the pinned recipe title.
 *
 * [bestIsHigh] says which end earns the "best" marker: lowest for cost, fat, saturates, sugars and salt, highest
 * for protein, fibre, veg and rating, and null for the columns that are not judged (kcal, carbs, time). It also
 * decides which way a first tap on the heading sorts: the best first, else lowest first.
 */
enum class CompareColumn(val label: String, val bestIsHigh: Boolean?, val nutrient: Nutrient? = null) {
    TITLE("Recipe", null),
    COST("Cost", false),
    KCAL("kcal", null, Nutrient.KCAL),
    PROTEIN("Protein", true, Nutrient.PROTEIN),
    FAT("Fat", false, Nutrient.FAT),
    SATURATES("Saturates", false, Nutrient.SATURATES),
    CARBS("Carbs", null, Nutrient.CARBS),
    SUGARS("Sugars", false, Nutrient.SUGARS),
    ADDED_SUGARS("Added sugars", false, Nutrient.ADDED_SUGARS),
    FIBRE("Fibre", true, Nutrient.FIBRE),
    SALT("Salt", false, Nutrient.SALT),
    VEG("Veg", true),
    TIME("Time", null),
    RATING("Rating", true),
    ;

    /** True for the four nutrients that carry a traffic light. */
    val coloured: Boolean get() = nutrient != null && nutrient in TrafficLights.coloured

    companion object {
        /** The scrolling columns: everything but the title. */
        val scrolling: List<CompareColumn> = entries.filter { it != TITLE }
    }
}

/** The order of the table: a column and a direction. */
data class CompareSort(val column: CompareColumn = CompareColumn.TITLE, val descending: Boolean = false)

/** One recipe of the table, worked out at its own servings. */
data class CompareRow(
    val id: Long,
    /** The short title, else the title. */
    val title: String,
    val cost: Money,
    /** The cost leaves out ingredients that have no price. */
    val costAtLeast: Boolean,
    val nutrients: NutrientTotals,
    /** The nutrients that some counted ingredient doesn't know, so the amounts are "at least". */
    val unknownNutrients: Set<Nutrient>,
    val lights: Map<Nutrient, Light>,
    val veg: Double,
    val totalMins: Int?,
    val rating: Int,
)

/** What one cell of the table shows. */
data class CompareCell(
    val text: String,
    /** A small line under the text: "at least" when something is missing from the amount. */
    val note: String? = null,
    val light: Light? = null,
    /** This is the best value of its column. */
    val best: Boolean = false,
)

/** Building, ordering and marking the Compare table. Pure, so it is unit-tested on the JVM. */
object CompareMapping {

    /** One row per recipe that is not archived. */
    fun rows(book: FoodBook): List<CompareRow> = book.liveRecipes.map { recipe ->
        val numbers = book.analyse(recipe)
        CompareRow(
            id = recipe.id,
            title = recipe.shortTitle?.takeIf { it.isNotBlank() } ?: recipe.title,
            cost = numbers.cost.perPortion,
            costAtLeast = numbers.cost.atLeast,
            nutrients = numbers.nutrition.perPortion,
            unknownNutrients = numbers.nutrition.unknownNutrients,
            lights = numbers.nutrition.lights,
            veg = numbers.veg,
            totalMins = recipe.totalMins,
            rating = recipe.rating,
        )
    }

    /**
     * What a heading tap does: the same column again turns the order round; another column sorts by it,
     * best first (highest for protein, fibre, veg and rating; otherwise lowest or A to Z first).
     */
    fun tapped(current: CompareSort, column: CompareColumn): CompareSort =
        if (current.column == column) {
            current.copy(descending = !current.descending)
        } else {
            CompareSort(column, descending = column.bestIsHigh == true)
        }

    /**
     * The number a column is sorted and marked by, as shown (so recipes that show the same value tie), or null when the
     * row has no value there. A cost that leaves out unpriced ingredients and an unrated recipe have none.
     */
    fun sortValue(row: CompareRow, column: CompareColumn): Double? = when (column) {
        CompareColumn.TITLE -> null
        CompareColumn.COST -> if (row.costAtLeast) null else row.cost.pence.toDouble()
        CompareColumn.KCAL -> Math.round(row.nutrients.kcal).toDouble()
        CompareColumn.VEG -> oneDecimal(row.veg)
        CompareColumn.TIME -> row.totalMins?.toDouble()
        CompareColumn.RATING -> if (row.rating > 0) row.rating.toDouble() else null
        else -> column.nutrient?.let { oneDecimal(row.nutrients[it]) }
    }

    /** [rows] in the order of [sort]. Rows with no value in the column come last, whichever way it runs; ties go A to Z. */
    fun sorted(rows: List<CompareRow>, sort: CompareSort): List<CompareRow> {
        val byTitle = compareBy<CompareRow>({ it.title.lowercase(Locale.ROOT) }, { it.id })
        if (sort.column == CompareColumn.TITLE) {
            return rows.sortedWith(if (sort.descending) byTitle.reversed() else byTitle)
        }
        val (withValue, without) = rows.partition { sortValue(it, sort.column) != null }
        val byValue = compareBy<CompareRow> { sortValue(it, sort.column) }
        val order = if (sort.descending) byValue.reversed() else byValue
        return withValue.sortedWith(order.then(byTitle)) + without.sortedWith(byTitle)
    }

    /**
     * The ids of the recipes that are best in [column]: lowest or highest by [CompareColumn.bestIsHigh]. Nobody is
     * marked when the column isn't judged, when fewer than two recipes have a value, or when they all tie. A
     * nutrient that some ingredient doesn't know is only a lower bound, so that recipe isn't compared on it.
     */
    fun bestIds(rows: List<CompareRow>, column: CompareColumn): Set<Long> {
        val high = column.bestIsHigh ?: return emptySet()
        val values = rows.mapNotNull { row ->
            val unknown = column.nutrient?.let { it in row.unknownNutrients } == true
            if (unknown) null else sortValue(row, column)?.let { row.id to it }
        }
        if (values.size < 2) return emptySet()
        val best = if (high) values.maxOf { it.second } else values.minOf { it.second }
        val worst = if (high) values.minOf { it.second } else values.maxOf { it.second }
        if (best == worst) return emptySet()
        return values.filter { it.second == best }.map { it.first }.toSet()
    }

    /** The best ids of every judged column. */
    fun bestByColumn(rows: List<CompareRow>): Map<CompareColumn, Set<Long>> =
        CompareColumn.scrolling.filter { it.bestIsHigh != null }.associateWith { bestIds(rows, it) }

    /** What the cell of [row] under [column] shows; [best] marks the column's best. */
    fun cell(row: CompareRow, column: CompareColumn, best: Boolean): CompareCell {
        val nutrient = column.nutrient
        return when (column) {
            CompareColumn.TITLE -> CompareCell(row.title)
            CompareColumn.COST -> when {
                row.costAtLeast && row.cost.pence <= 0L -> CompareCell("–", note = "no price")
                row.costAtLeast -> CompareCell(row.cost.format(), note = "at least")
                else -> CompareCell(row.cost.format(), best = best)
            }
            CompareColumn.KCAL -> CompareCell(FoodFormat.kcal(row.nutrients.kcal), note = atLeast(row, nutrient))
            CompareColumn.VEG -> CompareCell(FoodFormat.veg(row.veg), best = best)
            CompareColumn.TIME -> CompareCell(row.totalMins?.let { FoodFormat.minutes(it) } ?: "–")
            CompareColumn.RATING -> CompareCell(if (row.rating > 0) "${row.rating}/5" else "–", best = best)
            else -> CompareCell(
                text = FoodFormat.grams(row.nutrients[requireNotNull(nutrient)]),
                note = atLeast(row, nutrient),
                light = if (column.coloured) row.lights[nutrient] else null,
                best = best,
            )
        }
    }

    private fun atLeast(row: CompareRow, nutrient: Nutrient?): String? =
        if (nutrient != null && nutrient in row.unknownNutrients) "at least" else null

    private fun oneDecimal(value: Double): Double = Math.round(value * 10.0) / 10.0
}
