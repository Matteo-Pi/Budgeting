package io.github.matteopi.budgeting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.importer.AmountMismatch
import io.github.matteopi.budgeting.core.importer.BalanceGap
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.NotOnStatement
import io.github.matteopi.budgeting.core.importer.PdfCells
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.importer.StatementProblem
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.SantanderPdfFixture
import io.github.matteopi.budgeting.ui.importer.ImportStep
import io.github.matteopi.budgeting.ui.importer.ImportUiState
import io.github.matteopi.budgeting.ui.importer.PdfStatementReader
import io.github.matteopi.budgeting.ui.importer.StatementImportViewModel
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import wizard's check step (docs/PLAN.md §5.16, §6.5) driven through the view model against the real database:
 * hand-entered transactions around the invented Santander statement of [SantanderPdfFixture] (and around a small
 * CSV) produce every kind of question; each answer is given and the ledger is checked afterwards (no duplicates,
 * amounts fixed, deletions, moves, the difference recorded and the balance then matching), and undo.
 */
@RunWith(AndroidJUnit4::class)
class StatementImportChecksTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = DataTestSupport.container
    private val files = mutableListOf<File>()

    /** After the last day of the statement, so every row counts. */
    private val farFuture: LocalDate = LocalDate.of(2030, 1, 1)

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun newViewModel(): StatementImportViewModel {
        var viewModel: StatementImportViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel = StatementImportViewModel(container) }
        return requireNotNull(viewModel)
    }

    private suspend fun StatementImportViewModel.awaitState(what: String, condition: (ImportUiState) -> Boolean): ImportUiState =
        try {
            withTimeout(DataTestSupport.WAIT_MS) { state.first(condition) }
        } catch (e: TimeoutCancellationException) {
            val s = state.value
            throw AssertionError("Timed out waiting for $what; step=${s.step}, error=${s.error}, rows=${s.rows.size}", e)
        }

    private suspend fun statementChunks() = PdfStatementReader.read(
        context,
        SantanderPdfFixture.write(context, File(context.cacheDir, "check-test-${System.nanoTime()}.pdf").also { files += it }),
    )

    private fun date(month: Int, day: Int) = LocalDate.of(2026, month, day)

    private suspend fun ledger(): List<Transaction> = DataTestSupport.transactions().sortedBy { it.id }

    private class Accounts(val current: Account, val second: Account, val savings: Account)

    private suspend fun accounts(currentOpening: Money = Money.ZERO, currentOpened: LocalDate = date(9, 10)) = Accounts(
        current = createAccount("Santander current", opening = currentOpening, date = currentOpened, sortOrder = 0),
        second = createAccount("Second current", opening = Money.pounds(1_000), date = date(9, 1), sortOrder = 1),
        savings = createAccount("Santander savings", AccountType.SAVINGS, opening = Money.pounds(100), date = date(10, 1), sortOrder = 2),
    )

    private suspend fun openReview(viewModel: StatementImportViewModel, account: Account): ImportUiState {
        viewModel.awaitState("the accounts and categories") { it.accounts.size >= 3 && it.categories.isNotEmpty() }
        viewModel.selectAccount(account.id)
        viewModel.onPdfLoaded("statement.pdf", statementChunks())
        return viewModel.awaitState("the review of the statement") { it.step == ImportStep.REVIEW }
    }

    private suspend fun openCsvReview(viewModel: StatementImportViewModel, account: Account, text: String): ImportUiState {
        viewModel.awaitState("the accounts and categories") { it.accounts.size >= 3 && it.categories.isNotEmpty() }
        viewModel.selectAccount(account.id)
        viewModel.onFileLoaded("statement.csv", text)
        viewModel.awaitState("the column mapping") { it.step == ImportStep.MAPPING }
        viewModel.confirmMapping()
        return viewModel.awaitState("the review of the file") { it.step == ImportStep.REVIEW }
    }

    /** What the user typed in by hand around the statement of [SantanderPdfFixture]. */
    private class Hand(
        /** £1.83 on 9 Oct: the bank has it on the 8th (TESCO STORES 1234). */
        val tesco: Transaction,
        /** £2.30 on 6 Oct for "Corner cafe": the bank says £2.20 on the 7th. */
        val cafe: Transaction,
        /** £9.99 on 3 Oct: not on the statement. */
        val cinema: Transaction,
        /** £12.34 on 9 Oct: not on the statement, and in its last three days. */
        val lunch: Transaction,
        /** The leg on the current account of a £25 move to savings on 6 Oct, which the statement does not show. */
        val topUp: Transaction?,
        val topUpOther: Transaction?,
    )

    private suspend fun seedHandEntered(accounts: Accounts, withTopUp: Boolean = true): Hand {
        val current = accounts.current.id
        val tesco = DataTestSupport.addNormal(current, DataTestSupport.category("Groceries").id, Money(-183), date(10, 9), "Tesco")
        val cafe = DataTestSupport.addNormal(current, DataTestSupport.category("Eating out").id, Money(-230), date(10, 6), "Corner cafe")
        val cinema = DataTestSupport.addNormal(current, DataTestSupport.category("Nights out").id, Money(-999), date(10, 3), "Cinema")
        val lunch = DataTestSupport.addNormal(current, DataTestSupport.category("Eating out").id, Money(-1_234), date(10, 9), "Lunch")
        var topUp: Transaction? = null
        var topUpOther: Transaction? = null
        if (withTopUp) {
            container.transactionRepository.addTransfer(current, accounts.savings.id, Money(2_500), date(10, 6), "Savings top-up")
            val legs = DataTestSupport.transactions().filter { it.label == "Savings top-up" }
            topUp = legs.single { it.accountId == current }
            topUpOther = legs.single { it.accountId == accounts.savings.id }
        }
        return Hand(tesco, cafe, cinema, lunch, topUp, topUpOther)
    }

    // ---- every question, every answer ------------------------------------------------------------------------------

    @Test
    fun matchingFixingDeletingAndRecordingTheDifferenceLeaveTheLedgerAgreeingWithTheStatement() = runBlocking<Unit> {
        val accounts = accounts()
        val hand = seedHandEntered(accounts)
        val topUp = requireNotNull(hand.topUp)
        val viewModel = newViewModel()
        val review = openReview(viewModel, accounts.current)

        // The review ticks the rows it will ask about (and does not untick them as duplicates any more).
        assertEquals(setOf(10, 12), review.askedRows)
        assertTrue(review.rowSelected.all { it })

        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        assertEquals(
            listOf(
                PossibleDuplicate::class, AmountMismatch::class,
                NotOnStatement::class, NotOnStatement::class, NotOnStatement::class,
                BalanceGap::class,
            ),
            check.questions.map { it::class },
        )
        val duplicate = check.questions.filterIsInstance<PossibleDuplicate>().single()
        val mismatch = check.questions.filterIsInstance<AmountMismatch>().single()
        val missing = check.questions.filterIsInstance<NotOnStatement>()
        assertEquals(12, duplicate.rowIndex)
        assertEquals(hand.tesco, duplicate.existing)
        assertEquals(10, mismatch.rowIndex)
        assertEquals(hand.cafe, mismatch.existing)
        assertEquals(listOf(hand.cinema.id, topUp.id, hand.lunch.id), missing.map { it.existing.id })
        assertEquals(listOf(false, false, true), missing.map { it.mayNotHaveReachedBank }, "only the last three days may not have reached the bank")
        assertEquals(hand.topUpOther, missing[1].transferPartner)
        assertEquals(listOf(accounts.second.id), missing[1].moveTargets.map { it.id }, "not the account of its other leg")
        // With the likeliest answers the unknown entries (£9.99, £25.00 and £12.34) are the difference.
        assertEquals(BalanceGap(date(10, 10), Money(-3_254), Money(1_479)), check.questions.last())

        // Nothing moves on until every question is answered, and an answer a question does not accept is ignored.
        assertFalse(check.complete)
        viewModel.confirmChecks()
        viewModel.answerQuestion(duplicate.id, CheckAnswer.Delete)
        assertEquals(ImportStep.CHECK, viewModel.state.value.step)
        assertTrue(viewModel.state.value.check!!.answers.isEmpty())

        viewModel.answerQuestion(duplicate.id, CheckAnswer.SameUseBank)
        viewModel.answerQuestion(mismatch.id, CheckAnswer.FixMine)
        viewModel.answerQuestion(missing[0].id, CheckAnswer.Delete)
        viewModel.answerQuestion(missing[1].id, CheckAnswer.Delete)
        viewModel.answerQuestion(missing[2].id, CheckAnswer.Keep)
        // The balance card follows the answers: only the £12.34 lunch is now missing from the statement.
        val gap = viewModel.state.value.check!!.questions.filterIsInstance<BalanceGap>().single()
        assertEquals(BalanceGap(date(10, 10), Money(245), Money(1_479)), gap)
        assertEquals(Money(1_234), gap.difference)
        assertFalse(viewModel.state.value.check!!.complete, "the balance question is still open")
        viewModel.answerQuestion(gap.id, CheckAnswer.RecordDifference)
        assertTrue(viewModel.state.value.check!!.complete)

        viewModel.confirmChecks()
        viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val batch = assertNotNull(summary.summaryBatch)
        val ledger = ledger()

        // The two matched rows were not imported; the other 17 were.
        assertEquals(17, batch.rowCount)
        assertEquals(0, ledger.count { it.label == "TESCO STORES 1234" || it.label == "SQ *CORNER CAFE" }, "no duplicates")

        // Same, use the bank's date: the entry keeps everything but the date, and remembers the bank's row.
        val tesco = ledger.single { it.id == hand.tesco.id }
        assertEquals(date(10, 8), tesco.date)
        assertEquals(hand.tesco.copy(date = date(10, 8), importKey = review.importKeys[12]), tesco)
        // Fix mine: the bank's amount and date, the entry's own label and category.
        val cafe = ledger.single { it.id == hand.cafe.id }
        assertEquals(hand.cafe.copy(amount = Money(-220), date = date(10, 7), importKey = review.importKeys[10]), cafe)
        // Deleted: the cinema, and both legs of the move to savings. Kept: the lunch, as it was.
        assertTrue(ledger.none { it.id == hand.cinema.id })
        assertTrue(ledger.none { it.label == "Savings top-up" }, "both legs of the transfer")
        assertEquals(hand.lunch, ledger.single { it.id == hand.lunch.id })
        assertEquals(Money.pounds(100), DataTestSupport.balanceAsOf(accounts.savings.id, farFuture), "savings is as it was")

        // The difference: a balance update on the statement's last day, tagged with the batch.
        val update = ledger.single { it.kind == TxKind.RECONCILIATION }
        assertEquals(Money(1_234), update.amount)
        assertEquals(date(10, 10), update.date)
        assertEquals(DataTestSupport.untrackedCategory().id, update.categoryId)
        assertEquals(batch.id, update.importBatchId)
        val records = container.accountRepository.observeReconciliationsForAccount(accounts.current.id).first()
        assertEquals(1, records.size)
        assertEquals(date(10, 10), records.single().date)
        assertEquals(Money(245), records.single().expected)
        assertEquals(Money(1_479), records.single().actual)
        assertEquals(update.id, records.single().transactionId)

        // The balance then matches the statement.
        assertEquals(Money(1_479), DataTestSupport.balanceAsOf(accounts.current.id, SantanderPdfFixture.PERIOD_END))
        assertTrue(assertNotNull(summary.balanceCheck).matches)
        assertEquals("1 matched to your own entry, 1 amount corrected, 2 deleted, difference of £12.34 recorded", summary.checksSummary)
        assertTrue(summary.changedExistingTransactions)

        // Undo takes away what the batch made, the balance update with it; the changes to the user's own entries stay.
        viewModel.undoBatch(batch.id)
        withTimeout(DataTestSupport.WAIT_MS) { container.importRepository.observeBatches().first { it.isEmpty() } }
        val after = ledger()
        assertTrue(after.none { it.importBatchId != null })
        assertTrue(after.none { it.kind == TxKind.RECONCILIATION })
        assertTrue(container.accountRepository.observeReconciliationsForAccount(accounts.current.id).first().isEmpty())
        assertEquals(tesco, after.single { it.id == hand.tesco.id })
        assertEquals(cafe, after.single { it.id == hand.cafe.id })
        assertTrue(after.none { it.id == hand.cinema.id })
    }

    @Test
    fun theShortcutKeepsDuplicatesAsEnteredAndAnEntryCanBeMovedToAnotherAccount() = runBlocking<Unit> {
        val accounts = accounts()
        val hand = seedHandEntered(accounts, withTopUp = false)
        val tescoBig = DataTestSupport.addNormal(
            accounts.current.id, DataTestSupport.category("Groceries").id, Money(-775), date(10, 8), "Tesco big shop",
        )
        val viewModel = newViewModel()
        val review = openReview(viewModel, accounts.current)
        assertEquals(setOf(10, 12, 13), review.askedRows)

        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        val duplicates = check.questions.filterIsInstance<PossibleDuplicate>()
        assertEquals(listOf(12, 13), duplicates.map { it.rowIndex })
        val mismatch = check.questions.filterIsInstance<AmountMismatch>().single()
        val missing = check.questions.filterIsInstance<NotOnStatement>()
        assertEquals(listOf(hand.cinema.id, hand.lunch.id), missing.map { it.existing.id })

        // The shortcut answers every duplicate "same, keep mine"; the big Tesco shop is then said to be a different purchase.
        viewModel.answerAllPossibleDuplicates()
        assertEquals(setOf(CheckAnswer.SameKeepMine), duplicates.map { viewModel.state.value.check!!.answers[it.id] }.toSet())
        viewModel.answerQuestion(duplicates[1].id, CheckAnswer.Different)
        viewModel.answerQuestion(mismatch.id, CheckAnswer.Different)
        // The account being imported into is not somewhere to move an entry to; the second account is.
        viewModel.answerQuestion(missing[0].id, CheckAnswer.MoveTo(accounts.current.id))
        assertNull(viewModel.state.value.check!!.answers[missing[0].id])
        viewModel.answerQuestion(missing[0].id, CheckAnswer.MoveTo(accounts.second.id))
        viewModel.answerQuestion(missing[1].id, CheckAnswer.Keep)
        val answered = viewModel.state.value.check!!.answers
        assertEquals(CheckAnswer.SameKeepMine, answered[duplicates[0].id])
        assertEquals(CheckAnswer.Different, answered[duplicates[1].id])

        // Going back to the review and forward again keeps the answers the questions still need.
        viewModel.backTo(ImportStep.REVIEW)
        viewModel.confirmReview()
        val again = assertNotNull(viewModel.awaitState("the check step again") { it.step == ImportStep.CHECK }.check)
        assertEquals(answered, again.answers.filterKeys { it in answered.keys })

        // The app would hold -£7.60 on the 10th (the cafe's £2.30 and the bank's £2.20 are both in, and so are both £7.75s): leave it.
        val gap = again.questions.filterIsInstance<BalanceGap>().single()
        assertEquals(BalanceGap(date(10, 10), Money(-760), Money(1_479)), gap)
        viewModel.answerQuestion(gap.id, CheckAnswer.Leave)
        assertTrue(viewModel.state.value.check!!.complete)

        viewModel.confirmChecks()
        viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledger = ledger()

        // Same, keep mine: the entry is exactly as it was, with the bank's row remembered; the row is not imported.
        assertEquals(hand.tesco.copy(importKey = review.importKeys[12]), ledger.single { it.id == hand.tesco.id })
        assertEquals(0, ledger.count { it.label == "TESCO STORES 1234" })
        // Different: the bank's row comes in as well as the entry, which is left alone (no key).
        assertEquals(tescoBig, ledger.single { it.id == tescoBig.id })
        assertEquals(1, ledger.count { it.label == "TESCO STORES 5678" })
        assertEquals(1, ledger.count { it.label == "SQ *CORNER CAFE" })
        assertEquals(hand.cafe, ledger.single { it.id == hand.cafe.id })
        // Moved: the cinema is on the second account now.
        assertEquals(accounts.second.id, ledger.single { it.id == hand.cinema.id }.accountId)
        assertEquals(Money.pounds(1_000) - Money(999), DataTestSupport.balanceAsOf(accounts.second.id, farFuture))
        // Left alone: no balance update, and the summary says the app is a little off.
        assertTrue(ledger.none { it.kind == TxKind.RECONCILIATION })
        assertEquals("1 matched to your own entry, 1 moved to Second current", summary.checksSummary)
        val balance = assertNotNull(summary.balanceCheck)
        assertFalse(balance.matches)
        assertTrue("(£22.39 different)" in balance.message, balance.message)
    }

    // ---- a statement that does not add up -------------------------------------------------------------------------

    @Test
    fun aRowTheStatementReaderDoubtsCanBeLeftOutAndTheBalanceCardFollows() = runBlocking<Unit> {
        val accounts = accounts()
        // The balance printed after the £0.77 row (24.19) is misread as 24.18: that row and the next no longer follow.
        val tampered = PdfCells.merge(statementChunks()).map { if (it.page == 2 && it.text == "24.19") it.copy(text = "24.18") else it }
        val viewModel = newViewModel()
        viewModel.awaitState("the accounts and categories") { it.accounts.size >= 3 && it.categories.isNotEmpty() }
        viewModel.selectAccount(accounts.current.id)
        viewModel.onPdfLoaded("statement.pdf", tampered)
        viewModel.awaitState("the review") { it.step == ImportStep.REVIEW }

        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        val problems = check.questions.filterIsInstance<StatementProblem>()
        assertEquals(listOf(16, 17), problems.map { it.rowIndex })
        assertTrue(check.questions.none { it is BalanceGap }, "the amounts still add up to the statement")

        viewModel.answerQuestion(problems[0].id, CheckAnswer.SkipRow)
        viewModel.answerQuestion(problems[1].id, CheckAnswer.Ok)
        // Leaving the £0.77 row out leaves the app £0.77 above the statement.
        val gap = viewModel.state.value.check!!.questions.filterIsInstance<BalanceGap>().single()
        assertEquals(BalanceGap(date(10, 10), Money(1_556), Money(1_479)), gap)
        viewModel.answerQuestion(gap.id, CheckAnswer.Leave)

        viewModel.confirmChecks()
        viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledger = ledger()

        assertEquals(18, assertNotNull(summary.summaryBatch).rowCount)
        assertTrue(ledger.none { it.amount == Money(-77) }, "the skipped row was not imported")
        assertEquals("1 row left out", summary.checksSummary)
        assertFalse(summary.changedExistingTransactions)
        assertTrue("(£0.77 different)" in assertNotNull(summary.balanceCheck).message)
    }

    // ---- a CSV ---------------------------------------------------------------------------------------------------

    private val csv = """
        Date,Description,Amount,Balance
        01/10/2026,CARD PAYMENT TO TESCO STORES 5421,-12.50,987.50
        03/10/2026,CARD PAYMENT TO GREGGS,-3.85,983.65
        05/10/2026,FASTER PAYMENTS RECEIPT FROM MUM,50.00,1033.65
        07/10/2026,CARD PAYMENT TO COSTA COFFEE,-2.50,1031.15
    """.trimIndent()

    @Test
    fun aCsvIsCheckedTooAndItsBalanceColumnGivesTheClosingBalance() = runBlocking<Unit> {
        val accounts = accounts(currentOpening = Money.pounds(1_000), currentOpened = date(9, 30))
        val current = accounts.current.id
        val tesco = DataTestSupport.addNormal(current, DataTestSupport.category("Groceries").id, Money(-1_250), date(10, 1), "Tesco")
        val greggs = DataTestSupport.addNormal(current, DataTestSupport.category("Eating out").id, Money(-380), date(10, 3), "Greggs bakery")
        val cinema = DataTestSupport.addNormal(current, DataTestSupport.category("Nights out").id, Money(-800), date(10, 4), "Cinema")
        val snack = DataTestSupport.addNormal(current, DataTestSupport.category("Other").id, Money(-100), date(10, 6), "Snack")
        val viewModel = newViewModel()
        val review = openCsvReview(viewModel, accounts.current, csv)
        assertEquals(setOf(0, 1), review.askedRows)
        assertEquals(4, review.rows.size)
        // The £12.50 Tesco row has the same amount as the hand-entered one on the same day; the others are new.
        assertEquals(
            listOf(RowStatus.POSSIBLE_DUPLICATE, RowStatus.NEW, RowStatus.NEW, RowStatus.NEW),
            review.rowStatuses,
        )

        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        val duplicate = check.questions.filterIsInstance<PossibleDuplicate>().single()
        assertEquals(0, duplicate.rowIndex)
        assertEquals(tesco, duplicate.existing)
        val mismatch = check.questions.filterIsInstance<AmountMismatch>().single()
        assertEquals(1, mismatch.rowIndex)
        assertEquals(greggs, mismatch.existing)
        val missing = check.questions.filterIsInstance<NotOnStatement>()
        assertEquals(listOf(cinema.id, snack.id), missing.map { it.existing.id })
        assertEquals(listOf(false, true), missing.map { it.mayNotHaveReachedBank })
        // £1,000 less the entries, with the likeliest answers, against the file's last balance of £1,031.15.
        assertEquals(BalanceGap(date(10, 7), Money(102_215), Money(103_115)), check.questions.last())

        viewModel.answerQuestion(duplicate.id, CheckAnswer.SameKeepMine)
        viewModel.answerQuestion(mismatch.id, CheckAnswer.FixMine)
        viewModel.answerQuestion(missing[0].id, CheckAnswer.Delete)
        viewModel.answerQuestion(missing[1].id, CheckAnswer.Keep)
        val gap = viewModel.state.value.check!!.questions.filterIsInstance<BalanceGap>().single()
        assertEquals(Money(103_015), gap.appBalance)
        viewModel.answerQuestion(gap.id, CheckAnswer.RecordDifference)

        viewModel.confirmChecks()
        viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledger = ledger()

        // Two rows came in (the money from Mum and the coffee); the other two were matched to the user's own entries.
        assertEquals(2, assertNotNull(summary.summaryBatch).rowCount)
        assertEquals(tesco.copy(importKey = review.importKeys[0]), ledger.single { it.id == tesco.id })
        assertEquals(greggs.copy(amount = Money(-385), date = date(10, 3), importKey = review.importKeys[1]), ledger.single { it.id == greggs.id })
        assertTrue(ledger.none { it.id == cinema.id })
        assertEquals(snack, ledger.single { it.id == snack.id })
        val update = ledger.single { it.kind == TxKind.RECONCILIATION }
        assertEquals(Money(100), update.amount)
        assertEquals(date(10, 7), update.date)
        assertEquals(Money(103_115), DataTestSupport.balanceAsOf(current, date(10, 7)))
        assertEquals("Matches your statement: £1,031.15 on 7 Oct ✓", assertNotNull(summary.balanceCheck).message)
        assertEquals("1 matched to your own entry, 1 amount corrected, 1 deleted, difference of £1.00 recorded", summary.checksSummary)
    }

    @Test
    fun whenEveryRowIsMatchedToAnEntryNothingIsImportedAndNoBatchIsMade() = runBlocking<Unit> {
        val accounts = accounts(currentOpening = Money.pounds(1_000), currentOpened = date(9, 30))
        val current = accounts.current.id
        val tesco = DataTestSupport.addNormal(current, DataTestSupport.category("Groceries").id, Money(-450), date(10, 6), "Tesco")
        val greggs = DataTestSupport.addNormal(current, DataTestSupport.category("Eating out").id, Money(-200), date(10, 6), "Greggs")
        val viewModel = newViewModel()
        val review = openCsvReview(
            viewModel,
            accounts.current,
            "Date,Description,Amount\n05/10/2026,CARD PAYMENT TO TESCO STORES,-4.50\n06/10/2026,CARD PAYMENT TO GREGGS,-2.00\n",
        )
        assertEquals(setOf(0, 1), review.askedRows)

        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        assertEquals(2, check.questions.size)
        viewModel.answerAllPossibleDuplicates()
        assertTrue(viewModel.state.value.check!!.complete)
        assertTrue(viewModel.state.value.check!!.plan.importRows.isEmpty())

        // With nothing to categorise, the next step saves the answers.
        viewModel.confirmChecks()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }

        assertNull(summary.summaryBatch)
        assertEquals("2 matched to your own entries", summary.checksSummary)
        assertTrue(container.importRepository.observeBatches().first().isEmpty())
        val ledger = ledger()
        assertEquals(tesco.copy(importKey = review.importKeys[0]), ledger.single { it.id == tesco.id })
        assertEquals(greggs.copy(importKey = review.importKeys[1]), ledger.single { it.id == greggs.id })
        assertTrue(ledger.none { it.importBatchId != null })
    }

    // ---- nothing to ask --------------------------------------------------------------------------------------------

    @Test
    fun aStatementThatWasAlreadyImportedHasNothingToAskAndNothingToImport() = runBlocking<Unit> {
        val accounts = accounts()
        val first = newViewModel()
        openReview(first, accounts.current)
        first.confirmReview()
        first.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        first.confirmImport()
        first.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledgerAfterFirst = ledger()

        val second = newViewModel()
        val review = openReview(second, accounts.current)
        assertTrue(review.rowStatuses.all { it == RowStatus.ALREADY_IMPORTED })
        assertTrue(review.askedRows.isEmpty())

        second.confirmReview()
        delay(500)
        assertEquals(ImportStep.REVIEW, second.state.value.step, "nothing is selected, so there is nothing to confirm")
        assertEquals(ledgerAfterFirst, ledger())
        assertNull(second.state.value.check)
    }
}
