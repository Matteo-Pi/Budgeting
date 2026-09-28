package io.github.matteopi.budgeting.domain

import io.github.matteopi.budgeting.core.forecast.Recurrence
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.PlanRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/** One thing the user needs to confirm, edit, skip or snooze: a CONFIRM occurrence or item. */
sealed interface PendingItem {
    data class Recurring(val rule: RecurringRule, val date: LocalDate) : PendingItem
    data class Scheduled(val item: ScheduledItem) : PendingItem
}

/** Turns recurring rules and scheduled items into real transactions, automatically or on confirmation. */
class PostingService(
    private val planRepository: PlanRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: Clock,
) {
    /** Posts every due AUTO recurring occurrence and AUTO scheduled item up to and including [today]. Idempotent. */
    suspend fun postDue(today: LocalDate) {
        val rules = planRepository.observeRecurringRules().first()
        for (rule in rules) {
            if (!rule.active || rule.postingMode != PostingMode.AUTO) continue
            val from = (rule.postedThrough ?: rule.startDate.minusDays(1)).plusDays(1)
            if (from > today) continue
            val occurrences = Recurrence.occurrences(rule, from, today)
            for (date in occurrences) postRuleOccurrence(rule, date)
            planRepository.updateRecurringRule(rule.copy(postedThrough = today))
        }

        val items = planRepository.observeScheduledItems().first()
        for (item in items) {
            if (item.posted || item.skipped || item.postingMode != PostingMode.AUTO) continue
            if (item.date > today) continue
            postScheduledItem(item, item.date)
            planRepository.updateScheduledItem(item.copy(posted = true))
        }
    }

    /** CONFIRM occurrences/items due for the user's attention, oldest first. */
    suspend fun pendingConfirmations(today: LocalDate): List<PendingItem> {
        val rules = planRepository.observeRecurringRules().first()
        val ruleItems = rules.filter { it.active && it.postingMode == PostingMode.CONFIRM }.flatMap { rule ->
            val from = (rule.postedThrough ?: rule.startDate.minusDays(1)).plusDays(1)
            if (from > today) {
                emptyList()
            } else {
                Recurrence.occurrences(rule, from, today).map { PendingItem.Recurring(rule, it) }
            }
        }
        val items = planRepository.observeScheduledItems().first()
        val scheduledItems = items
            .filter { it.postingMode == PostingMode.CONFIRM && !it.posted && !it.skipped && it.date <= today }
            .map { PendingItem.Scheduled(it) }
        return (ruleItems + scheduledItems).sortedBy {
            when (it) {
                is PendingItem.Recurring -> it.date
                is PendingItem.Scheduled -> it.item.date
            }
        }
    }

    /** Confirms a pending item arrived, with a possibly-adjusted [amount] and [date]. */
    suspend fun confirm(item: PendingItem, amount: Money, date: LocalDate) {
        when (item) {
            is PendingItem.Recurring -> {
                postRuleOccurrence(item.rule, date, amount)
                val through = maxOf(item.rule.postedThrough ?: item.rule.startDate.minusDays(1), item.date)
                planRepository.updateRecurringRule(item.rule.copy(postedThrough = through))
            }
            is PendingItem.Scheduled -> {
                postScheduledItem(item.item, date, amount)
                planRepository.updateScheduledItem(item.item.copy(posted = true))
            }
        }
    }

    /** Marks a pending item as not happening this time; no transaction is posted. */
    suspend fun skip(item: PendingItem) {
        when (item) {
            is PendingItem.Recurring -> {
                val through = maxOf(item.rule.postedThrough ?: item.rule.startDate.minusDays(1), item.date)
                planRepository.updateRecurringRule(item.rule.copy(postedThrough = through))
            }
            is PendingItem.Scheduled -> planRepository.updateScheduledItem(item.item.copy(skipped = true))
        }
    }

    private suspend fun postRuleOccurrence(rule: RecurringRule, date: LocalDate, amountOverride: Money? = null) {
        val amount = amountOverride ?: rule.amount
        val toAccountId = rule.toAccountId
        if (toAccountId != null) {
            transactionRepository.addTransfer(rule.accountId, toAccountId, amount, date, rule.label)
        } else {
            transactionRepository.add(
                Transaction(
                    accountId = rule.accountId,
                    amount = amount,
                    date = date,
                    createdAtEpochMillis = clock.nowMillis(),
                    categoryId = rule.categoryId,
                    label = rule.label,
                    kind = TxKind.NORMAL,
                    recurringRuleId = rule.id,
                ),
            )
        }
    }

    private suspend fun postScheduledItem(item: ScheduledItem, date: LocalDate, amountOverride: Money? = null) {
        val amount = amountOverride ?: item.amount
        val toAccountId = item.toAccountId
        if (toAccountId != null) {
            transactionRepository.addTransfer(item.accountId, toAccountId, amount, date, item.label)
        } else {
            transactionRepository.add(
                Transaction(
                    accountId = item.accountId,
                    amount = amount,
                    date = date,
                    createdAtEpochMillis = clock.nowMillis(),
                    categoryId = item.categoryId,
                    label = item.label,
                    kind = TxKind.NORMAL,
                    scheduledItemId = item.id,
                ),
            )
        }
    }
}
