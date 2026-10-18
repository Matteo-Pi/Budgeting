package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.VegKind
import java.time.LocalDate

/** One portion row of the form: a name such as "each" and its amount in the ingredient's unit. */
data class PortionRow(val key: Long, val name: String = "", val amountText: String = "")

/**
 * The ingredient editor's fields as typed (docs/PLAN.md §11.5): text stays text until [IngredientFormRules]
 * checks it, so half-typed numbers are never lost.
 */
data class IngredientForm(
    val name: String = "",
    val unit: BaseUnit = BaseUnit.G,
    val packSizeText: String = "",
    val packLabel: String = "",
    val priceText: String = "",
    val priceDate: LocalDate? = null,
    /** True once the person picked the price date, so typing a price no longer sets it to today. */
    val priceDateTouched: Boolean = false,
    val store: String = "",
    val productName: String = "",
    val sourceUrl: String = "",
    val barcode: String = "",
    /** The per-100 values as typed (added sugars too); a missing or blank entry means unknown. */
    val nutrition: Map<Nutrient, String> = emptyMap(),
    val nutritionSource: NutritionSource = NutritionSource.TYPED,
    val vegKind: VegKind = VegKind.NONE,
    val shelf: Shelf = Shelf.OTHER,
    val note: String = "",
    val portions: List<PortionRow> = emptyList(),
    /** The receipts product this ingredient is linked to. */
    val productId: Long? = null,
    val nextKey: Long = 1,
    /** What the recipe filters count it as; null guesses from the name (docs/PLAN.md §11.11). */
    val foodGroup: FoodGroup? = null,
)

/** What is wrong with an [IngredientForm], field by field. */
data class IngredientFormErrors(
    val name: Boolean = false,
    val packSize: Boolean = false,
    val price: Boolean = false,
    val nutrition: Set<Nutrient> = emptySet(),
    /** Keys of the portion rows with a missing name or an amount that is not above zero. */
    val portions: Set<Long> = emptySet(),
) {
    val any: Boolean get() = name || packSize || price || nutrition.isNotEmpty() || portions.isNotEmpty()

    /** The sentence that says what to fix, or null when nothing is wrong. */
    val message: String?
        get() = when {
            name -> "An ingredient needs a name."
            packSize -> "The pack size must be a number above zero."
            price -> "The price must be an amount of money such as 0.99."
            nutrition.isNotEmpty() -> "Nutrition values must be numbers of zero or more."
            portions.isNotEmpty() -> "Each portion needs a name and an amount above zero."
            else -> null
        }
}

/** Turning the editor's form into an [Ingredient] and back, with the checks (docs/PLAN.md §11.5). */
object IngredientFormRules {

    /** The order the nutrition fields are shown in. */
    val NUTRIENT_ORDER: List<Nutrient> = listOf(
        Nutrient.KCAL, Nutrient.FAT, Nutrient.SATURATES, Nutrient.CARBS,
        Nutrient.SUGARS, Nutrient.ADDED_SUGARS, Nutrient.FIBRE, Nutrient.PROTEIN, Nutrient.SALT,
    )

    fun nutrientLabel(nutrient: Nutrient): String = when (nutrient) {
        Nutrient.KCAL -> "Energy (kcal)"
        Nutrient.FAT -> "Fat (g)"
        Nutrient.SATURATES -> "Saturates (g)"
        Nutrient.CARBS -> "Carbohydrate (g)"
        Nutrient.SUGARS -> "Sugars (g)"
        Nutrient.ADDED_SUGARS -> "Added sugars (g)"
        Nutrient.FIBRE -> "Fibre (g)"
        Nutrient.PROTEIN -> "Protein (g)"
        Nutrient.SALT -> "Salt (g)"
    }

    /** A number as typed ("12", "0.5", "1,5"), or null when it isn't one or is negative or not finite. */
    fun parseNumber(text: String): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (value.isNaN() || value.isInfinite() || value < 0.0) null else value
    }

    /** Money as typed ("0.99", "£1.50"), or null when it isn't an amount of zero or more. */
    fun parsePrice(text: String): Money? = Money.parse(text)?.takeIf { !it.isNegative }

    /** Only digits stay in a barcode. */
    fun digitsOnly(text: String): String = text.filter { it in '0'..'9' }

    fun errors(form: IngredientForm): IngredientFormErrors {
        val packSize = parseNumber(form.packSizeText)
        return IngredientFormErrors(
            name = form.name.isBlank(),
            packSize = packSize == null || packSize <= 0.0,
            price = form.priceText.isNotBlank() && parsePrice(form.priceText) == null,
            nutrition = form.nutrition.filter { (_, text) -> text.isNotBlank() && parseNumber(text) == null }.keys,
            portions = form.portions
                .filter { row ->
                    val blank = row.name.isBlank() && row.amountText.isBlank()
                    val amount = parseNumber(row.amountText)
                    !blank && (row.name.isBlank() || amount == null || amount <= 0.0)
                }
                .map { it.key }
                .toSet(),
        )
    }

    /** The ingredient the form describes, or null while [errors] says something is wrong. [today] dates a price typed without a date. */
    fun build(form: IngredientForm, base: Ingredient?, today: LocalDate): Ingredient? {
        if (errors(form).any) return null
        val price = if (form.priceText.isBlank()) null else parsePrice(form.priceText)
        fun value(nutrient: Nutrient): Double? = form.nutrition[nutrient]?.takeIf { it.isNotBlank() }?.let { parseNumber(it) }
        val per100 = Nutrition(
            kcal = value(Nutrient.KCAL),
            fat = value(Nutrient.FAT),
            saturates = value(Nutrient.SATURATES),
            carbs = value(Nutrient.CARBS),
            sugars = value(Nutrient.SUGARS),
            fibre = value(Nutrient.FIBRE),
            protein = value(Nutrient.PROTEIN),
            salt = value(Nutrient.SALT),
            addedSugars = value(Nutrient.ADDED_SUGARS),
        )
        val portions = form.portions
            .filter { !(it.name.isBlank() && it.amountText.isBlank()) }
            .mapNotNull { row -> parseNumber(row.amountText)?.let { Portion(row.name.trim(), it) } }
        return (base ?: Ingredient(name = form.name.trim())).copy(
            name = form.name.trim(),
            unit = form.unit,
            packSize = parseNumber(form.packSizeText) ?: 1.0,
            packLabel = form.packLabel.trim(),
            price = price,
            priceDate = if (price == null) null else form.priceDate ?: today,
            store = form.store.trim().ifEmpty { null },
            productId = form.productId,
            productName = form.productName.trim().ifEmpty { null },
            sourceUrl = form.sourceUrl.trim().ifEmpty { null },
            barcode = digitsOnly(form.barcode).ifEmpty { null },
            per100 = per100,
            nutritionSource = form.nutritionSource,
            vegKind = form.vegKind,
            shelf = form.shelf,
            note = form.note.trim(),
            portions = portions,
            foodGroup = form.foodGroup,
        )
    }

    /** The form for [ingredient] (a new ingredient when null). */
    fun fromIngredient(ingredient: Ingredient?): IngredientForm {
        if (ingredient == null) return IngredientForm()
        fun text(value: Double?): String = value?.let { Quantities.plain(it, 3) }.orEmpty()
        val per100 = ingredient.per100
        return IngredientForm(
            name = ingredient.name,
            unit = ingredient.unit,
            packSizeText = Quantities.plain(ingredient.packSize, 3),
            packLabel = ingredient.packLabel,
            priceText = ingredient.price?.let { priceInput(it) }.orEmpty(),
            priceDate = ingredient.priceDate,
            store = ingredient.store.orEmpty(),
            productName = ingredient.productName.orEmpty(),
            sourceUrl = ingredient.sourceUrl.orEmpty(),
            barcode = ingredient.barcode.orEmpty(),
            nutrition = mapOf(
                Nutrient.KCAL to text(per100.kcal),
                Nutrient.FAT to text(per100.fat),
                Nutrient.SATURATES to text(per100.saturates),
                Nutrient.CARBS to text(per100.carbs),
                Nutrient.SUGARS to text(per100.sugars),
                Nutrient.FIBRE to text(per100.fibre),
                Nutrient.PROTEIN to text(per100.protein),
                Nutrient.SALT to text(per100.salt),
                Nutrient.ADDED_SUGARS to text(per100.addedSugars),
            ),
            nutritionSource = ingredient.nutritionSource,
            vegKind = ingredient.vegKind,
            shelf = ingredient.shelf,
            note = ingredient.note,
            portions = ingredient.portions.mapIndexed { index, portion ->
                PortionRow(index + 1L, portion.name, Quantities.plain(portion.amount, 3))
            },
            productId = ingredient.productId,
            nextKey = ingredient.portions.size + 1L,
            foodGroup = ingredient.foodGroup,
        )
    }

    /** A price as it is typed into a field: 99 pence is "0.99", 150 is "1.50". */
    fun priceInput(price: Money): String {
        val pence = price.pence
        return "${pence / 100}.${(pence % 100).toString().padStart(2, '0')}"
    }
}
