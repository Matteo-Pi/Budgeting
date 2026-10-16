package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.AmountMismatch
import io.github.matteopi.budgeting.core.importer.BalanceGap
import io.github.matteopi.budgeting.core.importer.BeforeOpening
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.ImportPlan
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.NotOnStatement
import io.github.matteopi.budgeting.core.importer.ParsedStatement
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
import io.github.matteopi.budgeting.core.importer.StatementProblem
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

    /** "10 Oct 2026". */
    fun dayMonthYear(date: LocalDate): String = date.format(DAY_MONTH_YEAR)

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

/** Wording for the check step and for what the checks changed (docs/PLAN.md §6.5), free of Android so it can be unit tested. */
object CheckText {
    const val ALL_DUPLICATES = "Same as mine for all possible duplicates"
    const val MOVE_TO_ANOTHER = "Move to another account"
    const val MAY_NOT_HAVE_REACHED_BANK = "It's from the last few days of this statement, so it may not have reached the bank yet."
    const val NOT_UNDONE = "Changes you chose for transactions already in the app aren't undone."
    const val NOTHING_IMPORTED = "No new transactions were added."
    const val ANSWER_ALL = "Answer every question to carry on."
    const val SO_FAR = "Worked out from your answers so far."

    /** "3 things to check". */
    fun heading(count: Int): String = if (count == 1) "1 thing to check" else "$count things to check"

    fun title(question: ImportQuestion): String = when (question) {
        is PossibleDuplicate -> "This might be the same as something you entered"
        is AmountMismatch -> "Same purchase, different amount?"
        is NotOnStatement -> "In the app but not on this statement"
        is StatementProblem -> "Something on the statement doesn't add up"
        is BeforeOpening -> "Dated before this account's starting balance"
        is BalanceGap -> "Your balance won't match the statement"
    }

    /** The button for [answer] to [question]. A [CheckAnswer.MoveTo] is named by the account picker instead. */
    fun label(question: ImportQuestion, answer: CheckAnswer): String = when (answer) {
        CheckAnswer.SameUseBank -> "Same, use the bank's date"
        CheckAnswer.SameKeepMine -> "Same, keep mine as it is"
        CheckAnswer.Different -> if (question is AmountMismatch) "No, add the bank's too" else "Different, add the bank's too"
        CheckAnswer.FixMine -> "Yes, use the bank's amount and date"
        CheckAnswer.Keep -> "Keep it"
        CheckAnswer.Delete -> if (question is NotOnStatement && question.transferPartner != null) "Delete both sides" else "Delete it"
        is CheckAnswer.MoveTo -> MOVE_TO_ANOTHER
        CheckAnswer.SkipRow -> "Leave this row out"
        CheckAnswer.Ok -> if (question is StatementProblem) "OK, carry on" else "OK"
        CheckAnswer.RecordDifference -> "Record the difference so they match"
        CheckAnswer.Leave -> "Leave it"
    }

    /** The chip once an account has been picked for a move: "Move to Cash". */
    fun movedTo(accountName: String): String = "Move to $accountName"

    /** "On 10 Oct 2026 the app would show £50.00, but your statement says £14.79: a difference of £35.21." */
    fun balanceGap(gap: BalanceGap): String =
        "On ${StatementText.dayMonthYear(gap.date)} the app would show ${gap.appBalance.format()}, but your statement says " +
            "${gap.statementBalance.format()}: a difference of ${gap.difference.abs().format()}."

    /** What recording the gap does, in words. */
    fun balanceGapHelp(gap: BalanceGap): String =
        "Recording it saves a balance update for ${StatementText.dayMonthYear(gap.date)} in Untracked, like Update balance does."

    fun beforeOpening(question: BeforeOpening): String {
        val rows = if (question.count == 1) "1 row on this statement is" else "${question.count} rows on this statement are"
        return "$rows dated before ${StatementText.dayMonthYear(question.openingDate)}, when this account's starting balance " +
            "was set. Its starting balance will be adjusted so they aren't counted twice."
    }

    /** The warning on a move between accounts that is about to be deleted. */
    fun transferWarning(otherAccountName: String?): String =
        "This is one side of a move between accounts" +
            (if (otherAccountName != null) " (the other side is in $otherAccountName)" else "") +
            ". Deleting it deletes both sides."

    /**
     * What the answers changed, for the summary: "2 matched to your own entries, 1 deleted, 1 moved to Cash,
     * difference of £3.20 recorded", or null when they changed nothing. [recordedDifference] is what was saved
     * as a balance update, if anything.
     */
    fun changesSummary(plan: ImportPlan, accounts: List<Account>, recordedDifference: Money?): String? {
        val parts = mutableListOf<String>()
        if (plan.matched > 0) parts += "${plan.matched} matched to your own ${if (plan.matched == 1) "entry" else "entries"}"
        if (plan.corrected > 0) parts += "${plan.corrected} ${if (plan.corrected == 1) "amount" else "amounts"} corrected"
        if (plan.skipped > 0) parts += "${plan.skipped} ${if (plan.skipped == 1) "row" else "rows"} left out"
        if (plan.deleted > 0) parts += "${plan.deleted} deleted"
        for ((accountId, count) in plan.movedTo) {
            parts += "$count moved to ${accounts.firstOrNull { it.id == accountId }?.name ?: "another account"}"
        }
        if (recordedDifference != null) parts += "difference of ${recordedDifference.abs().format()} recorded"
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }
}
