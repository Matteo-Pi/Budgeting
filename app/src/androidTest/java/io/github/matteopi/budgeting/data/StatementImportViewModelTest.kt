package io.github.matteopi.budgeting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.importer.BeforeOpening
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.SantanderPdfFixture
import io.github.matteopi.budgeting.ui.importer.ImportStep
import io.github.matteopi.budgeting.ui.importer.ImportUiState
import io.github.matteopi.budgeting.ui.importer.MerchantGroup
import io.github.matteopi.budgeting.ui.importer.PdfStatementReader
import io.github.matteopi.budgeting.ui.importer.StatementImportViewModel
import io.github.matteopi.budgeting.ui.importer.StatementText
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The statement import wizard's view model, driven the way the screens drive it, with the invented PDF statement
 * of [SantanderPdfFixture] read through PDFBox: review, categorise (including moves between the user's own
 * accounts), import, the balance check against the statement, and undo. Everything runs against the real database.
 */
@RunWith(AndroidJUnit4::class)
class StatementImportViewModelTest {
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
        SantanderPdfFixture.write(context, File(context.cacheDir, "import-test-${System.nanoTime()}.pdf").also { files += it }),
    )

    private fun date(month: Int, day: Int) = LocalDate.of(2026, month, day)

    private fun List<Transaction>.of(accountId: Long, kind: TxKind) =
        filter { it.accountId == accountId && it.kind == kind }.sortedWith(compareBy({ it.date }, { it.id }))

    private fun ImportUiState.group(sampleDescription: String): MerchantGroup =
        merchantGroups.firstOrNull { it.sampleDescription == sampleDescription }
            ?: throw AssertionError("No group \"$sampleDescription\"; have ${merchantGroups.map { it.sampleDescription }}")

    private suspend fun ledger(): List<Transaction> = DataTestSupport.transactions().sortedBy { it.id }

    /** The user's accounts: the one being imported into, a second current account and a savings account. */
    private class Accounts(val current: Account, val second: Account, val savings: Account)

    private suspend fun accounts(
        currentOpening: Money = Money.ZERO,
        currentOpened: LocalDate = date(9, 10),
        secondOpened: LocalDate = date(9, 1),
        savingsOpened: LocalDate = date(10, 1),
    ) = Accounts(
        current = createAccount("Santander current", opening = currentOpening, date = currentOpened, sortOrder = 0),
        second = createAccount("Second current", opening = Money.pounds(1_000), date = secondOpened, sortOrder = 1),
        savings = createAccount("Santander savings", AccountType.SAVINGS, opening = Money.pounds(100), date = savingsOpened, sortOrder = 2),
    )

    /** Loads the statement into [viewModel] for [account] and waits for the review. */
    private suspend fun openReview(viewModel: StatementImportViewModel, account: Account): ImportUiState {
        viewModel.awaitState("the accounts and categories") { it.accounts.size >= 3 && it.categories.isNotEmpty() }
        viewModel.selectAccount(account.id)
        viewModel.onPdfLoaded("statement.pdf", statementChunks())
        return viewModel.awaitState("the review of the statement") { it.step == ImportStep.REVIEW }
    }

    // ---- review ---------------------------------------------------------------------------------------------------

    @Test
    fun aStatementPdfGoesStraightToTheReviewWithItsRowsAndNoMappingStep() = runBlocking<Unit> {
        val accounts = accounts()
        val viewModel = newViewModel()

        val review = openReview(viewModel, accounts.current)

        assertEquals(ImportStep.REVIEW, review.step)
        assertEquals("statement.pdf", review.fileName)
        assertNull(review.table)
        assertNull(review.mapping)
        val statement = assertNotNull(review.statement)
        assertEquals(emptyList(), statement.problems)
        assertEquals(SantanderPdfFixture.rows.size, review.rows.size)
        assertEquals(SantanderPdfFixture.rows.map { Money(it.pence) }, review.rows.map { it.amount })
        assertTrue(review.rowStatuses.all { it == RowStatus.NEW })
        assertTrue(review.rowSelected.all { it })
        assertEquals(SantanderPdfFixture.rows.map { it.likelyTransfer }, review.rows.indices.map { review.isLikelyTransfer(it) })
        assertEquals(accounts.current.id, review.selectedAccountId)
        assertNull(review.error)
    }

    @Test
    fun aPdfThatIsNotASantanderStatementStaysOnThePickerWithAClearMessage() = runBlocking<Unit> {
        accounts()
        val viewModel = newViewModel()
        viewModel.awaitState("the accounts") { it.accounts.size >= 3 }
        val invoice = SantanderPdfFixture.writeOther(context, File(context.cacheDir, "invoice-${System.nanoTime()}.pdf").also { files += it })

        viewModel.onPdfLoaded("invoice.pdf", PdfStatementReader.read(context, invoice))

        val state = viewModel.awaitState("the error") { it.error != null }
        assertEquals(StatementText.NOT_SANTANDER, state.error)
        assertEquals(ImportStep.PICK_FILE, state.step)
        assertNull(state.statement)
    }

    @Test
    fun theCategoriseStepOffersTheRightRowsAsMovesBetweenAccounts() = runBlocking<Unit> {
        val accounts = accounts()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)

        viewModel.confirmReview()
        val state = viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }

        val transferGroups = state.merchantGroups.filter { it.transfer }.map { it.sampleDescription }.toSet()
        assertEquals(
            setOf(
                "From A Tester",
                "TRANSFER TO ALEX TESTER",
                "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678",
                "ALEX TESTER, ref Transfer",
            ),
            transferGroups,
        )
        assertTrue(state.merchantGroups.filter { it.transfer }.all { it.otherAccountId == null }, "the other account defaults to \"not in the app\"")
        assertEquals(5, state.group("UNION SHOP").rowIndices.size)
        assertEquals(2, state.group("MORRISONS LEEDS").rowIndices.size)
        assertEquals(2, state.group("TESCO STORES 1234").rowIndices.size, "both Tesco branches share one merchant")
        assertEquals(1, state.group("JO BLOGGS, ref pizza").rowIndices.size)
        assertFalse(state.group("JO BLOGGS, ref pizza").transfer)
        assertFalse(state.group("SAM SMITH, ref tickets").transfer)
    }

    // ---- importing ------------------------------------------------------------------------------------------------

    @Test
    fun importingMakesTransferLegsOnBothAccountsAndCategorisedRowsAndUndoRemovesEverything() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val eatingOut = DataTestSupport.category("Eating out")
        val accounts = accounts()
        val ledgerBefore = ledger()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)

        viewModel.confirmReview()
        val categorise = viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        // Moves: the money from and to the holder's other current account, and the regular transfer to savings.
        viewModel.setGroupOtherAccount(categorise.group("From A Tester").merchantKey, accounts.second.id)
        viewModel.setGroupOtherAccount(categorise.group("TRANSFER TO ALEX TESTER").merchantKey, accounts.second.id)
        viewModel.setGroupOtherAccount(categorise.group("ALEX TESTER, ref Transfer").merchantKey, accounts.second.id)
        viewModel.setGroupOtherAccount(
            categorise.group("REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678").merchantKey,
            accounts.savings.id,
        )
        // Spending: the supermarkets are suggested as Groceries; the rest is chosen by hand.
        viewModel.setGroupCategory(categorise.group("UNION SHOP").merchantKey, shopping.id)
        viewModel.setGroupCategory(categorise.group("JO BLOGGS, ref pizza").merchantKey, eatingOut.id)
        assertEquals(groceries.id, categorise.group("MORRISONS LEEDS").categoryId, "suggested from the merchant name")

        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val batch: ImportBatch = assertNotNull(summary.summaryBatch)
        val ledger = ledger()

        // The batch.
        assertEquals(19, batch.rowCount)
        assertEquals(date(9, 24), batch.firstDate)
        assertEquals(date(10, 10), batch.lastDate)
        assertEquals("statement.pdf", batch.fileName)

        // Transfer legs on the imported account: no category, their own group each, the row's import key.
        val legs = ledger.of(accounts.current.id, TxKind.TRANSFER)
        assertEquals(
            listOf(Money(50_000), Money(-20_000), Money(-20_000), Money(-3_000)),
            legs.map { it.amount },
        )
        assertEquals(listOf(date(9, 24), date(9, 27), date(9, 29), date(10, 4)), legs.map { it.date })
        assertTrue(legs.all { it.categoryId == null && it.importKey != null && it.importBatchId == batch.id })
        assertEquals(4, legs.mapNotNull { it.transferGroupId }.toSet().size, "a fresh transfer group per row")

        // The opposite legs on the second account: negated, same date and label and group, tagged with the batch, no key.
        val secondLegs = ledger.of(accounts.second.id, TxKind.TRANSFER)
        assertEquals(listOf(Money(-50_000), Money(20_000), Money(3_000)), secondLegs.map { it.amount })
        assertEquals(listOf(date(9, 24), date(9, 27), date(10, 4)), secondLegs.map { it.date })
        assertTrue(secondLegs.all { it.categoryId == null && it.importKey == null && it.importBatchId == batch.id })
        for (leg in secondLegs) {
            val own = legs.single { it.transferGroupId == leg.transferGroupId }
            assertEquals(own.label, leg.label)
            assertEquals(own.date, leg.date)
            assertEquals(-own.amount, leg.amount)
        }

        // The regular transfer to savings is dated before the savings account opened (its balance includes it): no leg there.
        assertTrue(ledger.of(accounts.savings.id, TxKind.TRANSFER).isEmpty())
        val toSavings = legs.single { it.label == "REGULAR TRANSFER PAYMENT TO ACCOUNT SAVING R12345678" }
        assertEquals(1, ledger.count { it.transferGroupId == toSavings.transferGroupId }, "a lone leg")

        // Spending rows.
        val normal = ledger.of(accounts.current.id, TxKind.NORMAL)
        assertEquals(15, normal.size)
        assertTrue(normal.all { it.importBatchId == batch.id && it.importKey != null && it.transferGroupId == null })
        assertEquals(setOf(groceries.id), normal.filter { it.label.startsWith("MORRISONS") || it.label.startsWith("TESCO") || it.label.startsWith("SAINSBURYS") }.map { it.categoryId }.toSet())
        assertEquals(5, normal.count { it.label == "UNION SHOP" && it.categoryId == shopping.id })
        assertEquals(eatingOut.id, normal.single { it.label == "JO BLOGGS, ref pizza" }.categoryId)
        assertNull(normal.single { it.label == "BOULDER WORLD" }.categoryId, "left uncategorised")
        val tesco = normal.single { it.label == "TESCO STORES 1234" }
        assertEquals(date(10, 8), tesco.date, "dated by the card date")
        assertEquals(Money(-183), tesco.amount)

        // Merchant rules are remembered for spending, never for moves.
        val rules = container.importRepository.observeMerchantRules().first().map { it.merchantKey }
        assertTrue("UNION SHOP" in rules, rules.toString())
        assertTrue(rules.none { it.contains("TESTER") || it.startsWith("REGULAR TRANSFER") }, rules.toString())

        // Balances, and the check against the statement.
        assertEquals(Money(SantanderPdfFixture.CLOSING_PENCE), DataTestSupport.balanceAsOf(accounts.current.id, SantanderPdfFixture.PERIOD_END))
        assertEquals(Money.pounds(1_000) - Money(50_000) + Money(20_000) + Money(3_000), DataTestSupport.balanceAsOf(accounts.second.id, farFuture))
        assertEquals(Money.pounds(100), DataTestSupport.balanceAsOf(accounts.savings.id, farFuture), "savings is untouched")
        val check = assertNotNull(summary.balanceCheck)
        assertTrue(check.matches, check.message)
        assertEquals("Matches your statement: £14.79 on 10 Oct ✓", check.message)

        // Undo takes away everything the batch made, on both accounts.
        viewModel.undoBatch(batch.id)
        withTimeout(DataTestSupport.WAIT_MS) { container.importRepository.observeBatches().first { it.isEmpty() } }
        assertEquals(ledgerBefore, ledger(), "every transaction is exactly as it was, ids included")
        assertTrue(DataTestSupport.transactions().none { it.importBatchId != null })
    }

    @Test
    fun rowsBeforeTheOpeningBalancesAreNotCountedTwiceOnEitherAccount() = runBlocking<Unit> {
        // The Santander current account was opened on 5 Oct with the £67.83 it had after the 4th; the second
        // account was opened on 26 Sep, so the move on the 24th is already in its opening balance.
        val accounts = accounts(currentOpening = Money(6_783), currentOpened = date(10, 5), secondOpened = date(9, 26))
        val ledgerBefore = ledger()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)

        // The check step tells the user the starting balance will be adjusted; that needs no decision.
        viewModel.confirmReview()
        val check = assertNotNull(viewModel.awaitState("the check step") { it.step == ImportStep.CHECK }.check)
        assertEquals(listOf<ImportQuestion>(BeforeOpening(5, date(10, 5))), check.questions)
        assertTrue(check.complete, "information only, so it is answered already")
        viewModel.confirmChecks()
        val categorise = viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        for (description in listOf("From A Tester", "TRANSFER TO ALEX TESTER", "ALEX TESTER, ref Transfer")) {
            viewModel.setGroupOtherAccount(categorise.group(description).merchantKey, accounts.second.id)
        }
        viewModel.confirmImport()
        val summary = viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val batch = assertNotNull(summary.summaryBatch)
        val ledger = ledger()

        // Second account: the leg dated 24 Sep (before it opened) is left out; 27 Sep and 4 Oct are there.
        assertEquals(listOf(date(9, 27), date(10, 4)), ledger.of(accounts.second.id, TxKind.TRANSFER).map { it.date })

        // Current account: the first five rows (including three moves) are before its opening date, so the
        // opening balance moves back to the day before the first of them and shrinks by their total.
        val openings = ledger.of(accounts.current.id, TxKind.OPENING_BALANCE)
        assertEquals(3, openings.size, "the original opening balance and the two entries that move it")
        assertEquals(date(9, 23), openings.first().date)
        assertEquals(Money.ZERO, openings.first().amount, "£67.83 less the first five rows, which come to £67.83")
        assertEquals(2, openings.count { it.importBatchId == batch.id }, "the move is tagged with the batch, so undo can remove it")

        // The balance on the statement's last day is the statement's closing balance, and the app says so.
        assertEquals(Money(SantanderPdfFixture.CLOSING_PENCE), DataTestSupport.balanceAsOf(accounts.current.id, SantanderPdfFixture.PERIOD_END))
        assertEquals(Money(6_783), DataTestSupport.balanceAsOf(accounts.current.id, date(10, 4)), "the balance after the 4th Oct row")
        assertTrue(assertNotNull(summary.balanceCheck).matches)

        viewModel.undoBatch(batch.id)
        withTimeout(DataTestSupport.WAIT_MS) { container.importRepository.observeBatches().first { it.isEmpty() } }
        assertEquals(ledgerBefore, ledger(), "undo restores every transaction, the opening balance included")
    }

    @Test
    fun aTransferWithNoOtherAccountInTheAppIsASingleLegAndNothingElseChanges() = runBlocking<Unit> {
        val accounts = accounts()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)

        viewModel.confirmReview()
        viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.confirmImport()
        viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledger = ledger()

        assertEquals(4, ledger.of(accounts.current.id, TxKind.TRANSFER).size)
        assertTrue(ledger.of(accounts.second.id, TxKind.TRANSFER).isEmpty())
        assertTrue(ledger.of(accounts.savings.id, TxKind.TRANSFER).isEmpty())
        // The moves still count in the balance, so it still matches the statement.
        assertEquals(Money(SantanderPdfFixture.CLOSING_PENCE), DataTestSupport.balanceAsOf(accounts.current.id, SantanderPdfFixture.PERIOD_END))
    }

    @Test
    fun turningTheSwitchOffMakesGroupsOrdinarySpendingAgain() = runBlocking<Unit> {
        val accounts = accounts()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)
        viewModel.confirmReview()
        val categorise = viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        viewModel.setGroupTransfer(categorise.group("TRANSFER TO ALEX TESTER").merchantKey, false)
        viewModel.setGroupTransfer(categorise.group("ALEX TESTER, ref Transfer").merchantKey, false)
        viewModel.confirmImport()
        viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }
        val ledger = ledger()

        // Two of the four moves are now NORMAL rows (the money is still in the balance either way).
        assertEquals(2, ledger.of(accounts.current.id, TxKind.TRANSFER).size)
        assertEquals(17, ledger.of(accounts.current.id, TxKind.NORMAL).size)
        assertEquals(Money(SantanderPdfFixture.CLOSING_PENCE), DataTestSupport.balanceAsOf(accounts.current.id, SantanderPdfFixture.PERIOD_END))
    }

    // ---- the same statement twice, other accounts --------------------------------------------------------------------

    @Test
    fun importingTheSameStatementAgainFindsEveryRowAlreadyImported() = runBlocking<Unit> {
        val accounts = accounts()
        val first = newViewModel()
        openReview(first, accounts.current)
        first.confirmReview()
        first.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        first.confirmImport()
        first.awaitState("the summary") { it.step == ImportStep.SUMMARY }

        val second = newViewModel()
        val review = openReview(second, accounts.current)
        assertTrue(review.rowStatuses.all { it == RowStatus.ALREADY_IMPORTED }, review.rowStatuses.toString())
        assertTrue(review.rowSelected.none { it })

        // Choosing another account at the review classifies the rows again against that account.
        second.selectAccount(accounts.second.id)
        val other = second.awaitState("the rows classified for the second account") {
            it.selectedAccountId == accounts.second.id && it.rowStatuses.none { s -> s == RowStatus.ALREADY_IMPORTED }
        }
        assertTrue(other.rowStatuses.all { it == RowStatus.NEW }, other.rowStatuses.toString())
        assertTrue(other.rowSelected.all { it })
    }

    @Test
    fun theLegsAnImportLeftOnAnotherAccountAreRecognisedWhenThatAccountsStatementIsImported() = runBlocking<Unit> {
        val accounts = accounts()
        val viewModel = newViewModel()
        openReview(viewModel, accounts.current)
        viewModel.confirmReview()
        val categorise = viewModel.awaitState("the categorise step") { it.step == ImportStep.CATEGORISE }
        for (description in listOf("From A Tester", "TRANSFER TO ALEX TESTER", "ALEX TESTER, ref Transfer")) {
            viewModel.setGroupOtherAccount(categorise.group(description).merchantKey, accounts.second.id)
        }
        viewModel.confirmImport()
        viewModel.awaitState("the summary") { it.step == ImportStep.SUMMARY }

        // The second account's own statement shows the same three moves (from its side), a day later in one case.
        val rows = listOf(
            ParsedRow(date(9, 24), Money(-50_000), "TRANSFER TO A TESTER", null, 0),
            ParsedRow(date(9, 28), Money(20_000), "TRANSFER FROM A TESTER", null, 1),
            ParsedRow(date(10, 4), Money(3_000), "TRANSFER FROM A TESTER", null, 2),
            ParsedRow(date(10, 4), Money(-1_234), "SOMETHING ELSE", null, 3),
        )
        val keys = ImportDedupe.importKeys(accounts.second.id, rows)
        val statuses = ImportDedupe.classify(accounts.second.id, rows, keys, DataTestSupport.transactions())

        assertEquals(
            listOf(RowStatus.POSSIBLE_DUPLICATE, RowStatus.POSSIBLE_DUPLICATE, RowStatus.POSSIBLE_DUPLICATE, RowStatus.NEW),
            statuses,
        )
    }
}
