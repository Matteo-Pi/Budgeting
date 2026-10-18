package io.github.matteopi.budgeting.ui.food.ingredient

import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.receipts.PurchasedItem
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.FoodGroupKind
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.PriceSource
import io.github.matteopi.budgeting.core.recipes.Quantities
import io.github.matteopi.budgeting.core.recipes.ResolvedPrice
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.VegKind
import io.github.matteopi.budgeting.ui.receipts.list.ReceiptMapping
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the ingredient screens word things (docs/PLAN.md §11.5, §11.8). Pure, so the wording can be
 * unit-tested. British English; money through `Money.format`; dates as "15 Oct" with `Locale.ENGLISH`
 * (en-GB abbreviates September as "Sept").
 */
object FoodTexts {

    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun shelfName(shelf: Shelf): String = when (shelf) {
        Shelf.FRUIT_VEG -> "Fruit & veg"
        Shelf.MEAT -> "Meat & fish"
        Shelf.DAIRY -> "Dairy & eggs"
        Shelf.FROZEN -> "Frozen"
        Shelf.CUPBOARD -> "Cupboard"
        Shelf.OILS -> "Oils"
        Shelf.HERBS_SPICES -> "Herbs & spices"
        Shelf.OTHER -> "Other"
    }

    fun vegKindName(kind: VegKind): String = when (kind) {
        VegKind.NONE -> "Doesn't count"
        VegKind.VEG -> "Vegetable or fruit"
        VegKind.PULSE -> "Beans or lentils"
    }

    /** *Counts as*: a group's name, or "Automatic (Chicken)" / "Automatic (neither)" with the guess for [group] null. */
    fun foodGroupName(group: FoodGroup?, guessed: FoodGroup?): String = when (group) {
        null -> "Automatic (${guessed?.label ?: "neither a protein nor a carb"})"
        FoodGroup.NONE -> "Neither a protein nor a carb"
        else -> "${group.label} (${if (group.kind == FoodGroupKind.CARB) "carb" else "protein"})"
    }

    fun nutritionSourceName(source: NutritionSource): String = when (source) {
        NutritionSource.LABEL -> "From the label"
        NutritionSource.BARCODE -> "From a barcode lookup"
        NutritionSource.TYPED -> "Typed in"
        NutritionSource.ESTIMATE -> "An estimate"
    }

    /** "g" or "ml". */
    fun unitSymbol(unit: BaseUnit): String = if (unit == BaseUnit.G) "g" else "ml"

    /** The pack as the shop describes it ("1 kg bag"), or its size ("500 g") when no label was typed. */
    fun packText(ingredient: Ingredient): String =
        ingredient.packLabel.ifBlank { Quantities.format(ingredient.packSize, ingredient.unit) }

    /** "15 Oct"; with the year ("15 Oct 2025") when it isn't the year of [today]. */
    fun shortDate(date: LocalDate, today: LocalDate): String {
        val text = date.format(SHORT_DATE)
        return if (date.year == today.year) text else "$text ${date.year}"
    }

    /**
     * The price line of an ingredient row: "£0.99 · 1 kg bag · typed 15 Oct" for a typed price, or
     * "paid £0.89 at Tesco 3 Nov" for the latest price paid on a receipt. Null when it has no price.
     */
    fun priceLine(ingredient: Ingredient, price: ResolvedPrice?, today: LocalDate): String? {
        if (price == null) return null
        return when (val source = price.source) {
            is PriceSource.Receipt ->
                "paid ${price.packPrice.format()} at ${ReceiptMapping.storeName(source.store)} ${shortDate(source.date, today)}"
            is PriceSource.Typed -> {
                val typed = source.date?.let { "typed ${shortDate(it, today)}" } ?: "typed"
                "${price.packPrice.format()} · ${packText(ingredient)} · $typed"
            }
        }
    }

    /**
     * The latest price paid for [productId] in [purchases] (by date, then input order; free items are skipped,
     * as `Prices` does), or null when it was never bought.
     */
    fun latestPaid(purchases: List<PurchasedItem>, productId: Long): PurchasedItem? {
        var best: PurchasedItem? = null
        for (purchase in purchases) {
            if (purchase.productId != productId || purchase.spend.pence <= 0) continue
            val current = best
            if (current == null || !purchase.date.isBefore(current.date)) best = purchase
        }
        return best
    }

    /** "last paid £0.89 at Tesco 3 Nov"; a weighed item reads "last paid £1.20 for 452 g at Tesco 3 Nov". */
    fun lastPaidText(purchase: PurchasedItem, today: LocalDate): String {
        val where = "at ${ReceiptMapping.storeName(purchase.store)} ${shortDate(purchase.date, today)}"
        val grams = purchase.weightGrams
        if (grams != null && grams > 0) return "last paid ${purchase.spend.format()} for $grams g $where"
        val quantity = if (purchase.quantity > 0) purchase.quantity else 1
        val each = Money(Quantities.roundHalfUp(purchase.spend.pence.toDouble() / quantity))
        return "last paid ${each.format()} $where"
    }

    /** "1 item" / "3 items". */
    fun items(count: Int): String = if (count == 1) "1 item" else "$count items"

    /** "1 pack" / "2 packs". */
    fun packs(count: Int): String = if (count == 1) "1 pack" else "$count packs"
}
