package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.importer.CategorySuggester
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.SelectedImportRow
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Statement import: [io.github.matteopi.budgeting.data.repo.ImportRepository] against the real database. */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    /** Five rows, all dated before an account opened ten days ago; they sum to +£67.75. */
    private fun statement(): List<ParsedRow> = listOf(
        ParsedRow(today.minusDays(40), Money(-2_340), "CARD PAYMENT TO TESCO STORES 5421 ON 01-09-2026", null, 0),
        ParsedRow(today.minusDays(33), Money(10_000), "FASTER PAYMENTS RECEIPT FROM MUM", null, 1),
        ParsedRow(today.minusDays(20), Money(-385), "CARD PAYMENT TO GREGGS", null, 2),
        // Two identical purchases on the same day: they must still get distinct import keys.
        ParsedRow(today.minusDays(20), Money(-250), "CARD PAYMENT TO COSTA COFFEE", null, 3),
        ParsedRow(today.minusDays(20), Money(-250), "CARD PAYMENT TO COSTA COFFEE", null, 4),
    )

    private val statementTotal = Money(6_775)

    private suspend fun openingBalanceOf(accountId: Long): Transaction? =
        transactions()
            .filter { it.accountId == accountId && it.kind == TxKind.OPENING_BALANCE }
            .minByOrNull { it.date }

    private fun selected(accountId: Long, rows: List<ParsedRow>, categoryId: Long?): List<SelectedImportRow> {
        val keys = ImportDedupe.importKeys(accountId, rows)
        return rows.mapIndexed { i, row -> SelectedImportRow(row, keys[i], categoryId) }
    }

    /** What the import wizard does: the earliest OPENING_BALANCE entry is the effective opening balance. */
    private suspend fun importInto(
        accountId: Long,
        rows: List<ParsedRow>,
        categoryId: Long? = null,
        rules: List<MerchantRule> = emptyList(),
        fileName: String = "statement.csv",
        nowMillis: Long = container.clock.nowMillis(),
    ): ImportBatch = container.importRepository.importRows(
        accountId = accountId,
        fileName = fileName,
        rows = selected(accountId, rows, categoryId),
        rulesToRemember = rules,
        openingBalanceTransaction = openingBalanceOf(accountId),
        nowMillis = nowMillis,
    )

    private suspend fun ledger(): List<Transaction> = transactions().sortedBy { it.id }

    // ---- what an import creates ---------------------------------------------------------------

    @Test
    fun importCreatesNormalTransactionsWithImportKeysAndABatch() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        val keys = ImportDedupe.importKeys(account.id, rows)
        val now = 1_780_000_000_000L

        val batch = importInto(account.id, rows, groceries.id, fileName = "santander-sep.csv", nowMillis = now)

        assertTrue(batch.id > 0)
        assertEquals(account.id, batch.accountId)
        assertEquals("santander-sep.csv", batch.fileName)
        assertEquals(5, batch.rowCount)
        assertEquals(today.minusDays(40), batch.firstDate)
        assertEquals(today.minusDays(20), batch.lastDate)
        assertEquals(now, batch.importedAtEpochMillis)
        assertEquals(listOf(batch), container.importRepository.observeBatches().first(), "the returned batch is what was stored")

        val imported = transactions().filter { it.importBatchId == batch.id && it.kind == TxKind.NORMAL }
        assertEquals(5, imported.size)
        assertEquals(5, imported.mapNotNull { it.importKey }.toSet().size, "every row has its own import key, even identical ones")
        val byKey = imported.associateBy { it.importKey }
        rows.forEachIndexed { i, row ->
            val tx = byKey.getValue(keys[i])
            assertEquals(account.id, tx.accountId)
            assertEquals(row.amount, tx.amount)
            assertEquals(row.date, tx.date)
            assertEquals(row.description, tx.label)
            assertEquals(groceries.id, tx.categoryId)
            assertEquals(TxKind.NORMAL, tx.kind)
            assertEquals(batch.id, tx.importBatchId)
            assertNull(tx.transferGroupId)
        }
    }

    @Test
    fun rowsWithoutACategoryStayUncategorised() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))

        val batch = importInto(account.id, statement(), categoryId = null)

        val imported = transactions().filter { it.importBatchId == batch.id && it.kind == TxKind.NORMAL }
        assertEquals(5, imported.size)
        assertTrue(imported.all { it.categoryId == null })
    }

    @Test
    fun importingWithoutAnOpeningBalanceJustAddsTheRows() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = listOf(
            ParsedRow(today.minusDays(40), Money(-2_000), "CARD PAYMENT TO TESCO", null, 0),
            ParsedRow(today.minusDays(3), Money(-1_000), "CARD PAYMENT TO ALDI", null, 1),
        )

        container.importRepository.importRows(
            accountId = account.id,
            fileName = "no-opening.csv",
            rows = selected(account.id, rows, null),
            rulesToRemember = emptyList(),
            openingBalanceTransaction = null,
            nowMillis = container.clock.nowMillis(),
        )

        assertTrue(transactions().none { it.kind == TxKind.OPENING_BALANCE && it.importBatchId != null })
        assertEquals(Money(50_000 - 3_000L), DataTestSupport.balance(account.id))
    }

    // ---- opening balance ----------------------------------------------------------------------

    @Test
    fun rowsBeforeTheOpeningBalanceMoveItSoTheCurrentBalanceIsUnchanged() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))

        val batch = importInto(account.id, statement())

        assertEquals(Money(50_000), DataTestSupport.balance(account.id), "the current balance is unchanged")
        assertEquals(Money(50_000), container.accountRepository.observeBalances().first()[account.id])

        // The effective opening balance is now the earlier, smaller one the statement starts from.
        val effective = assertNotNull(openingBalanceOf(account.id))
        assertEquals(Money(50_000) - statementTotal, effective.amount)
        assertEquals(today.minusDays(41), effective.date)

        // The history reads correctly day by day: 432.25 before the first row, back to 500.00 by the end.
        assertEquals(Money(43_225), DataTestSupport.balanceAsOf(account.id, today.minusDays(41)))
        assertEquals(Money(40_885), DataTestSupport.balanceAsOf(account.id, today.minusDays(40)))
        assertEquals(Money(50_885), DataTestSupport.balanceAsOf(account.id, today.minusDays(33)))
        assertEquals(Money(50_000), DataTestSupport.balanceAsOf(account.id, today.minusDays(20)))

        // The move is two entries tagged with the batch, so undo can remove them.
        val moves = transactions().filter { it.importBatchId == batch.id && it.kind == TxKind.OPENING_BALANCE }
        assertEquals(2, moves.size)
        assertEquals(-statementTotal, Money(moves.sumOf { it.amount.pence }))
    }

    @Test
    fun rowsAfterTheOpeningBalanceChangeTheBalanceNormally() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = listOf(
            ParsedRow(today.minusDays(40), Money(-2_000), "CARD PAYMENT TO TESCO", null, 0),
            ParsedRow(today.minusDays(2), Money(-1_000), "CARD PAYMENT TO ALDI", null, 1),
        )

        importInto(account.id, rows)

        // Only the row dated after the opening balance is new money leaving the account.
        assertEquals(Money(49_000), DataTestSupport.balance(account.id))
        assertEquals(Money(52_000), assertNotNull(openingBalanceOf(account.id)).amount)
    }

    @Test
    fun aSecondImportOfEarlierRowsMovesTheOpeningBalanceAgainAndKeepsTheBalance() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        importInto(account.id, statement(), fileName = "one.csv")
        val earlier = listOf(
            ParsedRow(today.minusDays(90), Money(-1_000), "CARD PAYMENT TO ASDA", null, 0),
            ParsedRow(today.minusDays(80), Money(2_500), "FASTER PAYMENTS RECEIPT FROM DAD", null, 1),
        )

        importInto(account.id, earlier, fileName = "two.csv")

        assertEquals(Money(50_000), DataTestSupport.balance(account.id))
        val effective = assertNotNull(openingBalanceOf(account.id))
        assertEquals(today.minusDays(91), effective.date)
        assertEquals(Money(43_225 - 1_500L), effective.amount)
    }

    // ---- undo ---------------------------------------------------------------------------------

    @Test
    fun undoRestoresTheExactPreviousBalancesAndTransactions() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val current = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val savings = createAccount("Savings", opening = Money(20_000), date = today.minusDays(10))
        DataTestSupport.addNormal(current.id, groceries.id, Money(-1_234), today.minusDays(4), "Tesco")
        val ledgerBefore = ledger()
        val balancesBefore = container.accountRepository.observeBalances().first()
        val openingBefore = openingBalanceOf(current.id)

        val batch = importInto(current.id, statement(), groceries.id)
        assertNotEquals(openingBefore, openingBalanceOf(current.id), "the import really moved the opening balance")
        assertTrue(ledger().size > ledgerBefore.size)

        container.importRepository.undoBatch(batch.id)

        assertEquals(ledgerBefore, ledger(), "every transaction is exactly as it was, ids included")
        assertEquals(balancesBefore, container.accountRepository.observeBalances().first())
        assertEquals(openingBefore, openingBalanceOf(current.id), "the opening balance is back where it was")
        assertEquals(Money(48_766), DataTestSupport.balance(current.id))
        assertEquals(Money(20_000), DataTestSupport.balance(savings.id))
        assertTrue(container.importRepository.observeBatches().first().isEmpty())
    }

    @Test
    fun undoingTwoImportsInReverseOrderRestoresEachStep() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val ledger0 = ledger()

        val first = importInto(account.id, statement(), fileName = "one.csv")
        val ledger1 = ledger()
        val second = importInto(
            account.id,
            listOf(
                ParsedRow(today.minusDays(90), Money(-1_000), "CARD PAYMENT TO ASDA", null, 0),
                ParsedRow(today.minusDays(80), Money(2_500), "FASTER PAYMENTS RECEIPT FROM DAD", null, 1),
            ),
            fileName = "two.csv",
        )
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))

        container.importRepository.undoBatch(second.id)
        assertEquals(ledger1, ledger())
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))
        assertEquals(listOf(first.id), container.importRepository.observeBatches().first().map { it.id })

        container.importRepository.undoBatch(first.id)
        assertEquals(ledger0, ledger())
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))
        assertTrue(container.importRepository.observeBatches().first().isEmpty())
    }

    @Test
    fun undoAlsoWorksForRowsThatDoNotMoveTheOpeningBalance() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(30))
        val ledgerBefore = ledger()
        val batch = importInto(
            account.id,
            listOf(ParsedRow(today.minusDays(3), Money(-700), "CARD PAYMENT TO GREGGS", null, 0)),
        )
        assertEquals(Money(49_300), DataTestSupport.balance(account.id))

        container.importRepository.undoBatch(batch.id)

        assertEquals(ledgerBefore, ledger())
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun anUndoneFileCanBeImportedAgain() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()

        val batch = importInto(account.id, rows)
        container.importRepository.undoBatch(batch.id)

        val keys = ImportDedupe.importKeys(account.id, rows)
        assertEquals(
            List(rows.size) { RowStatus.NEW },
            ImportDedupe.classify(account.id, rows, keys, transactions()),
            "after an undo the rows are new again",
        )
        importInto(account.id, rows)
        assertEquals(Money(50_000), DataTestSupport.balance(account.id))
        assertEquals(5, transactions().count { it.importKey != null })
    }

    // ---- dedupe -------------------------------------------------------------------------------

    @Test
    fun importingTheSameFileAgainIsClassifiedAlreadyImported() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        val keysBefore = ImportDedupe.importKeys(account.id, rows)
        assertEquals(
            List(rows.size) { RowStatus.NEW },
            ImportDedupe.classify(account.id, rows, keysBefore, transactions()),
        )

        importInto(account.id, rows)

        val keysAgain = ImportDedupe.importKeys(account.id, rows)
        assertEquals(keysBefore, keysAgain, "the keys are stable for the same file")
        assertEquals(
            List(rows.size) { RowStatus.ALREADY_IMPORTED },
            ImportDedupe.classify(account.id, rows, keysAgain, transactions()),
        )
    }

    @Test
    fun theSameRowsInAnotherAccountAreNew() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val other = createAccount("Other", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        importInto(current.id, rows)

        val otherKeys = ImportDedupe.importKeys(other.id, rows)
        assertEquals(
            List(rows.size) { RowStatus.NEW },
            ImportDedupe.classify(other.id, rows, otherKeys, transactions()),
        )
    }

    @Test
    fun aMatchingManualEntryIsAPossibleDuplicate() = runBlocking<Unit> {
        val eatingOut = DataTestSupport.category("Eating out")
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        DataTestSupport.addNormal(account.id, eatingOut.id, Money(-385), today.minusDays(21), "Greggs (typed in by hand)")
        val rows = statement()
        val keys = ImportDedupe.importKeys(account.id, rows)

        val statuses = ImportDedupe.classify(account.id, rows, keys, transactions())

        assertEquals(RowStatus.POSSIBLE_DUPLICATE, statuses[2], "£3.85 within two days of the manual £3.85")
        assertEquals(4, statuses.count { it == RowStatus.NEW })
    }

    @Test
    fun aSecondImportOfTheSameFileIsRejectedAndLeavesNoTrace() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        importInto(account.id, rows)
        val ledgerAfterFirst = ledger()
        val batchesAfterFirst = container.importRepository.observeBatches().first()

        val failure = runCatching { importInto(account.id, rows, fileName = "again.csv") }.exceptionOrNull()

        assertNotNull(failure, "import keys are unique, so duplicates cannot get in")
        assertEquals(ledgerAfterFirst, ledger(), "the failed import rolled back completely")
        assertEquals(batchesAfterFirst, container.importRepository.observeBatches().first())
    }

    // ---- merchant rules -----------------------------------------------------------------------

    @Test
    fun merchantRulesAreSavedAndUpsertedByMerchantKey() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val eatingOut = DataTestSupport.category("Eating out")
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        val tescoKey = MerchantKey.normalise(rows[0].description)
        val greggsKey = MerchantKey.normalise(rows[2].description)
        assertTrue(tescoKey.isNotEmpty() && greggsKey.isNotEmpty() && tescoKey != greggsKey)

        importInto(
            account.id, rows, groceries.id,
            rules = listOf(MerchantRule(merchantKey = tescoKey, categoryId = groceries.id, label = "Tesco")),
        )

        val first = container.importRepository.observeMerchantRules().first()
        assertEquals(1, first.size)
        assertEquals(tescoKey, first.single().merchantKey)
        assertEquals(groceries.id, first.single().categoryId)
        assertEquals("Tesco", first.single().label)
        assertEquals(
            groceries.id,
            CategorySuggester.suggest(tescoKey, first, DataTestSupport.awaitCategories()),
            "the suggester uses the saved rule",
        )

        // A later import remembers a different category for the same merchant, and a new merchant.
        importInto(
            account.id,
            listOf(ParsedRow(today.minusDays(60), Money(-500), "CARD PAYMENT TO TESCO STORES 9999", null, 0)),
            shopping.id,
            rules = listOf(
                MerchantRule(merchantKey = tescoKey, categoryId = shopping.id),
                MerchantRule(merchantKey = greggsKey, categoryId = eatingOut.id),
            ),
            fileName = "two.csv",
        )

        val second = container.importRepository.observeMerchantRules().first().associateBy { it.merchantKey }
        assertEquals(2, second.size, "the Tesco rule was replaced, not duplicated")
        assertEquals(shopping.id, second.getValue(tescoKey).categoryId)
        assertNull(second.getValue(tescoKey).label)
        assertEquals(eatingOut.id, second.getValue(greggsKey).categoryId)
    }

    @Test
    fun aFailedImportDoesNotSaveItsMerchantRules() = runBlocking<Unit> {
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statement()
        importInto(account.id, rows)

        runCatching {
            importInto(
                account.id, rows, groceries.id,
                rules = listOf(MerchantRule(merchantKey = "SOMETHING NEW", categoryId = groceries.id)),
            )
        }

        assertTrue(container.importRepository.observeMerchantRules().first().isEmpty())
    }
}
