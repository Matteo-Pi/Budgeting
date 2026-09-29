package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.forecast.ForecastInput
import java.time.LocalDate

/** One failed check on one generated case. [check] is a short stable name used to group failures. */
class Failure(val check: String, val detail: String)

/** Collects the failed checks of one generated case instead of stopping at the first. */
class Failures {
    val items = ArrayList<Failure>()

    fun add(check: String, detail: String) {
        items += Failure(check, detail)
    }

    val isEmpty: Boolean get() = items.isEmpty()
}

inline fun Failures.expect(check: String, ok: Boolean, detail: () -> String) {
    if (!ok) add(check, detail())
}

/** Runs [block]; an exception (e.g. an index error from a broken shape) becomes a failure of [check]. */
inline fun Failures.guard(check: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        add("$check:exception", "${t::class.simpleName}: ${t.message}")
    }
}

/** A failure tied to the seed that produced it. */
class CaseFailure(val seed: Long, val check: String, val detail: String)

/** Groups case failures by check name and renders a compact report that names the seeds to replay. */
object StressReport {
    fun render(title: String, failures: List<CaseFailure>, describe: (Long) -> String, replayHint: String): String {
        val byCheck = failures.groupBy { it.check }
        val sb = StringBuilder()
        sb.append(title).append(": ").append(failures.size).append(" failed check(s) in ")
            .append(byCheck.size).append(" distinct check(s).\n")
        sb.append("Replay one case with: ").append(replayHint).append("\n")
        for ((check, list) in byCheck.entries.sortedByDescending { it.value.size }.take(12)) {
            val first = list.first()
            sb.append("\n[").append(check).append("] x").append(list.size)
                .append(" seeds=").append(list.take(6).joinToString(",") { it.seed.toString() }).append('\n')
            sb.append("  seed ").append(first.seed).append(": ").append(first.detail.take(600)).append('\n')
            sb.append("  input: ").append(describe(first.seed).take(1800).replace("\n", "\n         ")).append('\n')
        }
        return sb.toString()
    }
}

internal fun env(name: String, default: Long): Long = System.getenv(name)?.toLongOrNull() ?: default

/** Compact description of a [ForecastInput], for failure messages. */
object InputDescription {
    fun describe(input: ForecastInput, seed: Long? = null): String {
        val s = input.settings
        val sb = StringBuilder()
        sb.append("today=").append(input.today).append(' ').append(input.today.dayOfWeek.name.take(3))
            .append(" horizon=").append(s.forecastHorizonDays)
            .append(" sims=").append(s.simulations)
            .append(" zero=").append(s.zeroPoint)
            .append(" buffer=").append(s.buffer)
            .append(" histStart=").append(s.historyStartDate ?: "-")
            .append(" defaultAcct=").append(s.defaultAccountId ?: "-")
            .append(" mc=").append(input.runMonteCarlo)
            .append(" mcSeed=").append(input.seed)
        if (seed != null) sb.append(" caseSeed=").append(seed)
        sb.append('\n')
        sb.append("accounts: ").append(
            input.accounts.joinToString(" | ") {
                "#${it.id} ${it.type} ${if (it.includeInSafeToSpend) "pool" else "non-pool"}" +
                    (if (it.archived) " archived" else "") + (if (it.overdraftLimit.isZero) "" else " od${it.overdraftLimit}")
            },
        ).append('\n')
        val byKind = input.transactions.groupingBy { it.kind }.eachCount()
        val dates = input.transactions.map { it.date }
        sb.append("tx: ").append(input.transactions.size).append(' ').append(byKind)
            .append(" span=").append(dates.minOrNull() ?: "-").append("..").append(dates.maxOrNull() ?: "-")
            .append(" future=").append(input.transactions.count { it.date.isAfter(input.today) })
            .append(" reconRecords=").append(input.reconciliations.size).append('\n')
        sb.append("categories: ").append(input.categories.size)
            .append(" learned=").append(input.categories.count { it.forecastMode.name == "LEARNED" }).append('\n')
        if (input.recurringRules.isNotEmpty()) {
            sb.append("rules: ").append(
                input.recurringRules.joinToString(" | ") {
                    "${it.frequency}/${it.interval} ${it.startDate}..${it.endDate ?: "-"} ${it.postingMode} ${it.amount}" +
                        " #${it.accountId}" + (it.toAccountId?.let { t -> "->#$t" } ?: "") +
                        (if (it.isMajorIncome) " major" else "") + (it.postedThrough?.let { p -> " posted<=$p" } ?: "") +
                        (if (it.active) "" else " inactive")
                },
            ).append('\n')
        }
        if (input.scheduledItems.isNotEmpty()) {
            sb.append("scheduled: ").append(
                input.scheduledItems.joinToString(" | ") {
                    "${it.date} ${it.postingMode} ${it.amount} #${it.accountId}" + (it.toAccountId?.let { t -> "->#$t" } ?: "") +
                        (if (it.isMajorIncome) " major" else "") + (if (it.posted) " posted" else "") + (if (it.skipped) " skipped" else "")
                },
            ).append('\n')
        }
        if (input.termPeriods.isNotEmpty()) {
            sb.append("terms: ").append(input.termPeriods.joinToString(" | ") { "${it.startDate}..${it.endDate}" }).append('\n')
        }
        if (input.predictions.isNotEmpty()) {
            sb.append("predictions: ").append(
                input.predictions.joinToString(" | ") {
                    "c${it.categoryId}/${it.regime.name.take(1)} ${it.mode} ${it.weeklyAmount}" +
                        (if (it.mode.name == "ADJUST_PERCENT") " ${it.adjustPercent}%" else "")
                },
            ).append('\n')
        }
        if (input.sinkingFunds.isNotEmpty()) {
            sb.append("funds: ").append(
                input.sinkingFunds.joinToString(" | ") { "${it.startDate}..${it.targetDate} ${it.target} ${it.status}" },
            ).append('\n')
        }
        input.scenario?.let { scenario ->
            sb.append("scenario: ").append(
                scenario.items.joinToString(" | ") {
                    "${it.type}" + (it.accountId?.let { a -> " #$a" } ?: "") + " ${it.amount} ${it.date ?: "-"}" +
                        (it.frequency?.let { f -> " $f/${it.interval}" } ?: "") + (if (it.type.name == "CATEGORY_ADJUST") " c${it.categoryId} ${it.percent}%" else "")
                },
            ).append('\n')
        }
        return sb.toString()
    }

    fun shortDate(date: LocalDate?): String = date?.toString() ?: "-"
}
