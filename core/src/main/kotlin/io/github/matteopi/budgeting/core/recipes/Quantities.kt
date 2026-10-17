package io.github.matteopi.budgeting.core.recipes

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/** A unit written in a recipe line. See [Quantities.toBase] for what each one means. */
enum class Measure { G, KG, ML, L, TSP, TBSP, PINCH, OZ, LB, CUP }

/** A recipe quantity in an ingredient's base unit; [estimated] when the conversion had to assume 1 ml = 1 g. */
data class BaseQuantity(val quantity: Double, val estimated: Boolean)

/** Amount and unit parsing, base-unit conversion and number formatting (docs/PLAN.md §11.2). */
object Quantities {

    const val ML_PER_TSP = 5.0
    const val ML_PER_TBSP = 15.0
    const val G_PER_PINCH = 0.3
    const val G_PER_OZ = 28.35
    const val G_PER_LB = 453.6
    const val ML_PER_CUP = 240.0

    /** Energy: kJ per kcal. */
    const val KJ_PER_KCAL = 4.184

    private val MEASURE_WORDS: Map<String, Measure> = buildMap {
        for (w in listOf("g", "gram", "grams", "gramme", "grammes")) put(w, Measure.G)
        for (w in listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "kilogramme", "kilogrammes")) put(w, Measure.KG)
        for (w in listOf("ml", "mls", "millilitre", "millilitres", "milliliter", "milliliters")) put(w, Measure.ML)
        for (w in listOf("l", "litre", "litres", "liter", "liters")) put(w, Measure.L)
        for (w in listOf("tsp", "tsps", "teaspoon", "teaspoons")) put(w, Measure.TSP)
        for (w in listOf("tbsp", "tbsps", "tbs", "tablespoon", "tablespoons")) put(w, Measure.TBSP)
        for (w in listOf("pinch", "pinches")) put(w, Measure.PINCH)
        for (w in listOf("oz", "ounce", "ounces")) put(w, Measure.OZ)
        for (w in listOf("lb", "lbs", "pound", "pounds")) put(w, Measure.LB)
        for (w in listOf("cup", "cups")) put(w, Measure.CUP)
    }

    /** The measure a word names ("tbsp", "Tablespoons", "g."), or null. */
    fun measureOf(word: String): Measure? = MEASURE_WORDS[word.trim().trimEnd('.').lowercase(Locale.ROOT)]

    /**
     * Converts [amount] of [measure] to [ingredient]'s base unit.
     *
     * - Weights on a millilitre ingredient and volumes on a gram ingredient assume 1 g = 1 ml and are
     *   marked estimated.
     * - A spoon on a gram ingredient uses the ingredient's own "tsp"/"tbsp" portion if set (one spoon
     *   can stand in for the other at 1 tbsp = 3 tsp); otherwise 1 ml = 1 g, estimated.
     * - A pinch uses the ingredient's "pinch" portion if set, else 0.3.
     * - A cup on a gram ingredient uses its "cup" portion if set, else 240 g, estimated.
     */
    fun toBase(amount: Double, measure: Measure, ingredient: Ingredient): BaseQuantity {
        val isMl = ingredient.unit == BaseUnit.ML
        return when (measure) {
            Measure.G -> BaseQuantity(amount, isMl)
            Measure.KG -> BaseQuantity(amount * 1000, isMl)
            Measure.OZ -> BaseQuantity(amount * G_PER_OZ, isMl)
            Measure.LB -> BaseQuantity(amount * G_PER_LB, isMl)
            Measure.ML -> BaseQuantity(amount, !isMl)
            Measure.L -> BaseQuantity(amount * 1000, !isMl)
            Measure.PINCH -> {
                val own = portion(ingredient, "pinch")
                BaseQuantity(amount * (own?.amount ?: G_PER_PINCH), own == null && isMl)
            }
            Measure.TSP, Measure.TBSP -> spoon(amount, measure, ingredient)
            Measure.CUP -> if (isMl) {
                BaseQuantity(amount * ML_PER_CUP, false)
            } else {
                val own = portion(ingredient, "cup")
                BaseQuantity(amount * (own?.amount ?: ML_PER_CUP), own == null)
            }
        }
    }

    private fun spoon(amount: Double, measure: Measure, ingredient: Ingredient): BaseQuantity {
        if (ingredient.unit == BaseUnit.ML) {
            return BaseQuantity(amount * if (measure == Measure.TSP) ML_PER_TSP else ML_PER_TBSP, false)
        }
        val tsp = portion(ingredient, "tsp")?.amount
        val tbsp = portion(ingredient, "tbsp")?.amount
        val perSpoon = if (measure == Measure.TSP) {
            tsp ?: tbsp?.div(3.0)
        } else {
            tbsp ?: tsp?.times(3.0)
        }
        return if (perSpoon != null) {
            BaseQuantity(amount * perSpoon, false)
        } else {
            BaseQuantity(amount * if (measure == Measure.TSP) ML_PER_TSP else ML_PER_TBSP, true)
        }
    }

    /** The portion of [ingredient] called [name], matching singular or plural. */
    fun portion(ingredient: Ingredient, name: String): Portion? {
        val key = stem(name)
        return ingredient.portions.firstOrNull { stem(it.name) == key }
    }

    /** The portion named by the first of [words] that is one of [ingredient]'s portion names, or null. */
    fun portionNamedIn(ingredient: Ingredient, words: List<String>): Portion? {
        for (word in words) {
            portion(ingredient, word)?.let { return it }
        }
        return null
    }

    /** Rounds half up (towards positive infinity). */
    fun roundHalfUp(x: Double): Long = Math.floor(x + 0.5).toLong()

    /**
     * A quantity for display: "150 g", "7.5 g", "1.2 kg", "250 ml", "1.5 l". Under 10 it shows one
     * decimal place, from 10 up a whole number, from 1000 up kilograms or litres to two places.
     */
    fun format(quantity: Double, unit: BaseUnit): String {
        val small = if (unit == BaseUnit.G) "g" else "ml"
        val big = if (unit == BaseUnit.G) "kg" else "l"
        if (quantity >= 1000) return "${plain(quantity / 1000, 2)} $big"
        val digits = if (quantity < 10) 1 else 0
        return "${plain(quantity, digits)} $small"
    }

    /** [value] rounded half up to [decimals] places, without trailing zeros: 7.50 -> "7.5", 7.0 -> "7". */
    fun plain(value: Double, decimals: Int): String {
        val text = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    /**
     * An amount with common fractions: 1.5 -> "1½", 0.5 -> "½", 2.25 -> "2¼", 3.0 -> "3". Anything
     * that isn't a whole number plus a quarter, half, third or two thirds is shown to 2 places.
     */
    fun formatCount(value: Double): String {
        var whole = Math.floor(value + 1e-9).toLong()
        var frac = value - whole
        if (frac > 0.99) {
            whole += 1
            frac = 0.0
        }
        val glyph = when {
            Math.abs(frac) < 0.01 -> ""
            Math.abs(frac - 0.25) < 0.01 -> "¼"
            Math.abs(frac - 0.5) < 0.01 -> "½"
            Math.abs(frac - 0.75) < 0.01 -> "¾"
            Math.abs(frac - 1.0 / 3) < 0.01 -> "⅓"
            Math.abs(frac - 2.0 / 3) < 0.01 -> "⅔"
            else -> return plain(value, 2)
        }
        return if (whole == 0L && glyph.isNotEmpty()) glyph else "$whole$glyph"
    }

    private val ACCENTS = Regex("""\p{M}+""")
    private val WORD = Regex("""[a-z0-9]+""")
    private val IRREGULAR = mapOf("leaves" to "leaf", "loaves" to "loaf", "halves" to "half", "knives" to "knife")

    /** Lower case, accents and apostrophes removed: "Purée" -> "puree", "bird's" -> "birds". */
    fun fold(text: String): String =
        ACCENTS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .replace("'", "").replace("’", "")
            .lowercase(Locale.ROOT)

    /**
     * A form of [word] that is the same for its singular and plural, for comparing names:
     * "cloves" and "clove", "tomatoes" and "tomato", "chillies" and "chilli" all share one stem.
     * Not a real stemmer: it only has to give the same answer on both sides of a comparison.
     */
    fun stem(word: String): String {
        var w = fold(word)
        IRREGULAR[w]?.let { return it }
        if (w.length > 3) {
            w = when {
                w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
                w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") ||
                    w.endsWith("xes") || w.endsWith("zes") || w.endsWith("oes") -> w.dropLast(2)
                w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
                else -> w
            }
        }
        return if (w.length > 2 && w.endsWith("y")) w.dropLast(1) + "i" else w
    }

    /** Words of [text] in lower case without accents or apostrophes: "Bird's-eye chilli" -> [birds, eye, chilli]. */
    fun words(text: String): List<String> = WORD.findAll(fold(text)).map { it.value }.toList()
}
