package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import java.time.LocalDate

/**
 * One committed flow's effect on one specific account (a transfer gives one per leg). Used by
 * [AccountSplit]; dated exactly like the pool's [CommittedFlow] (overdue items on tomorrow).
 */
internal data class AccountFlow(val date: LocalDate, val accountId: Long, val amount: Money)

/** The pool flows plus, built from the same sources, the per-account effects. */
internal class CommittedFlowSet(val flows: List<CommittedFlow>, val accountFlows: List<AccountFlow>)

/** Builds the list of every known future flow in the horizon. See `docs/PLAN.md` §5.5. */
object CommittedFlows {

    fun build(input: ForecastInput, forecastEnd: LocalDate): List<CommittedFlow> =
        buildWithAccountFlows(input, forecastEnd).flows

    /**
     * The pool flows of [build] and, alongside, every flow's effect per account for the
     * per-account forecast (`docs/PLAN.md` §5.11a): a non-transfer hits its own account, a
     * transfer A->B is `-amount` on A and `+amount` on B whatever the pool membership, and
     * sinking-fund targets and scenario items without an account go to the default account.
     * Only non-archived accounts are covered.
     */
    internal fun buildWithAccountFlows(input: ForecastInput, forecastEnd: LocalDate): CommittedFlowSet {
        val today = input.today
        val poolIds = Balances.pool(input.accounts).map { it.id }.toSet()
        val coveredIds = input.accounts.filter { !it.archived }.map { it.id }.toSet()
        val defaultAccountId = AccountSplit.defaultAccountId(input.accounts, input.settings)
        val categoryById = input.categories.associateBy { it.id }
        val flows = mutableListOf<CommittedFlow>()
        val accountFlows = mutableListOf<AccountFlow>()

        fun addAccountFlow(date: LocalDate, accountId: Long?, amount: Money) {
            if (accountId != null && accountId in coveredIds) accountFlows += AccountFlow(date, accountId, amount)
        }
        // A rule/item stores one record for a whole transfer: -amount leaves A, +amount reaches B.
        fun addAccountFlows(date: LocalDate, accountId: Long, toAccountId: Long?, amount: Money) {
            if (toAccountId == null) {
                addAccountFlow(date, accountId, amount)
            } else {
                addAccountFlow(date, accountId, -amount)
                addAccountFlow(date, toAccountId, amount)
            }
        }

        for (rule in input.recurringRules) {
            if (!rule.active) continue
            val from = rule.postedThrough?.plusDays(1) ?: rule.startDate
            if (from.isAfter(forecastEnd)) continue
            val occurrences = Recurrence.occurrences(rule, from, forecastEnd)
            for (occurrence in occurrences) {
                val overdue = !occurrence.isAfter(today)
                val date = if (overdue) today.plusDays(1) else occurrence
                addAccountFlows(date, rule.accountId, rule.toAccountId, rule.amount)
                val effect = poolEffect(rule.accountId, rule.toAccountId, rule.amount, poolIds) ?: continue
                val source = if (overdue && rule.postingMode == PostingMode.CONFIRM) {
                    FlowSource.OVERDUE_CONFIRM
                } else {
                    FlowSource.RECURRING
                }
                flows += flow(date, effect, label(rule.label, rule.categoryId, categoryById), source, rule.id, rule.categoryId, rule.accountId, rule.isMajorIncome)
            }
        }

        for (item in input.scheduledItems) {
            if (item.posted || item.skipped || item.date.isAfter(forecastEnd)) continue
            val overdue = !item.date.isAfter(today)
            val date = if (overdue) today.plusDays(1) else item.date
            addAccountFlows(date, item.accountId, item.toAccountId, item.amount)
            val effect = poolEffect(item.accountId, item.toAccountId, item.amount, poolIds) ?: continue
            val source = if (overdue && item.postingMode == PostingMode.CONFIRM) {
                FlowSource.OVERDUE_CONFIRM
            } else {
                FlowSource.SCHEDULED
            }
            flows += flow(date, effect, label(item.label, item.categoryId, categoryById), source, item.id, item.categoryId, item.accountId, item.isMajorIncome)
        }

        // Defensive: the UI shouldn't allow future-dated transactions, but if one exists it is
        // a known future flow. Each leg of a transfer is already an independent, correctly
        // signed row, so no pool-effect combination is needed here (unlike rules/items, which
        // store one record for the whole transfer).
        for (tx in input.transactions) {
            if (!tx.date.isAfter(today) || tx.date.isAfter(forecastEnd)) continue
            addAccountFlow(tx.date, tx.accountId, tx.amount)
            if (tx.accountId !in poolIds) continue
            val label = label(tx.label, tx.categoryId, categoryById)
            flows += CommittedFlow(tx.date, tx.amount, label, FlowSource.TRANSACTION, tx.id, tx.categoryId, tx.accountId, isMajorIncome = false)
        }

        for (fund in input.sinkingFunds) {
            if (fund.status != SinkingFundStatus.ACTIVE) continue
            if (fund.targetDate.isAfter(today) && !fund.targetDate.isAfter(forecastEnd)) {
                flows += CommittedFlow(fund.targetDate, -fund.target, fund.name, FlowSource.SINKING_FUND, fund.id, fund.categoryId, null, isMajorIncome = false)
                addAccountFlow(fund.targetDate, defaultAccountId, -fund.target)
            }
        }

        val scenario = input.scenario
        if (scenario != null) {
            for (item in scenario.items) {
                when (item.type) {
                    ScenarioItemType.ONE_OFF -> {
                        val date = item.date ?: continue
                        if (!date.isAfter(today) || date.isAfter(forecastEnd)) continue
                        addAccountFlow(date, item.accountId ?: defaultAccountId, item.amount)
                        val effect = scenarioPoolEffect(item.accountId, item.amount, poolIds) ?: continue
                        flows += flow(date, effect, label(item.label, item.categoryId, categoryById), FlowSource.SCENARIO, item.id, item.categoryId, item.accountId, isMajorIncome = false)
                    }
                    ScenarioItemType.RECURRING -> {
                        val start = item.date ?: continue
                        val frequency = item.frequency ?: continue
                        val from = if (start.isAfter(today)) start else today.plusDays(1)
                        if (from.isAfter(forecastEnd)) continue
                        val occurrences = Recurrence.occurrences(frequency, item.interval, start, item.endDate, from, forecastEnd)
                        for (occurrence in occurrences) {
                            addAccountFlow(occurrence, item.accountId ?: defaultAccountId, item.amount)
                            val effect = scenarioPoolEffect(item.accountId, item.amount, poolIds) ?: continue
                            flows += flow(occurrence, effect, label(item.label, item.categoryId, categoryById), FlowSource.SCENARIO, item.id, item.categoryId, item.accountId, isMajorIncome = false)
                        }
                    }
                    ScenarioItemType.CATEGORY_ADJUST -> {
                        // Applied to learned estimates directly, see Tier2.
                    }
                }
            }
        }

        return CommittedFlowSet(flows.sortedWith(compareBy({ it.date }, { it.label })), accountFlows)
    }

    private fun flow(
        date: LocalDate,
        amount: Money,
        label: String,
        source: FlowSource,
        sourceId: Long?,
        categoryId: Long?,
        accountId: Long?,
        isMajorIncome: Boolean,
    ) = CommittedFlow(date, amount, label, source, sourceId, categoryId, accountId, isMajorIncome && amount.isPositive)

    private fun label(label: String, categoryId: Long?, categoryById: Map<Long, Category>): String =
        label.ifBlank { categoryId?.let { categoryById[it]?.name } ?: "" }

    /**
     * The signed effect on the pool of an [amount] moving from [accountId] (optionally to
     * [toAccountId] for a transfer). Returns null when the flow has no effect on the pool
     * (both legs inside it, or both outside it) and should be omitted.
     */
    private fun poolEffect(accountId: Long, toAccountId: Long?, amount: Money, poolIds: Set<Long>): Money? {
        if (toAccountId == null) {
            return if (accountId in poolIds) amount else null
        }
        val fromInPool = accountId in poolIds
        val toInPool = toAccountId in poolIds
        return when {
            fromInPool && toInPool -> null
            fromInPool -> -amount
            toInPool -> amount
            else -> null
        }
    }

    /**
     * Scenario items have no `toAccountId` (they represent a hypothetical flow, not a real
     * transfer): when [accountId] names a real account the item only counts if that account is
     * in the pool; a null [accountId] means the what-if amount isn't tied to a specific account
     * and always affects the pool directly.
     */
    private fun scenarioPoolEffect(accountId: Long?, amount: Money, poolIds: Set<Long>): Money? =
        if (accountId == null || accountId in poolIds) amount else null
}
