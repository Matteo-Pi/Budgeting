package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** One successfully parsed statement row. [rowIndex] is its index into the source [CsvTable.rows]. */
data class ParsedRow(
    val date: LocalDate,
    /** Signed: negative = money out. */
    val amount: Money,
    val description: String,
    val balance: Money?,
    val rowIndex: Int,
)

data class RowError(val rowIndex: Int, val message: String)

data class ParseOutcome(val rows: List<ParsedRow>, val errors: List<RowError>)

/** Turns a generic [CsvTable] into bank statement rows. See docs/PLAN.md §5.16. */
object StatementImport {

    private val DATE_HEADERS = listOf("DATE", "TRANSACTION DATE")
    private val DESCRIPTION_HEADERS = listOf("DESCRIPTION", "DETAILS", "NARRATIVE", "REFERENCE")
    private val AMOUNT_HEADERS = listOf("AMOUNT", "VALUE")
    private val CREDIT_HEADERS = listOf("MONEY IN", "PAID IN", "CREDIT")
    private val DEBIT_HEADERS = listOf("MONEY OUT", "PAID OUT", "DEBIT")
    private val BALANCE_HEADERS = listOf("BALANCE")

    /** In priority order; the first pattern every sampled date fully matches wins. */
    private val DATE_PATTERNS = listOf(
        "dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "d MMM yyyy", "dd MMM yyyy", "d/M/yyyy",
    )

    fun guessMapping(table: CsvTable): StatementMapping {
        val firstRow = table.rows.firstOrNull().orEmpty()
        val headerNames = firstRow.map { it.trim().uppercase(Locale.UK) }

        val dateCol = headerNames.indexOfFirstMatch(DATE_HEADERS)
        val descCol = headerNames.indexOfFirstMatch(DESCRIPTION_HEADERS)
        val amountCol = headerNames.indexOfFirstMatch(AMOUNT_HEADERS)
        val creditCol = headerNames.indexOfFirstMatch(CREDIT_HEADERS)
        val debitCol = headerNames.indexOfFirstMatch(DEBIT_HEADERS)
        val balanceCol = headerNames.indexOfFirstMatch(BALANCE_HEADERS)

        val hasHeader = dateCol >= 0 && descCol >= 0 && (amountCol >= 0 || (creditCol >= 0 && debitCol >= 0))

        val sampleDateCol = if (hasHeader) dateCol else 0
        val dataRows = if (hasHeader) table.rows.drop(1) else table.rows
        val dateSamples = dataRows.mapNotNull { row -> row.getOrNull(sampleDateCol)?.takeIf { it.isNotBlank() } }
            .take(5)
        val datePattern = guessDatePattern(dateSamples)

        if (!hasHeader) {
            return StatementMapping(
                hasHeader = false,
                dateColumn = 0,
                datePattern = datePattern,
                amountMode = AmountMode.SIGNED,
                amountColumn = 2,
                descriptionColumn = 1,
                balanceColumn = null,
            )
        }

        return if (amountCol < 0 && creditCol >= 0 && debitCol >= 0) {
            StatementMapping(
                hasHeader = true,
                dateColumn = dateCol,
                datePattern = datePattern,
                amountMode = AmountMode.DEBIT_CREDIT,
                debitColumn = debitCol,
                creditColumn = creditCol,
                descriptionColumn = descCol,
                balanceColumn = balanceCol.takeIf { it >= 0 },
            )
        } else {
            StatementMapping(
                hasHeader = true,
                dateColumn = dateCol,
                datePattern = datePattern,
                amountMode = AmountMode.SIGNED,
                amountColumn = amountCol,
                descriptionColumn = descCol,
                balanceColumn = balanceCol.takeIf { it >= 0 },
            )
        }
    }

    fun parseRows(table: CsvTable, mapping: StatementMapping): ParseOutcome {
        val dataRows = if (mapping.hasHeader) table.rows.drop(1) else table.rows
        val startIndex = if (mapping.hasHeader) 1 else 0
        // Locale.ENGLISH (not Locale.UK) so month names parse as the standard "Sep" etc.;
        // modern CLDR data has en_GB abbreviate September as "Sept".
        val formatter = DateTimeFormatter.ofPattern(mapping.datePattern, Locale.ENGLISH)

        val rows = mutableListOf<ParsedRow>()
        val errors = mutableListOf<RowError>()

        dataRows.forEachIndexed { i, row ->
            val rowIndex = startIndex + i
            if (row.all { it.isBlank() }) return@forEachIndexed

            val dateText = row.getOrNull(mapping.dateColumn)?.trim().orEmpty()
            val date = try {
                LocalDate.parse(dateText, formatter)
            } catch (e: DateTimeParseException) {
                errors += RowError(rowIndex, "Couldn't read the date \"$dateText\".")
                return@forEachIndexed
            }

            val amount = readAmount(row, mapping)
            if (amount == null) {
                errors += RowError(rowIndex, "Couldn't read the amount on this row.")
                return@forEachIndexed
            }

            val description = row.getOrNull(mapping.descriptionColumn)?.trim().orEmpty()
            val balance = mapping.balanceColumn?.let { col ->
                row.getOrNull(col)?.trim()?.takeIf { it.isNotBlank() }?.let { parseStatementAmount(it) }
            }

            rows += ParsedRow(date, amount, description, balance, rowIndex)
        }

        return ParseOutcome(rows, errors)
    }

    private fun readAmount(row: List<String>, mapping: StatementMapping): Money? = when (mapping.amountMode) {
        AmountMode.SIGNED -> {
            val text = row.getOrNull(mapping.amountColumn ?: -1)?.trim().orEmpty()
            parseStatementAmount(text)
        }
        AmountMode.DEBIT_CREDIT -> {
            val debitText = row.getOrNull(mapping.debitColumn ?: -1)?.trim().orEmpty()
            val creditText = row.getOrNull(mapping.creditColumn ?: -1)?.trim().orEmpty()
            val debit = if (debitText.isBlank()) Money.ZERO else parseStatementAmount(debitText)?.abs()
            val credit = if (creditText.isBlank()) Money.ZERO else parseStatementAmount(creditText)?.abs()
            if (debit == null || credit == null) null else credit - debit
        }
    }

    private fun List<String>.indexOfFirstMatch(names: List<String>): Int =
        indexOfFirst { cell -> names.any { it == cell } }

    private fun guessDatePattern(samples: List<String>): String {
        if (samples.isEmpty()) return DATE_PATTERNS.first()
        for (pattern in DATE_PATTERNS) {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
            val allParse = samples.all { sample ->
                try {
                    LocalDate.parse(sample, formatter)
                    true
                } catch (e: DateTimeParseException) {
                    false
                }
            }
            if (allParse) return pattern
        }
        return DATE_PATTERNS.first()
    }

    /** Accepts "£", thousands commas, a leading "-", "(12.50)" negatives, and "CR"/"DR" suffixes. */
    private fun parseStatementAmount(raw: String): Money? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        val upper = s.uppercase(Locale.UK)
        if (upper.endsWith("CR")) {
            s = s.substring(0, s.length - 2).trim()
        } else if (upper.endsWith("DR")) {
            negative = true
            s = s.substring(0, s.length - 2).trim()
        }
        s = s.replace("£", "").replace(",", "").trim()
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1).trim()
        } else if (s.startsWith("+")) {
            s = s.substring(1).trim()
        }
        if (s.isEmpty() || !s.matches(Regex("""\d+(\.\d{1,2})?|\.\d{1,2}"""))) return null
        val parts = s.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = parts.getOrNull(1).orEmpty().padEnd(2, '0').ifEmpty { "00" }.toLong()
        val value = whole * 100 + frac
        return Money(if (negative) -value else value)
    }
}
