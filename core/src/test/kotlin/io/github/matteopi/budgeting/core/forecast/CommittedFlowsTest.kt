package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommittedFlowsTest {
    private val today = LocalDate.of(2024, 6, 3) // Monday

    @Test
    fun transferBetweenTwoPoolAccountsHasNoPoolEffect() {
        val rule = RecurringRule(
            id = 1, label = "To savings", accountId = TestData.CURRENT, categoryId = null,
            amount = Money.pounds(50), frequency = Frequency.WEEKLY, startDate = today.plusDays(1),
            postingMode = PostingMode.AUTO, toAccountId = TestData.SAVINGS,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        assertTrue(CommittedFlows.build(input, today.plusDays(30)).isEmpty())
    }

    @Test
    fun transferOutOfThePoolIsANegativeFlow() {
        val rule = RecurringRule(
            id = 1, label = "To outside", accountId = TestData.CURRENT, categoryId = null,
            amount = Money.pounds(50), frequency = Frequency.WEEKLY, startDate = today.plusDays(1),
            postingMode = PostingMode.AUTO, toAccountId = TestData.OUTSIDE_POOL,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        val flows = CommittedFlows.build(input, today.plusDays(1))
        assertEquals(listOf(Money.pounds(-50)), flows.map { it.amount })
    }

    @Test
    fun transferIntoThePoolIsAPositiveFlow() {
        val rule = RecurringRule(
            id = 1, label = "From outside", accountId = TestData.OUTSIDE_POOL, categoryId = null,
            amount = Money.pounds(50), frequency = Frequency.WEEKLY, startDate = today.plusDays(1),
            postingMode = PostingMode.AUTO, toAccountId = TestData.CURRENT,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        val flows = CommittedFlows.build(input, today.plusDays(1))
        assertEquals(listOf(Money.pounds(50)), flows.map { it.amount })
    }

    @Test
    fun transferBetweenTwoAccountsOutsideThePoolIsSkipped() {
        val accounts = TestData.accounts() + Account(
            id = 4, name = "Outside2", type = AccountType.CASH, includeInSafeToSpend = false,
        )
        val rule = RecurringRule(
            id = 1, label = "Outside to outside", accountId = TestData.OUTSIDE_POOL, categoryId = null,
            amount = Money.pounds(50), frequency = Frequency.WEEKLY, startDate = today.plusDays(1),
            postingMode = PostingMode.AUTO, toAccountId = 4,
        )
        val input = TestData.input(today, accounts = accounts, recurringRules = listOf(rule))
        assertTrue(CommittedFlows.build(input, today.plusDays(1)).isEmpty())
    }

    @Test
    fun overdueAutoRecurringMovesToTomorrowWithRecurringSource() {
        val rule = RecurringRule(
            id = 1, label = "Rent", accountId = TestData.CURRENT, categoryId = TestData.RENT,
            amount = Money.pounds(-400), frequency = Frequency.MONTHLY, startDate = today.minusDays(40),
            postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        val overdue = CommittedFlows.build(input, today.plusDays(60)).first()
        assertEquals(today.plusDays(1), overdue.date)
        assertEquals(FlowSource.RECURRING, overdue.source)
    }

    @Test
    fun overdueConfirmRecurringMovesToTomorrowWithOverdueConfirmSource() {
        val rule = RecurringRule(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(1500), frequency = Frequency.MONTHLY, startDate = today.minusDays(2),
            postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        val overdue = CommittedFlows.build(input, today.plusDays(10)).single()
        assertEquals(today.plusDays(1), overdue.date)
        assertEquals(FlowSource.OVERDUE_CONFIRM, overdue.source)
        assertTrue(overdue.isMajorIncome)
    }

    @Test
    fun futureRecurringOccurrenceKeepsItsOwnDateWithRecurringSource() {
        val rule = RecurringRule(
            id = 1, label = "Student loan", accountId = TestData.CURRENT, categoryId = TestData.STUDENT_LOAN,
            amount = Money.pounds(1500), frequency = Frequency.MONTHLY, startDate = today.plusDays(30),
            postingMode = PostingMode.CONFIRM, isMajorIncome = true,
        )
        val input = TestData.input(today, recurringRules = listOf(rule))
        val flow = CommittedFlows.build(input, today.plusDays(60)).single()
        assertEquals(today.plusDays(30), flow.date)
        assertEquals(FlowSource.RECURRING, flow.source)
    }

    @Test
    fun overdueScheduledItemMovesToTomorrow() {
        val item = ScheduledItem(
            id = 1, label = "One-off", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES,
            amount = Money.pounds(-30), date = today.minusDays(3), postingMode = PostingMode.AUTO,
        )
        val input = TestData.input(today, scheduledItems = listOf(item))
        val flow = CommittedFlows.build(input, today.plusDays(10)).single()
        assertEquals(today.plusDays(1), flow.date)
        assertEquals(FlowSource.SCHEDULED, flow.source)
    }

    @Test
    fun postedOrSkippedScheduledItemsAreExcluded() {
        val posted = ScheduledItem(
            id = 1, label = "A", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES,
            amount = Money.pounds(-10), date = today.plusDays(2), postingMode = PostingMode.AUTO, posted = true,
        )
        val skipped = ScheduledItem(
            id = 2, label = "B", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES,
            amount = Money.pounds(-10), date = today.plusDays(2), postingMode = PostingMode.AUTO, skipped = true,
        )
        val input = TestData.input(today, scheduledItems = listOf(posted, skipped))
        assertTrue(CommittedFlows.build(input, today.plusDays(10)).isEmpty())
    }

    @Test
    fun sinkingFundAddsANegativeFlowOnTheTargetDate() {
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(600), startDate = today.minusDays(30), targetDate = today.plusDays(60))
        val input = TestData.input(today, sinkingFunds = listOf(fund))
        val flow = CommittedFlows.build(input, today.plusDays(90)).single()
        assertEquals(today.plusDays(60) to Money.pounds(-600), flow.date to flow.amount)
        assertEquals(FlowSource.SINKING_FUND, flow.source)
    }

    @Test
    fun sinkingFundWithATargetDateNotAfterTodayIsNotIncluded() {
        val fund = SinkingFund(id = 1, name = "Trip", target = Money.pounds(600), startDate = today.minusDays(90), targetDate = today.minusDays(1))
        val input = TestData.input(today, sinkingFunds = listOf(fund))
        assertTrue(CommittedFlows.build(input, today.plusDays(90)).isEmpty())
    }

    @Test
    fun scenarioOneOffAndRecurringItemsAreIncluded() {
        val scenario = Scenario(
            id = 1, name = "What if",
            items = listOf(
                ScenarioItem(id = 1, type = ScenarioItemType.ONE_OFF, label = "Festival", amount = Money.pounds(-120), date = today.plusDays(10)),
                ScenarioItem(id = 2, type = ScenarioItemType.RECURRING, label = "New sub", amount = Money.pounds(-10), date = today.plusDays(1), frequency = Frequency.WEEKLY, interval = 1),
            ),
        )
        val input = TestData.input(today, scenario = scenario)
        val flows = CommittedFlows.build(input, today.plusDays(15))
        assertTrue(flows.any { it.label == "Festival" && it.amount == Money.pounds(-120) && it.source == FlowSource.SCENARIO })
        assertTrue(flows.count { it.label == "New sub" } >= 2)
    }

    @Test
    fun scenarioOneOffInThePastIsExcluded() {
        val scenario = Scenario(
            id = 1, name = "What if",
            items = listOf(ScenarioItem(id = 1, type = ScenarioItemType.ONE_OFF, label = "Past", amount = Money.pounds(-10), date = today)),
        )
        val input = TestData.input(today, scenario = scenario)
        assertTrue(CommittedFlows.build(input, today.plusDays(15)).isEmpty())
    }

    @Test
    fun flowsAreSortedByDateThenLabel() {
        val itemA = ScheduledItem(id = 1, label = "Zoo", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES, amount = Money.pounds(-5), date = today.plusDays(5), postingMode = PostingMode.AUTO)
        val itemB = ScheduledItem(id = 2, label = "Apple", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES, amount = Money.pounds(-5), date = today.plusDays(5), postingMode = PostingMode.AUTO)
        val input = TestData.input(today, scheduledItems = listOf(itemA, itemB))
        val flows = CommittedFlows.build(input, today.plusDays(10))
        assertEquals(listOf("Apple", "Zoo"), flows.map { it.label })
    }

    @Test
    fun blankLabelFallsBackToTheCategoryName() {
        val item = ScheduledItem(id = 1, label = "", accountId = TestData.CURRENT, categoryId = TestData.GROCERIES, amount = Money.pounds(-5), date = today.plusDays(2), postingMode = PostingMode.AUTO)
        val input = TestData.input(today, scheduledItems = listOf(item))
        assertEquals("Groceries", CommittedFlows.build(input, today.plusDays(10)).single().label)
    }
}
