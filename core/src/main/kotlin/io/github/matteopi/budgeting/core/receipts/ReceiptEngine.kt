package io.github.matteopi.budgeting.core.receipts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate

/**
 * What distinguishes one store's till roll from another's. Everything else (item lines, quantity
 * and weight lines, discounts, totals, dates, noise) is shared by [ReceiptEngine], so tuning a
 * store later means editing its profile, not the engine.
 *
 * @property namePattern matches the store name in the first [ReceiptEngine.HEADER_ROWS] rows (upper case).
 * @property keywordPattern matches a keyword that only this store prints, anywhere on the receipt.
 * @property discountPattern store-specific loyalty price lines ("CLUBCARD PRICE", "NECTAR PRICE", ...).
 */
internal class StoreProfile(
    val store: Store,
    val namePattern: Regex?,
    val keywordPattern: Regex?,
    val discountPattern: Regex?,
)

internal object StoreProfiles {
    val TESCO = StoreProfile(
        store = Store.TESCO,
        namePattern = Regex("""\bTESC[O0]\b"""),
        keywordPattern = Regex("""CLUBCARD\s*PRICE|\bCC\s*PRICE\b"""),
        discountPattern = Regex("""CLUBCARD\s*(?:PRICE|DISC\w*|SAVING|OFFER)|\bCC\s*PRICE\b"""),
    )
    val SAINSBURYS = StoreProfile(
        store = Store.SAINSBURYS,
        namePattern = Regex("""SA[I1]NSBURY"""),
        keywordPattern = Regex("""NECTAR\s*PRICE"""),
        discountPattern = Regex("""NECTAR\s*(?:PRICE|DISC\w*|SAVING|OFFER)"""),
    )
    val MORRISONS = StoreProfile(
        store = Store.MORRISONS,
        namePattern = Regex("""M[O0]RR[I1]S[O0]N"""),
        keywordPattern = Regex("""MORE\s*CARD\s*PRICE"""),
        discountPattern = Regex("""MORE\s*CARD(?!\s*(?:NUMBER|NO\b|POINTS?|BALANCE|SWIPED|USED))|MORE\s*(?:PRICE|OFFER)"""),
    )

    /** Fallback: knows every store's loyalty price wording, since the store name may be unreadable. */
    val GENERIC = StoreProfile(
        store = Store.OTHER,
        namePattern = null,
        keywordPattern = null,
        discountPattern = Regex(
            listOf(TESCO, SAINSBURYS, MORRISONS).joinToString("|") { "(?:${it.discountPattern!!.pattern})" },
        ),
    )
}

/**
 * The shared receipt reading logic, driven by a [StoreProfile]. Pure; deterministic.
 *
 * Reading goes row by row, top to bottom:
 * - a row with a date is used for the date and otherwise skipped;
 * - a "TOTAL" style label with one amount sets the total; nothing after it is read as an item
 *   (payment, change, VAT summaries and loyalty footers all come after the total);
 * - discount rows (loyalty price wording, multibuy, coupon, a negative amount) go on the item above;
 * - quantity ("2 @ £1.10") and weight ("0.452 kg @ £1.20/kg") rows go on the item above if the
 *   item's price agrees with them, else on the next item;
 * - a row ending in an amount, with a name, is an item; a row without an amount is remembered as
 *   a possible name for a following weight/quantity row that carries the price;
 * - header and footer noise is skipped.
 */
internal object ReceiptEngine {

    const val HEADER_ROWS = 15

    private const val AMT = ReceiptText.AMOUNT
    private const val D = ReceiptText.DG

    /** Number that may contain OCR look-alikes: "0.452", "O.452", "452". */
    private const val NUM = """(?:$D+(?:[.,]$D+)?)"""

    private val WEIGHT = Regex(
        """^(?:(.*?)\s+)?($NUM)\s*(kg|g)\s*(?:@|x)\s*[£€]?\s?($NUM)\s*(?:/|per\s*)?\s*(?:kg|g)?(?:\s+-?[£€]?\s?($AMT))?\s*[A-Za-z*]?$""",
        RegexOption.IGNORE_CASE,
    )

    private val QUANTITY = Regex(
        """^(?:QTY\s*)?(\d{1,3})\s*(?:@|x|×)\s*[£€]?\s?($AMT)(?:\s*(?:ea|each|/ea))?(?:\s+-?[£€]?\s?($AMT))?\s*[A-Za-z*]?$""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_QUANTITY = Regex("""^(\d{1,2})\s*[xX×]\s+(?=[A-Za-z])(.+)$""")

    private val TOTAL_LABEL = Regex(
        """^\W*(?:\d+\s*ITEMS?\s*)?(?:GRAND\s+)?(?:T[O0]TAL(?:\s+(?:TO\s+PAY|DUE|PAYABLE|AMOUNT))?|BALANCE\s+(?:DUE|TO\s+PAY)|TO\s+PAY|AMOUNT\s+(?:DUE|TO\s+PAY))\s*(?:\(?\s*\d+\s*ITEMS?\s*\)?)?\s*:?\s*(?:GBP)?\s*$""",
    )

    /** Lines that look like totals or savings summaries but are not the amount paid. */
    private val SUMMARY_NOISE = Regex(
        """SUB\s*-?\s*T[O0]TAL|\bT[O0]TAL\b.*\b(?:SAVINGS?|DISCOUNTS?|VAT|POINTS?|PAID|TENDER\w*|CHANGE)\b|\bYOU\s+SAVED\b|\bSAVINGS\b""",
    )

    private val OFF = Regex("""(?:^|[\s£€])[£€]?(\d+[.,]\d{2})\s*OFF\b""")

    private val N_FOR = Regex("""\b\d+\s*F[O0]R\s*[£€]?\s*\d+(?:[.,]\d{1,2})?""")

    /** Wording that always means a discount when the line has an amount. */
    private val STRONG_DISCOUNT = Regex(
        """PROMOTION|MULTI\s*-?\s*BUY|\bSAVING\b|COUPON|\bDISCOUNT\b|REDUCED\s+(?:TO\s+CLEAR|PRICE)|YELLOW\s+STICKER|\bREDUCTION\b""",
    )

    /** Wording that is a discount only with a negative amount or nothing else on the line ("REDUCED FAT MILK"). */
    private val WEAK_DISCOUNT = Regex("""\bREDUCED\b|\bOFFER\b""")

    private val PAYMENT_START = Regex(
        """^\W*(?:VISA|MASTERCARD|MAESTRO|AMEX|CASH|CARD|DEBIT|CREDIT|CHANGE|TENDER\w*|PAID|PAYMENT|CONTACTLESS|CHIP|SWIPE\w*|CHEQUE|VOUCHER|GIFT\s*CARD|REFUND)\b""",
    )

    private val NOISE = Regex(
        listOf(
            """\bVAT\b""", """\bTAX\b""", """^\W*[A-Z]?\s*\d{1,2}(?:\.\d+)?\s*%""",
            """\bPOINTS?\b""", """\bPTS\b""", """\b\d+\s*ITEMS?\b""", """\bITEMS?\s*(?:SOLD|COUNT|TOTAL)\b""", """\bBALANCE\b""",
            """\bTEL\b""", """\bPHONE\b""", """\bFAX\b""", """WWW\.""", """\.COM\b""", """\.CO\.UK\b""",
            """\bSTORE\b""", """\bTILL\b""", """\bCASHIER\b""", """\bCHECKOUT\b""", """\bTRANS(?:ACTION)?\b""",
            """THANK""", """WELCOME""", """\bSERVED\b""", """\bRECEIPT\b""", """\bCUSTOMER\b""",
            """\bAUTH\w*""", """\bTERMINAL\b""", """\bMERCHANT\b""", """\bPAN\b""", """\bAID\b""", """\bCONTACTLESS\b""",
            """\bCHANGE\s+DUE\b""", """\bBARCODE\b""", """\bOPENING\s+HOURS\b""",
            """\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b""",
            """\b0\d{2,4}[\s-]?\d{3,4}[\s-]?\d{3,4}\b""",
            """\d{8,}""",
        ).joinToString("|"),
    )

    private val CODE_PUNCTUATION = Regex("""^[\s*\-:.]+|[\s*\-:.]+$""")

    /** Does [rows] look like it came from the store described by [profile]? */
    fun detect(rows: List<ReceiptRow>, profile: StoreProfile): Boolean {
        val name = profile.namePattern
        if (name != null && rows.take(HEADER_ROWS).any { name.containsMatchIn(ReceiptText.upper(it.text)) }) return true
        val keyword = profile.keywordPattern
        return keyword != null && rows.any { keyword.containsMatchIn(ReceiptText.upper(it.text)) }
    }

    private class Measure(val quantity: Int?, val weightGrams: Int?, val unit: Money, val total: Money?, val fixed: Boolean) {
        val expected: Money
            get() = if (weightGrams != null) {
                Money((weightGrams.toLong() * unit.pence + 500) / 1000)
            } else {
                unit * (quantity ?: 1)
            }
    }

    private class Work(val name: String, val price: Money, var ocrFixed: Boolean) {
        var quantity = 1
        var weightGrams: Int? = null
        var unitPrice: Money? = null
        var discount = Money.ZERO
        var measured = false
        var penalty = 0f
    }

    fun parse(rows: List<ReceiptRow>, source: ReceiptSource, profile: StoreProfile): ReceiptDraft {
        val items = ArrayList<Work>()
        val warnings = ArrayList<String>()
        var date: LocalDate? = null
        var total: Money? = null
        var pendingName: String? = null
        var pendingMeasure: Measure? = null

        fun attachDiscount(amount: Money, text: String) {
            val last = items.lastOrNull()
            if (last == null) {
                warnings += "Discount \"$text\" has no item above it, so it was ignored."
            } else {
                last.discount = last.discount - amount.abs()
            }
        }

        fun measureText(m: Measure) = if (m.weightGrams != null) "weight line" else "quantity line"

        fun applyMeasure(w: Work, m: Measure) {
            w.measured = true
            w.quantity = m.quantity ?: 1
            w.weightGrams = m.weightGrams
            w.unitPrice = m.unit
            if (m.fixed) w.ocrFixed = true
            if ((w.price - m.expected).abs().pence > 1) w.penalty += PENALTY_MEASURE
        }

        // A quantity or weight line that fits no later item belongs to the item above it.
        fun flushPending() {
            val pending = pendingMeasure
            val last = items.lastOrNull()
            if (pending != null && last != null && !last.measured) {
                applyMeasure(last, pending)
                pendingMeasure = null
            }
        }

        for (row in rows) {
            val text = ReceiptText.collapseSpaces(row.text)
            if (text.isEmpty()) continue
            val upper = ReceiptText.upper(text)

            val rowDate = ReceiptDates.find(text)
            if (rowDate != null) {
                if (date == null) date = rowDate
                continue
            }

            val trailing = ReceiptText.trailingAmount(text)

            if (trailing != null && TOTAL_LABEL.matches(ReceiptText.upper(trailing.name))) {
                flushPending()
                if (total == null) {
                    total = trailing.amount.abs()
                } else if (total != trailing.amount.abs()) {
                    warnings += "The receipt shows more than one total (${total.format()} and ${trailing.amount.abs().format()})."
                }
                continue
            }
            if (total != null) continue

            if (SUMMARY_NOISE.containsMatchIn(upper)) {
                pendingName = null
                continue
            }

            val off = OFF.find(upper)?.let { ReceiptText.parseAmount(it.groupValues[1]) }
            if (off != null) {
                attachDiscount(off.amount, text)
                pendingName = null
                continue
            }

            val offer = N_FOR.find(upper)
            val offerHasOwnAmount = offer != null && trailing != null && trailing.start > offer.range.last
            val storeDiscount = profile.discountPattern?.containsMatchIn(upper) == true
            val strong = storeDiscount || STRONG_DISCOUNT.containsMatchIn(upper) || (offer != null)
            if (strong) {
                val usable = trailing != null && (offer == null || offerHasOwnAmount)
                if (usable) attachDiscount(trailing!!.amount, text)
                pendingName = null
                continue
            }
            if (trailing != null && trailing.amount.isNegative) {
                attachDiscount(trailing.amount, text)
                pendingName = null
                continue
            }
            if (trailing != null && WEAK_DISCOUNT.containsMatchIn(upper)) {
                val rest = WEAK_DISCOUNT.replace(ReceiptText.upper(trailing.name), " ")
                if (ReceiptText.letterCount(rest) == 0) {
                    attachDiscount(trailing.amount, text)
                    pendingName = null
                    continue
                }
            }

            if (PAYMENT_START.containsMatchIn(upper) || NOISE.containsMatchIn(upper)) {
                pendingName = null
                continue
            }

            val weight = WEIGHT.matchEntire(text)
            val quantity = if (weight == null) QUANTITY.matchEntire(text) else null
            val measure: Measure? = when {
                weight != null -> parseWeight(weight)
                quantity != null -> parseQuantity(quantity)
                else -> null
            }
            if (measure != null) {
                val inlineName = weight?.groupValues?.get(1)?.let { cleanName(it) }.orEmpty()
                val name = inlineName.ifEmpty { pendingName.orEmpty() }
                if (measure.total != null) {
                    if (ReceiptText.letterCount(name) >= 2) {
                        val w = Work(name, measure.total, false)
                        applyMeasure(w, measure)
                        items += w
                    } else {
                        val last = items.lastOrNull()
                        if (last != null && !last.measured && (last.price - measure.total).abs().pence <= 1) {
                            applyMeasure(last, measure)
                        } else {
                            warnings += "Could not match the ${measureText(measure)} \"$text\" to an item."
                        }
                    }
                    pendingName = null
                    pendingMeasure = null
                } else {
                    val last = items.lastOrNull()
                    if (name.isEmpty() && last != null && !last.measured && (last.price - measure.expected).abs().pence <= 1) {
                        applyMeasure(last, measure)
                    } else {
                        pendingMeasure = measure
                        if (inlineName.isNotEmpty()) pendingName = inlineName
                    }
                }
                continue
            }

            if (trailing != null) {
                var name = cleanName(trailing.name)
                if (ReceiptText.letterCount(name) < 2) {
                    val pn = pendingName
                    val pm = pendingMeasure
                    if (pn != null && pm != null && !trailing.amount.isNegative) {
                        val w = Work(pn, trailing.amount, trailing.ocrFixed)
                        applyMeasure(w, pm)
                        items += w
                    }
                    pendingName = null
                    pendingMeasure = null
                    continue
                }
                var leadingQty = 1
                LEADING_QUANTITY.matchEntire(name)?.let {
                    leadingQty = it.groupValues[1].toInt()
                    name = cleanName(it.groupValues[2])
                }
                val w = Work(name, trailing.amount, trailing.ocrFixed)
                if (leadingQty > 1) {
                    w.quantity = leadingQty
                    if (w.price.pence % leadingQty == 0L) w.unitPrice = Money(w.price.pence / leadingQty)
                }
                pendingMeasure?.let { applyMeasure(w, it) }
                items += w
                pendingName = null
                pendingMeasure = null
                continue
            }

            val candidate = cleanName(text)
            pendingName = if (ReceiptText.letterCount(candidate) >= 2) candidate else null
        }

        flushPending()
        if (pendingMeasure != null) warnings += "A quantity or weight line was not matched to an item."
        if (date == null) warnings += "Could not find the date."
        if (total == null) warnings += "Could not find the total."

        val draftItems = items.map { toDraftItem(it) }
        return ReceiptDraft(profile.store, date, draftItems, total, source, warnings)
    }

    private fun parseWeight(m: MatchResult): Measure? {
        val (numText, numFixed) = ReceiptText.fixNumber(m.groupValues[2]) ?: return null
        val value = numText.toDoubleOrNull() ?: return null
        val grams = if (m.groupValues[3].equals("g", ignoreCase = true)) Math.round(value) else Math.round(value * 1000)
        val perKg = ReceiptText.parseLooseMoney(m.groupValues[4]) ?: return null
        val total = m.groupValues[5].takeIf { it.isNotEmpty() }?.let { ReceiptText.parseAmount(it) }
        if (grams <= 0 || grams > Int.MAX_VALUE) return null
        return Measure(
            quantity = null,
            weightGrams = grams.toInt(),
            unit = perKg.amount,
            total = total?.amount,
            fixed = numFixed || perKg.ocrFixed || total?.ocrFixed == true,
        )
    }

    private fun parseQuantity(m: MatchResult): Measure? {
        val qty = m.groupValues[1].toIntOrNull() ?: return null
        val unit = ReceiptText.parseAmount(m.groupValues[2]) ?: return null
        val total = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ReceiptText.parseAmount(it) }
        if (qty < 1) return null
        return Measure(qty, null, unit.amount, total?.amount, unit.ocrFixed || total?.ocrFixed == true)
    }

    private fun cleanName(raw: String): String =
        ReceiptText.collapseSpaces(CODE_PUNCTUATION.replace(raw.trim(), ""))

    private fun toDraftItem(w: Work): DraftItem {
        var penalty = w.penalty
        if (w.ocrFixed) penalty += PENALTY_OCR
        penalty += namePenalty(w.name)
        if (w.price.pence == 0L || w.price.pence > SUSPICIOUS_PRICE_PENCE) penalty += PENALTY_PRICE
        if (w.discount.abs() > w.price) penalty += PENALTY_PRICE
        val confidence = (1f - penalty).coerceIn(MIN_CONFIDENCE, 1f)
        return DraftItem(
            rawName = w.name,
            quantity = w.quantity,
            weightGrams = w.weightGrams,
            unitPrice = w.unitPrice,
            price = w.price,
            discount = w.discount,
            confidence = confidence,
        )
    }

    private val SANDWICHED_DIGIT = Regex("""[A-Za-z][0-9][A-Za-z]""")
    private val ODD_CHARS = Regex("""[^A-Za-z0-9 .&'%/+\-]""")

    /** Names with few letters, stray symbols or digits inside words usually mean a misread. */
    private fun namePenalty(name: String): Float {
        var p = 0f
        if (ReceiptText.letterCount(name) < 3) p += PENALTY_NAME
        if (ODD_CHARS.findAll(name).count() * 5 > name.length) p += PENALTY_NAME
        if (name.split(' ').any { SANDWICHED_DIGIT.containsMatchIn(it) }) p += PENALTY_NAME
        return p
    }

    private const val PENALTY_OCR = 0.3f
    private const val PENALTY_NAME = 0.2f
    private const val PENALTY_PRICE = 0.3f
    private const val PENALTY_MEASURE = 0.2f
    private const val MIN_CONFIDENCE = 0.1f
    private const val SUSPICIOUS_PRICE_PENCE = 30_000L
}
