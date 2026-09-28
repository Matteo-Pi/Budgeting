package io.github.matteopi.budgeting.core.importer

enum class AmountMode { SIGNED, DEBIT_CREDIT }

/** How to read a [CsvTable] as bank statement rows. Column indices are into a raw table row. */
data class StatementMapping(
    val hasHeader: Boolean,
    val dateColumn: Int,
    /** A [java.time.format.DateTimeFormatter] pattern, e.g. "dd/MM/yyyy". */
    val datePattern: String,
    val amountMode: AmountMode,
    /** Used when [amountMode] is [AmountMode.SIGNED]. */
    val amountColumn: Int? = null,
    /** Used when [amountMode] is [AmountMode.DEBIT_CREDIT]. */
    val debitColumn: Int? = null,
    val creditColumn: Int? = null,
    val descriptionColumn: Int,
    val balanceColumn: Int? = null,
)
