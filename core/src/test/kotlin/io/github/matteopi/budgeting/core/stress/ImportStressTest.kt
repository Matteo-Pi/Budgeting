package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.importer.AmountMode
import io.github.matteopi.budgeting.core.importer.CsvParser
import io.github.matteopi.budgeting.core.importer.CsvTable
import io.github.matteopi.budgeting.core.importer.StatementImport
import io.github.matteopi.budgeting.core.importer.StatementMapping
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Stress tests of the CSV parser and the statement importer: random garbage never throws, and
 * randomly generated well-formed files round-trip exactly. Replay one case with
 * `STRESS_SEED=<seed> STRESS_COUNT=1`; `STRESS_COUNT` scales every loop here as a multiplier
 * base (default counts below).
 */
class ImportStressTest {

    private val seedBase = env("STRESS_SEED", 1L)

    /** The default count, `STRESS_COUNT` if that asks for more, or exactly one case when replaying a seed (`STRESS_COUNT=1`). */
    private fun scaled(default: Int): Int {
        val requested = System.getenv("STRESS_COUNT")?.toIntOrNull() ?: return default
        return if (requested == 1) 1 else maxOf(default, requested)
    }

    private fun statementCaseText(random: Random): String =
        if (random.nextInt(3) == 0) Garbage.text(random) else Garbage.statementLike(random)

    // --- garbage never throws ---------------------------------------------------------------------------

    @Test
    fun csvParserNeverThrowsOnGarbageAndKeepsItsShapeGuarantees() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(4000)) {
            val seed = seedBase + i
            val text = Garbage.text(Random(seed))
            try {
                val table = CsvParser.parse(text)
                if (table.delimiter !in listOf(',', ';', '\t')) failures += CaseFailure(seed, "csv.delimiter", "delimiter '${table.delimiter}'")
                if (table.rows.any { it.isEmpty() }) failures += CaseFailure(seed, "csv.emptyRow", "a row with no cells")
                if (table.rows.any { row -> row.any { it != it.trim() } }) failures += CaseFailure(seed, "csv.untrimmed", "a cell keeps surrounding whitespace")
                val withBom = if (text.startsWith('\uFEFF')) table else CsvParser.parse("\uFEFF$text")
                if (withBom != table) failures += CaseFailure(seed, "csv.bom", "a leading BOM changes the result")
                if (CsvParser.parse(text) != table) failures += CaseFailure(seed, "csv.deterministic", "two parses differ")
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "csv.exception", "${t::class.simpleName}: ${t.message} on ${text.take(120).escaped()}")
            }
        }
        report("CsvParser garbage", failures) { seed -> Garbage.text(Random(seed)).take(300).escaped() }
    }

    @Test
    fun statementImportNeverThrowsOnGarbageOrRandomMappings() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(4000)) {
            val seed = seedBase + i
            val random = Random(seed)
            val text = statementCaseText(random)
            try {
                val table = CsvParser.parse(text)
                val guessed = StatementImport.guessMapping(table)
                checkOutcome(seed, table, guessed, failures)
                repeat(3) { checkOutcome(seed, table, Garbage.mapping(random), failures) }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "statement.exception", "${t::class.simpleName}: ${t.message} on ${text.take(120).escaped()}")
            }
        }
        report("StatementImport garbage", failures) { seed -> statementCaseText(Random(seed)).take(400).escaped() }
    }

    private fun checkOutcome(seed: Long, table: CsvTable, mapping: StatementMapping, failures: MutableList<CaseFailure>) {
        val outcome = StatementImport.parseRows(table, mapping)
        val firstData = if (mapping.hasHeader) 1 else 0
        val rowIndexes = outcome.rows.map { it.rowIndex }
        val errorIndexes = outcome.errors.map { it.rowIndex }
        val all = rowIndexes + errorIndexes
        if (all.any { it !in firstData until table.rows.size }) failures += CaseFailure(seed, "statement.rowIndexRange", "row index outside the data rows: $all")
        if (all.size != all.toSet().size) failures += CaseFailure(seed, "statement.rowIndexUnique", "a row is both parsed and an error, or repeated: $all")
        if (rowIndexes != rowIndexes.sorted() || errorIndexes != errorIndexes.sorted()) failures += CaseFailure(seed, "statement.rowIndexOrder", "results are not in row order")
        val nonBlank = (firstData until table.rows.size).filter { idx -> table.rows[idx].any { it.isNotBlank() } }
        if (all.sorted() != nonBlank) failures += CaseFailure(seed, "statement.everyRowAccountedFor", "non-blank rows $nonBlank vs parsed/errors ${all.sorted()}")
        if (outcome.errors.any { it.message.isBlank() }) failures += CaseFailure(seed, "statement.errorMessage", "blank error message")
        if (StatementImport.parseRows(table, mapping) != outcome) failures += CaseFailure(seed, "statement.deterministic", "two parses differ")
    }

    // --- well-formed round trips ------------------------------------------------------------------------------

    @Test
    fun wellFormedCsvRoundTripsExactly() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(3000)) {
            val seed = seedBase + i
            try {
                val random = Random(seed)
                val file = CsvFile.random(random)
                val table = CsvParser.parse(file.text)
                if (table.rows != file.rows) {
                    failures += CaseFailure(seed, "csv.roundTrip.rows", "expected ${file.rows.take(4)} but parsed ${table.rows.take(4)} (rows ${file.rows.size} vs ${table.rows.size}) from ${file.text.take(200).escaped()}")
                }
                if (table.delimiter != file.delimiter) {
                    failures += CaseFailure(seed, "csv.roundTrip.delimiter", "expected '${file.delimiter}' but detected '${table.delimiter}' from ${file.text.take(200).escaped()}")
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "csv.roundTrip.exception", "${t::class.simpleName}: ${t.message}")
            }
        }
        report("CSV round trip", failures) { seed -> CsvFile.random(Random(seed)).text.take(400).escaped() }
    }

    @Test
    fun wellFormedStatementsRoundTripExactly() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(3000)) {
            val seed = seedBase + i
            try {
                val statement = Statement.random(Random(seed))
                val table = CsvParser.parse(statement.text)
                val mapping = StatementImport.guessMapping(table)
                val outcome = StatementImport.parseRows(table, mapping)
                val parsed = outcome.rows.map { Triple(it.date, it.amount.pence, it.description) to it.balance?.pence }
                val expected = statement.rows.map { Triple(it.date, it.pence, it.description) to it.balance }
                if (outcome.errors.isNotEmpty()) {
                    failures += CaseFailure(seed, "statement.roundTrip.errors", "${outcome.errors.take(3)} for ${statement.text.take(300).escaped()}")
                }
                if (parsed != expected) {
                    failures += CaseFailure(seed, "statement.roundTrip.rows", "expected ${expected.take(3)} but parsed ${parsed.take(3)} (${expected.size} vs ${parsed.size}); mapping=$mapping for ${statement.text.take(300).escaped()}")
                }
                for (row in outcome.rows) {
                    val cells = table.rows.getOrNull(row.rowIndex)
                    if (cells?.getOrNull(mapping.descriptionColumn) != row.description) {
                        failures += CaseFailure(seed, "statement.roundTrip.rowIndex", "row index ${row.rowIndex} does not point at '${row.description}'")
                        break
                    }
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "statement.roundTrip.exception", "${t::class.simpleName}: ${t.message}")
            }
        }
        report("Statement round trip", failures) { seed -> Statement.random(Random(seed)).text.take(500).escaped() }
    }

    private fun report(title: String, failures: List<CaseFailure>, describe: (Long) -> String) {
        if (failures.isEmpty()) return
        fail(StressReport.render(title, failures, describe, "STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*ImportStressTest*'"))
    }
}

internal fun String.escaped(): String =
    replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\uFEFF", "<BOM>")

/** Random text that is nothing like a statement, and text that is almost like one. */
internal object Garbage {
    private val pieces = listOf(
        ",", ",", ",", ";", ";", "\t", "\"", "\"", "\"\"", "\n", "\n", "\r\n", "\r", " ", "  ", "a", "B", "z", "0", "1", "9", "12", "2026",
        "£", "-", "(", ")", ".", "/", "CR", "DR", "Date", "Description", "Amount", "Balance", "Money in", "Money out", "Debit", "Credit",
        "\uFEFF", "é", "日本", "😀", "\u0000", "\\", "'", "=", "|", "%", "12.50", "-3.2", "(4.00)", "1,234.50", "31/02/2026", "01/13/2026",
    )

    fun text(r: Random): String {
        val n = r.nextInt(0, 300)
        return buildString { repeat(n) { append(pieces[r.nextInt(pieces.size)]) } }
    }

    private val headerNames = listOf(
        "Date", "Transaction date", "Description", "Details", "Narrative", "Reference", "Amount", "Value", "Money in", "Paid in", "Credit",
        "Money out", "Paid out", "Debit", "Balance", "Type", "", "date", " DATE ", "amount",
    )
    private val dateSamples = listOf(
        "01/09/2026", "31/12/26", "2026-09-01", "01-09-2026", "1 Sep 2026", "01 Sep 2026", "1/9/2026", "31/02/2026", "not a date", "", "2026-13-40",
    )
    private val amountSamples = listOf("-12.50", "£1,234.50", "(12.50)", "12.50CR", "3.00 DR", "+4", ".5", "1.234", "", "abc", "-", "£", "99999999999999999999", "0", "-0.00")

    /** A table-ish file: header-like row(s), then rows of random date/text/amount cells with ragged widths. */
    fun statementLike(r: Random): String {
        val delimiter = listOf(",", ";", "\t")[r.nextInt(3)]
        val eol = listOf("\n", "\r\n", "\r")[r.nextInt(3)]
        val sb = StringBuilder()
        if (r.nextInt(6) == 0) sb.append('\uFEFF')
        if (r.nextInt(4) != 0) {
            sb.append((0 until r.nextInt(1, 7)).joinToString(delimiter) { headerNames[r.nextInt(headerNames.size)] }).append(eol)
        }
        repeat(r.nextInt(0, 25)) {
            val cells = (0 until r.nextInt(1, 7)).map {
                when (r.nextInt(5)) {
                    0 -> dateSamples[r.nextInt(dateSamples.size)]
                    1, 2 -> amountSamples[r.nextInt(amountSamples.size)]
                    3 -> pieces[r.nextInt(pieces.size)] + "TESCO " + pieces[r.nextInt(pieces.size)]
                    else -> ""
                }
            }
            sb.append(cells.joinToString(delimiter) { if (r.nextInt(8) == 0) "\"" + it.replace("\"", "\"\"") + "\"" else it })
            if (r.nextInt(12) != 0) sb.append(eol)
        }
        return sb.toString()
    }

    private val datePatterns = listOf("dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "d MMM yyyy", "dd MMM yyyy", "d/M/yyyy", "yyyy/MM/dd", "MM/dd/yyyy")

    fun mapping(r: Random): StatementMapping {
        val mode = if (r.nextBoolean()) AmountMode.SIGNED else AmountMode.DEBIT_CREDIT
        fun column() = r.nextInt(-3, 9)
        fun optional(): Int? = if (r.nextInt(3) == 0) null else column()
        return StatementMapping(
            hasHeader = r.nextBoolean(),
            dateColumn = column(),
            datePattern = datePatterns[r.nextInt(datePatterns.size)],
            amountMode = mode,
            amountColumn = optional(),
            debitColumn = optional(),
            creditColumn = optional(),
            descriptionColumn = column(),
            balanceColumn = optional(),
        )
    }
}

/** A generated, well-formed CSV: the cells it was written from, the delimiter and the text. */
internal class CsvFile(val rows: List<List<String>>, val delimiter: Char, val text: String) {
    companion object {
        private val safe = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 £€é日本😀-_.()/&:*+#@!?'"
        private val tricky = listOf(",", ";", "\t", "\"", "\n", "\r\n", "\r", ",,", "\"\"", ";,")

        fun cell(r: Random): String {
            val n = r.nextInt(0, 14)
            val raw = buildString {
                repeat(n) { if (r.nextInt(6) == 0) append(tricky[r.nextInt(tricky.size)]) else append(safe[r.nextInt(safe.length)]) }
            }
            return raw.trim() // the parser trims every cell, so generate cells that are trim-stable
        }

        fun random(r: Random): CsvFile {
            val delimiter = listOf(',', ';', '\t')[r.nextInt(3)]
            val columns = r.nextInt(2, 9)
            val rowCount = r.nextInt(2, 30)
            val dataRows = List(rowCount) { List(columns) { cell(r) } }
            val eol = listOf("\n", "\r\n", "\r")[r.nextInt(3)]
            val sb = StringBuilder()
            val rows = mutableListOf<List<String>>()
            if (r.nextInt(5) == 0) sb.append('\uFEFF')
            for ((i, row) in dataRows.withIndex()) {
                rows += row
                sb.append(row.joinToString(delimiter.toString()) { quote(it, r) })
                if (i < dataRows.size - 1 || r.nextBoolean()) sb.append(eol)
                // An empty (or blank) line between records: it parses as a row of one empty cell.
                if (i < dataRows.size - 1 && r.nextInt(12) == 0) {
                    rows += listOf("")
                    sb.append(if (r.nextBoolean()) "" else "  ").append(eol)
                }
            }
            return CsvFile(rows, delimiter, sb.toString())
        }

        /** Quotes a cell that needs it (it holds any candidate delimiter, a quote or a line break) and sometimes one that does not. */
        fun quote(cell: String, r: Random): String {
            val needs = cell.any { it == ',' || it == ';' || it == '\t' || it == '"' || it == '\n' || it == '\r' }
            return if (needs || r.nextInt(10) == 0) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
        }
    }
}

/** One expected statement row. */
internal class StatementRow(val date: LocalDate, val pence: Long, val description: String, val balance: Long?)

/** A generated, well-formed bank statement export in one of the layouts and formats the importer supports. */
internal class Statement(val rows: List<StatementRow>, val text: String) {
    companion object {
        private val merchants = listOf(
            "TESCO STORES 3012", "Amazon.co.uk", "GREGGS", "Uber Eats", "J SMITH", "Rent, March", "Cafe \"Nero\"", "Sainsbury's", "SLC STUDENT LOANS",
            "Multi\nline memo", "  padded  ", "日本語 shop", "",
        ).map { it.trim() }
        private val datePatterns = listOf("dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "dd MMM yyyy")
        private val dateHeaders = listOf("Date", "Transaction date", "DATE", " transaction date ")
        private val descriptionHeaders = listOf("Description", "Details", "Narrative", "Reference", "DESCRIPTION")
        private val amountHeaders = listOf("Amount", "Value", "AMOUNT")
        private val creditHeaders = listOf("Money in", "Paid in", "Credit", "MONEY IN")
        private val debitHeaders = listOf("Money out", "Paid out", "Debit", "MONEY OUT")

        fun random(r: Random): Statement {
            val pattern = datePatterns[r.nextInt(datePatterns.size)]
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
            val yearLow = if (pattern == "dd/MM/yy") 2000 else 1990
            val yearHigh = if (pattern == "dd/MM/yy") 2099 else 2040
            val start = LocalDate.of(r.nextInt(yearLow, yearHigh), 1, 1)
            val hasHeader = r.nextInt(5) != 0 // headerless files use the default layout: date, description, amount
            val hasBalance = hasHeader && r.nextInt(3) == 0
            val rows = List(r.nextInt(1, 25)) { i ->
                val magnitude = if (r.nextInt(20) == 0) r.nextLong(0, 90_000_000) else r.nextLong(0, 200_000)
                StatementRow(
                    date = start.plusDays(r.nextLong(0, 364)),
                    pence = if (r.nextBoolean()) -magnitude else magnitude,
                    description = (merchants[r.nextInt(merchants.size)] + if (r.nextInt(3) == 0) " $i" else "").trim(),
                    balance = if (hasBalance) r.nextLong(-5_000_000, 50_000_000) else null,
                )
            }
            val debitCredit = r.nextInt(3) == 0
            val amountStyle = r.nextInt(6)
            val eol = listOf("\n", "\r\n")[r.nextInt(2)]
            val delimiter = listOf(',', ';', '\t')[r.nextInt(3)]

            // Column layout: for headed files a random order plus an unrelated column; the default order otherwise.
            val columns = mutableListOf("date", "description")
            if (debitCredit && hasHeader) columns += listOf("credit", "debit") else columns += "amount"
            if (hasBalance) columns += "balance"
            if (hasHeader && r.nextBoolean()) columns += "type"
            if (hasHeader) columns.shuffle(r)

            fun header(column: String): String = when (column) {
                "date" -> dateHeaders[r.nextInt(dateHeaders.size)]
                "description" -> descriptionHeaders[r.nextInt(descriptionHeaders.size)]
                "amount" -> amountHeaders[r.nextInt(amountHeaders.size)]
                "credit" -> creditHeaders[r.nextInt(creditHeaders.size)]
                "debit" -> debitHeaders[r.nextInt(debitHeaders.size)]
                "balance" -> "Balance"
                else -> "Type"
            }

            val sb = StringBuilder()
            if (r.nextInt(6) == 0) sb.append('\uFEFF')
            if (hasHeader) sb.append(columns.joinToString(delimiter.toString()) { CsvFile.quote(header(it), r) }).append(eol)
            for ((i, row) in rows.withIndex()) {
                val cells = columns.map { column ->
                    when (column) {
                        "date" -> formatter.format(row.date)
                        "description" -> row.description
                        "amount" -> formatAmount(row.pence, amountStyle, r)
                        "credit" -> if (row.pence > 0 || (row.pence == 0L && r.nextBoolean())) formatAmount(row.pence, 0, r) else ""
                        "debit" -> if (row.pence < 0 || (row.pence == 0L && r.nextBoolean())) formatAmount(-row.pence, 0, r).let { if (r.nextInt(4) == 0) "-$it" else it } else ""
                        "balance" -> row.balance?.let { formatAmount(it, 0, r) }.orEmpty()
                        else -> "x"
                    }
                }
                sb.append(cells.joinToString(delimiter.toString()) { CsvFile.quote(it, r) })
                if (i < rows.size - 1 || r.nextBoolean()) sb.append(eol)
                // Blank lines between rows (never before the header): a real export can have them.
                if (i < rows.size - 1 && r.nextInt(15) == 0) sb.append(if (r.nextBoolean()) "" else "   ").append(eol)
            }
            return Statement(rows, sb.toString())
        }

        /** Renders [pence] in one of the amount notations the importer accepts. */
        private fun formatAmount(pence: Long, style: Int, r: Random): String {
            val magnitude = Math.abs(pence)
            val pounds = magnitude / 100
            val fraction = (magnitude % 100).toString().padStart(2, '0')
            val negative = pence < 0
            val plain = when (r.nextInt(4)) {
                0 -> if (magnitude % 100 == 0L) "$pounds" else "$pounds.$fraction"
                else -> "$pounds.$fraction"
            }
            val grouped = pounds.toString().reversed().chunked(3).joinToString(",").reversed() + "." + fraction
            return when (style) {
                0 -> (if (negative) "-" else "") + plain
                1 -> (if (negative) "-£" else "£") + grouped
                2 -> if (negative) "($plain)" else plain
                3 -> if (negative) "${plain}DR" else "${plain}CR"
                4 -> if (negative) "$plain DR" else "+$plain"
                else -> (if (negative) "-" else "") + grouped
            }
        }
    }
}
