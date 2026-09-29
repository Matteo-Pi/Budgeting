package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.DefaultCategories
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/** A generated forecast input; [summary] is a compact description for failure reports. */
class GeneratedInput(val seed: Long, val input: ForecastInput) {
    val summary: String by lazy { InputDescription.describe(input, seed) }
}

/**
 * Seeded random [ForecastInput]s, realistic and awkward: several accounts (non-pool, archived,
 * overdrafts, negative balances), up to a year of history with every transaction kind, rules
 * with every frequency and odd dates, scheduled items, overlapping terms, extreme predictions,
 * strange sinking funds and optional scenarios. Everything comes from [Random] seeded with the
 * case seed, so a failing seed replays exactly.
 */
object InputGenerator {
    fun generate(seed: Long): GeneratedInput = Builder(seed).build()

    private class Builder(private val seed: Long) {
        private val r = Random(seed)

        private fun chance(p: Double) = r.nextDouble() < p
        private fun between(lo: Int, hi: Int) = lo + r.nextInt(hi - lo + 1)
        private fun betweenL(lo: Long, hi: Long) = lo + r.nextLong(hi - lo + 1)
        private fun <T> pick(items: List<T>): T = items[r.nextInt(items.size)]

        private val today: LocalDate = pickToday()
        private val horizon: Int = when {
            chance(0.08) -> 14
            chance(0.08) -> 365
            else -> between(14, 365)
        }
        private val accounts: List<Account> = makeAccounts()
        private val categories: List<Category> = makeCategories()
        private val learned = categories.filter { it.forecastMode == ForecastMode.LEARNED }
        private val learnedOut = learned.filter { it.flow == Flow.OUT }
        private val learnedIn = learned.filter { it.flow == Flow.IN }
        private val scheduledOnly = categories.filter { it.forecastMode == ForecastMode.SCHEDULED_ONLY }
        private val untracked = categories.firstOrNull { it.isSystem }

        fun build(): GeneratedInput {
            val rules = makeRules()
            val items = makeItems()
            val terms = makeTerms()
            val predictions = makePredictions()
            val funds = makeFunds()
            val (transactions, reconciliations) = makeTransactions(rules.size, items.size)
            val settings = makeSettings()
            val scenario = makeScenario()
            val input = ForecastInput(
                today = today,
                accounts = accounts,
                categories = categories,
                transactions = transactions,
                recurringRules = rules,
                scheduledItems = items,
                termPeriods = terms,
                predictions = predictions,
                sinkingFunds = funds,
                reconciliations = reconciliations,
                settings = settings,
                scenario = scenario,
                runMonteCarlo = true,
                seed = r.nextLong(),
            )
            return GeneratedInput(seed, input)
        }

        // --- calendar ---------------------------------------------------------------------------

        private fun pickToday(): LocalDate {
            val base = LocalDate.ofEpochDay(betweenL(LocalDate.of(2020, 1, 1).toEpochDay(), LocalDate.of(2035, 12, 31).toEpochDay()))
            return when (r.nextInt(10)) {
                0 -> base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                1 -> base.with(TemporalAdjusters.lastDayOfMonth())
                2 -> LocalDate.of(pick(listOf(2024, 2028, 2032)), 2, 29)
                3 -> base.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                else -> base
            }
        }

        // --- accounts and categories --------------------------------------------------------------

        private fun makeAccounts(): List<Account> {
            val n = pick(listOf(1, 1, 2, 2, 3, 3, 4, 5))
            val ids: List<Long> = if (chance(0.7)) {
                (1..n).map { it.toLong() }
            } else {
                val set = LinkedHashSet<Long>()
                while (set.size < n) set.add(betweenL(1, 90))
                set.toList()
            }
            val guaranteePool = chance(0.88)
            return ids.mapIndexed { i, id ->
                val forcedPool = guaranteePool && i == 0
                Account(
                    id = id,
                    name = "Account $id",
                    type = pick(AccountType.entries),
                    overdraftLimit = if (chance(0.4)) Money.ZERO else Money(betweenL(0, 200_000)),
                    includeInSafeToSpend = forcedPool || chance(0.8),
                    archived = !forcedPool && chance(0.1),
                    sortOrder = r.nextInt(10),
                )
            }
        }

        private fun makeCategories(): List<Category> {
            if (chance(0.02)) return emptyList()
            val result = mutableListOf<Category>()
            DefaultCategories.all.forEachIndexed { i, c ->
                if (c.isSystem && chance(0.08)) return@forEachIndexed
                result += c.copy(
                    id = i + 1L,
                    forecastMode = if (chance(0.05)) {
                        if (c.forecastMode == ForecastMode.LEARNED) ForecastMode.SCHEDULED_ONLY else ForecastMode.LEARNED
                    } else {
                        c.forecastMode
                    },
                    archived = !c.isSystem && chance(0.05),
                )
            }
            repeat(between(0, 3)) { k ->
                result += Category(
                    id = 100L + k, name = "Extra $k", flow = pick(listOf(Flow.OUT, Flow.OUT, Flow.IN, Flow.BOTH)),
                    forecastMode = pick(ForecastMode.entries), archived = chance(0.1),
                )
            }
            return result
        }

        private fun anyAccountId(): Long = if (chance(0.03)) 999L else pick(accounts).id

        private fun anyCategoryId(): Long? = when {
            learned.isNotEmpty() && chance(0.6) -> pick(learned).id
            scheduledOnly.isNotEmpty() && chance(0.5) -> pick(scheduledOnly).id
            chance(0.15) -> null
            chance(0.1) -> 999L
            categories.isNotEmpty() -> pick(categories).id
            else -> null
        }

        /** Skews towards the first categories, like groceries in a real set. */
        private fun skewPick(list: List<Category>): Category = list[minOf(r.nextInt(list.size), r.nextInt(list.size))]

        // --- amounts ---------------------------------------------------------------------------

        private fun spendPence(): Long = when {
            chance(0.02) -> 0L
            chance(0.05) -> betweenL(20_000, 300_000)
            else -> betweenL(50, 12_000)
        }

        private fun incomePence(): Long = if (chance(0.05)) betweenL(100_000, 600_000) else betweenL(1_000, 80_000)

        private fun magnitude(): Long = when {
            chance(0.03) -> 0L
            chance(0.05) -> betweenL(100_000, 600_000)
            else -> betweenL(300, 90_000)
        }

        // --- rules, scheduled items ---------------------------------------------------------------

        private fun ruleStart(): LocalDate = when (r.nextInt(20)) {
            0, 1 -> LocalDate.of(1970, 1, 1).plusDays(betweenL(0, 365L * 30))
            2 -> LocalDate.of(1970, 1, 1)
            3, 4 -> LocalDate.of(today.year + 1, 1, 1).plusDays(betweenL(0, 364))
            5, 6 -> today.plusDays(betweenL(-10, 10))
            7 -> today.minusYears(betweenL(2, 12)).plusDays(betweenL(0, 365))
            8 -> LocalDate.of(pick(listOf(2024, 2028)), 1, 31)
            else -> today.plusDays(betweenL(-400, 200))
        }

        private fun makeRules(): List<RecurringRule> = List(pick(listOf(0, 0, 1, 2, 2, 3, 4, 5, 6, 8))) { i ->
            val transfer = chance(0.15)
            val accountId = anyAccountId()
            val toAccountId = if (transfer) (if (chance(0.1)) accountId else anyAccountId()) else null
            val positive = transfer || chance(0.25)
            val base = magnitude()
            val amount = when {
                transfer -> if (chance(0.03)) -base else base
                positive -> base * 2
                else -> -base
            }
            val start = ruleStart()
            val end = when {
                chance(0.55) -> null
                chance(0.15) -> start.minusDays(betweenL(1, 400))
                chance(0.1) -> start
                else -> start.plusDays(betweenL(0, 800))
            }
            RecurringRule(
                id = i + 1L,
                label = if (chance(0.15)) "" else "Rule ${i + 1}",
                accountId = accountId,
                categoryId = anyCategoryId(),
                amount = Money(amount),
                frequency = pick(Frequency.entries),
                interval = between(0, 4),
                startDate = start,
                endDate = end,
                postingMode = pick(PostingMode.entries),
                // A rule left unposted since 1970 floods tomorrow with thousands of overdue flows; keep that rare.
                postedThrough = when {
                    start.isBefore(today.minusDays(400)) && chance(0.85) -> today.plusDays(betweenL(-120, 15))
                    chance(0.5) -> null
                    else -> today.plusDays(betweenL(-120, 15))
                },
                toAccountId = toAccountId,
                isMajorIncome = (positive && !transfer && chance(0.4)) || chance(0.03),
                active = !chance(0.08),
            )
        }

        private fun makeItems(): List<ScheduledItem> = List(pick(listOf(0, 0, 1, 2, 3, 4, 6, 8))) { i ->
            val transfer = chance(0.15)
            val accountId = anyAccountId()
            val toAccountId = if (transfer) (if (chance(0.1)) accountId else anyAccountId()) else null
            val positive = transfer || chance(0.3)
            val base = magnitude()
            val date = when {
                chance(0.25) -> today.minusDays(betweenL(1, 90))
                chance(0.1) -> today
                chance(0.15) -> today.plusDays(horizon + betweenL(1, 400))
                else -> today.plusDays(betweenL(1, horizon.toLong()))
            }
            ScheduledItem(
                id = i + 1L,
                label = if (chance(0.15)) "" else "Item ${i + 1}",
                accountId = accountId,
                categoryId = anyCategoryId(),
                amount = Money(if (positive) base * 2 else -base),
                date = date,
                postingMode = pick(PostingMode.entries),
                toAccountId = toAccountId,
                isMajorIncome = (positive && !transfer && chance(0.3)) || chance(0.03),
                posted = chance(0.15),
                skipped = chance(0.1),
            )
        }

        // --- terms, predictions, funds ------------------------------------------------------------

        private fun makeTerms(): List<TermPeriod> {
            val out = mutableListOf<TermPeriod>()
            repeat(pick(listOf(0, 0, 1, 1, 2, 3, 4))) { i ->
                val start = when {
                    chance(0.1) -> today.plusYears(betweenL(3, 60))
                    chance(0.3) && out.isNotEmpty() -> pick(out).startDate.plusDays(betweenL(-20, 40))
                    else -> today.plusDays(betweenL(-500, 300))
                }
                val end = when {
                    chance(0.1) -> start.minusDays(betweenL(1, 60))
                    chance(0.05) -> start
                    else -> start.plusDays(betweenL(20, 120))
                }
                out += TermPeriod(id = i + 1L, name = "Term ${i + 1}", startDate = start, endDate = end)
            }
            return out
        }

        private fun makePredictions(): List<Prediction> = List(pick(listOf(0, 0, 1, 2, 3, 5, 8, 10))) { i ->
            val categoryId = when {
                learned.isNotEmpty() && chance(0.9) -> pick(learned).id
                scheduledOnly.isNotEmpty() && chance(0.5) -> pick(scheduledOnly).id
                else -> 999L
            }
            Prediction(
                id = i + 1L,
                categoryId = categoryId,
                regime = pick(Regime.entries),
                mode = pick(PredictionMode.entries),
                weeklyAmount = Money(if (chance(0.05)) betweenL(100_000, 500_000) else betweenL(0, 30_000)),
                adjustPercent = when {
                    chance(0.15) -> -100
                    chance(0.15) -> 500
                    else -> between(-100, 500)
                },
            )
        }

        private fun makeFunds(): List<SinkingFund> = List(pick(listOf(0, 0, 0, 1, 1, 2, 3))) { i ->
            val start = today.plusDays(betweenL(-90, 60))
            val target = when (r.nextInt(10)) {
                0 -> start.minusDays(betweenL(1, 200))
                1 -> today.minusDays(betweenL(1, 200))
                2 -> start
                else -> start.plusDays(betweenL(10, 300))
            }
            SinkingFund(
                id = i + 1L,
                name = "Fund ${i + 1}",
                target = if (chance(0.1)) Money.ZERO else Money(betweenL(1_000, 500_000)),
                startDate = start,
                targetDate = target,
                categoryId = anyCategoryId(),
                status = if (chance(0.8)) SinkingFundStatus.ACTIVE else pick(SinkingFundStatus.entries),
            )
        }

        // --- transactions --------------------------------------------------------------------------

        private fun makeTransactions(ruleCount: Int, itemCount: Int): Pair<List<Transaction>, List<Reconciliation>> {
            val txs = mutableListOf<Transaction>()
            val recs = mutableListOf<Reconciliation>()
            var nextId = 1L + r.nextInt(1000)
            fun tx(
                accountId: Long, amount: Long, date: LocalDate, categoryId: Long?, kind: TxKind = TxKind.NORMAL,
                group: String? = null, ruleId: Long? = null, itemId: Long? = null,
            ): Transaction = Transaction(
                id = nextId++, accountId = accountId, amount = Money(amount), date = date,
                createdAtEpochMillis = r.nextLong(0, 2_000_000_000_000L), categoryId = categoryId,
                label = if (chance(0.5)) "" else "Tx $nextId", kind = kind, transferGroupId = group,
                recurringRuleId = ruleId, scheduledItemId = itemId,
            )

            val weeks = when {
                chance(0.08) -> 0
                chance(0.2) -> between(1, 8)
                else -> between(9, 60)
            }
            val activity = pick(listOf(0, 1, 2, 4, 6))
            val poolAccounts = accounts.filter { it.includeInSafeToSpend && !it.archived }
            fun txAccount(): Long = if (poolAccounts.isNotEmpty() && chance(0.8)) pick(poolAccounts).id else pick(accounts).id

            // Ordinary history.
            for (w in 0 until weeks) {
                val count = if (activity == 0) 0 else r.nextInt(activity * 2 + 1)
                repeat(count) {
                    val date = today.minusDays(w * 7L + r.nextInt(7))
                    val roll = r.nextInt(100)
                    var amount: Long
                    val categoryId: Long?
                    when {
                        roll < 65 && learnedOut.isNotEmpty() -> {
                            categoryId = skewPick(learnedOut).id
                            amount = -spendPence()
                        }
                        roll < 85 && learnedIn.isNotEmpty() -> {
                            categoryId = pick(learnedIn).id
                            amount = incomePence()
                        }
                        else -> {
                            categoryId = anyCategoryId()
                            amount = if (chance(0.7)) -spendPence() else incomePence()
                        }
                    }
                    if (chance(0.03)) amount = -amount // a refund on an OUT category, a reversal on an IN one
                    txs += tx(
                        txAccount(), amount, date, categoryId,
                        ruleId = if (chance(0.03)) (1L + r.nextInt(maxOf(1, ruleCount))) else null,
                        itemId = if (chance(0.03)) (1L + r.nextInt(maxOf(1, itemCount))) else null,
                    )
                }
            }

            // Opening balances (some negative, some in the middle of the history).
            for (account in accounts) {
                if (!chance(0.85)) continue
                val date = if (chance(0.1)) today.minusDays(betweenL(0, weeks * 7L + 1)) else today.minusDays(weeks * 7L + betweenL(0, 200))
                txs += tx(account.id, betweenL(-100_000, 500_000), date, null, TxKind.OPENING_BALANCE)
            }

            // Transfers between accounts (both legs), a few onto the same account, a few with one leg missing.
            if (chance(0.6)) {
                repeat(between(1, 6)) {
                    val from = pick(accounts).id
                    val others = accounts.filter { it.id != from }
                    val to = if (others.isNotEmpty() && chance(0.9)) pick(others).id else from
                    val amount = betweenL(500, 80_000)
                    val date = if (chance(0.05)) today.plusDays(betweenL(1, 30)) else today.minusDays(betweenL(0, weeks * 7L + 10))
                    val group = "g$nextId"
                    txs += tx(from, -amount, date, null, TxKind.TRANSFER, group)
                    if (!chance(0.03)) txs += tx(to, amount, date, null, TxKind.TRANSFER, group)
                }
            }

            // Balance updates, with and without their Reconciliation record.
            if (chance(0.4)) {
                repeat(between(1, 4)) {
                    val account = pick(accounts)
                    val date = today.minusDays(betweenL(0, weeks * 7L + 5))
                    val amount = betweenL(-8_000, 8_000)
                    val categoryId = if (chance(0.9)) untracked?.id else anyCategoryId()
                    val recTx = tx(account.id, amount, date, categoryId, TxKind.RECONCILIATION)
                    txs += recTx
                    if (chance(0.6)) {
                        val periodStart = if (chance(0.1)) date.plusDays(betweenL(1, 10)) else date.minusDays(betweenL(0, 40))
                        recs += Reconciliation(
                            id = recs.size + 1L, accountId = account.id, date = date,
                            expected = Money(betweenL(-50_000, 50_000)), actual = Money(betweenL(-50_000, 50_000)),
                            transactionId = recTx.id, periodStart = periodStart,
                        )
                    }
                }
            }
            if (chance(0.1)) {
                recs += Reconciliation(
                    id = recs.size + 1L, accountId = pick(accounts).id, date = today.minusDays(betweenL(0, 30)),
                    expected = Money.ZERO, actual = Money.ZERO, transactionId = if (chance(0.5)) null else 987_654L,
                    periodStart = today.minusDays(betweenL(0, 60)),
                )
            }

            // Transactions dated after today (the UI should not allow them).
            if (chance(0.25)) {
                repeat(between(1, 3)) {
                    val date = today.plusDays(betweenL(1, horizon + 120L))
                    val amount = if (chance(0.7)) -spendPence() else incomePence()
                    if (chance(0.15)) {
                        val group = "f$nextId"
                        txs += tx(pick(accounts).id, -amount, date, null, TxKind.TRANSFER, group)
                        txs += tx(pick(accounts).id, amount, date, null, TxKind.TRANSFER, group)
                    } else {
                        txs += tx(txAccount(), amount, date, anyCategoryId())
                    }
                }
            }

            // Postings that came from rules or items (excluded from learning).
            if (chance(0.4) && scheduledOnly.isNotEmpty()) {
                repeat(between(1, 6)) {
                    txs += tx(
                        txAccount(), -spendPence(), today.minusDays(betweenL(0, weeks * 7L + 5)), pick(scheduledOnly).id,
                        ruleId = 1L + r.nextInt(maxOf(1, ruleCount)), itemId = if (chance(0.3)) 1L + r.nextInt(maxOf(1, itemCount)) else null,
                    )
                }
            }
            return txs to recs
        }

        // --- settings, scenario -------------------------------------------------------------------------

        private fun makeSettings(): BudgetSettings {
            val defaultId: Long? = when (r.nextInt(8)) {
                0, 1 -> null
                2, 3, 4 -> pick(accounts).id
                5 -> 12345L
                else -> accounts.filter { it.archived || !it.includeInSafeToSpend }.let { if (it.isEmpty()) null else pick(it).id }
            }
            val historyStart: LocalDate? = when {
                chance(0.5) -> null
                chance(0.05) -> today.plusDays(betweenL(0, 30))
                else -> today.minusDays(betweenL(0, 90L * 7))
            }
            return BudgetSettings(
                zeroPoint = pick(ZeroPoint.entries),
                buffer = Money(betweenL(0, 100_000)),
                defaultAccountId = defaultId,
                historyStartDate = historyStart,
                simulations = between(50, 300),
                forecastHorizonDays = horizon,
            )
        }

        private fun makeScenario(): Scenario? {
            if (!chance(0.35)) return null
            val items = List(between(1, 6)) { i ->
                val type = pick(ScenarioItemType.entries)
                val date: LocalDate? = when {
                    chance(0.05) -> null
                    chance(0.25) -> today.minusDays(betweenL(1, 60))
                    chance(0.15) -> today.plusDays(horizon + betweenL(1, 300))
                    else -> today.plusDays(betweenL(1, horizon.toLong()))
                }
                ScenarioItem(
                    id = i + 1L,
                    scenarioId = 1,
                    type = type,
                    label = if (chance(0.2)) "" else "What-if ${i + 1}",
                    accountId = if (chance(0.4)) null else anyAccountId(),
                    categoryId = if (type == ScenarioItemType.CATEGORY_ADJUST && learned.isNotEmpty() && chance(0.85)) pick(learned).id else anyCategoryId(),
                    amount = Money(if (chance(0.5)) -betweenL(0, 200_000) else betweenL(0, 200_000)),
                    date = date,
                    frequency = if (chance(0.05)) null else pick(Frequency.entries),
                    interval = between(0, 4),
                    endDate = if (chance(0.6) || date == null) null else date.plusDays(betweenL(-30, 300)),
                    percent = when {
                        chance(0.15) -> -100
                        chance(0.15) -> 500
                        else -> between(-100, 500)
                    },
                    regime = when (r.nextInt(3)) {
                        0 -> null
                        1 -> Regime.TERM
                        else -> Regime.HOLIDAY
                    },
                )
            }
            return Scenario(id = 1, name = "What if", items = items)
        }
    }
}
