package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.domain.PendingItem
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [io.github.matteopi.budgeting.domain.PostingService] against the real database. `postDue` takes the
 * day as a parameter, so "tomorrow" and "next month" are simulated by passing a later date.
 */
@RunWith(AndroidJUnit4::class)
class PostingServiceTest {
    private val container get() = DataTestSupport.container
    private val posting get() = container.postingService
    private val today: LocalDate = DataTestSupport.today()
    private val tomorrow: LocalDate = today.plusDays(1)

    // ---- helpers ------------------------------------------------------------------------------

    private suspend fun addRule(
        label: String,
        account: Account,
        category: Category?,
        amount: Money,
        start: LocalDate,
        frequency: Frequency = Frequency.MONTHLY,
        interval: Int = 1,
        end: LocalDate? = null,
        mode: PostingMode = PostingMode.AUTO,
        postedThrough: LocalDate? = null,
        toAccountId: Long? = null,
        major: Boolean = false,
        active: Boolean = true,
    ): RecurringRule {
        val id = container.planRepository.addRecurringRule(
            RecurringRule(
                label = label,
                accountId = account.id,
                categoryId = category?.id,
                amount = amount,
                frequency = frequency,
                interval = interval,
                startDate = start,
                endDate = end,
                postingMode = mode,
                postedThrough = postedThrough,
                toAccountId = toAccountId,
                isMajorIncome = major,
                active = active,
            ),
        )
        return ruleNow(id)
    }

    private suspend fun ruleNow(id: Long): RecurringRule =
        container.planRepository.observeRecurringRules().first().single { it.id == id }

    private suspend fun addItem(
        label: String,
        account: Account,
        category: Category?,
        amount: Money,
        date: LocalDate,
        mode: PostingMode = PostingMode.AUTO,
        toAccountId: Long? = null,
        major: Boolean = false,
        posted: Boolean = false,
        skipped: Boolean = false,
    ): ScheduledItem {
        val id = container.planRepository.addScheduledItem(
            ScheduledItem(
                label = label,
                accountId = account.id,
                categoryId = category?.id,
                amount = amount,
                date = date,
                postingMode = mode,
                toAccountId = toAccountId,
                isMajorIncome = major,
                posted = posted,
                skipped = skipped,
            ),
        )
        return itemNow(id)
    }

    private suspend fun itemNow(id: Long): ScheduledItem =
        container.planRepository.observeScheduledItems().first().single { it.id == id }

    private suspend fun postedFromRule(ruleId: Long): List<Transaction> = transactions().filter { it.recurringRuleId == ruleId }

    private suspend fun postedFromItem(itemId: Long): List<Transaction> = transactions().filter { it.scheduledItemId == itemId }

    private suspend fun transferLegs(): List<Transaction> = transactions().filter { it.kind == TxKind.TRANSFER }

    private fun assertOneTransferOf(legs: List<Transaction>, from: Account, to: Account, pence: Long, date: LocalDate) {
        assertEquals(2, legs.size, "a transfer is two legs")
        val out = legs.single { it.accountId == from.id }
        val incoming = legs.single { it.accountId == to.id }
        assertEquals(Money(-pence), out.amount)
        assertEquals(Money(pence), incoming.amount)
        assertNotNull(out.transferGroupId)
        assertEquals(out.transferGroupId, incoming.transferGroupId, "both legs share a group id")
        assertNull(out.categoryId)
        assertNull(incoming.categoryId)
        assertEquals(date, out.date)
        assertEquals(date, incoming.date)
    }

    // ---- AUTO recurring rules -----------------------------------------------------------------

    @Test
    fun aRuleStartingInThePastPostsNothingRetroactively() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")

        // Weekly from 22 days ago: occurrences 22, 15, 8 and 1 days ago — all already in the opening balance.
        val rule = addRule("Gym", account, gym, Money(-1_000), start = today.minusDays(22), frequency = Frequency.WEEKLY)

        assertEquals(today.minusDays(1), rule.postedThrough, "past occurrences are already in the opening balance")
        posting.postDue(today)
        assertTrue(postedFromRule(rule.id).isEmpty(), "no past occurrence is posted")
        assertEquals(Money(100_000), DataTestSupport.balance(account.id))
        assertTrue(posting.pendingConfirmations(today).isEmpty())
    }

    @Test
    fun aPastStartRuleStillPostsThePaymentDueToday() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")

        // Weekly from exactly three weeks ago: one occurrence falls today.
        val rule = addRule("Gym", account, gym, Money(-1_000), start = today.minusDays(21), frequency = Frequency.WEEKLY)
        posting.postDue(today)
        posting.postDue(today)

        assertEquals(listOf(today), postedFromRule(rule.id).map { it.date }, "only today's payment, once")
        assertEquals(Money(99_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun aRuleStartingTodayPostsTodaysPayment() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val rent = DataTestSupport.category("Rent & bills")

        val rule = addRule("Rent", account, rent, Money(-45_000), start = today)

        assertNull(rule.postedThrough, "nothing before today to treat as paid")
        posting.postDue(today)
        assertEquals(listOf(today), postedFromRule(rule.id).map { it.date })
        assertEquals(Money(55_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun aRuleThatIsDueTodayPostsExactlyOnce() = runBlocking<Unit> {
        // A restore (or import) keeps an explicit postedThrough, so a rule starting today can genuinely be due.
        val account = createAccount("Current", opening = Money(100_000))
        val rent = DataTestSupport.category("Rent & bills")
        val rule = addRule("Rent", account, rent, Money(-45_000), start = today, postedThrough = today.minusDays(1))

        posting.postDue(today)
        posting.postDue(today)

        val posted = postedFromRule(rule.id)
        assertEquals(1, posted.size)
        val tx = posted.single()
        assertEquals(today, tx.date)
        assertEquals(Money(-45_000), tx.amount)
        assertEquals(TxKind.NORMAL, tx.kind)
        assertEquals(rent.id, tx.categoryId)
        assertEquals("Rent", tx.label)
        assertEquals(account.id, tx.accountId)
        assertEquals(Money(55_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun aRuleStartingTomorrowPostsExactlyOnceWhenItFallsDue() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val rent = DataTestSupport.category("Rent & bills")
        val rule = addRule("Rent", account, rent, Money(-45_000), start = tomorrow)
        assertNull(rule.postedThrough, "a future rule has nothing posted yet")

        posting.postDue(today)
        assertTrue(postedFromRule(rule.id).isEmpty(), "not due yet")

        posting.postDue(tomorrow)
        posting.postDue(tomorrow)
        posting.postDue(tomorrow.plusDays(5))

        val posted = postedFromRule(rule.id)
        assertEquals(1, posted.size, "posting again on the same or a later day adds nothing")
        assertEquals(tomorrow, posted.single().date)
        assertEquals(Money(-45_000), posted.single().amount)
        assertEquals(rent.id, posted.single().categoryId)
        assertEquals(tomorrow.plusDays(5), ruleNow(rule.id).postedThrough)
    }

    @Test
    fun aRuleFromThePastNextPostsItsNextOccurrenceOnly() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        // Weekly from 71 days ago: the last past occurrence was yesterday, the next is in 6 days.
        val rule = addRule("Gym", account, gym, Money(-1_000), start = today.minusDays(71), frequency = Frequency.WEEKLY)
        assertEquals(today.minusDays(1), rule.postedThrough)

        posting.postDue(today)
        assertTrue(postedFromRule(rule.id).isEmpty())

        posting.postDue(today.plusDays(6))
        posting.postDue(today.plusDays(6))
        assertEquals(listOf(today.plusDays(6)), postedFromRule(rule.id).map { it.date })

        posting.postDue(today.plusDays(20))
        assertEquals(
            listOf(today.plusDays(6), today.plusDays(13), today.plusDays(20)),
            postedFromRule(rule.id).map { it.date }.sorted(),
        )
    }

    @Test
    fun occurrencesAreDatedOnTheirOwnDayNotOnThePostingDay() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val rule = addRule("Gym", account, gym, Money(-1_000), start = tomorrow, frequency = Frequency.WEEKLY)

        posting.postDue(today.plusDays(15))

        assertEquals(
            listOf(tomorrow, today.plusDays(8), today.plusDays(15)),
            postedFromRule(rule.id).map { it.date }.sorted(),
        )
    }

    @Test
    fun aRuleWithAnEarlierPostedThroughCatchesUpWhatIsDue() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val rule = addRule(
            "Gym", account, gym, Money(-1_000),
            start = today.minusDays(21), frequency = Frequency.WEEKLY, postedThrough = today.minusDays(22),
        )

        posting.postDue(today)

        assertEquals(
            listOf(today.minusDays(21), today.minusDays(14), today.minusDays(7), today),
            postedFromRule(rule.id).map { it.date }.sorted(),
        )
        assertEquals(today, ruleNow(rule.id).postedThrough)
    }

    @Test
    fun callingPostDueTwiceNeverDuplicates() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val bills = DataTestSupport.category("Rent & bills")
        val rule = addRule(
            "Gym", account, gym, Money(-1_000),
            start = today.minusDays(21), frequency = Frequency.WEEKLY, postedThrough = today.minusDays(22),
        )
        val item = addItem("Deposit", account, bills, Money(-5_000), date = today)

        posting.postDue(today)
        val afterFirst = transactions().size
        posting.postDue(today)
        posting.postDue(today)

        assertEquals(afterFirst, transactions().size, "later calls add nothing")
        assertEquals(4, postedFromRule(rule.id).size)
        assertEquals(1, postedFromItem(item.id).size)
    }

    @Test
    fun concurrentPostDueCallsNeverDuplicate() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val bills = DataTestSupport.category("Rent & bills")
        val rule = addRule(
            "Gym", account, gym, Money(-1_000),
            start = today.minusDays(21), frequency = Frequency.WEEKLY, postedThrough = today.minusDays(22),
        )
        val item = addItem("Deposit", account, bills, Money(-5_000), date = today)

        // Line every caller up behind a gate so they really do start at the same moment, as the
        // app-start posting and the daily worker can.
        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val callers = (1..8).map { async(Dispatchers.Default) { gate.await(); posting.postDue(today) } }
            gate.complete(Unit)
            callers.awaitAll()
        }

        assertEquals(4, postedFromRule(rule.id).size, "four weekly occurrences, each posted once")
        assertEquals(1, postedFromItem(item.id).size, "the scheduled item is posted once")
        assertEquals(Money(100_000 - 4 * 1_000 - 5_000L), DataTestSupport.balance(account.id))
    }

    @Test
    fun anInactiveRuleIsNeverPosted() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val rule = addRule(
            "Gym", account, gym, Money(-1_000),
            start = tomorrow, frequency = Frequency.WEEKLY, active = false,
        )

        posting.postDue(today.plusDays(30))

        assertTrue(postedFromRule(rule.id).isEmpty())
    }

    @Test
    fun aRuleStopsAtItsEndDate() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val gym = DataTestSupport.category("Subscriptions")
        val rule = addRule(
            "Gym", account, gym, Money(-1_000),
            start = tomorrow, frequency = Frequency.WEEKLY, end = today.plusDays(8),
        )

        posting.postDue(today.plusDays(40))

        assertEquals(listOf(tomorrow, today.plusDays(8)), postedFromRule(rule.id).map { it.date }.sorted())
    }

    @Test
    fun postDueWithNothingToPostDoesNothing() = runBlocking<Unit> {
        createAccount("Current", opening = Money(100_000))
        val before = transactions()

        posting.postDue(today)

        assertEquals(before, transactions())
        assertTrue(posting.pendingConfirmations(today).isEmpty())
    }

    // ---- AUTO scheduled items -----------------------------------------------------------------

    @Test
    fun anAutoScheduledItemDueTodayPostsOnce() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val shopping = DataTestSupport.category("Shopping")
        val item = addItem("Laptop deposit", account, shopping, Money(-25_000), date = today)

        posting.postDue(today)
        posting.postDue(today)

        val posted = postedFromItem(item.id)
        assertEquals(1, posted.size)
        val tx = posted.single()
        assertEquals(Money(-25_000), tx.amount)
        assertEquals(today, tx.date)
        assertEquals(shopping.id, tx.categoryId)
        assertEquals("Laptop deposit", tx.label)
        assertEquals(TxKind.NORMAL, tx.kind)
        assertTrue(itemNow(item.id).posted)
        assertEquals(Money(75_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun anOverdueAutoItemPostsOnItsOwnDateAndFutureOrSettledOnesAreLeftAlone() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(20))
        val shopping = DataTestSupport.category("Shopping")
        val overdue = addItem("Overdue", account, shopping, Money(-1_000), date = today.minusDays(3))
        val future = addItem("Future", account, shopping, Money(-2_000), date = tomorrow)
        val skipped = addItem("Skipped", account, shopping, Money(-3_000), date = today, skipped = true)
        val alreadyPosted = addItem("Already posted", account, shopping, Money(-4_000), date = today, posted = true)

        posting.postDue(today)

        assertEquals(listOf(today.minusDays(3)), postedFromItem(overdue.id).map { it.date })
        assertTrue(postedFromItem(future.id).isEmpty())
        assertTrue(postedFromItem(skipped.id).isEmpty())
        assertTrue(postedFromItem(alreadyPosted.id).isEmpty())
        assertTrue(itemNow(overdue.id).posted)
        assertTrue(!itemNow(future.id).posted)
        assertTrue(!itemNow(skipped.id).posted)
    }

    @Test
    fun aFutureAutoItemPostsOnceItsDayArrives() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000))
        val shopping = DataTestSupport.category("Shopping")
        val item = addItem("Trip", account, shopping, Money(-9_000), date = today.plusDays(5))

        posting.postDue(today)
        assertTrue(postedFromItem(item.id).isEmpty())

        posting.postDue(today.plusDays(5))
        posting.postDue(today.plusDays(6))
        assertEquals(listOf(today.plusDays(5)), postedFromItem(item.id).map { it.date })
    }

    // ---- CONFIRM items ------------------------------------------------------------------------

    @Test
    fun confirmItemsAppearAsPendingAndAreNeverPostedAutomatically() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(20))
        val loan = DataTestSupport.category("Student loan")
        val wages = DataTestSupport.category("Wages")
        val due = addItem("Loan due today", account, loan, Money(100_000), date = today, mode = PostingMode.CONFIRM, major = true)
        val overdue = addItem("Loan overdue", account, loan, Money(50_000), date = today.minusDays(3), mode = PostingMode.CONFIRM)
        val notYet = addItem("Loan next week", account, loan, Money(70_000), date = today.plusDays(7), mode = PostingMode.CONFIRM)
        val rule = addRule("Shifts", account, wages, Money(8_000), start = tomorrow, frequency = Frequency.WEEKLY, mode = PostingMode.CONFIRM)

        val pendingToday = posting.pendingConfirmations(today)
        assertEquals(
            listOf(overdue.id, due.id),
            pendingToday.filterIsInstance<PendingItem.Scheduled>().map { it.item.id },
            "due scheduled items, oldest first; future ones are not pending",
        )
        assertTrue(pendingToday.none { it is PendingItem.Recurring }, "the rule's first occurrence is tomorrow")
        assertTrue(pendingToday.none { it is PendingItem.Scheduled && it.item.id == notYet.id })

        val pendingLater = posting.pendingConfirmations(today.plusDays(8))
        val occurrences = pendingLater.filterIsInstance<PendingItem.Recurring>()
        assertEquals(listOf(tomorrow, today.plusDays(8)), occurrences.map { it.date })
        assertTrue(occurrences.all { it.rule.id == rule.id })

        posting.postDue(today.plusDays(8))
        assertTrue(postedFromRule(rule.id).isEmpty(), "CONFIRM rules are not auto-posted")
        assertTrue(postedFromItem(due.id).isEmpty(), "CONFIRM items are not auto-posted")
        assertTrue(postedFromItem(overdue.id).isEmpty())
    }

    @Test
    fun confirmingARecurringOccurrencePostsTheEditedAmountAndDate() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val wages = DataTestSupport.category("Wages")
        val rule = addRule(
            "Shifts", account, wages, Money(10_000),
            start = today.minusDays(7), frequency = Frequency.WEEKLY, mode = PostingMode.CONFIRM,
            postedThrough = today.minusDays(8),
        )

        val pending = posting.pendingConfirmations(today)
        assertEquals(listOf(today.minusDays(7), today), pending.map { (it as PendingItem.Recurring).date })

        // The first payday arrived a day late and £5 short.
        posting.confirm(pending.first(), Money(9_500), today.minusDays(6))

        val posted = postedFromRule(rule.id)
        assertEquals(1, posted.size)
        assertEquals(Money(9_500), posted.single().amount, "the edited amount, not the rule's £100")
        assertEquals(today.minusDays(6), posted.single().date, "the edited date")
        assertEquals(wages.id, posted.single().categoryId)
        assertEquals(TxKind.NORMAL, posted.single().kind)
        assertEquals(today.minusDays(7), ruleNow(rule.id).postedThrough)
        assertEquals(1, posting.pendingConfirmations(today).size, "only today's occurrence is still pending")

        posting.confirm(posting.pendingConfirmations(today).single(), Money(10_000), today)
        assertEquals(2, postedFromRule(rule.id).size)
        assertTrue(posting.pendingConfirmations(today).isEmpty())
        assertEquals(today, ruleNow(rule.id).postedThrough)
        assertEquals(Money(100_000 + 9_500 + 10_000L), DataTestSupport.balance(account.id))
    }

    @Test
    fun confirmingAScheduledItemPostsTheEditedAmountAndDate() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val loan = DataTestSupport.category("Student loan")
        val item = addItem("Student loan 1", account, loan, Money(100_000), date = today, mode = PostingMode.CONFIRM, major = true)

        val pending = posting.pendingConfirmations(today).single()
        assertTrue(pending is PendingItem.Scheduled)
        posting.confirm(pending, Money(99_500), today.minusDays(1))

        val posted = postedFromItem(item.id)
        assertEquals(1, posted.size)
        assertEquals(Money(99_500), posted.single().amount)
        assertEquals(today.minusDays(1), posted.single().date)
        assertEquals(loan.id, posted.single().categoryId)
        assertTrue(itemNow(item.id).posted)
        assertTrue(!itemNow(item.id).skipped)
        assertTrue(posting.pendingConfirmations(today).isEmpty())
        assertEquals(Money(199_500), DataTestSupport.balance(account.id))
    }

    @Test
    fun skippingARecurringOccurrenceRemovesItWithoutPosting() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val wages = DataTestSupport.category("Wages")
        val rule = addRule(
            "Shifts", account, wages, Money(10_000),
            start = today.minusDays(7), frequency = Frequency.WEEKLY, mode = PostingMode.CONFIRM,
            postedThrough = today.minusDays(8),
        )
        val pending = posting.pendingConfirmations(today)
        assertEquals(2, pending.size)

        posting.skip(pending.first())

        assertTrue(postedFromRule(rule.id).isEmpty(), "skipping posts nothing")
        assertEquals(today.minusDays(7), ruleNow(rule.id).postedThrough)
        val remaining = posting.pendingConfirmations(today)
        assertEquals(listOf(today), remaining.map { (it as PendingItem.Recurring).date }, "the later occurrence is untouched")
        assertEquals(Money(100_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun skippingAScheduledItemMarksItSkippedWithoutPosting() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val loan = DataTestSupport.category("Student loan")
        val item = addItem("Student loan 2", account, loan, Money(100_000), date = today, mode = PostingMode.CONFIRM, major = true)

        posting.skip(posting.pendingConfirmations(today).single())

        assertTrue(postedFromItem(item.id).isEmpty())
        assertTrue(itemNow(item.id).skipped)
        assertTrue(!itemNow(item.id).posted)
        assertTrue(posting.pendingConfirmations(today).isEmpty())
        assertEquals(Money(100_000), DataTestSupport.balance(account.id))
    }

    @Test
    fun aSkippedItemIsNeverPostedByPostDue() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val loan = DataTestSupport.category("Student loan")
        val item = addItem("Loan", account, loan, Money(100_000), date = today, mode = PostingMode.CONFIRM, major = true)
        posting.skip(posting.pendingConfirmations(today).single())

        posting.postDue(today)
        posting.postDue(today.plusDays(3))

        assertTrue(postedFromItem(item.id).isEmpty())
    }

    // ---- transfers ----------------------------------------------------------------------------

    @Test
    fun anAutoTransferRulePostsTwoLegsOnce() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(10_000))
        addRule(
            "Top up savings", current, null, Money(5_000),
            start = tomorrow, frequency = Frequency.MONTHLY, toAccountId = savings.id,
        )

        posting.postDue(tomorrow)
        posting.postDue(tomorrow)

        assertOneTransferOf(transferLegs(), current, savings, 5_000, tomorrow)
        assertEquals(Money(95_000), DataTestSupport.balanceAsOf(current.id, tomorrow))
        assertEquals(Money(15_000), DataTestSupport.balanceAsOf(savings.id, tomorrow))
    }

    @Test
    fun anAutoTransferScheduledItemPostsTwoLegsOnce() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(10_000))
        val item = addItem("Move to savings", current, null, Money(20_000), date = today, toAccountId = savings.id)

        posting.postDue(today)
        posting.postDue(today)

        assertOneTransferOf(transferLegs(), current, savings, 20_000, today)
        assertTrue(itemNow(item.id).posted)
        assertEquals(Money(80_000), DataTestSupport.balance(current.id))
        assertEquals(Money(30_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun confirmingATransferPostsTwoLegsWithTheEditedAmount() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000), date = today.minusDays(5))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(0), date = today.minusDays(5))
        addItem("Move to savings", current, null, Money(20_000), date = today, mode = PostingMode.CONFIRM, toAccountId = savings.id)

        posting.confirm(posting.pendingConfirmations(today).single(), Money(12_500), today)

        assertOneTransferOf(transferLegs(), current, savings, 12_500, today)
        assertTrue(posting.pendingConfirmations(today).isEmpty())
    }
}
