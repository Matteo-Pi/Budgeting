package io.github.matteopi.budgeting.core.recipes

import io.github.matteopi.budgeting.core.receipts.ReceiptRow

/** One value read from a label. [lessThan] for "<0.5 g" (read as 0.5) and "trace" (read as 0). */
data class LabelValue(val value: Double, val lessThan: Boolean = false, val confidence: Double = 1.0)

/**
 * What a photo of a nutrition label gave (docs/PLAN.md §11.5): the per 100 g (or ml) values found, keyed by
 * nutrient, whether the "per 100" column was found in the heading (else the first column was assumed), and the
 * unit the heading names.
 */
data class LabelReading(
    val values: Map<Nutrient, LabelValue>,
    val columnFound: Boolean,
    /** ML when the heading says "per 100ml", G when it says grams, null when no heading was found. */
    val unit: BaseUnit?,
) {
    val isEmpty: Boolean get() = values.isEmpty()

    /** The values as per-100 nutrition; nutrients not found are unknown (null). */
    fun toNutrition(): Nutrition = Nutrition(
        kcal = values[Nutrient.KCAL]?.value,
        fat = values[Nutrient.FAT]?.value,
        saturates = values[Nutrient.SATURATES]?.value,
        carbs = values[Nutrient.CARBS]?.value,
        sugars = values[Nutrient.SUGARS]?.value,
        fibre = values[Nutrient.FIBRE]?.value,
        protein = values[Nutrient.PROTEIN]?.value,
        salt = values[Nutrient.SALT]?.value,
        addedSugars = values[Nutrient.ADDED_SUGARS]?.value,
    )
}

/**
 * Reads the text of a UK nutrition label (OCR rows, top to bottom) into per 100 g or ml values (docs/PLAN.md §11.5).
 *
 * - The heading row is the one that says "per 100g" / "per 100ml" / "100g"; the values column is found by counting
 *   the "per …" columns before it ("Per serving (30g) Per 100g" puts it second). With no heading the first value
 *   column is assumed and every value's confidence is lowered.
 * - A nutrient row is found by its name (energy, fat, of which saturates, carbohydrate, of which sugars, fibre,
 *   protein, salt; sodium × 2.5 when there is no salt row). An "added sugars" or "free sugars" row, which few UK
 *   labels have, is read as added sugars (docs/PLAN.md §11.11). Mono- and polyunsaturates, starch and polyols are
 *   ignored. "%" values (reference intakes) are never read as amounts.
 * - Energy is read in kcal; a column with only kJ is converted (÷ 4.184), and kcal on the row after an energy row
 *   that only has kJ are used.
 */
object NutritionLabel {

    private const val KJ_PER_KCAL = 4.184
    private const val SALT_PER_SODIUM = 2.5
    private const val UNSURE = 0.6

    private val NUMBER = Regex("""(<\s*)?(\d+(?:\.\d+)?)\s*(kcal|kj|mg|g|ml)?(?![\d.])(\s*%)?""")
    private val TRACE = Regex("""\btrace\b""")
    private val PER_SEGMENT = Regex("""\bper\b""")

    fun parse(rows: List<ReceiptRow>): LabelReading = parseText(rows.map { it.text })

    fun parseText(rows: List<String>): LabelReading {
        val lines = rows.map { clean(it) }.filter { it.isNotBlank() }
        val headerIndex = lines.indexOfFirst { isHeading(it) }
        val column = if (headerIndex >= 0) columnOf(lines[headerIndex]) else 0
        val confidence = if (headerIndex >= 0) 1.0 else UNSURE
        val unit = when {
            headerIndex < 0 -> null
            Regex("""100\s*ml""").containsMatchIn(lines[headerIndex]) -> BaseUnit.ML
            else -> BaseUnit.G
        }

        val values = LinkedHashMap<Nutrient, LabelValue>()
        var sodium: LabelValue? = null
        val start = if (headerIndex >= 0) headerIndex + 1 else 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val nutrient = nutrientOf(line)
            when {
                nutrient == Nutrient.KCAL && Nutrient.KCAL !in values -> {
                    energy(line, lines.getOrNull(i + 1), column, confidence)?.let { (value, usedNext) ->
                        values[Nutrient.KCAL] = value
                        if (usedNext) i++
                    }
                }
                nutrient != null && nutrient != Nutrient.KCAL && nutrient !in values ->
                    amount(line, column, confidence)?.let { values[nutrient] = it }
                nutrient == null && sodium == null && Regex("""\bsodium\b""").containsMatchIn(line) ->
                    sodium = amount(line, column, confidence)
            }
            i++
        }
        if (Nutrient.SALT !in values) {
            sodium?.let { values[Nutrient.SALT] = it.copy(value = it.value * SALT_PER_SODIUM, confidence = it.confidence * 0.9) }
        }
        return LabelReading(values, columnFound = headerIndex >= 0, unit = unit)
    }

    private fun clean(text: String): String = text.lowercase()
        .replace(Regex("""(\d),(\d)"""), "$1.$2")
        .replace('|', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun isHeading(line: String): Boolean =
        Regex("""per\s*100\s*(g|ml)\b""").containsMatchIn(line) ||
            (Regex("""\b100\s*(g|ml)\b""").containsMatchIn(line) && nutrientOf(line) == null && !line.contains("kcal"))

    /** The position of the per-100 column among the "per …" columns of the heading (0 when it is first or alone). */
    private fun columnOf(heading: String): Int {
        val segments = PER_SEGMENT.split(heading).drop(1).map { it.trim() }
        val index = segments.indexOfFirst { Regex("""^100\s*(g|ml)""").containsMatchIn(it) }
        return if (index >= 0) index else 0
    }

    private fun nutrientOf(line: String): Nutrient? {
        if (line.contains("unsaturat") || line.contains("starch") || line.contains("polyol")) return null
        return when {
            line.contains("energy") -> Nutrient.KCAL
            line.contains("saturat") -> Nutrient.SATURATES
            Regex("""\b(added|free)\s+sugar""").containsMatchIn(line) -> Nutrient.ADDED_SUGARS
            line.contains("sugar") -> Nutrient.SUGARS
            line.contains("fibre") || line.contains("fiber") -> Nutrient.FIBRE
            line.contains("protein") -> Nutrient.PROTEIN
            Regex("""\bsalt\b""").containsMatchIn(line) -> Nutrient.SALT
            line.contains("carbohydrate") || Regex("""\bcarbs?\b""").containsMatchIn(line) -> Nutrient.CARBS
            Regex("""\bfat\b""").containsMatchIn(line) -> Nutrient.FAT
            else -> null
        }
    }

    private class Token(val value: Double, val unit: String?, val lessThan: Boolean)

    /** The amounts on [line], in order, leaving out percentages. */
    private fun tokens(line: String): List<Token> {
        val out = ArrayList<Token>()
        for (match in NUMBER.findAll(line)) {
            if (match.groupValues[4].isNotEmpty()) continue
            val value = match.groupValues[2].toDoubleOrNull() ?: continue
            out += Token(value, match.groupValues[3].ifEmpty { null }, match.groupValues[1].isNotEmpty())
        }
        return out
    }

    private fun amount(line: String, column: Int, confidence: Double): LabelValue? {
        val found = tokens(line)
        if (found.isEmpty()) {
            return if (TRACE.containsMatchIn(line)) LabelValue(0.0, lessThan = true, confidence = confidence * 0.8) else null
        }
        val token = found.getOrNull(column) ?: found.last()
        val sure = if (found.getOrNull(column) == null) confidence * UNSURE else confidence
        val grams = if (token.unit == "mg") token.value / 1000 else token.value
        return LabelValue(grams, lessThan = token.lessThan, confidence = if (token.lessThan) sure * 0.9 else sure)
    }

    /** The energy in kcal of [column], with whether [next] (a row of kcal under a kJ-only energy row) was used. */
    private fun energy(line: String, next: String?, column: Int, confidence: Double): Pair<LabelValue, Boolean>? {
        val found = tokens(line)
        val labelSaysKcal = line.contains("kcal") && found.none { it.unit == "kcal" || it.unit == "kj" }
        val kcal = found.filter { it.unit == "kcal" || (labelSaysKcal && it.unit == null) }
        if (kcal.isNotEmpty()) {
            val token = kcal.getOrNull(column) ?: kcal.last()
            return LabelValue(token.value, confidence = if (kcal.getOrNull(column) == null) confidence * UNSURE else confidence) to false
        }
        // The kcal can be on the next row: a bare "129kcal 194kcal" row, or "Energy (kcal) 129 194" under "Energy (kJ)".
        if (next != null && next.contains("kcal") && nutrientOf(next).let { it == null || it == Nutrient.KCAL }) {
            val below = tokens(next).filter { it.unit == "kcal" || (next.contains("kcal") && it.unit == null) }
            if (below.isNotEmpty()) {
                val token = below.getOrNull(column) ?: below.last()
                return LabelValue(token.value, confidence = confidence * 0.9) to true
            }
        }
        val kj = found.filter { it.unit == "kj" || (line.contains("kj") && it.unit == null) }
        if (kj.isNotEmpty()) {
            val token = kj.getOrNull(column) ?: kj.last()
            return LabelValue(token.value / KJ_PER_KCAL, confidence = confidence * 0.8) to false
        }
        return null
    }
}
