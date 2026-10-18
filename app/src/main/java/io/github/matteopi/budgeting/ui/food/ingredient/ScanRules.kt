package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.recipes.LabelReading
import io.github.matteopi.budgeting.core.recipes.Nutrient
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.OffDraft
import io.github.matteopi.budgeting.core.recipes.Quantities

/**
 * Putting what a label photo or a barcode lookup found into the ingredient editor's form (docs/PLAN.md §11.5).
 * Pure, so it is unit-tested on the JVM. Nothing is saved until the person presses Save.
 */
object ScanRules {

    /** The label's order, for the review dialog. */
    val ORDER = listOf(
        Nutrient.KCAL, Nutrient.FAT, Nutrient.SATURATES, Nutrient.CARBS,
        Nutrient.SUGARS, Nutrient.ADDED_SUGARS, Nutrient.FIBRE, Nutrient.PROTEIN, Nutrient.SALT,
    )

    private fun text(value: Double): String = Quantities.plain(value, 2)

    /**
     * [form] with the values the label gave (others are left as they are), marked as read from a label. A label
     * that says "per 100ml" makes a new ingredient (no pack size yet) a millilitre one.
     */
    fun applyLabel(form: IngredientForm, reading: LabelReading): IngredientForm {
        val nutrition = form.nutrition.toMutableMap()
        for ((nutrient, value) in reading.values) nutrition[nutrient] = text(value.value)
        val unit = reading.unit?.takeIf { form.packSizeText.isBlank() } ?: form.unit
        return form.copy(nutrition = nutrition, nutritionSource = NutritionSource.LABEL, unit = unit)
    }

    /**
     * [form] with what Open Food Facts gave: the barcode always; the name, pack size and pack label only where the
     * form has none yet; the nutrition it knows (marked as from a barcode lookup).
     */
    fun applyDraft(form: IngredientForm, draft: OffDraft): IngredientForm {
        val nutrition = form.nutrition.toMutableMap()
        val per100 = draft.per100
        for (nutrient in ORDER) per100[nutrient]?.let { nutrition[nutrient] = text(it) }
        val packBlank = form.packSizeText.isBlank()
        return form.copy(
            name = form.name.ifBlank { draft.name },
            productName = form.productName.ifBlank { draft.name },
            barcode = draft.barcode,
            unit = if (packBlank) draft.unit ?: form.unit else form.unit,
            packSizeText = if (packBlank) draft.packSize?.let { text(it) }.orEmpty() else form.packSizeText,
            packLabel = form.packLabel.ifBlank { draft.quantityText.orEmpty() },
            nutrition = nutrition,
            nutritionSource = if (per100.isEmpty) form.nutritionSource else NutritionSource.BARCODE,
        )
    }

    /** The rows of the label review: "Fat", "5.0 g" (with "less than" or "check this" notes). */
    fun labelRows(reading: LabelReading): List<Triple<String, String, String?>> = ORDER.mapNotNull { nutrient ->
        val value = reading.values[nutrient] ?: return@mapNotNull null
        val amount = if (nutrient == Nutrient.KCAL) "${Quantities.roundHalfUp(value.value)} kcal" else "${text(value.value)} g"
        val note = when {
            value.lessThan && value.value == 0.0 -> "trace"
            value.lessThan -> "less than"
            value.confidence < 0.8 -> "check this"
            else -> null
        }
        Triple(IngredientFormRules.nutrientLabel(nutrient).substringBefore(" ("), amount, note)
    }
}
