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
        const val DEFAULT_COUNT = 2000
        const val SLOWEST_ALLOWED_NANOS = 3_000_000_000L
    }
}
