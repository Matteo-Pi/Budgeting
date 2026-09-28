package io.github.matteopi.budgeting.ui.activity

import java.time.LocalDate

/**
 * Locale-independent weekday/month abbreviations for short date labels shown across Home,
 * Activity and Accounts. Deliberately avoids `DateTimeFormatter.ofPattern("d MMM", Locale.UK)`:
 * the JVM's CLDR data for `Locale.UK` abbreviates September as "Sept" (4 letters), while
 * docs/PLAN.md's examples ("Mon 28 Sep", "12 Jan") use the traditional 3-letter form.
 */
private val WEEKDAY_ABBREVIATIONS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH_ABBREVIATIONS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** e.g. "28 Sep". */
fun LocalDate.toShortLabel(): String = "$dayOfMonth ${MONTH_ABBREVIATIONS[monthValue - 1]}"

/** e.g. "28 Sep 2026". */
fun LocalDate.toShortLabelWithYear(): String = "${toShortLabel()} $year"

/** e.g. "Mon 28 Sep". */
fun LocalDate.toWeekdayShortLabel(): String = "${WEEKDAY_ABBREVIATIONS[dayOfWeek.value - 1]} ${toShortLabel()}"
