package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.forecast.AccountDayForecast
import io.github.matteopi.budgeting.core.forecast.ForecastEngine
import io.github.matteopi.budgeting.core.forecast.ForecastInput
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Property-based stress test of [ForecastEngine]: [DEFAULT_COUNT] seeded random inputs (see
 * [InputGenerator]), each run through the engine several times and checked against
 * [ForecastInvariants] plus the metamorphic relations below.
 *
 * Replay: `STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*ForecastStressTest*'`.
 * `STRESS_COUNT` also scales the run up (say 20000) for a longer soak.
 */
class ForecastStressTest {

    private class CaseOutcome(val failures: List<CaseFailure>)

    private val engineRuns = AtomicLong()
    private val engineNanos = AtomicLong()
    private val slowestNanos = AtomicLong()

    @Test
    fun generatedInputsUpholdEveryInvariantAndMetamorphicRelation() {
        val firstSeed = env("STRESS_SEED", 1L)
        val count = env("STRESS_COUNT", DEFAULT_COUNT.toLong()).toInt()
        val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(threads)
        val begin = System.nanoTime()
        val outcomes = try {
            (0 until count).map { i -> executor.submit(Callable { runCase(firstSeed + i) }) }.map { it.get() }
        } finally {
            executor.shutdown()
        }
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        println(
            "ForecastStressTest: $count generated inputs, ${engineRuns.get()} engine runs on $threads threads in ${elapsedMs}ms " +
                "(mean ${engineNanos.get() / maxOf(1L, engineRuns.get()) / 1_000_000}ms, slowest ${slowestNanos.get() / 1_000_000}ms per run)",
        )
        val failures = outcomes.flatMap { it.failures }
        if (failures.isNotEmpty()) {
            fail(
                StressReport.render(
                    "ForecastStressTest", failures,
                    describe = { seed -> InputGenerator.generate(seed).summary },
                    replayHint = "STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*ForecastStressTest*'",
                ),
            )
        }
        assertTrue(count >= 1)
    }

    @Test
    fun generatorIsDeterministicPerSeed() {
        for (seed in listOf(1L, 2L, 17L, 4242L)) {
            assertEquals(InputGenerator.generate(seed).input, InputGenerator.generate(seed).input, "seed $seed")
        }
    }

    // --- one generated case ---------------------------------------------------------------------------

    private fun runCase(seed: Long): CaseOutcome {
        val f = Failures()
        try {
            checkCase(seed, f)
        } catch (t: Throwable) {
            f.add("case.exception", "${t::class.simpleName}: ${t.message}\n" + t.stackTrace.take(8).joinToString("\n"))
        }
        return CaseOutcome(f.items.map { CaseFailure(seed, it.check, it.detail) })
    }

    private fun run(input: ForecastInput, f: Failures, label: String): ForecastResult? {
        val start = System.nanoTime()
        val result = try {
            ForecastEngine.run(input)
        } catch (t: Throwable) {
            f.add("run.exception", "$label: ${t::class.simpleName}: ${t.message}\n" + t.stackTrace.take(8).joinToString("\n"))
            return null
        }
        val nanos = System.nanoTime() - start
        engineRuns.incrementAndGet()
        engineNanos.addAndGet(nanos)
        slowestNanos.accumulateAndGet(nanos) { a, b -> maxOf(a, b) }
        f.expect("run.slow", nanos <= SLOWEST_ALLOWED_NANOS) { "$label took ${nanos / 1_000_000}ms (limit ${SLOWEST_ALLOWED_NANOS / 1_000_000}ms)" }
        return result
    }

    private fun checkCase(seed: Long, f: Failures) {
        val generated = InputGenerator.generate(seed)
        val mcInput = generated.input
        val offInput = mcInput.copy(runMonteCarlo = false)

        // Monte Carlo on: completes, deterministic, invariants hold.
        val first = run(mcInput, f, "monte carlo run 1") ?: return
        val second = run(mcInput, f, "monte carlo run 2")
        if (second != null) f.expect("determinism", first == second) { "two runs of the same input differ" }
        ForecastInvariants.check(mcInput, first, f)

        // Monte Carlo off: same everything except the percentiles and the risk summary.
        val off = run(offInput, f, "no-monte-carlo run") ?: return
        ForecastInvariants.check(offInput, off, f)
        f.expect("mc.doesNotChangeTheRest", stripMonteCarlo(first) == off) { "results differ beyond percentiles/risk between MC on and off" }

        metamorphic(seed, offInput, off, f)
        moreMetamorphic(seed, offInput, off, f)
        zeroPointUnderMonteCarlo(mcInput, first, f)
    }

    private fun stripMonteCarlo(result: ForecastResult): ForecastResult = result.copy(
        days = result.days.map { it.copy(p10 = null, p50 = null, p90 = null) },
        risk = null,
        accountForecasts = result.accountForecasts.map { account ->
            account.copy(days = account.days.map { it.copy(p10 = null, p50 = null, p90 = null) })
        },
    )

    // --- metamorphic relations --------------------------------------------------------------------------

    private fun metamorphic(seed: Long, base: ForecastInput, baseResult: ForecastResult, f: Failures) {
        val random = Random(seed xor 0x5DEECE66DL)
        val today = base.today
        val poolAccount = base.accounts.firstOrNull { it.includeInSafeToSpend && !it.archived }
        val baseSts = baseResult.safeToSpend

        if (poolAccount != null) {
            val pence = 100L + random.nextLong(200_000)
            fun injected(amount: Long, mode: PostingMode, major: Boolean) = ScheduledItem(
                id = 9_000_001L, label = "Injected", accountId = poolAccount.id, categoryId = null, amount = Money(amount),
                date = today.plusDays(1), postingMode = mode, isMajorIncome = major,
            )

            // A scheduled outflow tomorrow never raises safe-to-spend and never lowers the shortfall.
            val outflowInput = base.copy(scheduledItems = base.scheduledItems + injected(-pence, PostingMode.AUTO, false))
            run(outflowInput, f, "outflow variant")?.let { out ->
                ForecastInvariants.check(outflowInput, out, f)
                f.expect("meta.outflow.weekly", out.safeToSpend.weekly <= baseSts.weekly) { "weekly ${baseSts.weekly} -> ${out.safeToSpend.weekly} after -${Money(pence)}" }
                f.expect("meta.outflow.shortfall", out.safeToSpend.shortfall >= baseSts.shortfall) { "shortfall ${baseSts.shortfall} -> ${out.safeToSpend.shortfall} after -${Money(pence)}" }
                shiftedBy(baseResult, out, poolAccount.id, -pence, f, "outflow")
            }

            // A scheduled income tomorrow never lowers safe-to-spend and never raises the shortfall.
            val incomeInput = base.copy(
                scheduledItems = base.scheduledItems + injected(pence, if (random.nextBoolean()) PostingMode.AUTO else PostingMode.CONFIRM, random.nextInt(4) == 0),
            )
            run(incomeInput, f, "income variant")?.let { inc ->
                ForecastInvariants.check(incomeInput, inc, f)
                f.expect("meta.income.weekly", inc.safeToSpend.weekly >= baseSts.weekly) { "weekly ${baseSts.weekly} -> ${inc.safeToSpend.weekly} after +${Money(pence)}" }
                f.expect("meta.income.shortfall", inc.safeToSpend.shortfall <= baseSts.shortfall) { "shortfall ${baseSts.shortfall} -> ${inc.safeToSpend.shortfall} after +${Money(pence)}" }
                shiftedBy(baseResult, inc, poolAccount.id, pence, f, "income")
            }
        }

        // A bigger buffer never raises safe-to-spend and never lowers the shortfall.
        val bigger = Money(1L + random.nextLong(50_000))
        val bufferInput = base.copy(settings = base.settings.copy(buffer = base.settings.buffer + bigger))
        run(bufferInput, f, "buffer variant")?.let { buf ->
            ForecastInvariants.check(bufferInput, buf, f)
            f.expect("meta.buffer.weekly", buf.safeToSpend.weekly <= baseSts.weekly) { "weekly ${baseSts.weekly} -> ${buf.safeToSpend.weekly} after +${bigger} buffer" }
            f.expect("meta.buffer.shortfall", buf.safeToSpend.shortfall >= baseSts.shortfall) { "shortfall ${baseSts.shortfall} -> ${buf.safeToSpend.shortfall} after +${bigger} buffer" }
        }

        // Counting the overdraft as spendable never lowers safe-to-spend (ACCOUNT_BALANCE -> OVERDRAFT_LIMIT).
        val atZero = base.copy(settings = base.settings.copy(zeroPoint = ZeroPoint.ACCOUNT_BALANCE))
        val atOverdraft = base.copy(settings = base.settings.copy(zeroPoint = ZeroPoint.OVERDRAFT_LIMIT))
        val zeroResult = if (base.settings.zeroPoint == ZeroPoint.ACCOUNT_BALANCE) baseResult else run(atZero, f, "zero-point variant (balance)")
        val overdraftResult = if (base.settings.zeroPoint == ZeroPoint.OVERDRAFT_LIMIT) baseResult else run(atOverdraft, f, "zero-point variant (overdraft)")
        if (zeroResult != null && overdraftResult != null) {
            if (zeroResult !== baseResult) ForecastInvariants.check(atZero, zeroResult, f)
            if (overdraftResult !== baseResult) ForecastInvariants.check(atOverdraft, overdraftResult, f)
            f.expect("meta.zeroPoint.weekly", overdraftResult.safeToSpend.weekly >= zeroResult.safeToSpend.weekly) {
                "weekly ${zeroResult.safeToSpend.weekly} (balance) -> ${overdraftResult.safeToSpend.weekly} (overdraft limit)"
            }
            f.expect("meta.zeroPoint.shortfall", overdraftResult.safeToSpend.shortfall <= zeroResult.safeToSpend.shortfall) {
                "shortfall ${zeroResult.safeToSpend.shortfall} (balance) -> ${overdraftResult.safeToSpend.shortfall} (overdraft limit)"
            }
        }
    }

    // --- more relations: things that must not matter, and monotone knobs ---------------------------------------------------

    private fun moreMetamorphic(seed: Long, base: ForecastInput, baseResult: ForecastResult, f: Failures) {
        val random = Random(seed xor 0x2545F4914F6CDD1DL)
        val today = base.today
        val forecastEnd = today.plusDays(base.settings.forecastHorizonDays.toLong())
        val account = base.accounts.first()

        // Things the engine must ignore leave the whole result untouched.
        val inertItems = listOf(
            ScheduledItem(id = 9_100_001L, label = "Posted", accountId = account.id, categoryId = null, amount = Money(-12_345), date = today.plusDays(3), postingMode = PostingMode.AUTO, posted = true),
            ScheduledItem(id = 9_100_002L, label = "Skipped", accountId = account.id, categoryId = null, amount = Money(54_321), date = today.plusDays(4), postingMode = PostingMode.CONFIRM, isMajorIncome = true, skipped = true),
            ScheduledItem(id = 9_100_003L, label = "Beyond", accountId = account.id, categoryId = null, amount = Money(-99_999), date = forecastEnd.plusDays(1), postingMode = PostingMode.AUTO),
        )
        val inertRules = listOf(
            io.github.matteopi.budgeting.core.model.RecurringRule(
                id = 9_100_001L, label = "Inactive", accountId = account.id, categoryId = null, amount = Money(-777),
                frequency = io.github.matteopi.budgeting.core.model.Frequency.DAILY, startDate = today.plusDays(1),
                postingMode = PostingMode.AUTO, active = false,
            ),
            io.github.matteopi.budgeting.core.model.RecurringRule(
                id = 9_100_002L, label = "Later", accountId = account.id, categoryId = null, amount = Money(-888),
                frequency = io.github.matteopi.budgeting.core.model.Frequency.DAILY, startDate = forecastEnd.plusDays(1),
                postingMode = PostingMode.AUTO, isMajorIncome = true,
            ),
        )
        val inertFunds = listOf(
            io.github.matteopi.budgeting.core.model.SinkingFund(
                id = 9_100_001L, name = "Cancelled", target = Money(50_000), startDate = today.minusDays(5), targetDate = today.plusDays(9),
                status = io.github.matteopi.budgeting.core.model.SinkingFundStatus.CANCELLED,
            ),
            io.github.matteopi.budgeting.core.model.SinkingFund(
                id = 9_100_002L, name = "Completed", target = Money(60_000), startDate = today.minusDays(5), targetDate = today.plusDays(9),
                status = io.github.matteopi.budgeting.core.model.SinkingFundStatus.COMPLETED,
            ),
        )
        val padded = base.copy(
            scheduledItems = base.scheduledItems + inertItems,
            recurringRules = base.recurringRules + inertRules,
            sinkingFunds = base.sinkingFunds + inertFunds,
        )
        run(padded, f, "inert additions")?.let { result ->
            f.expect("meta.inert", result == baseResult) { "posted/skipped items, an inactive rule, a rule starting after the horizon, an item beyond it or a cancelled/completed fund changed the result" }
        }

        // The order of the input lists does not matter to any number in the result.
        val shuffled = base.copy(
            accounts = base.accounts.shuffled(random),
            transactions = base.transactions.shuffled(random),
            recurringRules = base.recurringRules.shuffled(random),
            scheduledItems = base.scheduledItems.shuffled(random),
            termPeriods = base.termPeriods.shuffled(random),
            sinkingFunds = base.sinkingFunds.shuffled(random),
            reconciliations = base.reconciliations.shuffled(random),
        )
        run(shuffled, f, "shuffled lists")?.let { result ->
            val same = result.days == baseResult.days && result.accountForecasts == baseResult.accountForecasts &&
                result.budgetPeriods == baseResult.budgetPeriods && result.categoryEstimates == baseResult.categoryEstimates &&
                result.needsPrediction == baseResult.needsPrediction && result.tier1WeeklyByCategory == baseResult.tier1WeeklyByCategory &&
                result.poolBalanceNow == baseResult.poolBalanceNow && result.historyStart == baseResult.historyStart &&
                result.completeWeeksOfHistory == baseResult.completeWeeksOfHistory &&
                result.fundReservations.sortedBy { it.fundId } == baseResult.fundReservations.sortedBy { it.fundId } &&
                result.safeToSpend.weekly == baseResult.safeToSpend.weekly && result.safeToSpend.perDay == baseResult.safeToSpend.perDay &&
                result.safeToSpend.shortfall == baseResult.safeToSpend.shortfall && result.safeToSpend.bindingDate == baseResult.safeToSpend.bindingDate
            f.expect("meta.order", same) { "shuffling the input lists changed a number in the result" }
        }

        // Splitting a past transaction into two of the same sign changes nothing.
        val splittable = base.transactions.filter {
            it.kind == io.github.matteopi.budgeting.core.model.TxKind.NORMAL && it.recurringRuleId == null && it.scheduledItemId == null &&
                !it.date.isAfter(today) && it.amount.abs().pence >= 2
        }
        if (splittable.isNotEmpty()) {
            val target = splittable[random.nextInt(splittable.size)]
            val part = 1L + random.nextLong(target.amount.abs().pence - 1)
            val sign = if (target.amount.isNegative) -1 else 1
            val nextId = (base.transactions.maxOf { it.id }) + 1
            val halves = listOf(
                target.copy(amount = Money(sign * part)),
                target.copy(id = nextId, amount = Money(sign * (target.amount.abs().pence - part))),
            )
            val split = base.copy(transactions = base.transactions.filter { it !== target } + halves)
            run(split, f, "split transaction")?.let { result ->
                f.expect("meta.split", result == baseResult) { "splitting ${target.amount} on ${target.date} (account ${target.accountId}, category ${target.categoryId}) into ${halves.map { it.amount }} changed the result" }
            }
        }

        // Predictions for a discretionary (learned OUT) category: more predicted spending never raises a balance
        // and never moves safe-to-spend, which leaves discretionary spending out of the headroom.
        val learnedOut = base.categories.firstOrNull {
            it.forecastMode == io.github.matteopi.budgeting.core.model.ForecastMode.LEARNED && it.flow == io.github.matteopi.budgeting.core.model.Flow.OUT
        }
        if (learnedOut != null) {
            fun withPrediction(weeklyPence: Long): ForecastInput = base.copy(
                predictions = base.predictions.filter { it.categoryId != learnedOut.id } + io.github.matteopi.budgeting.core.model.Regime.entries.mapIndexed { i, regime ->
                    io.github.matteopi.budgeting.core.model.Prediction(
                        id = 9_100_000L + i, categoryId = learnedOut.id, regime = regime,
                        mode = io.github.matteopi.budgeting.core.model.PredictionMode.REPLACE, weeklyAmount = Money(weeklyPence),
                    )
                },
            )
            val low = random.nextLong(0, 20_000)
            val high = low + 1 + random.nextLong(30_000)
            val lowInput = withPrediction(low)
            val highInput = withPrediction(high)
            val lowResult = run(lowInput, f, "low prediction")
            val highResult = run(highInput, f, "high prediction")
            if (lowResult != null && highResult != null) {
                ForecastInvariants.check(lowInput, lowResult, f)
                ForecastInvariants.check(highInput, highResult, f)
                val moreSpending = lowResult.days.zip(highResult.days).all { (l, h) -> h.expectedBalance <= l.expectedBalance }
                f.expect("meta.prediction.balances", moreSpending) { "predicting ${Money(high)} instead of ${Money(low)} a week for category ${learnedOut.id} raised a balance" }
                f.expect("meta.prediction.safeToSpend", lowResult.safeToSpend.weekly == highResult.safeToSpend.weekly && lowResult.safeToSpend.shortfall == highResult.safeToSpend.shortfall && lowResult.safeToSpend.bindingDate == highResult.safeToSpend.bindingDate) {
                    "discretionary predictions moved safe-to-spend: ${lowResult.safeToSpend.weekly} vs ${highResult.safeToSpend.weekly}"
                }
                f.expect("meta.prediction.committed", lowResult.committedFlows == highResult.committedFlows && lowResult.budgetPeriods.map { it.committedOut to it.committedIn } == highResult.budgetPeriods.map { it.committedOut to it.committedIn }) {
                    "discretionary predictions changed committed flows"
                }
            }
        }
    }

    /**
     * The zero point only moves the floor: the simulated paths are the same, the chance of going past the
     * overdraft is the same, and the chance of dipping below the floor can only fall when the floor drops.
     */
    private fun zeroPointUnderMonteCarlo(mcInput: ForecastInput, mcResult: ForecastResult, f: Failures) {
        val other = if (mcInput.settings.zeroPoint == ZeroPoint.ACCOUNT_BALANCE) ZeroPoint.OVERDRAFT_LIMIT else ZeroPoint.ACCOUNT_BALANCE
        val otherInput = mcInput.copy(settings = mcInput.settings.copy(zeroPoint = other))
        val otherResult = run(otherInput, f, "zero point $other under Monte Carlo") ?: return
        f.expect("meta.zeroPoint.paths", otherResult.days.map { Triple(it.p10, it.p50, it.p90) } == mcResult.days.map { Triple(it.p10, it.p50, it.p90) } &&
            otherResult.accountForecasts == mcResult.accountForecasts) { "the zero point changed simulated paths" }
        val risk = mcResult.risk
        val otherRisk = otherResult.risk
        if (risk == null || otherRisk == null) {
            f.add("meta.zeroPoint.risk", "missing risk summary")
            return
        }
        f.expect("meta.zeroPoint.beyondOverdraft", risk.probBeyondOverdraft == otherRisk.probBeyondOverdraft) { "${risk.probBeyondOverdraft} vs ${otherRisk.probBeyondOverdraft}" }
        val (atZero, atOverdraft) = if (mcInput.settings.zeroPoint == ZeroPoint.ACCOUNT_BALANCE) risk to otherRisk else otherRisk to risk
        f.expect("meta.zeroPoint.belowFloor", atOverdraft.probBelowFloor <= atZero.probBelowFloor) {
            "chance below the floor ${atZero.probBelowFloor} (balance) -> ${atOverdraft.probBelowFloor} (overdraft limit)"
        }
    }

    /**
     * Adding a flat [delta] on tomorrow to [accountId] shifts the pool and that account's expected
     * balance by exactly [delta] on every day after today and touches nothing else.
     */
    private fun shiftedBy(base: ForecastResult, variant: ForecastResult, accountId: Long, delta: Long, f: Failures, name: String) {
        f.guard("meta.$name.balances") {
            for (i in 1 until base.days.size) {
                if (variant.days[i].expectedBalance.pence != base.days[i].expectedBalance.pence + delta) {
                    f.add("meta.$name.poolShift", "day $i: ${base.days[i].expectedBalance} -> ${variant.days[i].expectedBalance}, expected a shift of ${Money(delta)}")
                    break
                }
            }
            f.expect("meta.$name.poolNow", variant.poolBalanceNow == base.poolBalanceNow) { "poolBalanceNow moved" }
            f.expect("meta.$name.categories", variant.categoryEstimates == base.categoryEstimates) { "category estimates changed" }
            for ((b, v) in base.accountForecasts.zip(variant.accountForecasts)) {
                val shift = if (b.accountId == accountId) delta else 0L
                for (i in 1 until b.days.size) {
                    if (v.days[i].expectedBalance.pence != b.days[i].expectedBalance.pence + shift) {
                        f.add("meta.$name.accountShift", "account ${b.accountId} day $i: ${describe(b.days[i])} -> ${describe(v.days[i])}, expected shift ${Money(shift)}")
                        return@guard
                    }
                }
            }
        }
    }

    private fun describe(day: AccountDayForecast) = day.expectedBalance.toString()

    private companion object {
        const val DEFAULT_COUNT = 2500
        const val SLOWEST_ALLOWED_NANOS = 3_000_000_000L
    }
}
