package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import io.github.matteopi.budgeting.work.DailyWorker
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** [DailyWorker], run directly (not through WorkManager's scheduler) against the app's real container. */
@RunWith(AndroidJUnit4::class)
class DailyWorkerTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder.from(DataTestSupport.app, DailyWorker::class.java).build()
        return worker.doWork()
    }

    private suspend fun assertWorkerSucceeds() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    private suspend fun lastSnapshotDate(): LocalDate? = container.settingsStore.appSettings.first().lastSnapshotDate

    // ---- an empty app --------------------------------------------------------------------------

    @Test
    fun succeedsOnAnEmptyApp() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()

        assertWorkerSucceeds()

        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())
        assertTrue(container.snapshotRepository.allCategorySnapshots().isEmpty())
        assertNull(lastSnapshotDate(), "with nothing to forecast the day is not marked as snapshotted")
        assertTrue(transactions().isEmpty())
    }

    @Test
    fun succeedsWhenRunRepeatedlyOnAnEmptyApp() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()

        assertWorkerSucceeds()
        assertWorkerSucceeds()
        assertWorkerSucceeds()

        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())
    }

    // ---- accuracy snapshots --------------------------------------------------------------------

    @Test
    fun withAccountsItStoresAccuracySnapshots() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        DataTestSupport.seedThreeAccountBudget()

        assertWorkerSucceeds()

        val balances = container.snapshotRepository.allBalanceSnapshots()
        assertEquals(6, balances.size, "+7, +14 and +28 days, each for tier 1 and the full model")
        assertTrue(balances.all { it.createdOn == today })
        assertEquals(setOf(today.plusDays(7), today.plusDays(14), today.plusDays(28)), balances.map { it.targetDate }.toSet())
        assertEquals(setOf(1, 3), balances.map { it.tier }.toSet())
        assertTrue(
            balances.filter { it.tier == 1 }.all { it.p10 == null && it.p90 == null },
            "tier 1 has no uncertainty band",
        )

        val categories = container.snapshotRepository.allCategorySnapshots()
        assertTrue(categories.isNotEmpty(), "next week's per-category predictions are stored too")
        assertTrue(categories.all { it.createdOn == today })
        assertTrue(categories.all { it.tier == 1 || it.tier == 3 })

        assertEquals(today, lastSnapshotDate())
    }

    @Test
    fun runningTwiceTheSameDayDoesNotDuplicateSnapshots() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        DataTestSupport.seedThreeAccountBudget()

        assertWorkerSucceeds()
        val balancesAfterFirst = container.snapshotRepository.allBalanceSnapshots()
        val categoriesAfterFirst = container.snapshotRepository.allCategorySnapshots()

        assertWorkerSucceeds()
        assertWorkerSucceeds()

        assertEquals(balancesAfterFirst, container.snapshotRepository.allBalanceSnapshots())
        assertEquals(categoriesAfterFirst, container.snapshotRepository.allCategorySnapshots())
        assertEquals(today, lastSnapshotDate())
    }

    @Test
    fun aDayAlreadySnapshottedIsNotSnapshottedAgain() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        DataTestSupport.seedThreeAccountBudget()
        container.settingsStore.setLastSnapshotDate(today)

        assertWorkerSucceeds()

        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())
        assertTrue(container.snapshotRepository.allCategorySnapshots().isEmpty())
    }

    @Test
    fun aNewDayGetsANewSnapshot() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        DataTestSupport.seedThreeAccountBudget()
        container.settingsStore.setLastSnapshotDate(today.minusDays(1))

        assertWorkerSucceeds()

        assertEquals(6, container.snapshotRepository.allBalanceSnapshots().size)
        assertEquals(today, lastSnapshotDate())
    }

    @Test
    fun noSnapshotsAreStoredWhenNoAccountIsInThePool() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        createAccount("Cash", AccountType.CASH, opening = Money(2_000), includeInPool = false)

        assertWorkerSucceeds()

        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())
        assertTrue(container.snapshotRepository.allCategorySnapshots().isEmpty())
        assertNull(lastSnapshotDate())
    }

    @Test
    fun noSnapshotsAreStoredWhenTheOnlyPoolAccountIsArchived() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        val current = createAccount("Current", opening = Money(50_000))
        container.accountRepository.archive(current.id)

        assertWorkerSucceeds()

        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())
        assertNull(lastSnapshotDate())
    }

    @Test
    fun snapshotsStartOnceTheFirstPoolAccountExists() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        assertWorkerSucceeds()
        assertTrue(container.snapshotRepository.allBalanceSnapshots().isEmpty())

        createAccount("Current", opening = Money(50_000))
        assertWorkerSucceeds()

        assertEquals(6, container.snapshotRepository.allBalanceSnapshots().size)
        assertEquals(today, lastSnapshotDate())
    }

    // ---- posting -------------------------------------------------------------------------------

    @Test
    fun itPostsDueAutoItemsAndLeavesConfirmItemsAlone() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val rent = DataTestSupport.category("Rent & bills")
        val loan = DataTestSupport.category("Student loan")
        val gymRuleId = container.planRepository.addRecurringRule(
            RecurringRule(
                label = "Gym", accountId = account.id, categoryId = rent.id, amount = Money(-1_500),
                frequency = Frequency.WEEKLY, startDate = today.minusDays(14), postingMode = PostingMode.AUTO,
                postedThrough = today.minusDays(15),
            ),
        )
        val autoItemId = container.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Deposit", accountId = account.id, categoryId = rent.id, amount = Money(-20_000),
                date = today, postingMode = PostingMode.AUTO,
            ),
        )
        val confirmItemId = container.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Student loan 1", accountId = account.id, categoryId = loan.id, amount = Money(100_000),
                date = today, postingMode = PostingMode.CONFIRM, isMajorIncome = true,
            ),
        )

        assertWorkerSucceeds()

        val ledger = transactions()
        assertEquals(
            listOf(today.minusDays(14), today.minusDays(7), today),
            ledger.filter { it.recurringRuleId == gymRuleId }.map { it.date }.sorted(),
            "three weekly gym payments are due",
        )
        assertEquals(1, ledger.count { it.scheduledItemId == autoItemId })
        assertEquals(0, ledger.count { it.scheduledItemId == confirmItemId }, "CONFIRM items wait for the user")
        val items = container.planRepository.observeScheduledItems().first()
        assertTrue(items.single { it.id == autoItemId }.posted)
        assertTrue(!items.single { it.id == confirmItemId }.posted)
        assertEquals(Money(100_000 - 3 * 1_500 - 20_000L), DataTestSupport.balance(account.id))
    }

    @Test
    fun runningTwiceNeverPostsAnythingTwice() = runBlocking<Unit> {
        val account = createAccount("Current", opening = Money(100_000), date = today.minusDays(30))
        val bills = DataTestSupport.category("Rent & bills")
        container.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Deposit", accountId = account.id, categoryId = bills.id, amount = Money(-20_000),
                date = today, postingMode = PostingMode.AUTO,
            ),
        )
        container.planRepository.addRecurringRule(
            RecurringRule(
                label = "Gym", accountId = account.id, categoryId = bills.id, amount = Money(-1_500),
                frequency = Frequency.WEEKLY, startDate = today.minusDays(7), postingMode = PostingMode.AUTO,
                postedThrough = today.minusDays(8),
            ),
        )

        assertWorkerSucceeds()
        val afterFirst = transactions().size
        assertWorkerSucceeds()
        assertWorkerSucceeds()

        assertEquals(afterFirst, transactions().size)
        assertEquals(Money(100_000 - 2 * 1_500 - 20_000L), DataTestSupport.balance(account.id))
    }

    // ---- the reminder ---------------------------------------------------------------------------

    @Test
    fun aDueReminderDoesNotBreakTheRun() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        createAccount("Current", opening = Money(50_000))
        container.settingsStore.setReconcileReminderEnabled(true)
        container.settingsStore.setReconcileReminderDay(today.dayOfWeek)

        // Due today (no balance update has ever been made). Whether or not the notification
        // permission is granted, the worker must finish normally.
        assertWorkerSucceeds()

        assertEquals(today, lastSnapshotDate())
    }

    @Test
    fun aReminderForAnotherDayDoesNotBreakTheRun() = runBlocking<Unit> {
        DataTestSupport.awaitCategories()
        createAccount("Current", opening = Money(50_000))
        container.settingsStore.setReconcileReminderEnabled(true)
        container.settingsStore.setReconcileReminderDay(today.dayOfWeek.plus(3))

        assertWorkerSucceeds()
    }
}
