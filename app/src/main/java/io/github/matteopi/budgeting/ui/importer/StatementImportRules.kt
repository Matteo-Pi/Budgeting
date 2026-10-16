package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.ParsedStatement
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Telling a PDF from a CSV when the user picks a file (the file type the picker reports is often not specific). */
object PdfDetection {
    /** How many bytes of the file [isPdf] needs to see. */
    const val HEADER_LENGTH = 4

    private const val MIME_TYPE = "application/pdf"
    private val MAGIC = "%PDF".toByteArray(Charsets.ISO_8859_1)

    /** True if the content resolver says it is a PDF, or the file starts with "%PDF". */
    fun isPdf(mimeType: String?, header: ByteArray): Boolean =
        mimeType.equals(MIME_TYPE, ignoreCase = true) ||
            (header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] })
}

/** The "move between my accounts" choice in the categorise step (docs/PLAN.md §6.5). */
object ImportTransfers {
    /** On when every row of the group looks like a move between the user's own accounts. */
    fun defaultOn(rowsLikelyTransfer: List<Boolean>): Boolean = rowsLikelyTransfer.isNotEmpty() && rowsLikelyTransfer.all { it }

    /** The accounts a move can go to or come from: every live account except the one being imported into. */
    fun otherAccounts(accounts: List<Account>, importingInto: Long?): List<Account> =
        accounts.filter { !it.archived && it.id != importingInto }

    /** [candidate] if it is still a valid other account, else null ("not in the app"). */
    fun validOtherAccountId(candidate: Long?, accounts: List<Account>, importingInto: Long?): Long? =
        candidate?.takeIf { id -> otherAccounts(accounts, importingInto).any { it.id == id } }
}

/** The result of comparing the app's balance with the statement's closing balance. */
data class BalanceCheck(val matches: Boolean, val message: String)

/** Wording for the statement import screens, kept free of Android so it can be unit tested. */
object StatementText {
    // Locale.ENGLISH: en-GB CLDR data abbreviates September as "Sept".
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** "Santander statement · 10 Sep – 10 Oct 2026 · 19 transactions · closing balance £14.79". */
    fun cardLine(statement: ParsedStatement): String {
        val start = if (statement.periodStart.year == statement.periodEnd.year) {
            statement.periodStart.format(DAY_MONTH)
        } else {
            statement.periodStart.format(DAY_MONTH_YEAR)
        }
        val count = statement.lines.size
        val parts = mutableListOf(
            "Santander statement",
            "$start – ${statement.periodEnd.format(DAY_MONTH_YEAR)}",
            if (count == 1) "1 transaction" else "$count transactions",
        )
        statement.closingBalance?.let { parts += "closing balance ${it.format()}" }
        return parts.joinToString(" · ")
    }

    /** "10 Oct". */
    fun dayMonth(date: LocalDate): String = date.format(DAY_MONTH)

    /**
     * The app's balance on the statement's last day ([appBalance], after the import) against the closing
     * balance the statement prints.
     */
    fun balanceCheck(statementBalance: Money, appBalance: Money, date: LocalDate): BalanceCheck {
        val day = dayMonth(date)
        if (appBalance == statementBalance) {
            return BalanceCheck(true, "Matches your statement: ${statementBalance.format()} on $day ✓")
        }
        val difference = (appBalance - statementBalance).abs()
        return BalanceCheck(
            false,
            "Your statement says ${statementBalance.format()} on $day; the app says ${appBalance.format()} " +
                "(${difference.format()} different). Transactions you entered by hand that are also on the " +
                "statement, or rows you unticked, would explain it.",
        )
    }

    const val NOT_SANTANDER =
        "This PDF doesn't look like a Santander statement. Statement PDFs from Santander online banking, " +
            "or CSV files from any bank, can be imported."
}
