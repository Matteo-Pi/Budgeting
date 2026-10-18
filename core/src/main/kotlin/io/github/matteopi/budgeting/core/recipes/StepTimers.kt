package io.github.matteopi.budgeting.core.recipes

import java.util.Locale

/**
 * A timer a step offers: [label] is the duration as written ("10–12 min"), [seconds] where the timer starts (a
 * range's lower end) and [maxSeconds] a range's upper end.
 */
data class StepTimer(val label: String, val seconds: Int, val maxSeconds: Int? = null)

/** Finding the timers in a step's time and body (docs/PLAN.md §11.11), and showing durations. */
object StepTimers {

    /** The longest timer: a day, which is also the most a phone's clock takes. */
    const val MAX_SECONDS = 24 * 60 * 60

    private const val NUMBER = """\d+(?:[.,]\d+)?(?:\s?[½¼¾⅓⅔])?|[½¼¾⅓⅔]"""
    private val DURATION = Regex(
        """(?i)(\bevery\s+)?($NUMBER)(?:\s*(?:-|–|—|to)\s*($NUMBER))?\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])""" +
            """(?:\s*(?:and\s+)?(\d+)(?:\s*(?:-|–|—|to)\s*(\d+))?\s*(minutes?|mins?|m)(?![a-z]))?""",
    )
    private val GLYPHS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)

    private fun number(text: String): Double? {
        val t = text.trim().replace(',', '.')
        val glyph = t.lastOrNull()?.let { GLYPHS[it] }
        val digits = (if (glyph != null) t.dropLast(1) else t).trim()
        val whole = if (digits.isEmpty()) 0.0 else digits.toDoubleOrNull() ?: return null
        return whole + (glyph ?: 0.0)
    }

    private fun unitSeconds(unit: String): Int = when (unit.lowercase(Locale.ROOT).first()) {
        'h' -> 3600
        'm' -> 60
        else -> 1
    }

    /** Every duration in [text], in reading order; "every 5 min" is a reminder and is skipped. */
    fun durations(text: String): List<StepTimer> = DURATION.findAll(text).mapNotNull { m ->
        if (m.groupValues[1].isNotEmpty()) return@mapNotNull null
        val unit = unitSeconds(m.groupValues[4])
        val low = number(m.groupValues[2]) ?: return@mapNotNull null
        val high = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { number(it) }
        // "1 hr 15 min": the minutes after an hour are added on.
        val extraLow = if (unit == 3600) m.groupValues[5].toIntOrNull() ?: 0 else 0
        val extraHigh = if (unit == 3600) m.groupValues[6].toIntOrNull() ?: extraLow else 0
        val seconds = Math.round(low * unit).toInt() + extraLow * 60
        val maxSeconds = if (high != null) Math.round(high * unit).toInt() + extraHigh * 60 else if (extraHigh > extraLow) {
            seconds + (extraHigh - extraLow) * 60
        } else {
            null
        }
        if (seconds <= 0 || seconds > MAX_SECONDS) return@mapNotNull null
        StepTimer(m.value.trim(), seconds, maxSeconds?.takeIf { it > seconds }?.coerceAtMost(MAX_SECONDS))
    }.toList()

    /**
     * The timers [step] offers: the first duration in its time ("about 2 min, then steam" gives 2 min), then the
     * durations in its body in reading order, leaving out any that start at the same time as one already listed.
     */
    fun of(step: RecipeStep): List<StepTimer> {
        val timers = ArrayList<StepTimer>()
        step.time?.let { durations(it).firstOrNull()?.let(timers::add) }
        for (timer in durations(step.body)) if (timers.none { it.seconds == timer.seconds }) timers += timer
        return timers
    }

    /** A duration in words: "30 sec", "2 min", "2 min 30 sec", "1 hr", "1 hr 15 min". */
    fun format(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
            minutes > 0 -> if (secs == 0) "$minutes min" else "$minutes min $secs sec"
            else -> "$secs sec"
        }
    }

    /** Time left on a clock face: "0:45", "12:05", "1:02:03". */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = s % 3600 / 60
        val secs = (s % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
    }
}
