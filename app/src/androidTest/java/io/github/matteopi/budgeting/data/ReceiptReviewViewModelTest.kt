package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.receipts.ProductNames
import io.github.matteopi.budgeting.core.receipts.ReceiptCheck
import io.github.matteopi.budgeting.core.receipts.ReceiptParsers
import io.github.matteopi.budgeting.core.receipts.ReceiptSource
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.ReceiptTestData
import io.github.matteopi.budgeting.ui.receipts.CategorySource
import io.github.matteopi.budgeting.ui.receipts.ReceiptReviewViewModel
import io.github.matteopi.budgeting.ui.receipts.ReviewDerived
import io.github.matteopi.budgeting.ui.receipts.ReviewItem
import io.github.matteopi.budgeting.ui.receipts.ReviewPhase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review screen's view model against the real database, fed parsed text instead of OCR:
 * splitting a shop per category, replacing the matching transaction, keeping the balance, and
 * learning product names for the next receipt.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptReviewViewModelTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()
    private val receiptDate: LocalDate = today.minusDays(1)

    private class Shop(val account: Account, val groceriesId: Long, val shoppingId: Long, val plain: Transaction)

    /** A £1,000 account with the £16.64 Tesco shop already entered by hand (what the receipt should replace). */
    private suspend fun shop(): Shop {
        val groceries = DataTestSupport.category("Groceries")
        val shopping = DataTestSupport.category("Shopping")
        val account = createAccount("Current", opening = Money.pounds(1_000), date = today.minusDays(10))
        val plain = DataTestSupport.addNormal(
            account.id, groceries.id, -ReceiptTestData.total, receiptDate, "Tesco", "weekly shop",
        )
        return Shop(account, groceries.id, shopping.id, plain)
    }

    private fun newViewModel(text: String = ReceiptTestData.text(receiptDate)): ReceiptReviewViewModel {
        var viewModel: ReceiptReviewViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ReceiptReviewViewModel(
                receiptRepository = container.receiptRepository,
                transactionRepository = container.transactionRepository,
                accountRepository = container.accountRepository,
                categoryRepository = container.categoryRepository,
                settingsStore = container.settingsStore,
                clock = container.clock,
                loadDraft = { ReceiptParsers.parseText(text, ReceiptSource.SHARED_TEXT) },
            )
        }
        return requireNotNull(viewModel)
    }

    private suspend fun ready(viewModel: ReceiptReviewViewModel): ReceiptReviewViewModel {
        withTimeout(DataTestSupport.WAIT_MS) { viewModel.phase.first { it is ReviewPhase.Ready } }
        return viewModel
    }

    /** The derived state once the accounts have loaded and [condition] holds (the initial value is an empty placeholder). */
    private suspend fun derived(viewModel: ReceiptReviewViewModel, condition: (ReviewDerived) -> Boolean = { true }): ReviewDerived =
        withTimeout(DataTestSupport.WAIT_MS) { viewModel.derived.first { it.accounts.isNotEmpty() && condition(it) } }

    private fun item(viewModel: ReceiptReviewViewModel, rawName: String): ReviewItem =
        viewModel.edit.value.items.first { it.rawName == rawName }

    private suspend fun save(viewModel: ReceiptReviewViewModel) {
        val done = CompletableDeferred<Unit>()
        viewModel.save(onSaved = { done.complete(Unit) })
        try {
            withTimeout(DataTestSupport.WAIT_MS) { done.await() }
        } catch (e: Exception) {
            throw AssertionError("Saving did not finish; error: ${viewModel.saveError.value}", e)
        }
    }

    private suspend fun receiptSplits(): List<Transaction> =
        DataTestSupport.transactions().filter { it.receiptId != null }.sortedBy { it.id }

    // ---- reading ---------------------------------------------------------------------------------

    @Test
    fun theDraftBecomesEditableItemsWithGuessedCategories() = runBlocking<Unit> {
        val shop = shop()
        val viewModel = ready(newViewModel())

        val edit = viewModel.edit.value
        assertEquals(Store.TESCO, edit.store)
        assertEquals(receiptDate, edit.date)
        assertEquals("16.64", edit.totalText)
        assertEquals(shop.account.id, edit.selectedAccountId, "the default account is preselected")
        assertEquals(ReceiptTestData.items.map { it.name }, edit.items.map { it.rawName })
        assertEquals((1L..10L).toList(), edit.items.map { it.key })
        assertTrue(edit.items.all { it.name == it.rawName && !it.fromAlias })
        assertTrue(
            edit.items.all { it.categoryId == shop.groceriesId && it.categorySource == CategorySource.GUESS },
            "with no history every item is guessed as Groceries",
        )
        assertEquals("1.30", edit.items.first().price)
        assertEquals("", edit.items.first().discount)

        val derived = derived(viewModel)
        assertEquals(ReceiptCheck.Matches, derived.check)
        assertTrue(derived.canSave, derived.problem.orEmpty())
        assertTrue(derived.hasUnsetCategories)
        assertEquals(shop.plain.id, derived.match?.transactionId, "the hand-entered Tesco shop is the match")
        assertTrue(derived.match!!.description.startsWith("Tesco -£16.64 on "), derived.match!!.description)
    }

    // ---- saving ----------------------------------------------------------------------------------

    @Test
    fun savingSplitsByCategoryReplacesTheMatchAndLearnsNames() = runBlocking<Unit> {
        val shop = shop()
        val balanceBefore = DataTestSupport.balance(shop.account.id)
        val viewModel = ready(newViewModel())

        val fusilli = item(viewModel, "FUSILLI PASTA 500G").key
        val tomatoes = item(viewModel, "TOMATOES CHERRY 250G").key
        val milk = item(viewModel, "SEMI SKIMMED MILK 2PT").key
        viewModel.setCategory(fusilli, shop.shoppingId)
        viewModel.setCategory(tomatoes, shop.shoppingId)
        viewModel.setName(milk, "Semi-skimmed milk 2 pints")

        val derived = derived(viewModel) { it.match != null }
        assertEquals(ReceiptCheck.Matches, derived.check)
        assertTrue(derived.canSave)
        save(viewModel)

        // One receipt, its ten items, two transactions that add up to the receipt total.
        val receipt = DataTestSupport.container.receiptRepository.observeReceipts().first().single()
        assertEquals("TESCO", receipt.store)
        assertEquals("SHARED_TEXT", receipt.source)
        assertEquals(ReceiptTestData.total, receipt.total)
        assertEquals(receiptDate, receipt.date)
        val items = container.receiptRepository.observeItems(receipt.id).first()
        assertEquals(10, items.size)

        assertNull(DataTestSupport.transaction(shop.plain.id), "the matched transaction was replaced")
        val splits = receiptSplits()
        assertEquals(2, splits.size)
        val groceriesPart = splits.first { it.categoryId == shop.groceriesId }
        val shoppingPart = splits.first { it.categoryId == shop.shoppingId }
        assertEquals(Money(-(ReceiptTestData.totalPence - 85 - 110)), groceriesPart.amount)
        assertEquals(Money(-(85 + 110)), shoppingPart.amount)
        assertEquals("Tesco · Groceries", groceriesPart.label)
        assertEquals("Tesco · Shopping", shoppingPart.label)
        assertEquals(-ReceiptTestData.total, splits.fold(Money.ZERO) { sum, tx -> sum + tx.amount })
        assertTrue(splits.all { it.accountId == shop.account.id && it.date == receiptDate })
        assertEquals(balanceBefore, DataTestSupport.balance(shop.account.id), "the balance is unchanged")
        assertEquals(receipt.transactionId, splits.first().id)

        // Names and categories were learned for the renamed item and the two categorised by hand only.
        val aliasKeys = container.receiptRepository.observeAliases().first().map { it.rawNameKey }.toSet()
        assertEquals(
            setOf(
                ProductNames.normalise("SEMI SKIMMED MILK 2PT"),
                ProductNames.normalise("FUSILLI PASTA 500G"),
                ProductNames.normalise("TOMATOES CHERRY 250G"),
            ),
            aliasKeys,
        )
        val products = container.receiptRepository.observeProducts().first()
        assertEquals(shop.shoppingId, products.first { it.name == "FUSILLI PASTA 500G" }.categoryId)
        assertNull(products.first { it.name == "Semi-skimmed milk 2 pints" }.categoryId, "a guess is not learned")
        assertEquals(3, items.count { it.productId != null })
        assertEquals("SEMI SKIMMED MILK 2PT", items.first { it.productId == products.first { p -> p.name == "Semi-skimmed milk 2 pints" }.id }.rawName)
    }

    @Test
    fun aSecondReceiptWithTheSameRawNamesIsPrefilled() = runBlocking<Unit> {
        val shop = shop()
        val first = ready(newViewModel())
        first.setCategory(item(first, "FUSILLI PASTA 500G").key, shop.shoppingId)
        first.setCategory(item(first, "TOMATOES CHERRY 250G").key, shop.shoppingId)
        first.setName(item(first, "SEMI SKIMMED MILK 2PT").key, "Semi-skimmed milk 2 pints")
        derived(first) { it.canSave }
        save(first)

        val second = ready(newViewModel(ReceiptTestData.text(today)))
        val edit = second.edit.value
        val milk = edit.items.first { it.rawName == "SEMI SKIMMED MILK 2PT" }
        assertEquals("Semi-skimmed milk 2 pints", milk.name)
        assertTrue(milk.fromAlias)
        // The milk's category was only a guess when learned, so it is still a guess (Groceries is also Tesco's most used).
        assertEquals(shop.groceriesId, milk.categoryId)
        for (raw in listOf("FUSILLI PASTA 500G", "TOMATOES CHERRY 250G")) {
            val pasta = edit.items.first { it.rawName == raw }
            assertEquals(raw, pasta.name)
            assertTrue(pasta.fromAlias)
            assertEquals(shop.shoppingId, pasta.categoryId, raw)
            assertEquals(CategorySource.ALIAS, pasta.categorySource, raw)
        }
        val bread = edit.items.first { it.rawName == "HOVIS MEDIUM SLICED 800G" }
        assertFalse(bread.fromAlias)

        // Today's shop has no matching transaction, so it is added and the balance falls by the total.
        val derived = derived(second) { it.canSave }
        assertNull(derived.match, "the first receipt's transactions are not matched again")
        val balanceBefore = DataTestSupport.balance(shop.account.id)
        val transactionsBefore = DataTestSupport.transactions().size
        save(second)

        assertEquals(balanceBefore - ReceiptTestData.total, DataTestSupport.balance(shop.account.id))
        assertEquals(transactionsBefore + 2, DataTestSupport.transactions().size)
        val todaysSplits = DataTestSupport.transactions().filter { it.receiptId != null && it.date == today }
        assertEquals(-ReceiptTestData.total, todaysSplits.fold(Money.ZERO) { sum, tx -> sum + tx.amount })
        assertEquals(2, container.receiptRepository.observeReceipts().first().size)
    }

    @Test
    fun turningReplaceOffKeepsTheMatchedTransaction() = runBlocking<Unit> {
        val shop = shop()
        val balanceBefore = DataTestSupport.balance(shop.account.id)
        val viewModel = ready(newViewModel())
        viewModel.setReplace(false)
        derived(viewModel) { it.canSave }
        save(viewModel)

        assertNotNull(DataTestSupport.transaction(shop.plain.id), "the hand-entered transaction stays")
        assertEquals(balanceBefore - ReceiptTestData.total, DataTestSupport.balance(shop.account.id))
        val splits = receiptSplits()
        assertEquals(1, splits.size, "all items are Groceries, so one transaction")
        assertEquals("Tesco", splits.single().label)
        assertEquals(-ReceiptTestData.total, splits.single().amount)
    }

    // ---- editing ---------------------------------------------------------------------------------

    @Test
    fun editsChangeTheCheckAndWhetherSavingIsAllowed() = runBlocking<Unit> {
        shop()
        val viewModel = ready(newViewModel())
        val bread = item(viewModel, "HOVIS MEDIUM SLICED 800G").key

        viewModel.setPrice(bread, "1.53")
        var derived = derived(viewModel) { it.mismatch }
        assertEquals(ReceiptCheck.Mismatch(Money(23)), derived.check)
        assertEquals("Items are £0.23 more than the total — check the highlighted lines", derived.checkMessage)
        assertTrue(derived.canSave, "a mismatch warns but does not block saving")

        viewModel.setPrice(bread, "1.30")
        derived = derived(viewModel) { !it.mismatch }
        assertEquals("Items add up to the total ✓", derived.checkMessage)

        viewModel.setPrice(bread, "abc")
        derived = derived(viewModel) { !it.canSave }
        assertTrue(derived.itemFlags.getValue(bread).priceInvalid)
        assertEquals("Fix the amounts marked in red", derived.problem)

        viewModel.setPrice(bread, "1.30")
        viewModel.deleteItem(bread)
        derived = derived(viewModel) { it.mismatch }
        assertEquals(ReceiptCheck.Mismatch(Money(-130)), derived.check)
        assertEquals("Items are £1.30 less than the total — check the highlighted lines", derived.checkMessage)

        viewModel.addItem()
        val added = viewModel.edit.value.items.last()
        assertEquals(11L, added.key)
        derived = derived(viewModel) { !it.canSave }
        assertEquals("Every item needs a name", derived.problem)
        viewModel.setName(added.key, "Bread rolls")
        viewModel.setPrice(added.key, "1.30")
        derived = derived(viewModel) { it.canSave }
        assertEquals(ReceiptCheck.Matches, derived.check)

        viewModel.setTotal("")
        derived = derived(viewModel) { !it.canSave }
        assertEquals("Enter the receipt total", derived.problem)
    }

    @Test
    fun anEmptyItemListCannotBeSaved() = runBlocking<Unit> {
        shop()
        val viewModel = ready(newViewModel("CORNER SHOP LTD\n${receiptDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}\nTOTAL   3.50"))
        val edit = viewModel.edit.value
        assertEquals(Store.OTHER, edit.store)
        assertTrue(edit.items.isEmpty())
        val derived = derived(viewModel)
        assertFalse(derived.canSave)
        assertEquals("Add at least one item", derived.problem)
    }

    @Test
    fun settingACategoryForAllUnsetConfirmsAndLearnsEveryItem() = runBlocking<Unit> {
        val shop = shop()
        val viewModel = ready(newViewModel())
        viewModel.setCategory(item(viewModel, "FUSILLI PASTA 500G").key, shop.shoppingId)
        viewModel.setCategoryForAllUnset(shop.groceriesId)

        val items = viewModel.edit.value.items
        assertTrue(items.all { it.categorySource != CategorySource.GUESS && it.categorySource != CategorySource.NONE })
        assertEquals(shop.shoppingId, items.first { it.rawName == "FUSILLI PASTA 500G" }.categoryId, "chosen items keep their category")
        assertEquals(9, items.count { it.categoryId == shop.groceriesId })
        assertFalse(derived(viewModel).hasUnsetCategories)

        save(viewModel)
        val aliases = container.receiptRepository.observeAliases().first()
        assertEquals(10, aliases.size)
    }

    @Test
    fun theShopsUsualCategoryIsGuessedFromEarlierReceipts() = runBlocking<Unit> {
        val shop = shop()
        // An earlier Tesco receipt whose items were mostly Shopping (saved without transactions, as a minimal history).
        container.receiptRepository.saveReceipt(
            receipt = Receipt(store = "TESCO", date = receiptDate.minusDays(7), total = Money(300), source = "PHOTO"),
            items = listOf("ONE", "TWO", "THREE").map {
                ReceiptItem(receiptId = 0, rawName = it, price = Money(100), categoryId = shop.shoppingId)
            } + ReceiptItem(receiptId = 0, rawName = "FOUR", price = Money(100), categoryId = shop.groceriesId),
            splitTransactions = emptyList(),
            replaceTransactionId = null,
        )

        val viewModel = ready(newViewModel())
        val bread = viewModel.edit.value.items.first { it.rawName == "HOVIS MEDIUM SLICED 800G" }
        assertEquals(shop.shoppingId, bread.categoryId, "Shopping is Tesco's most used category")
        assertEquals(CategorySource.GUESS, bread.categorySource)
    }
}
