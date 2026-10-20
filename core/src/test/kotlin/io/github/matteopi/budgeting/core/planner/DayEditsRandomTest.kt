package io.github.matteopi.budgeting.core.planner

import io.github.matteopi.budgeting.core.planner.day.DayEdits
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random edit sequences with fixed seeds (docs/PLAN.md §13.4): after every edit no two chunks overlap, each is at
 * least 5 minutes, each stays inside the window, and applying the inverse gives the rows back exactly. The days include
 * both of 2026's clock changes.
 */
class DayEditsRandomTest {

    private val zone = ZoneId.of("Europe/London")

    /** Applies [plan] as the repository does: inserts get the next ids. Returns the rows and the applied plan. */
    private fun apply(rows: List<RecordChunk>, plan: EditPlan<RecordChunk>, nextId: () -> Long): Pair<List<RecordChunk>, EditPlan<RecordChunk>> {
        val applied = plan.copy(inserts = plan.inserts.map { if (it.id == 0L) it.copy(id = nextId()) else it })
        val deleted = applied.deletes.map { it.id }.toSet()
        val updated = applied.updates.associate { it.second.id to it.second }
        applied.updates.forEach { (before, _) -> check(rows.any { it == before }) { "an update of a row that isn't there: $before" } }
        val result = rows.filter { it.id !in deleted }.map { updated[it.id] ?: it } + applied.inserts
        return result to applied
    }

    private fun checkInvariants(rows: List<RecordChunk>, window: Interval, context: () -> String) {
        val sorted = rows.sortedBy { it.start }
        for (r in sorted) {
            if (!r.end.isAfter(r.start)) fail("ends before it starts: $r\n${context()}")
            if (Duration.between(r.start, r.end).toMinutes() < DayEdits.MIN_MINUTES) fail("shorter than 5 minutes: $r\n${context()}")
            if (r.start.isBefore(window.start) || r.end.isAfter(window.end)) fail("outside the window: $r\n${context()}")
        }
        sorted.zipWithNext().forEach { (a, b) -> if (b.start.isBefore(a.end)) fail("overlap: $a and $b\n${context()}") }
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun `ten thousand random edit sequences keep every invariant`() {
        // The windows of 28 Mar and 24 Oct run through the night the clocks change.
        val days = listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 3, 28), LocalDate.of(2026, 10, 24))
        var sequences = 0
        for (seed in 1..10_000) {
            val random = Random(seed)
            val date = days[seed % days.size]
            val window = Interval(ZonedDateTime.of(date.atTime(6, 0), zone).toInstant(), ZonedDateTime.of(date.plusDays(1).atTime(2, 0), zone).toInstant())
            val minutes = Duration.between(window.start, window.end).toMinutes()
            fun anyInstant(): Instant = window.start.plusSeconds(60L * random.nextLong(-30, minutes + 30))
            var rows = emptyList<RecordChunk>()
            var id = 1L
            val nextId = { id++ }
            val log = ArrayList<String>()
            repeat(random.nextInt(4, 14)) {
                val plan: EditPlan<RecordChunk>? = when (random.nextInt(6)) {
                    0, 1 -> {
                        val a = anyInstant()
                        val b = a.plusSeconds(60L * random.nextLong(1, 240))
                        DayEdits.place(rows, RecordChunk(start = a, end = b, categoryId = random.nextLong(1, 4)), window)
                    }
                    2 -> rows.randomOrNull(random)?.let { DayEdits.split(it, anyInstant(), zone) }
                    3 -> {
                        val sorted = rows.sortedBy { it.start }
                        if (sorted.isEmpty()) null else {
                            val i = random.nextInt(sorted.size)
                            val left = sorted[i]
                            val right = sorted.getOrNull(i + 1)?.takeIf { it.start == left.end && random.nextBoolean() }
                            if (right == null && random.nextBoolean()) DayEdits.moveBoundary(rows, null, left, anyInstant(), window, zone)
                            else DayEdits.moveBoundary(rows, left, right, anyInstant(), window, zone)
                        }
                    }
                    4 -> {
                        val sorted = rows.sortedBy { it.start }
                        if (sorted.size < 2) null else {
                            val i = random.nextInt(sorted.size - 1)
                            DayEdits.merge(rows, sorted[i], sorted[i + 1])
                        }
                    }
                    else -> rows.randomOrNull(random)?.let { DayEdits.delete(it) }
                }
                if (plan == null || plan.isEmpty) return@repeat
                log += plan.toString()
                val before = rows
                val (after, applied) = apply(rows, plan, nextId)
                checkInvariants(after, window) { "seed $seed, $date\n" + log.joinToString("\n") }
                val (undone, _) = apply(after, applied.inverse()) { error("an inverse inserts only rows with ids") }
                assertEquals(before.sortedBy { it.id }, undone.sortedBy { it.id }, "the inverse gives the rows back (seed $seed)")
                rows = after
            }
            sequences++
        }
        assertTrue(sequences == 10_000)
    }
}
