package io.github.matteopi.budgeting.ui.insights

import io.github.matteopi.budgeting.core.analysis.WeekTotals
import io.github.matteopi.budgeting.core.model.Money
import java.time.LocalDate
import java.time.YearMonth

/** This calendar month's total for a category vs. the average of the other months in the period. */
data class CategoryMonthComparison(val categoryId: Long, val thisMonth: Money, val average: Money)

/**
 * Pure helpers for the Insights screen, built only on [io.github.matteopi.budgeting.core.analysis.Trends]'
 * public output. No Android/Compose imports, so it is unit-testable on the plain JVM.
 */
object InsightsMapping {

    /** For each [categoryIds], this calendar month's total vs. the average of the period's other months. */
    fun thisMonthVsAverage(weeklyByCategory: List<WeekTotals>, categoryIds: List<Long>, today: LocalDate): List<CategoryMonthComparison> {
        val currentMonth = YearMonth.from(today)
        val byMonth = weeklyByCategory.groupBy { YearMonth.from(it.weekStart) }
        val otherMonths = byMonth.keys.filter { it != currentMonth }

        return categoryIds.map { categoryId ->
            val thisMonthTotal = Money(byMonth[currentMonth].orEmpty().sumOf { it.totals[categoryId]?.pence ?: 0L })
            val average = if (otherMonths.isEmpty()) {
                Money.ZERO
            } else {
                val totalOverOtherMonths = otherMonths.sumOf { month ->
                    byMonth[month].orEmpty().sumOf { it.totals[categoryId]?.pence ?: 0L }
                }
                Money(totalOverOtherMonths / otherMonths.size)
            }
            CategoryMonthComparison(categoryId, thisMonthTotal, average)
        }
    }

    /** The one weekly total closest to the middle of a sorted-by-value list — a simple, robust median. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
