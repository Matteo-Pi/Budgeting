package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionEditTest {

    private val day = LocalDate.of(2026, 10, 5)

    private fun tx(
        id: Long = 1,
        kind: TxKind = TxKind.NORMAL,
        pence: Long = -500,
        group: String? = null,
        importBatchId: Long? = null,
        receiptId: Long? = null,
    ) = Transaction(
        id = id,
        accountId = 1,
        amount = Money(pence),
        date = day,
        categoryId = null,
        kind = kind,
        transferGroupId = group,
        importBatchId = importBatchId,
        receiptId = receiptId,
    )

    private fun category(id: Long, name: String, flow: Flow, archived: Boolean = false) =
        Category(id = id, name = name, flow = flow, forecastMode = ForecastMode.LEARNED, archived = archived)

    private fun account(id: Long, name: String, archived: Boolean = false) =
        Account(id = id, name = name, type = AccountType.CURRENT, archived = archived)

    // ---- the "change similar" question ----------------------------------------------------------

    @Test
    fun `the question names the merchant, the count and the category`() {
        val prompt = SimilarPrompt(merchantKey = "TESCO STORES", categoryId = 3, exceptId = 9, count = 7)
        assertEquals("Change the other 7 \"TESCO STORES\" transactions to Groceries too?", prompt.question("Groceries"))
        assertEquals("Use Groceries for TESCO STORES in future imports", prompt.rememberLabel("Groceries"))
    }

    @Test
    fun `the question is singular for one other transaction`() {
        val prompt = SimilarPrompt(merchantKey = "TESCO STORES", categoryId = 3, exceptId = 9, count = 1)
        assertEquals("Change the other \"TESCO STORES\" transaction to Groceries too?", prompt.question("Groceries"))
    }

    // ---- which form a row gets --------------------------------------------------------------------

    @Test
    fun `an ordinary transaction and a balance check use the single form`() {
        assertEquals(EditRules.EditorKind.SINGLE, EditRules.editorKind(ActivityRow.Single(tx())))
        assertEquals(EditRules.EditorKind.SINGLE, EditRules.editorKind(ActivityRow.Single(tx(kind = TxKind.RECONCILIATION))))
        assertEquals(EditRules.EditorKind.SINGLE, EditRules.editorKind(ActivityRow.Single(tx(receiptId = 4))))
    }

    @Test
    fun `an opening balance is a starting balance unless an import made it`() {
        assertEquals(
            EditRules.EditorKind.STARTING_BALANCE,
            EditRules.editorKind(ActivityRow.Single(tx(kind = TxKind.OPENING_BALANCE, pence = 10_000))),
        )
        assertEquals(
            EditRules.EditorKind.IMPORT_ENTRY,
            EditRules.editorKind(ActivityRow.Single(tx(kind = TxKind.OPENING_BALANCE, importBatchId = 3))),
        )
    }

    @Test
    fun `transfers use the transfer form, including a lone leg`() {
        val out = tx(1, TxKind.TRANSFER, -500, "g")
        val into = tx(2, TxKind.TRANSFER, 500, "g")
        assertEquals(EditRules.EditorKind.TRANSFER, EditRules.editorKind(ActivityRow.Transfer(out, into)))
        assertEquals(EditRules.EditorKind.TRANSFER, EditRules.editorKind(ActivityRow.Single(out)))
        assertEquals(EditRules.EditorKind.TRANSFER, EditRules.editorKind(ActivityRow.Single(tx(3, TxKind.TRANSFER, 500, null))))
    }

    // ---- what each form offers ------------------------------------------------------------------

    @Test
    fun `only an ordinary non-receipt transaction can become a move between accounts`() {
        assertTrue(EditRules.canMoveBetweenAccounts(tx()))
        assertFalse(EditRules.canMoveBetweenAccounts(tx(receiptId = 2)))
        assertFalse(EditRules.canMoveBetweenAccounts(tx(kind = TxKind.RECONCILIATION)))
        assertFalse(EditRules.canMoveBetweenAccounts(tx(kind = TxKind.OPENING_BALANCE)))
        assertFalse(EditRules.canMoveBetweenAccounts(tx(kind = TxKind.TRANSFER, group = "g")))
    }

    @Test
    fun `receipt parts are recognised`() {
        assertTrue(EditRules.isReceiptPart(tx(receiptId = 2)))
        assertFalse(EditRules.isReceiptPart(tx()))
    }

    @Test
    fun `categories offered match the direction, are not archived, and include both-ways ones`() {
        val groceries = category(1, "Groceries", Flow.OUT)
        val wages = category(2, "Wages", Flow.IN)
        val untracked = category(3, "Untracked", Flow.BOTH)
        val old = category(4, "Old", Flow.OUT, archived = true)
        val all = listOf(groceries, wages, untracked, old)

        assertEquals(listOf(groceries, untracked), EditRules.eligibleCategories(all, out = true))
        assertEquals(listOf(wages, untracked), EditRules.eligibleCategories(all, out = false))
    }

    @Test
    fun `the other account of a move is any other account that is not archived`() {
        val accounts = listOf(account(1, "Lloyds"), account(2, "Savings"), account(3, "Old", archived = true))
        assertEquals(listOf(2L), EditRules.otherAccountChoices(accounts, accountId = 1).map { it.id })
        assertEquals(listOf(1L, 2L), EditRules.otherAccountChoices(accounts + account(4, "Cash", archived = true), accountId = 3).map { it.id })
    }

    @Test
    fun `a transfer side offers live accounts plus its current one even if archived`() {
        val accounts = listOf(account(1, "Lloyds"), account(2, "Savings"), account(3, "Old", archived = true))
        assertEquals(listOf(1L, 2L), EditRules.transferAccountChoices(accounts, current = null).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), EditRules.transferAccountChoices(accounts, current = 3).map { it.id })
    }

    @Test
    fun `a transfer needs at least one account and two different ones`() {
        assertNull(EditRules.transferSidesError(1, 2))
        assertNull(EditRules.transferSidesError(1, null))
        assertNull(EditRules.transferSidesError(null, 2))
        assertEquals("Pick two different accounts.", EditRules.transferSidesError(1, 1))
        assertEquals(
            "At least one side of a transfer must be an account in the app.",
            EditRules.transferSidesError(null, null),
        )
    }
}
