package io.github.matteopi.budgeting.core.recipes

import java.util.Locale

/** A weight or volume written in brackets after a count, such as "(about 320 g)". [each] when it says "each". */
data class BracketWeight(val amount: Double, val measure: Measure, val each: Boolean = false)

/**
 * One ingredient line read apart: `[amount] [measure] name[, note] [(bracket)]`.
 *
 * - [amount] is the leading amount (the first value of a range); in "2 × 400 g tins" it is 400 and
 *   [multiplier] is 2. A bare "pinch of" is an amount of 1 with measure [Measure.PINCH]. Null when
 *   the line has no leading amount.
 * - [measure] is null for a count ("2 onions").
 * - [name] is what is left once the amount, measure, note and brackets are taken off, and
 *   [nameWords] its words, lower case ("garlic cloves" -> garlic, cloves).
 * - [note] is the words after a comma and the brackets that aren't a weight.
 */
data class ParsedLine(
    val text: String,
    val amount: Double? = null,
    val multiplier: Double? = null,
    val measure: Measure? = null,
    val name: String = "",
    val nameWords: List<String> = emptyList(),
    val note: String? = null,
    val bracketWeight: BracketWeight? = null,
    val optional: Boolean = false,
) {
    val hasAmount: Boolean get() = amount != null
}

/** A line's quantity in the ingredient's base unit. [estimated] when it assumed 1 ml = 1 g. */
data class ResolvedQuantity(val quantity: Double, val estimated: Boolean)

/** Reading, resolving and scaling the text of one ingredient line (docs/PLAN.md §11.4, §11.2). */
object IngredientLines {

    private class Token(val start: Int, val end: Int, val value: Double, val word: Boolean = false)

    private class Head(
        val lead: List<Token>,
        val times: List<Token>,
        val measure: Measure?,
        val nameStart: Int,
    ) {
        val amount: Double?
            get() = when {
                times.isNotEmpty() -> times.first().value
                lead.isNotEmpty() -> lead.first().value
                measure == Measure.PINCH -> 1.0
                else -> null
            }
        val multiplier: Double? get() = if (times.isNotEmpty()) lead.first().value else null
    }

    private class FoundBracket(val weight: BracketWeight, val tokens: List<Token>)

    private val FRACTIONS = mapOf(
        '½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )
    private val MODIFIERS = setOf("heaped", "heaping", "level", "rounded", "generous", "scant")
    private val BRACKET = Regex("""\(([^()]*)\)""")
    private val OPTIONAL = Regex("""(?i)\boptional\b""")
    private val NAME_WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)?""")
    private val SPACES = Regex("""\s+""")
    private val BRACKET_PREFIXES = listOf("approximately", "approx.", "approx", "about", "around", "roughly", "circa", "ca.", "c.", "~")
    private val BRACKET_TAILS = setOf("", "each", "total", "in total", "altogether", "drained", "peeled", "trimmed", "prepared")
    private val WEIGHT_MEASURES = setOf(Measure.G, Measure.KG, Measure.ML, Measure.L, Measure.OZ, Measure.LB)

    // ---- scanning --------------------------------------------------------------------------

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == ' ')) i++
        return i
    }

    private fun isDigit(s: String, i: Int) = i < s.length && s[i] in '0'..'9'

    private fun digitsEnd(s: String, from: Int): Int {
        var i = from
        while (isDigit(s, i)) i++
        return i
    }

    /** A number at [from]: 2, 1.5, ½, 1½, 1 1/2, 3/4 (no ranges). */
    private fun readNumber(s: String, from: Int): Token? {
        if (from >= s.length) return null
        if (!isDigit(s, from)) {
            val f = FRACTIONS[s[from]] ?: return null
            return Token(from, from + 1, f)
        }
        var j = digitsEnd(s, from)
        var hasDecimal = false
        if (j < s.length && s[j] == '.' && isDigit(s, j + 1)) {
            hasDecimal = true
            j = digitsEnd(s, j + 1)
        }
        val digits = s.substring(from, j)
        // "00 flour" and "000 flour" name a grade, not an amount.
        if (!hasDecimal && digits.length > 1 && digits.all { it == '0' }) return null
        var value = digits.toDouble()
        if (j < s.length && s[j] in FRACTIONS) {
            value += FRACTIONS.getValue(s[j])
            j++
        } else if (j < s.length && s[j] == '/' && isDigit(s, j + 1) && !hasDecimal) {
            val end = digitsEnd(s, j + 1)
            val denominator = s.substring(j + 1, end).toDouble()
            if (denominator > 0) {
                value /= denominator
                j = end
            }
        } else {
            val k = skipSpaces(s, j)
            if (k > j && k < s.length) {
                if (s[k] in FRACTIONS && !hasDecimal) {
                    value += FRACTIONS.getValue(s[k])
                    j = k + 1
                } else if (isDigit(s, k) && !hasDecimal) {
                    val numeratorEnd = digitsEnd(s, k)
                    if (numeratorEnd < s.length && s[numeratorEnd] == '/' && isDigit(s, numeratorEnd + 1)) {
                        val denominatorEnd = digitsEnd(s, numeratorEnd + 1)
                        val numerator = s.substring(k, numeratorEnd).toDouble()
                        val denominator = s.substring(numeratorEnd + 1, denominatorEnd).toDouble()
                        if (denominator > 0) {
                            value += numerator / denominator
                            j = denominatorEnd
                        }
                    }
                }
            }
        }
        return Token(from, j, value)
    }

    /** "a", "an", "half a" and "half an" as an amount. */
    private fun readLeadWord(s: String, from: Int): Token? = when {
        s.regionMatches(from, "half an ", 0, 8, ignoreCase = true) -> Token(from, from + 7, 0.5, true)
        s.regionMatches(from, "half a ", 0, 7, ignoreCase = true) -> Token(from, from + 6, 0.5, true)
        s.regionMatches(from, "an ", 0, 3, ignoreCase = true) -> Token(from, from + 2, 1.0, true)
        s.regionMatches(from, "a ", 0, 2, ignoreCase = true) -> Token(from, from + 1, 1.0, true)
        else -> null
    }

    /** A leading amount, or a range such as 1-2 or 1 to 2 (two tokens). */
    private fun readLead(s: String, from: Int): List<Token> {
        val first = readNumber(s, from) ?: return listOfNotNull(readLeadWord(s, from))
        val k = skipSpaces(s, first.end)
        val afterSeparator = when {
            k < s.length && (s[k] == '-' || s[k] == '–' || s[k] == '—') -> k + 1
            k > first.end && s.regionMatches(k, "to ", 0, 3, ignoreCase = true) -> k + 2
            else -> -1
        }
        if (afterSeparator >= 0) {
            readNumber(s, skipSpaces(s, afterSeparator))?.let { return listOf(first, it) }
        }
        return listOf(first)
    }

    private fun readTimes(s: String, from: Int): List<Token> {
        val k = skipSpaces(s, from)
        if (k >= s.length) return emptyList()
        val c = s[k]
        val after = when {
            c == '×' -> k + 1
            (c == 'x' || c == 'X') && k + 1 < s.length && (s[k + 1] == ' ' || isDigit(s, k + 1) || s[k + 1] in FRACTIONS) -> k + 1
            else -> return emptyList()
        }
        val tokens = readLead(s, skipSpaces(s, after))
        return if (tokens.isEmpty() || tokens.first().word) emptyList() else tokens
    }

    /** A run of letters at [from], or null. */
    private fun readLetters(s: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < s.length && s[i].isLetter()) i++
        return if (i > from) s.substring(from, i) to i else null
    }

    private fun scanHead(s: String): Head {
        val start = skipSpaces(s, 0)
        val lead = readLead(s, start)
        var pos = if (lead.isEmpty()) start else lead.last().end
        val times = if (lead.isNotEmpty() && !lead.first().word) readTimes(s, pos) else emptyList()
        if (times.isNotEmpty()) pos = times.last().end
        val hasAmount = lead.isNotEmpty()

        var measure: Measure? = null
        val p = skipSpaces(s, pos)
        val first = readLetters(s, p)
        if (first != null) {
            var candidate = first
            if (first.first.lowercase(Locale.ROOT) in MODIFIERS) {
                val second = readLetters(s, skipSpaces(s, first.second))
                if (second != null && Quantities.measureOf(second.first) != null) candidate = second
            }
            val found = Quantities.measureOf(candidate.first)
            if (found != null && (hasAmount || found == Measure.PINCH)) {
                measure = found
                pos = candidate.second
                if (pos < s.length && s[pos] == '.') pos++
                val q = skipSpaces(s, pos)
                if (s.regionMatches(q, "of ", 0, 3, ignoreCase = true)) pos = q + 3
            }
        }
        return Head(lead, times, measure, skipSpaces(s, pos))
    }

    /** The weight written in [content] (the inside of a bracket), if that is all it says. */
    private fun weightIn(content: String, offset: Int): FoundBracket? {
        var p = skipSpaces(content, 0)
        for (prefix in BRACKET_PREFIXES) {
            if (content.regionMatches(p, prefix, 0, prefix.length, ignoreCase = true)) {
                p = skipSpaces(content, p + prefix.length)
                break
            }
        }
        val tokens = readLead(content, p)
        if (tokens.isEmpty() || tokens.first().word) return null
        val letters = readLetters(content, skipSpaces(content, tokens.last().end)) ?: return null
        val measure = Quantities.measureOf(letters.first) ?: return null
        if (measure !in WEIGHT_MEASURES) return null
        var end = letters.second
        if (end < content.length && content[end] == '.') end++
        val tail = content.substring(end).trim().lowercase(Locale.ROOT)
        if (tail !in BRACKET_TAILS) return null
        val shifted = tokens.map { Token(it.start + offset, it.end + offset, it.value) }
        return FoundBracket(BracketWeight(tokens.first().value, measure, tail == "each"), shifted)
    }

    private fun weightBrackets(s: String): List<FoundBracket> =
        BRACKET.findAll(s).mapNotNull { m -> weightIn(m.groupValues[1], m.groups[1]!!.range.first) }.toList()

    // ---- parse -----------------------------------------------------------------------------

    /** Reads one ingredient line. See [ParsedLine]. Never fails: a line with no amount just has none. */
    fun parse(text: String): ParsedLine {
        val s = text.trim()
        val head = scanHead(s)

        var bracketWeight: BracketWeight? = null
        val bracketNotes = mutableListOf<String>()
        for (m in BRACKET.findAll(s)) {
            val content = m.groupValues[1]
            val found = weightIn(content, 0)
            if (found != null) {
                if (bracketWeight == null) bracketWeight = found.weight
            } else {
                val cleaned = cleanNote(content)
                if (cleaned.isNotEmpty()) bracketNotes += cleaned
            }
        }

        val rest = BRACKET.replace(s.substring(minOf(head.nameStart, s.length)), " ")
        val segments = rest.split(',').map { it.trim() }
        var used = 0
        var name = segments.first()
        while (used + 1 < segments.size && onlyDescriptors(name)) {
            used++
            name += " " + segments[used]
        }
        val commaNote = segments.drop(used + 1).joinToString(", ") { it }.let { cleanNote(it) }
        name = cleanName(name)
        val note = (listOf(commaNote) + bracketNotes).filter { it.isNotEmpty() }.distinct().joinToString("; ").ifEmpty { null }

        return ParsedLine(
            text = s,
            amount = head.amount,
            multiplier = head.multiplier,
            measure = head.measure,
            name = name,
            nameWords = NAME_WORD.findAll(name).map { it.value.lowercase(Locale.ROOT) }.toList(),
            note = note,
            bracketWeight = bracketWeight,
            optional = OPTIONAL.containsMatchIn(s),
        )
    }

    private fun onlyDescriptors(name: String): Boolean =
        NAME_WORD.findAll(name).all { NameWords.isDescriptor(it.value) || NameWords.isStopWord(it.value) }

    private fun cleanNote(note: String): String =
        SPACES.replace(OPTIONAL.replace(note, " "), " ").trim().trim(',', ';', ' ')

    private fun cleanName(name: String): String {
        var n = SPACES.replace(OPTIONAL.replace(name, " "), " ").trim()
        n = n.trim(' ', '.', ';', ':', '-', '–', '—', ',')
        if (n.startsWith("of ", ignoreCase = true)) n = n.substring(3).trim()
        return n
    }

    // ---- resolve ---------------------------------------------------------------------------

    /**
     * The line's quantity in [ingredient]'s base unit, or null when it can't be worked out (no
     * amount, or a count with no weight known: the review asks for it).
     *
     * - A measure is converted with [Quantities.toBase]. "N × 400 g tins": if the ingredient has a
     *   portion named by a word of the name (`tin`) that portion is used instead of the 400 g, so
     *   beans are weighed drained.
     * - A count uses a bracketed weight if there is one ("2 chicken breast fillets (about 320 g)"),
     *   else the portion named by a word of the name ("3 garlic cloves"), else the `each` portion.
     */
    fun resolve(parsed: ParsedLine, ingredient: Ingredient): ResolvedQuantity? {
        val amount = parsed.amount ?: return null
        val measure = parsed.measure
        if (measure != null) {
            val multiplier = parsed.multiplier
            if (multiplier != null) {
                Quantities.portionNamedIn(ingredient, parsed.nameWords)?.let {
                    return ResolvedQuantity(multiplier * it.amount, false)
                }
                val each = Quantities.toBase(amount, measure, ingredient)
                return ResolvedQuantity(multiplier * each.quantity, each.estimated)
            }
            val base = Quantities.toBase(amount, measure, ingredient)
            return ResolvedQuantity(base.quantity, base.estimated)
        }
        parsed.bracketWeight?.let { weight ->
            val base = Quantities.toBase(weight.amount, weight.measure, ingredient)
            return ResolvedQuantity(base.quantity * if (weight.each) amount else 1.0, base.estimated)
        }
        Quantities.portionNamedIn(ingredient, parsed.nameWords)?.let {
            return ResolvedQuantity(amount * it.amount, false)
        }
        Quantities.portion(ingredient, "each")?.let { return ResolvedQuantity(amount * it.amount, false) }
        return null
    }

    // ---- scale -----------------------------------------------------------------------------

    private enum class Rounding { HALVES, METRIC, BIG_METRIC, IMPERIAL }

    private fun roundingFor(measure: Measure?): Rounding = when (measure) {
        null, Measure.TSP, Measure.TBSP, Measure.CUP, Measure.PINCH -> Rounding.HALVES
        Measure.G, Measure.ML -> Rounding.METRIC
        Measure.KG, Measure.L -> Rounding.BIG_METRIC
        Measure.OZ, Measure.LB -> Rounding.IMPERIAL
    }

    /** Counts and spoons to the nearest half (quarters under 1, never to nothing), weights and volumes to the nearest 5 (1 dp under 10). */
    private fun scaledNumber(value: Double, rounding: Rounding): String = when (rounding) {
        Rounding.HALVES -> {
            // Under 1 a half is too coarse (¼ tsp would double), so quarters; never down to nothing.
            val step = if (value < 1.0) 4 else 2
            val rounded = Math.floor(value * step + 0.5) / step
            Quantities.formatCount(if (rounded <= 0.0 && value > 0.0) 0.25 else rounded)
        }
        Rounding.METRIC -> Quantities.plain(roundMetric(value), if (value < 10) 1 else 0)
        Rounding.BIG_METRIC -> Quantities.plain(Math.floor(value / 0.05 + 0.5) * 0.05, 2)
        Rounding.IMPERIAL -> Quantities.plain(value, 1)
    }

    private fun roundMetric(value: Double): Double =
        if (value < 10) Math.floor(value * 10 + 0.5) / 10 else Math.floor(value / 5 + 0.5) * 5

    /**
     * Rewrites [text] for [factor] times the quantity.
     *
     * - The leading amount is scaled: counts and spoons to the nearest ½ ("1½ onions", "1½ tbsp";
     *   quarters below 1, so ¼ tsp doesn't jump to ½), weights and volumes to the nearest 5 ("480 g"),
     *   or to 1 dp under 10.
     * - In "N × 400 g tins" only N is scaled.
     * - A bracketed weight such as "(about 320 g)" is scaled too.
     * - After a count or a spoon the base amount is appended ("1½ onions, diced (225 g)") when
     *   [baseQuantity] (the line's unscaled quantity in [unit]) is given and the line has no bracketed weight.
     * - A line with no leading amount but a [baseQuantity] gets "×1.5" appended; with neither it stays as written.
     */
    fun scaleText(text: String, factor: Double, baseQuantity: Double? = null, unit: BaseUnit? = null): String {
        if (factor.isNaN() || factor.isInfinite() || factor <= 0.0 || Math.abs(factor - 1.0) < 1e-9) return text
        val head = scanHead(text)
        if (head.lead.isEmpty() || head.lead.first().word) {
            return if (baseQuantity != null) "$text ×${Quantities.plain(factor, 2)}" else text
        }

        class Edit(val start: Int, val end: Int, val replacement: String)

        val edits = mutableListOf<Edit>()
        val leadRounding = if (head.times.isNotEmpty()) Rounding.HALVES else roundingFor(head.measure)
        for (token in head.lead) edits += Edit(token.start, token.end, scaledNumber(token.value * factor, leadRounding))
        val brackets = weightBrackets(text)
        for (bracket in brackets) {
            val rounding = roundingFor(bracket.weight.measure)
            for (token in bracket.tokens) edits += Edit(token.start, token.end, scaledNumber(token.value * factor, rounding))
        }
        val builder = StringBuilder(text)
        for (edit in edits.sortedByDescending { it.start }) builder.replace(edit.start, edit.end, edit.replacement)

        val countOrSpoon = head.times.isEmpty() &&
            (head.measure == null || head.measure == Measure.TSP || head.measure == Measure.TBSP || head.measure == Measure.CUP)
        if (baseQuantity != null && unit != null && countOrSpoon && brackets.isEmpty()) {
            val scaled = baseQuantity * factor
            builder.append(" (").append(Quantities.format(roundMetric(scaled), unit)).append(')')
        }
        return builder.toString()
    }
}
