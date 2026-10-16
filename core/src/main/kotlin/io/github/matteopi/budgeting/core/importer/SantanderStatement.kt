package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** One transaction row of a Santander statement. */
data class StatementLine(
    /** [ParsedRow.date] is the card date when the row has one; [ParsedRow.balance] is the balance after the row. */
    val row: ParsedRow,
    /** The description as printed (wrapped lines joined), before [SantanderStatement.cleanDescription]. */
    val rawDescription: String,
    /** The date the bank posted the row (the statement's own date column). */
    val postedDate: LocalDate,
    /** Looks like a move between the user's own accounts rather than spending. */
    val likelyTransfer: Boolean,
)

/** A Santander PDF statement read from its text layer. [problems] are sentences for the user. */
data class ParsedStatement(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val accountHolder: String?,
    val openingBalance: Money?,
    val closingBalance: Money?,
    val totalIn: Money?,
    /** Stored positive. */
    val totalOut: Money?,
    val lines: List<StatementLine>,
    val problems: List<String>,
)

sealed interface SantanderParseResult {
    data class Success(val statement: ParsedStatement) : SantanderParseResult

    /** [reason] is a plain sentence for the user. */
    data class Unreadable(val reason: String) : SantanderParseResult
}

/**
 * Reads a Santander UK PDF statement from [PdfCells] (the PDF's own text layer, no OCR), checks every
 * row against the running balance and the statement's totals, tidies the descriptions and flags
 * moves between the user's own accounts. See docs/PLAN.md §5.16 for the layout and the rules.
 */
object SantanderStatement {

    private const val NOT_FOUND =
        "I couldn't find the list of transactions in this PDF. Santander may have changed how its statements look."
    private const val NO_ROWS = "I found the transactions section of this PDF but no transaction rows in it."
    private const val NO_DIRECTION = "I couldn't tell which of the transactions in this PDF are money in and which are money out."

    private const val SAME_LINE = 0.3f
    private const val ROW_WINDOW = 2.5f
    private const val DATE_COLUMN_TOLERANCE = 2f

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val WHITESPACE = Regex("""\s+""")
    private val SPACE_COMMA = Regex("""\s+,""")

    private val PERIOD = Regex(
        """^Your transactions\s+(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3})[A-Za-z]*\s+(\d{4})""" +
            """\s+to\s+(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3})[A-Za-z]*\s+(\d{4})""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_CELL = Regex("""^(\d{1,2})(?:st|nd|rd|th) ([A-Z][a-z]{2})$""")

    /** Groups: 1 sign before the pound sign, 2 sign after it, 3 the digits, 4 a trailing sign or DR/OD/CR. */
    private val MONEY = Regex(
        """^([-−])?\s*£?\s*([-−])?\s*((?:\d{1,3}(?:,\d{3})+|\d{1,12})\.\d{2})\s*(-|−|DR|OD|CR)?$""",
    )
    private val MONEY_TAIL = Regex("""[-−]?\s*£?\s*(?:\d{1,3}(?:,\d{3})+|\d{1,12})\.\d{2}\s*(?:-|−|DR|OD|CR)?$""")

    private val CARD_DATE = Regex("""\bON\s+(\d{1,2})-(\d{1,2})-(\d{4})\b""")

    private val OPENING_LABEL = Regex("""^Balance brought forward from previous Statement""", RegexOption.IGNORE_CASE)
    private val TOTAL_IN_LABEL = Regex("""^Total money in\b""", RegexOption.IGNORE_CASE)
    private val TOTAL_OUT_LABEL = Regex("""^Total money out\b""", RegexOption.IGNORE_CASE)
    private val CLOSING_LABEL = Regex("""^Your balance at close of business""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_NAME = Regex("""^Account name:?\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** True if [cells] (from [PdfCells.merge]) look like a Santander statement. */
    fun looksLikeSantander(cells: List<PdfTextChunk>): Boolean =
        cells.any { it.text.contains("santander", ignoreCase = true) } &&
            cells.any { it.text.startsWith("Your transactions", ignoreCase = true) }

    fun parse(cells: List<PdfTextChunk>): SantanderParseResult {
        val heading = findHeading(cells) ?: return SantanderParseResult.Unreadable(NOT_FOUND)
        val header = (heading.index + 1 until cells.size).firstNotNullOfOrNull { headerAt(cells, it) }
            ?: return SantanderParseResult.Unreadable(NOT_FOUND)

        val holder = findAccountHolder(cells)
        val reader = TableReader(cells, heading.start, heading.end, header, holder)
        reader.read()?.let { return SantanderParseResult.Unreadable(it) }
        if (reader.lines.isEmpty()) return SantanderParseResult.Unreadable(NO_ROWS)

        val problems = ArrayList(reader.problems)
        val summaryOpening = summaryValue(cells, reader.consumed, OPENING_LABEL)
        val summaryClosing = summaryValue(cells, reader.consumed, CLOSING_LABEL)
        val totalIn = summaryValue(cells, reader.consumed, TOTAL_IN_LABEL)?.abs()
        val totalOut = summaryValue(cells, reader.consumed, TOTAL_OUT_LABEL)?.abs()

        val opening = summaryOpening ?: reader.tableOpening
        val closing = summaryClosing ?: reader.carriedForward
        if (summaryOpening != null && reader.tableOpening != null && summaryOpening != reader.tableOpening) {
            problems += "The statement's summary says the opening balance is ${summaryOpening.format()}, " +
                "but the transactions start from ${reader.tableOpening!!.format()}"
        }
        if (summaryClosing != null && reader.carriedForward != null && summaryClosing != reader.carriedForward) {
            problems += "The statement's summary says the closing balance is ${summaryClosing.format()}, " +
                "but the transactions end at ${reader.carriedForward!!.format()}"
        }

        val amounts = reader.lines.map { it.row.amount }
        val moneyIn = Money(amounts.filter { it.isPositive }.sumOf { it.pence })
        val moneyOut = Money(amounts.filter { it.isNegative }.sumOf { -it.pence })
        if (opening != null && closing != null) {
            val expected = opening + Money(amounts.sumOf { it.pence })
            if (expected != closing) {
                problems += "The transactions don't add up: ${opening.format()} at the start plus the rows comes to " +
                    "${expected.format()}, but the statement closes at ${closing.format()}"
            }
        }
        if (totalIn != null && totalIn != moneyIn) {
            problems += "The money in on the rows adds up to ${moneyIn.format()}, " +
                "but the statement says ${totalIn.format()}"
        }
        if (totalOut != null && totalOut != moneyOut) {
            problems += "The money out on the rows adds up to ${moneyOut.format()}, " +
                "but the statement says ${totalOut.format()}"
        }

        return SantanderParseResult.Success(
            ParsedStatement(
                periodStart = heading.start,
                periodEnd = heading.end,
                accountHolder = holder,
                openingBalance = opening,
                closingBalance = closing,
                totalIn = totalIn,
                totalOut = totalOut,
                lines = reader.lines,
                problems = problems,
            ),
        )
    }

    // ---- headings, columns and the summary -------------------------------------------------------

    private class Heading(val index: Int, val start: LocalDate, val end: LocalDate)

    /** Where the money columns end and where the dates start; [dateX] is null if the header had no "Date" cell. */
    private class Columns(val dateX: Float?, val moneyInRight: Float, val moneyOutRight: Float)

    private class Header(val columns: Columns, val endIndex: Int)

    private fun sameLine(a: PdfTextChunk, b: PdfTextChunk): Boolean =
        a.page == b.page && abs(a.y - b.y) <= SAME_LINE * max(a.height, b.height)

    private fun isLabel(cell: PdfTextChunk, label: String): Boolean =
        cell.text.replace(WHITESPACE, " ").trim().equals(label, ignoreCase = true)

    /** The first "Your transactions <date> to <date>" heading: that cell plus the cells to its right on the line. */
    private fun findHeading(cells: List<PdfTextChunk>): Heading? {
        for (i in cells.indices) {
            if (!cells[i].text.startsWith("Your transactions", ignoreCase = true)) continue
            val text = StringBuilder(cells[i].text)
            var j = i + 1
            while (j < cells.size && j < i + 8 && sameLine(cells[i], cells[j])) {
                text.append(' ').append(cells[j].text)
                j++
            }
            val match = PERIOD.find(text.toString().replace(WHITESPACE, " ").trim()) ?: continue
            val (d1, m1, y1, d2, m2, y2) = match.destructured
            val start = date(y1.toInt(), monthNumber(m1), d1.toInt()) ?: continue
            val end = date(y2.toInt(), monthNumber(m2), d2.toInt()) ?: continue
            if (end.isBefore(start)) continue
            return Heading(i, start, end)
        }
        return null
    }

    /** The table header row if cell [i] is one of its "Money in" / "Money out" cells. */
    private fun headerAt(cells: List<PdfTextChunk>, i: Int): Header? {
        val cell = cells[i]
        var moneyIn: PdfTextChunk? = if (isLabel(cell, "Money in")) cell else null
        var moneyOut: PdfTextChunk? = if (isLabel(cell, "Money out")) cell else null
        if (moneyIn == null && moneyOut == null) return null
        var endIndex = i
        for (j in cells.indices) {
            if (j == i || !sameLine(cell, cells[j])) continue
            if (moneyIn == null && isLabel(cells[j], "Money in")) {
                moneyIn = cells[j]
                endIndex = max(endIndex, j)
            }
            if (moneyOut == null && isLabel(cells[j], "Money out")) {
                moneyOut = cells[j]
                endIndex = max(endIndex, j)
            }
        }
        if (moneyIn == null || moneyOut == null) return null
        val dateCell = cells.firstOrNull { it !== cell && sameLine(cell, it) && isLabel(it, "Date") }
        return Header(Columns(dateCell?.x, moneyIn.endX, moneyOut.endX), endIndex)
    }

    /** "Account name" followed by the name in the next cell, or "Account name: X" in one cell. */
    private fun findAccountHolder(cells: List<PdfTextChunk>): String? {
        for (i in cells.indices) {
            val match = ACCOUNT_NAME.find(cells[i].text.trim()) ?: continue
            val inline = match.groupValues[1].trim()
            if (inline.isNotEmpty()) return inline
            val next = cells.getOrNull(i + 1) ?: continue
            if (sameLine(cells[i], next) && next.text.isNotBlank() && parseMoney(next.text) == null) return next.text.trim()
        }
        return null
    }

    /** The money cell to the right of the first summary cell matching [label] (outside the table). */
    private fun summaryValue(cells: List<PdfTextChunk>, consumed: BooleanArray, label: Regex): Money? {
        for (i in cells.indices) {
            if (consumed[i] || !label.containsMatchIn(cells[i].text.trim())) continue
            val value = cells.indices
                .filter { j -> j != i && !consumed[j] && sameLine(cells[i], cells[j]) && cells[j].x > cells[i].x }
                .filter { j -> parseMoney(cells[j].text) != null }
                .minByOrNull { j -> cells[j].x }
            if (value != null) return parseMoney(cells[value].text)
            // The value may share the label's cell ("Total money in: £500.00").
            MONEY_TAIL.find(cells[i].text.trim())?.let { tail -> parseMoney(tail.value)?.let { return it } }
        }
        return null
    }

    // ---- the transactions table -------------------------------------------------------------------

    private class TableReader(
        private val cells: List<PdfTextChunk>,
        private val periodStart: LocalDate,
        private val periodEnd: LocalDate,
        private val firstHeader: Header,
        private val holder: String?,
    ) {
        val lines = ArrayList<StatementLine>()
        val problems = ArrayList<String>()

        /** Cells that belong to a table row (so the summary search does not read them). */
        val consumed = BooleanArray(cells.size)

        var tableOpening: Money? = null
        var carriedForward: Money? = null

        private var columns = firstHeader.columns
        private var running: Money? = null

        /** Reads every row; returns why the statement can't be read, or null. */
        fun read(): String? {
            var page = cells[firstHeader.endIndex].page
            var ignoring = false
            var i = firstHeader.endIndex + 1
            while (i < cells.size) {
                val cell = cells[i]
                if (cell.page != page) {
                    // A later page: skip everything until its own header row shows the columns again.
                    page = cell.page
                    ignoring = true
                }
                if (ignoring) {
                    val header = headerAt(cells, i)
                    if (header != null) {
                        columns = header.columns
                        ignoring = false
                        i = max(i, header.endIndex) + 1
                    } else {
                        i++
                    }
                    continue
                }
                if (!isRowStart(cell)) {
                    i++
                    continue
                }

                consumed[i] = true
                val rowCells = ArrayList<PdfTextChunk>()
                var j = i + 1
                while (j < cells.size && cells[j].page == cell.page && !isRowStart(cells[j])) {
                    if (abs(cells[j].y - cell.y) <= ROW_WINDOW * cell.height) {
                        rowCells += cells[j]
                        consumed[j] = true
                    }
                    j++
                }
                when (val outcome = readRow(cell, rowCells)) {
                    RowOutcome.Continue -> Unit
                    RowOutcome.End -> return null
                    is RowOutcome.Failed -> return outcome.reason
                }
                i = j
            }
            return null
        }

        private sealed interface RowOutcome {
            data object Continue : RowOutcome
            data object End : RowOutcome
            data class Failed(val reason: String) : RowOutcome
        }

        private fun isRowStart(cell: PdfTextChunk): Boolean {
            if (!DATE_CELL.matches(cell.text.trim())) return false
            val dateX = columns.dateX ?: return true
            return abs(cell.x - dateX) <= DATE_COLUMN_TOLERANCE * cell.height
        }

        private fun readRow(dateCell: PdfTextChunk, rowCells: List<PdfTextChunk>): RowOutcome {
            val moneyCells = rowCells.withIndex()
                .filter { parseMoney(it.value.text) != null }
                .sortedBy { it.value.endX }
                .takeLast(2)
            val moneyIndices = moneyCells.map { it.index }.toSet()
            val description = rowCells.filterIndexed { index, _ -> index !in moneyIndices }
                .joinToString(" ") { it.text }
                .replace(WHITESPACE, " ")
                .replace(SPACE_COMMA, ",")
                .trim()
            val lower = description.lowercase(Locale.ENGLISH)

            val isBalanceRow = lower.startsWith("opening balance") || lower.startsWith("balance brought forward") ||
                lower.startsWith("balance carried forward")
            if (isBalanceRow) {
                val balance = moneyCells.lastOrNull()?.let { parseMoney(it.value.text) } ?: return RowOutcome.Continue
                return readBalanceRow(lower, balance)
            }

            val postedDate = postedDate(dateCell.text.trim())
            val label = dateCell.text.trim() + if (description.isNotEmpty()) " " + cleanDescription(description) else ""
            if (postedDate == null) {
                problems += "Couldn't read the date on the row \"$label\""
                return RowOutcome.Continue
            }
            if (moneyCells.size < 2) {
                problems += "Couldn't read the amount and balance on the row \"$label\""
                return RowOutcome.Continue
            }

            val amountCell = moneyCells[0].value
            val magnitude = parseMoney(amountCell.text)!!.abs()
            val balance = parseMoney(moneyCells[1].value.text)!!
            val cleaned = cleanDescription(description).ifBlank { "Transaction" }
            val rowNumber = lines.size + 1

            val previous = running
            val chain = previous?.let { balance - it }
            val signed = if (chain != null && chain.abs() == magnitude) {
                if (chain.isNegative) -magnitude else magnitude
            } else {
                val byPosition = directionByPosition(amountCell) ?: return RowOutcome.Failed(NO_DIRECTION)
                if (chain != null) {
                    problems += "Row $rowNumber (${shortDate(postedDate)}, $cleaned): the balance doesn't follow from the amount"
                }
                if (byPosition) magnitude else -magnitude
            }
            running = balance

            val cardDate = CARD_DATE.findAll(description).lastOrNull()?.let { match ->
                val (d, m, y) = match.destructured
                date(y.toInt(), m.toInt(), d.toInt())
            }?.takeIf { !it.isAfter(postedDate) }

            lines += StatementLine(
                row = ParsedRow(
                    date = cardDate ?: postedDate,
                    amount = signed,
                    description = cleaned,
                    balance = balance,
                    rowIndex = lines.size,
                ),
                rawDescription = description,
                postedDate = postedDate,
                likelyTransfer = likelyTransfer(description, holder),
            )
            return RowOutcome.Continue
        }

        private fun readBalanceRow(lowerDescription: String, balance: Money): RowOutcome {
            val previous = running
            val carried = lowerDescription.startsWith("balance carried forward")
            val atEnd = lowerDescription.startsWith("balance carried forward to next statement")
            if (atEnd) {
                carriedForward = balance
            } else if (!carried && tableOpening == null && previous == null) {
                tableOpening = balance
            }
            if (previous != null && previous != balance) {
                problems += if (carried) {
                    "The balance carried forward (${balance.format()}) doesn't match the last row's balance (${previous.format()})"
                } else {
                    "The balance brought forward (${balance.format()}) doesn't match the balance before it (${previous.format()})"
                }
            }
            running = balance
            return if (atEnd) RowOutcome.End else RowOutcome.Continue
        }

        /** True = money in, false = money out, null if the cell sits exactly between the two columns. */
        private fun directionByPosition(amountCell: PdfTextChunk): Boolean? {
            val toIn = abs(amountCell.endX - columns.moneyInRight)
            val toOut = abs(amountCell.endX - columns.moneyOutRight)
            return when {
                abs(toIn - toOut) < 0.01f -> null
                toIn < toOut -> true
                else -> false
            }
        }

        /** Rows have no year: it comes from the period, which may run from December into January. */
        private fun postedDate(text: String): LocalDate? {
            val match = DATE_CELL.matchEntire(text) ?: return null
            val month = monthNumber(match.groupValues[2])
            val year = when {
                periodStart.year == periodEnd.year -> periodStart.year
                month >= periodStart.monthValue -> periodStart.year
                else -> periodEnd.year
            }
            return date(year, month, match.groupValues[1].toInt())
        }
    }

    // ---- values -------------------------------------------------------------------------------------

    private fun monthNumber(abbreviation: String): Int =
        MONTHS.indexOfFirst { it.equals(abbreviation, ignoreCase = true) } + 1

    private fun date(year: Int, month: Int, day: Int): LocalDate? = try {
        if (month < 1) null else LocalDate.of(year, month, day)
    } catch (e: DateTimeException) {
        null
    }

    private fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

    /** A signed amount from a cell that is entirely money ("£1,234.50", "-£3.00", "12.00-", "5.00 OD"), else null. */
    private fun parseMoney(text: String): Money? {
        val match = MONEY.matchEntire(text.trim()) ?: return null
        val (before, after, digits, suffix) = match.destructured
        val parts = digits.replace(",", "").split(".")
        val pence = (parts[0].toLongOrNull() ?: return null) * 100 + (parts[1].toLongOrNull() ?: return null)
        val negative = before.isNotEmpty() || after.isNotEmpty() || suffix == "-" || suffix == "−" || suffix == "DR" || suffix == "OD"
        return Money(if (negative) -pence else pence)
    }

    // ---- descriptions -------------------------------------------------------------------------------

    private val MANDATE = Regex("""\s*,?\s*MANDATE NO\b\.?\s*\d*""", RegexOption.IGNORE_CASE)
    private val VIA_GROUP = Regex("""\s*\(VIA\s[^)]*\)""", RegexOption.IGNORE_CASE)
    private val CARD_DATE_TAIL = Regex("""[\s,]*\bON\s+\d{1,2}-\d{1,2}-\d{4}\s*$""", RegexOption.IGNORE_CASE)
    private val CARD_PREFIX = Regex("""^CARD PAYMENT TO\s+""", RegexOption.IGNORE_CASE)
    private val FOREIGN_TAIL = Regex(
        """\s*,\s*[\d,]+(?:\.\d+)?\s*[A-Z]{3}(?:\s*,\s*RATE\s+[\d.]+\s*/\s*[A-Z]{3})?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_DASH = Regex("""(?:\s+-+)+$""")
    private val PAYMENT_TO = Regex(
        """^(?:BILL PAYMENT VIA FASTER PAYMENT|STANDING ORDER VIA FASTER PAYMENT|DIRECT DEBIT PAYMENT|""" +
            """STANDING ORDER|FASTER PAYMENT|BILL PAYMENT)\s+TO\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val RECEIPT = Regex(
        """^FASTER PAYMENTS? RECEIPT(?:\s+REF\.?\s*(.*?))?\s+FROM\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val REFERENCE = Regex("""\s+REF(?:ERENCE)?\b[:.]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val DIGITS_ONLY = Regex("""^[\d\s-]*$""")
    private val TRANSFER_PREFIX = Regex(
        """^(?:TRANSFER (?:TO|FROM)\b|REGULAR TRANSFER PAYMENT (?:TO|FROM) ACCOUNT\b)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The description as the user wants to see it: the merchant for card payments ("UNION SHOP (VIA
     * GOOGLE PAY), ON 05-10-2026" becomes "UNION SHOP"), "<name>, ref <reference>" for bill payments,
     * standing orders and direct debits, "From <payer>" for faster payments received (an all-digit
     * reference is a sort code and account number, so it is dropped), transfers as they are; the
     * mandate number is always removed.
     */
    fun cleanDescription(raw: String): String {
        val original = raw.replace(WHITESPACE, " ").replace(SPACE_COMMA, ",").trim()
        val withoutMandate = MANDATE.replace(original, "").trim()

        val isCard = VIA_GROUP.containsMatchIn(withoutMandate) || CARD_DATE_TAIL.containsMatchIn(withoutMandate) ||
            CARD_PREFIX.containsMatchIn(withoutMandate)
        val cleaned = when {
            isCard -> cleanCard(withoutMandate)
            else -> cleanPayment(withoutMandate)
        }
        return cleaned.replace(WHITESPACE, " ").replace(SPACE_COMMA, ",").trim().ifEmpty { original }
    }

    private fun cleanCard(text: String): String {
        var s = VIA_GROUP.replace(text, "")
        s = CARD_DATE_TAIL.replace(s, "")
        s = CARD_PREFIX.replace(s, "")
        s = FOREIGN_TAIL.replace(s, "")
        s = s.trim().trimEnd(',').trim()
        return TRAILING_DASH.replace(s, "").trim()
    }

    private fun cleanPayment(text: String): String {
        RECEIPT.matchEntire(text)?.let { match ->
            val (payer, tailReference) = splitReference(match.groupValues[2])
            val reference = match.groupValues[1].trim().ifEmpty { tailReference }
            val keepReference = reference.isNotEmpty() && !DIGITS_ONLY.matches(reference)
            return "From $payer" + if (keepReference) ", ref $reference" else ""
        }
        PAYMENT_TO.matchEntire(text)?.let { match ->
            val (name, reference) = splitReference(match.groupValues[1])
            return name + if (reference.isNotEmpty()) ", ref $reference" else ""
        }
        return text
    }

    /** "ALEX TESTER REFERENCE rent" -> ("ALEX TESTER", "rent"); the reference keeps its case. */
    private fun splitReference(text: String): Pair<String, String> {
        val match = REFERENCE.find(text)
        val name = (if (match != null) text.substring(0, match.range.first) else text).trim().trimEnd(',').trim()
        val reference = match?.groupValues?.get(1)?.trim()?.trimEnd(',')?.trim().orEmpty()
        return name to reference
    }

    // ---- moves between the user's own accounts --------------------------------------------------------

    private val TITLES = setOf("MR", "MRS", "MS", "MISS", "MX", "DR", "PROF")

    /**
     * True for transfers between the user's accounts: a "TRANSFER TO/FROM" description or a regular
     * transfer to or from an account, or a faster payment to or from [holder] themselves (the statement's
     * account holder; titles are ignored, an initial stands for a name that starts with it).
     */
    fun likelyTransfer(rawDescription: String, holder: String?): Boolean {
        val text = rawDescription.replace(WHITESPACE, " ").trim()
        if (TRANSFER_PREFIX.containsMatchIn(text)) return true
        val counterparty = counterparty(text) ?: return false
        return isSamePerson(counterparty, holder)
    }

    /** The other party of a faster payment, bill payment, standing order or direct debit; null for anything else. */
    private fun counterparty(text: String): String? {
        val withoutMandate = MANDATE.replace(text, "").trim()
        val name = RECEIPT.matchEntire(withoutMandate)?.let { splitReference(it.groupValues[2]).first }
            ?: PAYMENT_TO.matchEntire(withoutMandate)?.let { splitReference(it.groupValues[1]).first }
            ?: return null
        return name.substringBefore(',').trim().ifEmpty { null }
    }

    private fun nameTokens(name: String): List<String> =
        name.uppercase(Locale.UK).split(Regex("[^A-Z]+")).filter { it.isNotEmpty() && it !in TITLES }

    private fun isSamePerson(counterparty: String, holder: String?): Boolean {
        if (holder == null) return false
        val holderTokens = nameTokens(holder)
        val tokens = nameTokens(counterparty)
        if (holderTokens.isEmpty() || tokens.isEmpty()) return false
        var fullMatches = 0
        for (token in tokens) {
            if (token in holderTokens) {
                fullMatches++
            } else if (token.length == 1 && holderTokens.any { it.startsWith(token) }) {
                continue
            } else {
                return false
            }
        }
        return fullMatches > 0
    }
}
