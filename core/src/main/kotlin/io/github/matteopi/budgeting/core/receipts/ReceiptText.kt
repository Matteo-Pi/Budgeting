package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.util.Locale

/** An amount found in a line of text. [ocrFixed] is true when look-alike letters were read as digits. */
internal data class FoundAmount(val amount: Money, val ocrFixed: Boolean)

/** An amount at the end of a line; [name] is the text before it and [start] where the amount begins. */
internal data class TrailingAmount(
    val name: String,
    val amount: Money,
    val ocrFixed: Boolean,
    val start: Int,
)

/**
 * Low-level text helpers shared by all receipt parsers: money and date reading that forgives the
 * usual OCR confusions (O/0, l/I/1, S/5, B/8) in amounts and dates only. Names are never "fixed".
 */
internal object ReceiptText {

    /** A digit or something OCR commonly reads in its place. */
    const val DG = "[0-9OoIl|SsB]"

    /** Like [DG] but not l/I/|: the last decimal of "1.5l" (litres) must not read as a price. */
    private const val DG_END = "[0-9OoSsB]"

    private const val THOUSANDS = """\d{1,3}(?:,\d{3})+"""

    /** A decimal money amount body: "1.25", "1,25", "l.2O", "1,234.50". */
    const val AMOUNT = """(?:$THOUSANDS|$DG{1,6})[.,]$DG$DG_END"""

    /** Optional sign and currency symbol in front of an amount; "E" only when glued to the digits. */
    private const val SIGN_CURRENCY = """-?(?:[£€]\s?|E(?=[0-9OoIl|SsB]))?-?"""

    private val TRAILING = Regex(
        """^(.*?)(?:^|[\s£€])($SIGN_CURRENCY$AMOUNT\s?-?)\s*(?:[A-Za-z*]\s*)?$""",
    )

    private val ANY_AMOUNT = Regex("""(?:^|[\s£€])($SIGN_CURRENCY$AMOUNT\s?-?)(?=\s|$)""")

    private val SPACES = Regex("""\s+""")

    private fun fixLetter(c: Char): Char = when (c) {
        'O', 'o' -> '0'
        'I', 'l', '|' -> '1'
        'S', 's' -> '5'
        'B' -> '8'
        else -> c
    }

    /**
     * Reads digits (and a decimal point or comma) from OCR-ish text, e.g. "1O,5" -> "10.5".
     * Returns null if anything else is in it; [fixed] reports whether a letter was replaced.
     */
    fun fixNumber(raw: String): Pair<String, Boolean>? {
        var fixed = false
        val sb = StringBuilder()
        for (c in raw) {
            val f = fixLetter(c)
            if (f != c) fixed = true
            when {
                f.isDigit() -> sb.append(f)
                f == '.' || f == ',' -> sb.append('.')
                else -> return null
            }
        }
        return sb.toString() to fixed
    }

    /** Parses one amount token such as "£1.25", "-0.50", "0.50-", "£l.2O"; null if it is not one. */
    fun parseAmount(token: String): FoundAmount? {
        val negative = token.contains('-')
        var body = token.filter { it != '-' && it != '£' && it != '€' && !it.isWhitespace() }
        if (body.startsWith("E") && body.length > 1) body = body.substring(1)
        if (!body.any { it.isDigit() }) return null
        var fixed = false
        val digits = StringBuilder()
        for (c in body) {
            val f = fixLetter(c)
            if (f != c) fixed = true
            digits.append(f)
        }
        var s = digits.toString()
        if (Regex("""\d{1,3}(,\d{3})+[.,]\d{2}""").matches(s)) {
            val lastSep = s.length - 3
            s = s.substring(0, lastSep).replace(",", "") + "." + s.substring(lastSep + 1)
        } else {
            s = s.replace(',', '.')
        }
        val m = Regex("""(\d+)\.(\d{2})""").matchEntire(s) ?: return null
        val pence = m.groupValues[1].toLongOrNull()?.let { it * 100 + m.groupValues[2].toLong() } ?: return null
        return FoundAmount(Money(if (negative) -pence else pence), fixed)
    }

    /** Like [parseAmount] but also accepts "1", "1.2" and "1.20" (e.g. a price per kilogram). */
    fun parseLooseMoney(token: String): FoundAmount? {
        val (s, fixed) = fixNumber(token) ?: return null
        val parts = s.split('.')
        if (parts.size > 2 || parts[0].isEmpty()) return null
        val frac = parts.getOrElse(1) { "" }
        if (frac.length > 2) return null
        val pence = parts[0].toLongOrNull()?.let { it * 100 + frac.padEnd(2, '0').toLong() } ?: return null
        return FoundAmount(Money(pence), fixed)
    }

    /** The amount at the very end of [text] (optional trailing till code letter such as "A" or "*"). */
    fun trailingAmount(text: String): TrailingAmount? {
        val m = TRAILING.matchEntire(text.trim()) ?: return null
        val token = m.groups[2]!!
        val found = parseAmount(token.value) ?: return null
        return TrailingAmount(m.groupValues[1].trim(), found.amount, found.ocrFixed, token.range.first)
    }

    /** Every amount in [text], left to right. */
    fun allAmounts(text: String): List<FoundAmount> =
        ANY_AMOUNT.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }.toList()

    fun collapseSpaces(text: String): String = SPACES.replace(text.trim(), " ")

    fun letterCount(text: String): Int = text.count { it.isLetter() }

    fun upper(text: String): String = text.uppercase(Locale.UK)
}

/** Finds the receipt date in a line: dd/MM/yyyy, dd/MM/yy, dd-MM-yy, dd.MM.yy, yyyy-MM-dd, "12 Oct 2026". */
internal object ReceiptDates {

    private const val D = ReceiptText.DG

    private val ISO = Regex("""(?<![\w])(\d{4})-(\d{2})-(\d{2})(?!\d)""")

    private val NUMERIC = Regex(
        """(?<![\w])($D{1,2})\s?[/.\-]\s?($D{1,2})\s?[/.\-]\s?($D{4}|$D{2})(?!\d)""",
    )

    private val TEXTUAL = Regex(
        """(?<![\w])(\d{1,2})\s*-?\s*(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?,?\s*-?\s*['’]?(\d{4}|\d{2})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    fun find(text: String): LocalDate? {
        ISO.find(text)?.let { m ->
            build(m.groupValues[3], m.groupValues[2].toIntOrNull(), m.groupValues[1])?.let { return it }
        }
        TEXTUAL.find(text)?.let { m ->
            val month = MONTHS.indexOf(ReceiptText.upper(m.groupValues[2])) + 1
            build(m.groupValues[1], month, m.groupValues[3])?.let { return it }
        }
        for (m in NUMERIC.findAll(text)) {
            val month = ReceiptText.fixNumber(m.groupValues[2])?.first?.toIntOrNull()
            build(m.groupValues[1], month, m.groupValues[3])?.let { return it }
        }
        return null
    }

    private fun build(dayRaw: String, month: Int?, yearRaw: String): LocalDate? {
        val day = ReceiptText.fixNumber(dayRaw)?.first?.toIntOrNull() ?: return null
        var year = ReceiptText.fixNumber(yearRaw)?.first?.toIntOrNull() ?: return null
        if (month == null) return null
        if (yearRaw.length <= 2) year += 2000
        if (year !in 2000..2100) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
