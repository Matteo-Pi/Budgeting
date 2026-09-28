package io.github.matteopi.budgeting.ui.components.charts

import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure, Compose-free axis-scaling helpers shared by every chart: "nice" round tick values (the
 * classic Heckbert algorithm) and month gridlines. Kept free of any Android/Compose import so it
 * can be unit-tested on the plain JVM.
 */
object ChartAxis {

    /**
     * A small number of evenly spaced, human-friendly values covering at least [min]..[max] (e.g.
     * 0, 50, 100 rather than 0, 43, 86). Returns an empty list if [max] <= [min] or [targetCount] < 1.
     */
    fun ticks(min: Double, max: Double, targetCount: Int = 4): List<Double> {
        if (max <= min || targetCount < 1) return emptyList()
        val range = niceNumber(max - min, round = false)
        val step = niceNumber(range / (targetCount - 1).coerceAtLeast(1), round = true)
        if (step <= 0.0) return emptyList()
        val niceMin = floor(min / step) * step
        val niceMax = ceil(max / step) * step
        val result = mutableListOf<Double>()
        var v = niceMin
        // A small tolerance keeps the last tick from being dropped to floating-point error.
        while (v <= niceMax + step * 1e-9) {
            result += v
            v += step
        }
        return result
    }

    /** [ticks], specialised for whole-pound money amounts. */
    fun moneyTicks(min: Money, max: Money, targetCount: Int = 4): List<Money> =
        ticks(min.pence / 100.0, max.pence / 100.0, targetCount).map { Money.pounds(Math.round(it)) }

    /** The first day of each calendar month overlapping [start]..[end] (clamped to [start]), ascending. */
    fun monthTicks(start: LocalDate, end: LocalDate): List<LocalDate> {
        if (end.isBefore(start)) return emptyList()
        val result = mutableListOf<LocalDate>()
        var month = YearMonth.from(start)
        val lastMonth = YearMonth.from(end)
        while (!month.isAfter(lastMonth)) {
            val first = month.atDay(1)
            result += if (first.isBefore(start)) start else first
            month = month.plusMonths(1)
        }
        return result
    }

    /** Rounds [value] to 1, 2 or 5 times a power of ten — the classic "nice numbers" step. */
    private fun niceNumber(value: Double, round: Boolean): Double {
        if (value <= 0.0) return 0.0
        val exponent = floor(ln(value) / ln(10.0))
        val fraction = value / 10.0.pow(exponent)
        val niceFraction = if (round) {
            when {
                fraction < 1.5 -> 1.0
                fraction < 3.0 -> 2.0
                fraction < 7.0 -> 5.0
                else -> 10.0
            }
        } else {
            when {
                fraction <= 1.0 -> 1.0
                fraction <= 2.0 -> 2.0
                fraction <= 5.0 -> 5.0
                else -> 10.0
            }
        }
        return niceFraction * 10.0.pow(exponent)
    }
}
