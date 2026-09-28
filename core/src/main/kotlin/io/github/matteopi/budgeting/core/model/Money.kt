package io.github.matteopi.budgeting.core.model

import kotlin.math.abs

/**
 * An amount of money in pence. Never use floating point for money.
 * Sign convention everywhere in the app: negative = money leaving an account.
 */
@JvmInline
value class Money(val pence: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(pence + other.pence)
    operator fun minus(other: Money) = Money(pence - other.pence)
    operator fun unaryMinus() = Money(-pence)
    operator fun times(n: Int) = Money(pence * n)
    operator fun times(n: Long) = Money(pence * n)

    fun abs() = Money(abs(pence))
    val isNegative: Boolean get() = pence < 0
    val isPositive: Boolean get() = pence > 0
    val isZero: Boolean get() = pence == 0L

    override fun compareTo(other: Money) = pence.compareTo(other.pence)

    /** Scales by a double and rounds half away from zero to the nearest penny. */
    fun scale(factor: Double): Money = Money(Math.round(pence * factor))

    /**
     * "£1,234.56", "-£3.20"; with [showPlus] positive amounts get a leading "+".
     * With [showPence] = false the pence are dropped, not rounded ("£1,234"), so a
     * displayed amount never overstates what is there.
     */
    fun format(showPlus: Boolean = false, showPence: Boolean = true): String {
        val sign = when {
            pence < 0 -> "-"
            showPlus && pence > 0 -> "+"
            else -> ""
        }
        val absPence = abs(pence)
        val pounds = absPence / 100
        val grouped = pounds.toString().reversed().chunked(3).joinToString(",").reversed()
        return if (showPence) {
            val p = (absPence % 100).toString().padStart(2, '0')
            "$sign£$grouped.$p"
        } else {
            "$sign£$grouped"
        }
    }

    override fun toString() = format()

    companion object {
        val ZERO = Money(0)

        fun pounds(pounds: Long) = Money(pounds * 100)

        /**
         * Parses user input such as "3.2", "£1,234.50", "-3", " 12 ". At most two decimal
         * places. Returns null for anything else.
         */
        fun parse(input: String): Money? {
            var s = input.trim().replace(",", "").replace("£", "")
            if (s.isEmpty()) return null
            val negative = s.startsWith("-")
            if (negative || s.startsWith("+")) s = s.substring(1).replace("£", "")
            if (s.isEmpty() || !s.matches(Regex("""\d+(\.\d{0,2})?|\.\d{1,2}"""))) return null
            val parts = s.split(".")
            val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
            val frac = parts.getOrNull(1).orEmpty().padEnd(2, '0').ifEmpty { "00" }.toLong()
            val value = whole * 100 + frac
            return Money(if (negative) -value else value)
        }
    }
}

fun Iterable<Money>.sum(): Money = Money(sumOf { it.pence })
