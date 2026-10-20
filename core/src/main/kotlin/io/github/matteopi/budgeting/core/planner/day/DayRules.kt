package io.github.matteopi.budgeting.core.planner.day

import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import java.time.Instant

/** How a plan block went, from the record (docs/PLAN.md §13.4). */
object PlanOutcome {
    const val DONE_SHARE = 0.8
    const val PARTLY_SHARE = 0.25

    /**
     * Once [block] has ended: the share of it covered by [records] of its category (and module, if it has one) gives
     * [Outcome.DONE] at [DONE_SHARE] or more, [Outcome.PARTLY] at [PARTLY_SHARE] or more, else [Outcome.SKIPPED]. An
     * override wins. Null while the block hasn't ended.
     */
    fun of(block: PlanBlock, records: List<RecordChunk>, now: Instant): Outcome? {
        block.outcomeOverride?.let { return it }
        if (now.isBefore(block.end)) return null
        val span = Interval(block.start, block.end)
        if (span.isEmpty) return Outcome.SKIPPED
        val matching = records.filter { it.categoryId == block.categoryId && (block.moduleId == null || it.moduleId == block.moduleId) }
        val share = Intervals.overlapMinutes(span, matching.map { Interval(it.start, it.end) }).toDouble() / span.minutes
        return when {
            share >= DONE_SHARE -> Outcome.DONE
            share >= PARTLY_SHARE -> Outcome.PARTLY
            else -> Outcome.SKIPPED
        }
    }
}

/** The fields of the chunk sheet that can carry an error. */
enum class ChunkField { TIME, CATEGORY, FOCUS }

/** What a chunk needs before it is saved (docs/PLAN.md §13.4). */
object ChunkRules {
    fun errors(start: Instant?, end: Instant?, category: TimeCategory?, focus: Int?): Map<ChunkField, String> {
        val errors = LinkedHashMap<ChunkField, String>()
        when {
            start == null || end == null -> errors[ChunkField.TIME] = "Type a start and an end time."
            !end.isAfter(start) -> errors[ChunkField.TIME] = "The end must be after the start."
            Interval(start, end).minutes < DayEdits.MIN_MINUTES -> errors[ChunkField.TIME] = "A chunk is at least ${DayEdits.MIN_MINUTES} minutes."
        }
        if (category == null) errors[ChunkField.CATEGORY] = "Choose what it was."
        if (focus != null) {
            if (focus !in 1..5) errors[ChunkField.FOCUS] = "Focus is 1 to 5."
            else if (category != null && !category.ratesFocus) errors[ChunkField.FOCUS] = "${category.name} has no focus rating."
        }
        return errors
    }
}

/** The categories a new Planner starts with (docs/PLAN.md §12.8); all carry system keys, so renaming keeps their meaning. */
object DefaultTimeCategories {
    val all: List<TimeCategory> = listOf(
        TimeCategory(name = "Study", colorArgb = 0xFF3F7CAC, systemKey = CategoryKey.STUDY, ratesFocus = true, sortOrder = 0),
        TimeCategory(name = "Lecture or class", colorArgb = 0xFF5C6BC0, systemKey = CategoryKey.CLASS, ratesFocus = true, sortOrder = 1),
        TimeCategory(name = "Exercise", colorArgb = 0xFF2E8B57, systemKey = CategoryKey.EXERCISE, sortOrder = 2),
        TimeCategory(name = "Social", colorArgb = 0xFFE59500, systemKey = CategoryKey.SOCIAL, sortOrder = 3),
        TimeCategory(name = "Cooking and eating", colorArgb = 0xFFD1495B, systemKey = CategoryKey.COOKING, sortOrder = 4),
        TimeCategory(name = "Travel", colorArgb = 0xFF8D6E63, systemKey = CategoryKey.TRAVEL, sortOrder = 5),
        TimeCategory(name = "Chores", colorArgb = 0xFF00897B, systemKey = CategoryKey.CHORES, sortOrder = 6),
        TimeCategory(name = "Rest", colorArgb = 0xFF7B4FA6, systemKey = CategoryKey.REST, sortOrder = 7),
        TimeCategory(name = "Phone and scrolling", colorArgb = 0xFFC2185B, systemKey = CategoryKey.PHONE, sortOrder = 8),
        TimeCategory(name = "Sleep", colorArgb = 0xFF455A64, systemKey = CategoryKey.SLEEP, sortOrder = 9),
        TimeCategory(name = "Other", colorArgb = 0xFF9E9E9E, systemKey = CategoryKey.OTHER, sortOrder = 10),
    )

    /** The defaults whose keys [existing] lacks: what seeding adds, so a user's own categories are never doubled. */
    fun missing(existing: List<TimeCategory>): List<TimeCategory> {
        val keys = existing.mapNotNull { it.systemKey }.toSet()
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        return all.filter { it.systemKey !in keys }.mapIndexed { i, c -> if (existing.isEmpty()) c else c.copy(sortOrder = nextOrder + i) }
    }
}
