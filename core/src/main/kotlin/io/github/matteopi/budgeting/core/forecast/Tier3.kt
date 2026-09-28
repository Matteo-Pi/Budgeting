package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Regime
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Result of the tier-3 Monte Carlo simulation. */
data class Tier3Output(
    /** Aligned with the engine's `today+1 .. forecastEnd` day list. */
    val p10ByDay: List<Money>,
    val p50ByDay: List<Money>,
    val p90ByDay: List<Money>,
    val risk: RiskSummary,
)

/**
 * Tier 3: a whole-week bootstrap Monte Carlo simulation of the pool balance path, with a
 * parametric (lognormal) fallback when there isn't enough history to bootstrap from. See
 * `docs/PLAN.md` §5.9.
 */
object Tier3 {
    fun run(
        input: ForecastInput,
        history: History,
        estimateByKey: Map<Pair<Long, Regime>, CategoryEstimate>,
        dayOfWeekShares: Map<Long, List<Double>>,
        forecastDays: List<LocalDate>,
        committedNetByDay: List<Money>,
        poolBalanceNow: Money,
        floor: Money,
        poolOverdraft: Money,
        horizonEnd: LocalDate,
    ): Tier3Output {
        val terms = input.termPeriods
        val learned = input.categories.filter { it.forecastMode == ForecastMode.LEARNED }
        val nCategories = learned.size
        val days = forecastDays.size
        val simulations = maxOf(1, input.settings.simulations)
        val random = Random(input.seed)

        // --- Per-category static data (flow direction, weekly estimates, parametric CV, DoW shares). ---
        val flowCode = IntArray(nCategories) // 0 = OUT, 1 = IN, 2 = BOTH
        val eTerm = LongArray(nCategories)
        val eHoliday = LongArray(nCategories)
        val cvTerm = DoubleArray(nCategories)
        val cvHoliday = DoubleArray(nCategories)
        val shares = Array(nCategories) { DoubleArray(7) }
        for ((idx, c) in learned.withIndex()) {
            flowCode[idx] = when (c.flow) {
                Flow.OUT -> 0
                Flow.IN -> 1
                Flow.BOTH -> 2
            }
            val termEst = estimateByKey[c.id to Regime.TERM]
            val holidayEst = estimateByKey[c.id to Regime.HOLIDAY]
            eTerm[idx] = (termEst?.weeklyAmount ?: Money.ZERO).pence
            eHoliday[idx] = (holidayEst?.weeklyAmount ?: Money.ZERO).pence
            cvTerm[idx] = cvFor(termEst)
            cvHoliday[idx] = cvFor(holidayEst)
            val s = dayOfWeekShares[c.id] ?: Tier2.UNIFORM_SHARES
            for (i in 0..6) shares[idx][i] = s[i]
        }

        // --- Historical weekly pence per category, aligned to history.weeks. ---
        val historyWeeks = history.weeks.size
        val weeklyPence = Array(nCategories) { idx ->
            val list = history.weeklyByCategory[learned[idx].id]
            LongArray(historyWeeks) { w -> list?.get(w)?.pence ?: 0L }
        }

        // --- Bootstrap pools per regime, decided once (static: doesn't depend on randomness). ---
        val termPoolIdx = history.weeks.indices.filter { history.weeks[it].regime == Regime.TERM }
        val holidayPoolIdx = history.weeks.indices.filter { history.weeks[it].regime == Regime.HOLIDAY }
        val allPoolIdx = history.weeks.indices.toList()
        fun poolFor(regime: Regime): IntArray? {
            val regimePool = if (regime == Regime.TERM) termPoolIdx else holidayPoolIdx
            return when {
                regimePool.size >= ForecastConstants.BOOTSTRAP_MIN_POOL -> regimePool.toIntArray()
                allPoolIdx.size >= ForecastConstants.BOOTSTRAP_MIN_POOL -> allPoolIdx.toIntArray()
                else -> null
            }
        }
        val termPool = poolFor(Regime.TERM)
        val holidayPool = poolFor(Regime.HOLIDAY)

        fun meanOf(pool: IntArray?, categoryIdx: Int): Double {
            if (pool == null || pool.isEmpty()) return 0.0
            var sum = 0.0
            for (i in pool) sum += weeklyPence[categoryIdx][i]
            return sum / pool.size
        }
        val meanTerm = DoubleArray(nCategories) { meanOf(termPool, it) }
        val meanHoliday = DoubleArray(nCategories) { meanOf(holidayPool, it) }

        // --- Group forecast days into simulated Mon-Sun weeks (the first/last may be partial). ---
        val simWeeks = buildSimWeeks(forecastDays, terms)

        // --- Deterministic per-day committed flow, prefix-summed once (shared by every simulation). ---
        val cumCommitted = LongArray(days)
        run {
            var running = 0L
            for (i in 0 until days) {
                running += committedNetByDay[i].pence
                cumCommitted[i] = running
            }
        }

        val windowEndIdx = forecastDays.indexOfFirst { it == horizonEnd }

        // --- Simulate. ---
        val balances = Array(days) { LongArray(simulations) }
        val minInWindow = LongArray(simulations)
        val endBalance = LongArray(simulations)
        val dayPence = LongArray(days)

        for (s in 0 until simulations) {
            dayPence.fill(0L)
            for (week in simWeeks) {
                val pool = if (week.regime == Regime.TERM) termPool else holidayPool
                val picked: Int? = if (pool != null) pool[random.nextInt(pool.size)] else null

                for (c in 0 until nCategories) {
                    val flow = flowCode[c]
                    val e = if (week.regime == Regime.TERM) eTerm[c] else eHoliday[c]
                    val cv = if (week.regime == Regime.TERM) cvTerm[c] else cvHoliday[c]

                    var simPence = if (picked == null) {
                        parametric(e, cv, random)
                    } else {
                        val mean = if (week.regime == Regime.TERM) meanTerm[c] else meanHoliday[c]
                        when (flow) {
                            2 -> Math.round(e + (weeklyPence[c][picked] - mean)) // BOTH: additive
                            else -> if (abs(mean) >= ForecastConstants.MULTIPLICATIVE_MEAN_FLOOR_PENCE) {
                                Math.round(e * weeklyPence[c][picked] / mean)
                            } else {
                                parametric(e, cv, random)
                            }
                        }
                    }
                    simPence = when (flow) {
                        0 -> minOf(simPence, 0L) // OUT: never positive
                        1 -> maxOf(simPence, 0L) // IN: never negative
                        else -> simPence
                    }

                    if (simPence != 0L) {
                        val dayShares = shares[c]
                        val dayIndices = week.dayIndices
                        val offsets = week.dayOfWeekOffsets
                        for (k in dayIndices.indices) {
                            dayPence[dayIndices[k]] += Math.round(simPence * dayShares[offsets[k]])
                        }
                    }
                }
            }

            var learnedRunning = 0L
            var minSeen = Long.MAX_VALUE
            for (dIdx in 0 until days) {
                learnedRunning += dayPence[dIdx]
                val bal = poolBalanceNow.pence + cumCommitted[dIdx] + learnedRunning
                balances[dIdx][s] = bal
                if (windowEndIdx >= 0 && dIdx <= windowEndIdx && bal < minSeen) minSeen = bal
            }
            minInWindow[s] = if (windowEndIdx >= 0) minSeen else poolBalanceNow.pence
            endBalance[s] = if (windowEndIdx >= 0) balances[windowEndIdx][s] else poolBalanceNow.pence
        }

        // --- Percentiles per day. ---
        val p10 = ArrayList<Money>(days)
        val p50 = ArrayList<Money>(days)
        val p90 = ArrayList<Money>(days)
        val column = LongArray(simulations)
        for (dIdx in 0 until days) {
            System.arraycopy(balances[dIdx], 0, column, 0, simulations)
            column.sort()
            p10 += Money(column[percentileIndex(0.10, simulations)])
            p50 += Money(column[percentileIndex(0.50, simulations)])
            p90 += Money(column[percentileIndex(0.90, simulations)])
        }

        // --- Risk summary over today+1 .. horizonEnd. ---
        val floorPence = floor.pence
        val overdraftBreachPence = -poolOverdraft.pence
        var belowFloorCount = 0
        var beyondOverdraftCount = 0
        for (s in 0 until simulations) {
            if (minInWindow[s] < floorPence) belowFloorCount++
            if (minInWindow[s] < overdraftBreachPence) beyondOverdraftCount++
        }
        var firstBreach: LocalDate? = null
        if (windowEndIdx >= 0) {
            for (dIdx in 0..windowEndIdx) {
                if (p10[dIdx].pence < floorPence) {
                    firstBreach = forecastDays[dIdx]
                    break
                }
            }
        }
        val (endP10, endP50, endP90) = if (windowEndIdx >= 0) {
            Triple(p10[windowEndIdx], p50[windowEndIdx], p90[windowEndIdx])
        } else {
            Triple(poolBalanceNow, poolBalanceNow, poolBalanceNow)
        }

        val risk = RiskSummary(
            probBelowFloor = belowFloorCount.toDouble() / simulations,
            probBeyondOverdraft = beyondOverdraftCount.toDouble() / simulations,
            firstLikelyBreach = firstBreach,
            endBalanceP10 = endP10,
            endBalanceP50 = endP50,
            endBalanceP90 = endP90,
        )

        return Tier3Output(p10, p50, p90, risk)
    }

    private fun cvFor(estimate: CategoryEstimate?): Double =
        if (estimate?.source == EstimateSource.PREDICTION_REPLACE) {
            ForecastConstants.PARAMETRIC_CV_REPLACE
        } else {
            ForecastConstants.PARAMETRIC_CV
        }
}

/** One simulated Mon-Sun week: its regime, and which forecast-day array indices fall inside it. */
internal class SimWeek(val regime: Regime, val dayIndices: IntArray, val dayOfWeekOffsets: IntArray)

/** Groups a contiguous ascending list of dates into calendar weeks. A first/last partial week
 * only carries the day indices that actually fall inside [forecastDays]. */
internal fun buildSimWeeks(forecastDays: List<LocalDate>, terms: List<io.github.matteopi.budgeting.core.model.TermPeriod>): List<SimWeek> {
    val result = mutableListOf<SimWeek>()
    var i = 0
    val days = forecastDays.size
    while (i < days) {
        val weekStart = Regimes.weekStart(forecastDays[i])
        val indices = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()
        var j = i
        while (j < days && Regimes.weekStart(forecastDays[j]) == weekStart) {
            indices += j
            offsets += forecastDays[j].dayOfWeek.value - 1
            j++
        }
        result += SimWeek(Regimes.regimeOfWeek(weekStart, terms), indices.toIntArray(), offsets.toIntArray())
        i = j
    }
    return result
}

/** `floor(q * (S-1))`, clamped to a valid array index. */
internal fun percentileIndex(q: Double, simulations: Int): Int {
    val idx = kotlin.math.floor(q * (simulations - 1)).toInt()
    return idx.coerceIn(0, simulations - 1)
}

/**
 * Lognormal parametric fallback: magnitude `|sim| = exp(μ + σZ)` with `σ = sqrt(ln(1+CV²))`,
 * `μ = ln|e| − σ²/2`, `Z` a standard normal draw (Box–Muller) from [random]. Sign follows [e];
 * `e == 0` returns 0 without consuming any randomness.
 */
internal fun parametric(e: Long, cv: Double, random: Random): Long {
    if (e == 0L) return 0L
    val sigma = sqrt(ln(1.0 + cv * cv))
    val mu = ln(abs(e).toDouble()) - sigma * sigma / 2.0
    val z = nextGaussian(random)
    val magnitude = exp(mu + sigma * z)
    val signed = if (e < 0) -magnitude else magnitude
    return Math.round(signed)
}

/** Standard normal draw via Box–Muller, using [Random.nextDouble] (never `java.util.Random`). */
internal fun nextGaussian(random: Random): Double {
    var u1: Double
    do {
        u1 = random.nextDouble()
    } while (u1 <= 1e-12)
    val u2 = random.nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
}
