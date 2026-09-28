package io.github.matteopi.budgeting.core.importer

import java.util.Locale

/** Turns a raw statement description into a stable, human-recognisable merchant key. */
object MerchantKey {

    private val PREFIXES = listOf(
        "CARD PAYMENT TO",
        "CONTACTLESS PAYMENT TO",
        "DIRECT DEBIT PAYMENT TO",
        "STANDING ORDER TO",
        "BILL PAYMENT VIA FASTER PAYMENT TO",
        "FASTER PAYMENTS RECEIPT FROM",
        "PURCHASE",
    ).sortedByDescending { it.length }

    private val REF_TAIL = Regex("""\bREF\S*.*$""")
    private val ON_DATE_TAIL = Regex(""",?\s+ON\s+\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b.*$""")
    private val CARD_MASK = Regex("""[*Xx]{2,}\d*""")
    private val DECIMAL_AMOUNT = Regex("""£?\d+\.\d{2}\b""")
    private val LONG_NUMBER = Regex("""\b\d{4,}\b""")
    private val GBP = Regex("""\bGBP\b""")
    private val COMMAS = Regex("[,]+")
    private val WHITESPACE = Regex("""\s+""")

    private const val MAX_LENGTH = 40

    fun normalise(description: String): String {
        var s = description.trim().uppercase(Locale.UK)

        for (prefix in PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.removePrefix(prefix).trim()
                break
            }
        }

        s = REF_TAIL.replace(s, "").trim()
        s = ON_DATE_TAIL.replace(s, "").trim()
        s = CARD_MASK.replace(s, " ")
        s = DECIMAL_AMOUNT.replace(s, " ")
        s = LONG_NUMBER.replace(s, " ")
        s = GBP.replace(s, " ")
        s = COMMAS.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()

        if (s.length > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).trim()
        return s
    }
}
