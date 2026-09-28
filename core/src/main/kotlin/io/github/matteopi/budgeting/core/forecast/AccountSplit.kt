package io.github.matteopi.budgeting.core.forecast

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import java.time.temporal.ChronoUnit

/**
 * Splits the pool forecast per account. See `docs/PLAN.md` §5.11a.
 *
 * Committed flows go to the account they name (see [CommittedFlows.buildWithAccountFlows]);
 * learned flows go to pool accounts only, split per category by that category's historical
 * share per account. Every split uses largest-remainder rounding, so the pool accounts'
 * expected balances add up to the pool's [DayForecast.expectedBalance] to the penny.
 */
object AccountSplit {
    /**
     * The account that receives flows and learned spending not tied to an account: the
     * configured `defaultAccountId` if it is a non-archived pool account, else the pool account
     * with the smallest id, else the smallest-id non-archived account (null if there is none).
     */
    fun defaultAccountId(accounts: List<Account>, settings: BudgetSettings): Long? {
        val pool = Balances.pool(accounts)
        val configured = settings.defaultAccountId
        if (configured != null && pool.any { it.id == configured }) return configured
        return pool.minOfOrNull { it.id } ?: accounts.filter { !it.archived }.minOfOrNull { it.id }
    }

    /**
     * Prepares everything per-account for the forecast days `today+1 .. today+days`.
     * [learned] must be the engine's learned categories in the same order Tier 3 uses.
     */
    internal fun plan(
        input: ForecastInput,
        history: History,
        learned: List<Category>,
        accountFlows: List<AccountFlow>,
        days: Int,
    ): AccountPlan {
        val today = input.today
        val accounts = input.accounts.filter { !it.archived }.sortedBy { it.id }
        val coveredIndex = HashMap<Long, Int>()
        for ((i, account) in accounts.withIndex()) coveredIndex.putIfAbsent(account.id, i)

        val balanceNow = LongArray(accounts.size) { Balances.accountBalance(accounts[it].id, input.transactions, today).pence }

        val committedByDay = Array(accounts.size) { LongArray(days) }
        for (flow in accountFlows) {
            val a = coveredIndex[flow.accountId] ?: continue
            val dayIndex = ChronoUnit.DAYS.between(today, flow.date).toInt() - 1
            if (dayIndex in 0 until days) committedByDay[a][dayIndex] += flow.amount.pence
        }

        val poolAccountIdx = accounts.indices.filter { accounts[it].includeInSafeToSpend }.toIntArray()
        val poolPosition = IntArray(accounts.size) { -1 }
        for ((p, a) in poolAccountIdx.withIndex()) poolPosition[a] = p

        val defaultId = defaultAccountId(input.accounts, input.settings)
        val defaultPoolPosition = defaultId?.let { id -> coveredIndex[id]?.let { poolPosition[it] } } ?: -1

        val categoryShares = Array(learned.size) { c ->
            categoryShares(learned[c], history, accounts, poolAccountIdx, defaultPoolPosition)
        }

        return AccountPlan(accounts, balanceNow, committedByDay, poolAccountIdx, poolPosition, categoryShares, defaultId, days)
    }

    /**
     * The category's split over the pool: each pool account's share of the category's absolute
     * amount in the history window; no history at all means 100% to the default account.
     */
    private fun categoryShares(
        category: Category,
        history: History,
        accounts: List<Account>,
        poolAccountIdx: IntArray,
        defaultPoolPosition: Int,
    ): CategoryShares {
        if (poolAccountIdx.isEmpty()) return CategoryShares(IntArray(0), DoubleArray(0))
        val byAccount = history.absByCategoryAccount[category.id].orEmpty()
        val weights = LongArray(poolAccountIdx.size) { p -> byAccount[accounts[poolAccountIdx[p]].id] ?: 0L }
        val total = weights.sum()
        if (total <= 0L) return CategoryShares(intArrayOf(maxOf(defaultPoolPosition, 0)), doubleArrayOf(1.0))
        val positions = weights.indices.filter { weights[it] > 0L }.toIntArray()
        return CategoryShares(positions, DoubleArray(positions.size) { weights[positions[it]].toDouble() / total })
    }

    /**
     * Tier-2 expected end-of-day balance per covered account and forecast day (`[account][day]`).
     * [learnedDayPence] holds the pool's expected learned pence per learned category and day
     * (`[category][day]`); each of those amounts is split over the pool accounts by its
     * category's shares, so the parts always add up to the original.
     */
    internal fun expectedBalances(plan: AccountPlan, learnedDayPence: Array<LongArray>): Array<LongArray> {
        val days = plan.days
        val accountCount = plan.accounts.size
        val poolSize = plan.poolSize
        val result = Array(accountCount) { LongArray(days) }
        val cumCommitted = LongArray(accountCount)
        val cumLearned = LongArray(poolSize)
        val out = LongArray(poolSize)
        val frac = DoubleArray(poolSize)
        for (d in 0 until days) {
            for (c in learnedDayPence.indices) {
                val amount = learnedDayPence[c][d]
                if (amount == 0L) continue
                val shares = plan.categoryShares[c]
                if (shares.positions.size == 1) {
                    cumLearned[shares.positions[0]] += amount
                } else if (shares.positions.isNotEmpty()) {
                    splitLargestRemainder(amount, shares.shares, out, frac)
                    for (j in shares.positions.indices) cumLearned[shares.positions[j]] += out[j]
                }
            }
            for (a in 0 until accountCount) {
                cumCommitted[a] += plan.committedByDay[a][d]
                val p = plan.poolPosition[a]
                result[a][d] = plan.balanceNow[a] + cumCommitted[a] + (if (p >= 0) cumLearned[p] else 0L)
            }
        }
        return result
    }

    /**
     * Splits [value] into `shares.size` whole-pence parts proportional to [shares] (which sum
     * to 1) that add up to exactly [value]: every part is rounded down, then the leftover pence
     * go one each to the largest fractional remainders (ties to the lowest index). Writes into
     * `out[0 until shares.size]`; [frac] is scratch of at least that size. Allocation-free.
     */
    internal fun splitLargestRemainder(value: Long, shares: DoubleArray, out: LongArray, frac: DoubleArray) {
        val n = shares.size
        var assigned = 0L
        val v = value.toDouble()
        for (j in 0 until n) {
            val q = v * shares[j]
            val f = Math.floor(q)
            out[j] = f.toLong()
            frac[j] = q - f
            assigned += out[j]
        }
        var remainder = value - assigned
        // Normally 0 <= remainder < n; the loops stay correct (and terminate) even if floating
        // point noise makes it fall outside, because each pick lowers/raises that fraction by 1.
        while (remainder > 0) {
            var best = 0
            for (j in 1 until n) if (frac[j] > frac[best]) best = j
            out[best]++
            frac[best] -= 1.0
            remainder--
        }
        while (remainder < 0) {
            var best = 0
            for (j in 1 until n) if (frac[j] < frac[best]) best = j
            out[best]--
            frac[best] += 1.0
            remainder++
        }
    }
}

/** A learned category's split over the pool: pool positions with a non-zero share, and the shares (sum 1). */
internal class CategoryShares(val positions: IntArray, val shares: DoubleArray)

/** Static per-account data for a run: balances, committed flows per day, and learned shares. */
internal class AccountPlan(
    /** Covered accounts: every non-archived account, ordered by id. */
    val accounts: List<Account>,
    /** Raw balance today per covered account. */
    val balanceNow: LongArray,
    /** `[covered account][forecast day]` net committed pence. */
    val committedByDay: Array<LongArray>,
    /** Covered index of each pool account, ascending id ("pool position" = index into this). */
    val poolAccountIdx: IntArray,
    /** Pool position per covered account, -1 for accounts outside the pool. */
    val poolPosition: IntArray,
    /** Per learned category, aligned with the engine's learned list. */
    val categoryShares: Array<CategoryShares>,
    val defaultAccountId: Long?,
    /** Number of forecast days after today. */
    val days: Int,
) {
    val poolSize: Int get() = poolAccountIdx.size
}

/** Tier-3 percentiles of one pool account's end-of-day balance, aligned with the forecast days after today. */
data class AccountPercentiles(
    val accountId: Long,
    val p10: List<Money>,
    val p50: List<Money>,
    val p90: List<Money>,
)
