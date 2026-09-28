package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the stress test against going vacuous: over the default seed range the generator must
 * really produce the realistic and the awkward shapes the properties are meant to cover.
 */
class InputGeneratorTest {

    private val inputs: List<ForecastInput> by lazy { (1L..2000L).map { InputGenerator.generate(it).input } }

    private fun count(predicate: (ForecastInput) -> Boolean): Int = inputs.count(predicate)

    private fun assertCovered(name: String, minimum: Int, predicate: (ForecastInput) -> Boolean) {
        val seen = count(predicate)
        assertTrue(seen >= minimum, "the generator produced only $seen input(s) with '$name' (want at least $minimum) in 2000 seeds")
    }

    @Test
    fun sameSeedGivesTheSameInputAndDifferentSeedsDiffer() {
        assertEquals(InputGenerator.generate(99).input, InputGenerator.generate(99).input)
        assertNotEquals(InputGenerator.generate(99).input, InputGenerator.generate(100).input)
        assertEquals(InputGenerator.generate(7).summary, InputGenerator.generate(7).summary)
    }

    @Test
    fun coversAccountShapes() {
        for (n in 1..5) assertCovered("$n accounts", 40) { it.accounts.size == n }
        assertCovered("an archived account", 200) { i -> i.accounts.any { it.archived } }
        assertCovered("a non-pool account", 300) { i -> i.accounts.any { !it.includeInSafeToSpend } }
        assertCovered("an overdraft", 500) { i -> i.accounts.any { it.overdraftLimit.isPositive } }
        assertCovered("no usable pool account", 20) { i -> i.accounts.none { it.includeInSafeToSpend && !it.archived } }
        assertCovered("a negative balance today", 100) { i ->
            i.accounts.any { a -> i.transactions.filter { it.accountId == a.id && !it.date.isAfter(i.today) }.sumOf { it.amount.pence } < 0 }
        }
        assertCovered("an overdraft above 1500 pounds", 50) { i -> i.accounts.any { it.overdraftLimit.pence > 150_000 } }
        assertCovered("a default account that is missing, archived or outside the pool", 200) { i ->
            val id = i.settings.defaultAccountId
            id != null && i.accounts.none { it.id == id && it.includeInSafeToSpend && !it.archived }
        }
    }

    @Test
    fun coversHistoryAndTransactionKinds() {
        assertCovered("no history at all", 50) { it.transactions.none { t -> t.kind == TxKind.NORMAL && !t.date.isAfter(it.today) } }
        assertCovered("about a year of history", 100) { i ->
            val first = i.transactions.filter { it.kind == TxKind.NORMAL }.minOfOrNull { it.date }
            first != null && ChronoUnit.WEEKS.between(first, i.today) >= 50
        }
        assertCovered("transfer legs", 300) { i -> i.transactions.any { it.kind == TxKind.TRANSFER } }
        assertCovered("a transfer onto the same account", 40) { i ->
            i.transactions.filter { it.kind == TxKind.TRANSFER }.groupBy { it.transferGroupId }.values.any { legs -> legs.size == 2 && legs[0].accountId == legs[1].accountId }
        }
        assertCovered("a transfer with one leg missing", 20) { i ->
            i.transactions.filter { it.kind == TxKind.TRANSFER }.groupBy { it.transferGroupId }.values.any { it.size == 1 }
        }
        assertCovered("a reconciliation with its record", 100) { i ->
            i.reconciliations.any { r -> i.transactions.any { it.id == r.transactionId && it.kind == TxKind.RECONCILIATION } }
        }
        assertCovered("a reconciliation without a record", 100) { i ->
            i.transactions.any { t -> t.kind == TxKind.RECONCILIATION && i.reconciliations.none { it.transactionId == t.id } }
        }
        assertCovered("a record whose period starts after its date", 10) { i -> i.reconciliations.any { it.periodStart.isAfter(it.date) } }
        assertCovered("an opening balance", 1000) { i -> i.transactions.any { it.kind == TxKind.OPENING_BALANCE } }
        assertCovered("a future-dated transaction", 200) { i -> i.transactions.any { it.date.isAfter(i.today) } }
        assertCovered("a transaction posted from a rule", 200) { i -> i.transactions.any { it.recurringRuleId != null } }
        assertCovered("a transaction with no or an unknown category", 200) { i ->
            i.transactions.any { t -> t.kind == TxKind.NORMAL && (t.categoryId == null || i.categories.none { it.id == t.categoryId }) }
        }
        assertCovered("a zero-amount transaction", 50) { i -> i.transactions.any { it.amount.isZero && it.kind == TxKind.NORMAL } }
    }

    @Test
    fun coversRecurringRules() {
        for (frequency in Frequency.entries) assertCovered("a $frequency rule", 300) { i -> i.recurringRules.any { it.frequency == frequency } }
        for (interval in 0..4) assertCovered("a rule with interval $interval", 200) { i -> i.recurringRules.any { it.interval == interval } }
        assertCovered("a rule starting before 1980", 100) { i -> i.recurringRules.any { it.startDate.isBefore(LocalDate.of(1980, 1, 1)) } }
        assertCovered("a rule starting next year", 100) { i -> i.recurringRules.any { it.startDate.year == i.today.year + 1 } }
        assertCovered("a rule ending before it starts", 60) { i -> i.recurringRules.any { it.endDate != null && it.endDate.isBefore(it.startDate) } }
        assertCovered("an AUTO rule", 300) { i -> i.recurringRules.any { it.postingMode == PostingMode.AUTO } }
        assertCovered("a CONFIRM rule", 300) { i -> i.recurringRules.any { it.postingMode == PostingMode.CONFIRM } }
        assertCovered("a transfer rule", 100) { i -> i.recurringRules.any { it.toAccountId != null } }
        assertCovered("a transfer onto the same account", 15) { i -> i.recurringRules.any { it.toAccountId != null && it.toAccountId == it.accountId } }
        assertCovered("a major income rule", 100) { i -> i.recurringRules.any { it.isMajorIncome } }
        assertCovered("a zero-amount rule", 30) { i -> i.recurringRules.any { it.amount.isZero } }
        assertCovered("an inactive rule", 30) { i -> i.recurringRules.any { !it.active } }
        assertCovered("a rule already posted through some date", 300) { i -> i.recurringRules.any { it.postedThrough != null } }
    }

    @Test
    fun coversScheduledItemsTermsPredictionsAndFunds() {
        assertCovered("a past scheduled item", 200) { i -> i.scheduledItems.any { it.date.isBefore(i.today) } }
        assertCovered("a scheduled item today", 60) { i -> i.scheduledItems.any { it.date == i.today } }
        assertCovered("a future scheduled item", 500) { i -> i.scheduledItems.any { it.date.isAfter(i.today) } }
        assertCovered("a scheduled item beyond the horizon", 50) { i ->
            i.scheduledItems.any { it.date.isAfter(i.today.plusDays(i.settings.forecastHorizonDays.toLong())) }
        }
        assertCovered("a posted scheduled item", 100) { i -> i.scheduledItems.any { it.posted } }
        assertCovered("a skipped scheduled item", 100) { i -> i.scheduledItems.any { it.skipped } }
        assertCovered("a scheduled transfer", 60) { i -> i.scheduledItems.any { it.toAccountId != null } }

        for (n in 0..4) assertCovered("$n terms", 20) { it.termPeriods.size == n }
        assertCovered("overlapping terms", 20) { i ->
            i.termPeriods.any { a -> i.termPeriods.any { b -> a !== b && !a.endDate.isBefore(a.startDate) && !b.endDate.isBefore(b.startDate) && !a.startDate.isAfter(b.endDate) && !b.startDate.isAfter(a.endDate) } }
        }
        assertCovered("a term ending before it starts", 60) { i -> i.termPeriods.any { it.endDate.isBefore(it.startDate) } }
        assertCovered("a term far in the future", 40) { i -> i.termPeriods.any { it.startDate.isAfter(i.today.plusYears(2)) } }

        for (mode in PredictionMode.entries) assertCovered("a $mode prediction", 200) { i -> i.predictions.any { it.mode == mode } }
        assertCovered("an adjust of -100 percent", 60) { i -> i.predictions.any { it.mode == PredictionMode.ADJUST_PERCENT && it.adjustPercent == -100 } }
        assertCovered("an adjust of +500 percent", 60) { i -> i.predictions.any { it.mode == PredictionMode.ADJUST_PERCENT && it.adjustPercent == 500 } }

        assertCovered("a fund starting after its target", 20) { i -> i.sinkingFunds.any { it.startDate.isAfter(it.targetDate) } }
        assertCovered("a fund whose target is in the past", 20) { i -> i.sinkingFunds.any { it.targetDate.isBefore(i.today) } }
        assertCovered("a fund with a zero target", 15) { i -> i.sinkingFunds.any { it.target.isZero } }
        assertCovered("a fund starting on its target", 15) { i -> i.sinkingFunds.any { it.startDate == it.targetDate } }
        for (status in SinkingFundStatus.entries) assertCovered("a $status fund", 20) { i -> i.sinkingFunds.any { it.status == status } }
    }

    @Test
    fun coversSettingsAndScenarios() {
        for (zero in ZeroPoint.entries) assertCovered("zero point $zero", 800) { it.settings.zeroPoint == zero }
        assertCovered("a zero buffer or close to it", 10) { it.settings.buffer.pence < 1000 }
        assertCovered("a buffer above 900 pounds", 100) { it.settings.buffer.pence > 90_000 }
        assertCovered("50 simulations or close", 50) { it.settings.simulations < 60 }
        assertCovered("300 simulations or close", 50) { it.settings.simulations > 290 }
        assertCovered("the shortest horizon", 50) { it.settings.forecastHorizonDays == 14 }
        assertCovered("the longest horizon", 50) { it.settings.forecastHorizonDays == 365 }
        assertCovered("a history start date", 500) { it.settings.historyStartDate != null }
        assertCovered("today on a Monday", 100) { it.today.dayOfWeek.value == 1 }
        assertCovered("today on a month end", 100) { it.today.plusDays(1).dayOfMonth == 1 }
        assertCovered("no scenario", 800) { it.scenario == null }
        for (type in ScenarioItemType.entries) assertCovered("a $type scenario item", 100) { i -> i.scenario?.items?.any { it.type == type } == true }
        assertCovered("a scenario percent of -100", 30) { i -> i.scenario?.items?.any { it.type == ScenarioItemType.CATEGORY_ADJUST && it.percent == -100 } == true }
        assertCovered("a scenario percent of +500", 30) { i -> i.scenario?.items?.any { it.type == ScenarioItemType.CATEGORY_ADJUST && it.percent == 500 } == true }
    }
}
