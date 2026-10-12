package io.github.matteopi.budgeting.core.receipts

/**
 * Reads the rows of one receipt. The store parsers are thin: they only say how to recognise their
 * store and which loyalty-price wording they know ([StoreProfile]); the reading itself is shared
 * in [ReceiptEngine]. Written from common UK till-receipt conventions and to be tuned on real
 * receipts later.
 */
interface ReceiptParser {
    /** True if [rows] look like this parser's store (the store name near the top, or wording only it prints). */
    fun detect(rows: List<ReceiptRow>): Boolean

    fun parse(rows: List<ReceiptRow>, source: ReceiptSource): ReceiptDraft
}

private class ProfileParser(private val profile: StoreProfile, private val alwaysDetects: Boolean = false) : ReceiptParser {
    override fun detect(rows: List<ReceiptRow>) = alwaysDetects || ReceiptEngine.detect(rows, profile)

    override fun parse(rows: List<ReceiptRow>, source: ReceiptSource) = ReceiptEngine.parse(rows, source, profile)
}

/** Tesco: "Clubcard Price" / "CC PRICE" lines. */
object TescoParser : ReceiptParser by ProfileParser(StoreProfiles.TESCO)

/** Sainsbury's: "Nectar Price" lines. */
object SainsburysParser : ReceiptParser by ProfileParser(StoreProfiles.SAINSBURYS)

/** Morrisons: "More Card" / "More Card Price" lines. */
object MorrisonsParser : ReceiptParser by ProfileParser(StoreProfiles.MORRISONS)

/** Any other shop (or an unreadable store name); understands every store's loyalty wording. Store = OTHER. */
object GenericParser : ReceiptParser by ProfileParser(StoreProfiles.GENERIC, alwaysDetects = true)

object ReceiptParsers {

    /** In detection order; [GenericParser] is the fallback and not part of this list. */
    val storeParsers: List<ReceiptParser> = listOf(TescoParser, SainsburysParser, MorrisonsParser)

    /** The first store parser that detects [rows], else [GenericParser]. */
    fun choose(rows: List<ReceiptRow>): ReceiptParser = storeParsers.firstOrNull { it.detect(rows) } ?: GenericParser

    fun parse(rows: List<ReceiptRow>, source: ReceiptSource): ReceiptDraft = choose(rows).parse(rows, source)

    /** OCR fragments: grouped into rows, then parsed. */
    fun parseLines(lines: List<ReceiptLine>, source: ReceiptSource): ReceiptDraft =
        parse(LineGrouping.rows(lines), source)

    /** Plain text, one receipt line per line. */
    fun parseText(text: String, source: ReceiptSource): ReceiptDraft = parse(LineGrouping.rows(text), source)
}
