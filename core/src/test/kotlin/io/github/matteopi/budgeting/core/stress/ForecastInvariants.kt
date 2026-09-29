package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.forecast.DataSufficiency
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.FlowSource
import io.github.matteopi.budgeting.core.forecast.Regimes
import io.github.matteopi.budgeting.core.forecast.SinkingFunds
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Properties every [ForecastResult] must have, whatever the input. The checks read the result
 * and the input only through their public shapes and recompute the interesting numbers (pool
 * balance, safe-to-spend) independently, so they do not just replay the engine's own code.
 */
object ForecastInvariants {
    private val BINDING_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun check(input: ForecastInput, result: ForecastResult, f: Failures) {
        f.guard("shape") { shape(input, result, f) }
        f.guard("accounts") { accounts(input, result, f) }
        f.guard("pool") { pool(input, result, f) }
        f.guard("percentiles") { percentiles(input, result, f) }
        f.guard("flows") { flows(input, result, f) }
        f.guard("budgetPeriods") { budgetPeriods(input, result, f) }
        f.guard("estimates") { estimates(input, result, f) }
        f.guard("funds") { funds(input, result, f) }
        f.guard("safeToSpend") { safeToSpend(input, result, f) }
    }

    // --- days ------------------------------------------------------------------------------------

    private fun shape(input: ForecastInput, result: ForecastResult, f: Failures) {
        val today = input.today
        val horizon = input.settings.forecastHorizonDays
        val forecastEnd = today.plusDays(horizon.toLong())
        f.expect("shape.today", result.today == today) { "${result.today} vs $today" }
        f.expect("shape.forecastEnd", result.forecastEnd == forecastEnd) { "${result.forecastEnd} vs $forecastEnd" }
        f.expect("shape.days.size", result.days.size == horizon + 1) { "${result.days.size} days for horizon $horizon" }
        f.expect("shape.days.start", result.days.first().date == today) { "${result.days.first().date}" }
        f.expect("shape.days.end", result.days.last().date == forecastEnd) { "${result.days.last().date}" }
        val gap = result.days.zipWithNext().firstOrNull { (a, b) -> b.date != a.date.plusDays(1) }
        f.expect("shape.days.consecutive", gap == null) { "gap between ${gap?.first?.date} and ${gap?.second?.date}" }
        val terms = input.termPeriods
        val badRegime = result.days.firstOrNull { it.regime != Regimes.regimeOf(it.date, terms) }
        f.expect("shape.days.regime", badRegime == null) { "regime of ${badRegime?.date}" }

        val first = result.days.first()
        f.expect("days[0].balances", first.expectedBalance == result.poolBalanceNow && first.tier1Balance == result.poolBalanceNow) {
            "today's balances ${first.expectedBalance}/${first.tier1Balance} vs pool ${result.poolBalanceNow}"
        }
        f.expect("days[0].flows", first.committedNet.isZero && first.expectedLearned.isZero) { "flows on today" }

        // The tier-2 balance is the previous balance plus that day's committed and learned flows.
        for (i in 1 until result.days.size) {
            val day = result.days[i]
            val prev = result.days[i - 1]
            if (day.expectedBalance.pence != prev.expectedBalance.pence + day.committedNet.pence + day.expectedLearned.pence) {
                f.add("days.expectedBalance.chain", "day ${day.date}: ${prev.expectedBalance} + ${day.committedNet} + ${day.expectedLearned} != ${day.expectedBalance}")
                break
            }
        }
        // The learned flow splits exactly into spending (out) and income (in).
        val badSplit = result.days.firstOrNull { day ->
            day.expectedSpending.pence > 0 || day.expectedIncome.pence < 0 ||
                day.expectedSpending.pence + day.expectedIncome.pence != day.expectedLearned.pence
        }
        f.expect("days.expectedLearned.split", badSplit == null) {
            "day ${badSplit?.date}: ${badSplit?.expectedSpending} + ${badSplit?.expectedIncome} vs ${badSplit?.expectedLearned}"
        }
                // Tier 1 adds the same flat learned amount every day on top of the committed flows.
        if (result.days.size > 2) {
            val steps = (1 until result.days.size).map {
                result.days[it].tier1Balance.pence - result.days[it - 1].tier1Balance.pence - result.days[it].committedNet.pence
            }
            f.expect("days.tier1.flat", steps.distinct().size == 1) { "tier-1 daily learned amounts differ: ${steps.distinct().take(4)}" }
        }
        f.expect("history.weeks", result.completeWeeksOfHistory in 0..52) { "${result.completeWeeksOfHistory} weeks" }
    }

    // --- accounts and the pool -----------------------------------------------------------------------

    private fun accounts(input: ForecastInput, result: ForecastResult, f: Failures) {
        val covered = input.accounts.filter { !it.archived }.sortedBy { it.id }
        f.expect("accounts.covered", result.accountForecasts.map { it.accountId } == covered.map { it.id }) {
            "forecasts for ${result.accountForecasts.map { it.accountId }}, expected ${covered.map { it.id }}"
        }
        if (result.accountForecasts.size != covered.size) return
        val dates = result.days.map { it.date }
        for ((forecast, account) in result.accountForecasts.zip(covered)) {
            f.expect("accounts.inPool", forecast.inPool == account.includeInSafeToSpend) { "account ${account.id}" }
            f.expect("accounts.overdraft", forecast.overdraft == account.overdraftLimit) { "account ${account.id}" }
            f.expect("accounts.days.aligned", forecast.days.map { it.date } == dates) { "account ${account.id}: ${forecast.days.size} days vs ${dates.size}" }
            val balance = input.transactions.filter { it.accountId == account.id && !it.date.isAfter(input.today) }.sumOf { it.amount.pence }
            f.expect("accounts.balanceNow", forecast.balanceNow.pence == balance) { "account ${account.id}: ${forecast.balanceNow} vs $balance" }
            if (forecast.days.isNotEmpty()) {
                f.expect("accounts.days[0]", forecast.days.first().expectedBalance == forecast.balanceNow) { "account ${account.id}" }
            }
        }
    }

    private fun pool(input: ForecastInput, result: ForecastResult, f: Failures) {
        val poolAccounts = input.accounts.filter { it.includeInSafeToSpend && !it.archived }
        val poolIds = poolAccounts.map { it.id }.toSet()
        val independent = input.transactions.filter { it.accountId in poolIds && !it.date.isAfter(input.today) }.sumOf { it.amount.pence }
        f.expect("pool.balanceNow", result.poolBalanceNow.pence == independent) { "${result.poolBalanceNow} vs independent $independent" }
        val overdraft = poolAccounts.sumOf { it.overdraftLimit.pence }
        f.expect("pool.overdraft", result.poolOverdraft.pence == overdraft) { "${result.poolOverdraft} vs $overdraft" }
        val floor = if (input.settings.zeroPoint.name == "OVERDRAFT_LIMIT") -overdraft else 0L
        f.expect("pool.floor", result.floor.pence == floor) { "${result.floor} vs $floor" }

        if (poolAccounts.isEmpty()) return // no account to attribute the pool's own flows to
        val pooled = result.accountForecasts.filter { it.inPool }
        f.expect("pool.balanceNow.sum", pooled.sumOf { it.balanceNow.pence } == result.poolBalanceNow.pence) { "account balances do not add up to the pool" }
        for ((i, day) in result.days.withIndex()) {
            val sum = pooled.sumOf { it.days[i].expectedBalance.pence }
            if (sum != day.expectedBalance.pence) {
                f.add("pool.accountsSum", "on ${day.date} (day $i) pool accounts sum to ${Money(sum)}, pool is ${day.expectedBalance}")
                break
            }
        }
    }

    // --- Monte Carlo ------------------------------------------------------------------------------------

    private fun percentiles(input: ForecastInput, result: ForecastResult, f: Failures) {
        if (!input.runMonteCarlo) {
            f.expect("mc.off.poolNull", result.days.all { it.p10 == null && it.p50 == null && it.p90 == null }) { "pool percentiles present without Monte Carlo" }
            f.expect("mc.off.accountsNull", result.accountForecasts.all { a -> a.days.all { it.p10 == null && it.p50 == null && it.p90 == null } }) { "account percentiles present without Monte Carlo" }
            f.expect("mc.off.risk", result.risk == null) { "risk present without Monte Carlo" }
            return
        }
        for (day in result.days) {
            val p10 = day.p10
            val p50 = day.p50
            val p90 = day.p90
            if (p10 == null || p50 == null || p90 == null) {
                f.add("mc.pool.present", "missing percentile on ${day.date}")
                break
            }
            if (!(p10 <= p50 && p50 <= p90)) {
                f.add("mc.pool.order", "on ${day.date}: $p10 / $p50 / $p90")
                break
            }
        }
        for (account in result.accountForecasts) {
            for (day in account.days) {
                val p10 = day.p10
                val p50 = day.p50
                val p90 = day.p90
                if (p10 == null || p50 == null || p90 == null) {
                    f.add("mc.account.present", "account ${account.accountId} missing a percentile on ${day.date}")
                    break
                }
                if (!(p10 <= p50 && p50 <= p90)) {
                    f.add("mc.account.order", "account ${account.accountId} on ${day.date}: $p10 / $p50 / $p90")
                    break
                }
            }
        }
        val first = result.days.first()
        f.expect("mc.days[0]", first.p10 == result.poolBalanceNow && first.p50 == result.poolBalanceNow && first.p90 == result.poolBalanceNow) {
            "today's band ${first.p10}/${first.p50}/${first.p90} vs ${result.poolBalanceNow}"
        }

        val risk = result.risk
        f.expect("mc.risk.present", risk != null) { "no risk summary" }
        if (risk == null) return
        f.expect("mc.risk.probabilities", risk.probBelowFloor in 0.0..1.0 && risk.probBeyondOverdraft in 0.0..1.0) { "$risk" }
        f.expect("mc.risk.beyondLeBelow", risk.probBeyondOverdraft <= risk.probBelowFloor) { "beyond ${risk.probBeyondOverdraft} > below ${risk.probBelowFloor}" }
        f.expect("mc.risk.endOrder", risk.endBalanceP10 <= risk.endBalanceP50 && risk.endBalanceP50 <= risk.endBalanceP90) { "$risk" }
        val last = result.days.last()
        f.expect("mc.risk.endMatchesDays", risk.endBalanceP10 == last.p10 && risk.endBalanceP50 == last.p50 && risk.endBalanceP90 == last.p90) {
            "end balances ${risk.endBalanceP10}/${risk.endBalanceP50}/${risk.endBalanceP90} vs last day ${last.p10}/${last.p50}/${last.p90}"
        }
        val breach = risk.firstLikelyBreach
        val expectedBreach = result.days.drop(1).firstOrNull { (it.p10 ?: Money.ZERO) < result.floor }?.date
        f.expect("mc.risk.firstBreach", breach == expectedBreach) { "firstLikelyBreach $breach vs recomputed $expectedBreach" }
    }

    // --- committed flows and budget periods ------------------------------------------------------------------------

    private fun flows(input: ForecastInput, result: ForecastResult, f: Failures) {
        val today = input.today
        val end = result.forecastEnd
        val sorted = result.committedFlows.sortedWith(compareBy({ it.date }, { it.label }))
        f.expect("flows.sorted", sorted == result.committedFlows) { "committedFlows are not sorted by date then label" }
        val outside = result.committedFlows.firstOrNull { !it.date.isAfter(today) || it.date.isAfter(end) }
        f.expect("flows.range", outside == null) { "flow '${outside?.label}' on ${outside?.date} is outside ($today, $end]" }
        val perDay = result.committedFlows.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.amount.pence } }
        for (day in result.days.drop(1)) {
            val expected = perDay[day.date] ?: 0L
            if (day.committedNet.pence != expected) {
                f.add("flows.committedNet", "on ${day.date}: day says ${day.committedNet}, flows sum to ${Money(expected)}")
                break
            }
        }
        f.expect("flows.majorIncomePositive", result.committedFlows.all { !it.isMajorIncome || it.amount.isPositive }) { "a major income with a non-positive amount" }
    }

    private fun budgetPeriods(input: ForecastInput, result: ForecastResult, f: Failures) {
        val periods = result.budgetPeriods
        val today = input.today
        f.expect("periods.nonEmpty", periods.isNotEmpty()) { "no budget periods" }
        if (periods.isEmpty()) return
        f.expect("periods.start", periods.first().start == today.plusDays(1)) { "first period starts ${periods.first().start}" }
        f.expect("periods.end", periods.last().end == result.forecastEnd) { "last period ends ${periods.last().end}" }
        for ((i, p) in periods.withIndex()) {
            f.expect("periods.startLeEnd", !p.end.isBefore(p.start)) { "period $i: ${p.start}..${p.end}" }
            if (i > 0) {
                f.expect("periods.contiguous", p.start == periods[i - 1].end.plusDays(1)) { "period $i starts ${p.start} after ${periods[i - 1].end}" }
            }
            val days = ChronoUnit.DAYS.between(p.start, p.end) + 1
            f.expect("periods.weeks", abs(p.weeks - days / 7.0) < 1e-9) { "period $i: weeks ${p.weeks} for $days days" }
            f.expect("periods.signs", p.committedOut.pence <= 0 && p.committedIn.pence >= 0 && p.startingIncome.pence >= 0) {
                "period $i: out ${p.committedOut} in ${p.committedIn} income ${p.startingIncome}"
            }
        }
        // The periods partition the horizon, so their committed flows add up to the whole (minus majors and funds).
        val counted = result.committedFlows.filter { it.source != FlowSource.SINKING_FUND && !(it.isMajorIncome && it.amount.isPositive) }
        val expected = counted.sumOf { it.amount.pence }
        val actual = periods.sumOf { it.committedIn.pence + it.committedOut.pence }
        f.expect("periods.flowsAddUp", expected == actual) { "periods carry $actual, flows carry $expected" }
    }

    // --- estimates ------------------------------------------------------------------------------------------------

    private fun estimates(input: ForecastInput, result: ForecastResult, f: Failures) {
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        f.expect("estimates.count", result.categoryEstimates.size == learned.size * Regime.entries.size) {
            "${result.categoryEstimates.size} estimates for ${learned.size} learned categories"
        }
        f.expect("estimates.tier1Keys", result.tier1WeeklyByCategory.keys == learned.map { it.id }.toSet()) { "tier-1 keys ${result.tier1WeeklyByCategory.keys}" }
        for (e in result.categoryEstimates) {
            val shares = e.dayOfWeekShares
            val ok = shares.size == 7 && shares.all { it >= 0.0 && it.isFinite() } && abs(shares.sum() - 1.0) <= 1e-9
            if (!ok) {
                f.add("estimates.dayOfWeekShares", "category ${e.categoryId}/${e.regime}: $shares (sum ${shares.sum()})")
                break
            }
        }
        for (e in result.categoryEstimates) {
            val expected = when {
                e.weeksOfData >= 6 -> DataSufficiency.TRUSTED
                e.weeksOfData >= 2 -> DataSufficiency.THIN
                else -> DataSufficiency.TOO_THIN
            }
            if (e.sufficiency != expected) {
                f.add("estimates.sufficiency", "category ${e.categoryId}/${e.regime}: ${e.weeksOfData} weeks but ${e.sufficiency}")
                break
            }
        }
        for (category in learned) {
            val weeks = result.categoryEstimates.filter { it.categoryId == category.id }.sumOf { it.weeksOfData }
            if (weeks != result.completeWeeksOfHistory) {
                f.add("estimates.weeksSplit", "category ${category.id}: regimes carry $weeks weeks, history has ${result.completeWeeksOfHistory}")
                break
            }
        }
        val estimateByKey = result.categoryEstimates.associateBy { it.categoryId to it.regime }
        val categoryById = input.categories.associateBy { it.id }
        for (request in result.needsPrediction) {
            val estimate = estimateByKey[request.categoryId to request.regime]
            val category = categoryById[request.categoryId]
            val hasPrediction = input.predictions.any { it.categoryId == request.categoryId && it.regime == request.regime }
            val bad = estimate == null || estimate.sufficiency != DataSufficiency.TOO_THIN || category == null ||
                category.flow != Flow.OUT || category.isSystem || category.archived || hasPrediction ||
                request.suggestedWeekly?.isNegative == true
            if (bad) {
                f.add("estimates.needsPrediction", "request $request: estimate=${estimate?.sufficiency} category=$category hasPrediction=$hasPrediction")
                break
            }
        }
        f.expect("estimates.needsPrediction.unique", result.needsPrediction.map { it.categoryId to it.regime }.let { it.size == it.toSet().size }) {
            "duplicate needsPrediction entries"
        }
        // Every (learned OUT, regime) with too little data, no prediction and a regime that occurs must be reported.
        val regimesInHorizon = result.days.map { it.regime }.toSet()
        for (category in learned) {
            if (category.flow != Flow.OUT || category.isSystem || category.archived) continue
            for (regime in Regime.entries) {
                val estimate = estimateByKey[category.id to regime] ?: continue
                val shouldRequest = estimate.sufficiency == DataSufficiency.TOO_THIN && regime in regimesInHorizon &&
                    input.predictions.none { it.categoryId == category.id && it.regime == regime }
                val requested = result.needsPrediction.any { it.categoryId == category.id && it.regime == regime }
                if (shouldRequest != requested) {
                    f.add("estimates.needsPrediction.complete", "category ${category.id}/$regime: should=$shouldRequest requested=$requested")
                    return
                }
            }
        }
    }

    // --- sinking funds ---------------------------------------------------------------------------------------------

    private fun funds(input: ForecastInput, result: ForecastResult, f: Failures) {
        val active = input.sinkingFunds.filter { it.status == SinkingFundStatus.ACTIVE }
        f.expect("funds.active", result.fundReservations.map { it.fundId } == active.map { it.id }) { "reservations ${result.fundReservations.map { it.fundId }}" }
        for (reservation in result.fundReservations) {
            val fund = active.first { it.id == reservation.fundId }
            f.expect("funds.reservedToday", reservation.reservedToday.pence in 0..maxOf(0L, fund.target.pence)) { "fund ${fund.id}: ${reservation.reservedToday} of ${fund.target}" }
            f.expect("funds.weekly", reservation.weeklyContribution.pence >= 0) { "fund ${fund.id}: weekly ${reservation.weeklyContribution}" }
        }
        val totalTarget = active.sumOf { it.target.pence }
        for (day in result.days) {
            val expected = SinkingFunds.totalReserved(input.sinkingFunds, day.date)
            if (day.reserved != expected || day.reserved.pence !in 0..maxOf(0L, totalTarget)) {
                f.add("funds.reserved", "on ${day.date}: ${day.reserved} (recomputed $expected, targets $totalTarget)")
                break
            }
        }
    }

    // --- safe to spend (independent recomputation) ---------------------------------------------------------------------

    private fun safeToSpend(input: ForecastInput, result: ForecastResult, f: Failures) {
        val sts = result.safeToSpend
        val today = input.today
        val horizon = input.settings.forecastHorizonDays

        f.expect("sts.weeklyNonNegative", sts.weekly.pence >= 0) { "weekly ${sts.weekly}" }
        f.expect("sts.shortfallNonNegative", sts.shortfall.pence >= 0) { "shortfall ${sts.shortfall}" }
        f.expect("sts.shortfallZeroesWeekly", !(sts.shortfall.pence > 0) || sts.weekly.pence == 0L) { "shortfall ${sts.shortfall} but weekly ${sts.weekly}" }
        f.expect("sts.perDay", abs(sts.perDay.pence * 7 - sts.weekly.pence) <= 7 && sts.perDay.pence >= 0) { "perDay ${sts.perDay} weekly ${sts.weekly}" }
        f.expect("sts.horizonEnd", sts.horizonEnd == result.forecastEnd) { "horizonEnd ${sts.horizonEnd}" }
        f.expect("sts.predictedNonNegative", sts.predictedWeeklySpending.pence >= 0) { "predicted ${sts.predictedWeeklySpending}" }

        val nextMajor = result.committedFlows.firstOrNull { it.isMajorIncome && it.amount.isPositive && it.date.isAfter(today) }
        f.expect("sts.nextMajorIncome", sts.nextMajorIncome == nextMajor) { "${sts.nextMajorIncome} vs $nextMajor" }
        val daysUntil = nextMajor?.let { ChronoUnit.DAYS.between(today, it.date).toInt() }
        f.expect("sts.daysUntilNextMajor", sts.daysUntilNextMajorIncome == daysUntil) { "${sts.daysUntilNextMajorIncome} vs $daysUntil" }

        // h(d) = pool + committed + restricted learned - reserved - floor - buffer; weekly = min over d of 7 h(d) / k(d).
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val estimateByKey = result.categoryEstimates.associateBy { it.categoryId to it.regime }
        var cumCommitted = 0L
        var cumLearned = 0L
        var bestH = 0L
        var bestK = 0L
        var bestIndex = -1
        var minH = Long.MAX_VALUE
        var minFloored = Long.MAX_VALUE
        for (k in 1..horizon) {
            val day = result.days[k]
            cumCommitted += day.committedNet.pence
            var restricted = 0L
            for (category in learned) {
                val weight = when (category.flow) {
                    Flow.OUT -> continue
                    Flow.IN -> 0.75
                    Flow.BOTH -> 1.0
                }
                val estimate = estimateByKey[category.id to Regimes.regimeOf(day.date, input.termPeriods)] ?: continue
                val amount = Math.round(estimate.weeklyAmount.pence * estimate.dayOfWeekShares[day.date.dayOfWeek.value - 1])
                restricted += Math.round(amount * weight)
            }
            cumLearned += restricted
            val h = result.poolBalanceNow.pence + cumCommitted + cumLearned - day.reserved.pence - result.floor.pence - input.settings.buffer.pence
            minH = minOf(minH, h)
            minFloored = minOf(minFloored, Math.floorDiv(7 * h, k.toLong()))
            // Strictly smaller 7h/k wins; the first day keeps ties. h1/k1 < h2/k2 <=> h1*k2 < h2*k1 (k > 0).
            if (bestIndex < 0 || h * bestK < bestH * k) {
                bestH = h
                bestK = k.toLong()
                bestIndex = k
            }
        }
        val expectedWeekly = maxOf(0L, minFloored)
        f.expect("sts.weekly", sts.weekly.pence == expectedWeekly) { "weekly ${sts.weekly} vs recomputed ${Money(expectedWeekly)}" }
        f.expect("sts.shortfall", sts.shortfall.pence == maxOf(0L, -minH)) { "shortfall ${sts.shortfall} vs recomputed ${Money(maxOf(0L, -minH))}" }
        f.expect("sts.bindingDate", sts.bindingDate == today.plusDays(bestIndex.toLong())) { "bindingDate ${sts.bindingDate} vs recomputed ${today.plusDays(bestIndex.toLong())}" }

        val expectedReason = bindingReason(sts.bindingDate, result, today)
        f.expect("sts.bindingReason", sts.bindingReason == expectedReason) { "'${sts.bindingReason}' vs recomputed '$expectedReason'" }

        val regimeToday = Regimes.regimeOf(today, input.termPeriods)
        val predicted = learned.filter { it.flow == Flow.OUT }.sumOf { abs((estimateByKey[it.id to regimeToday]?.weeklyAmount ?: Money.ZERO).pence) }
        f.expect("sts.predictedWeekly", sts.predictedWeeklySpending.pence == predicted) { "${sts.predictedWeeklySpending} vs $predicted" }
    }

    private fun bindingReason(bindingDate: LocalDate?, result: ForecastResult, today: LocalDate): String? {
        if (bindingDate == null) return null
        val outflow = result.committedFlows
            .filter { !it.date.isBefore(bindingDate.minusDays(2)) && !it.date.isAfter(bindingDate) && it.amount.isNegative }
            .minByOrNull { it.amount.pence }
        if (outflow != null) return "${outflow.label} on ${BINDING_DATE_FORMAT.format(outflow.date)}"
        val income = result.committedFlows
            .filter { it.isMajorIncome && it.amount.isPositive && it.date == bindingDate.plusDays(1) }
            .maxByOrNull { it.amount.pence }
        if (income != null) return "Until ${income.label} on ${BINDING_DATE_FORMAT.format(income.date)}"
        if (bindingDate == result.forecastEnd) {
            val weeks = maxOf(1L, Math.round(ChronoUnit.DAYS.between(today, result.forecastEnd) / 7.0))
            return if (weeks == 1L) "Over the next week" else "Over the next $weeks weeks"
        }
        return null
    }
}
