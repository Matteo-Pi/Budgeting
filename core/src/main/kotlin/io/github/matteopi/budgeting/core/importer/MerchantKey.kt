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

    /** "SQ *COFFEE SHOP": Square's marker in front of the merchant. */
    private val SQUARE_PREFIX = Regex("""^SQ\s*\*\s*""")

    /** "(VIA GOOGLE PAY)", "(VIA APPLE PAY)": how the card was presented, not who was paid. */
    private val WALLET_GROUP = Regex("""\(VIA\s[^)]*\)""")

    /** "MORRISONS LEEDS -": a dash dangling after the name once the wallet group is gone. */
    private val TRAILING_DASH = Regex("""(?:\s+-+)+$""")

    private val REF_TAIL = Regex("""\bREF\S*.*$""")
    private val ON_DATE_TAIL = Regex(""",?\s+ON\s+\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b.*$""")
    /** "XXXX1234" or "****1234" on its own, not the X and * inside a name such as "NYX*PHOTOBOOTH". */
    private val CARD_MASK = Regex("""(?<![A-Za-z])[*Xx]{2,}\d*(?![A-Za-z])""")
    private val DECIMAL_AMOUNT = Regex("""£?\d+\.\d{2}\b""")
    private val LONG_NUMBER = Regex("""\b\d{4,}\b""")
    private val GBP = Regex("""\bGBP\b""")
    private val COMMAS = Regex("[,]+")
    private val WHITESPACE = Regex("""\s+""")

    private const val MAX_LENGTH = 40

    /** More passes than any real description needs; only a pathological input could hit it. */
    private const val MAX_PASSES = 8

    /**
     * Normalises until nothing changes any more, so the result is a fixed point and a stored key
     * normalises to itself. One pass can expose something the next removes: a prefix that only
     * matches once the spaces inside it collapse ("CARD  PAYMENT TO ..."), a date tail that only
     * matches once a card mask is gone, a number cut down to four digits by the length cap.
     */
    fun normalise(description: String): String {
        var current = normalisePass(description)
        repeat(MAX_PASSES) {
            val next = normalisePass(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun normalisePass(description: String): String {
        var s = description.trim().uppercase(Locale.UK)

        // Strip prefixes repeatedly ("PURCHASE CARD PAYMENT TO ..." carries two).
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in PREFIXES) {
                if (s.startsWith(prefix)) {
                    s = s.removePrefix(prefix).trim()
                    stripped = true
                    break
                }
            }
            val withoutSquare = SQUARE_PREFIX.replace(s, "")
            if (withoutSquare != s) {
                s = withoutSquare.trim()
                stripped = true
            }
        }

        s = REF_TAIL.replace(s, "").trim()
        s = ON_DATE_TAIL.replace(s, "").trim()
        s = WALLET_GROUP.replace(s, " ")
        s = CARD_MASK.replace(s, " ")
        s = DECIMAL_AMOUNT.replace(s, " ")
        s = LONG_NUMBER.replace(s, " ")
        s = GBP.replace(s, " ")
        s = COMMAS.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()
        s = TRAILING_DASH.replace(s, "").trim()

        if (s.length > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).trim()
        return s
    }
}
