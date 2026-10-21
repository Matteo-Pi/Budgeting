package io.github.matteopi.budgeting.data.repo.planner

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.day.EditPlan
import io.github.matteopi.budgeting.core.planner.day.Interval
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import io.github.matteopi.budgeting.data.db.dao.DayDao
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.work.planner.PlannerUpdater
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The day record (docs/PLAN.md §13.5): categories, records, plan blocks and reviews. Every edit is a core
 * [EditPlan] applied in one transaction; the plan returned (its inserts carrying their new ids) undoes it with
 * [EditPlan.inverse].
 */
class DayRepository(
    private val dayDao: DayDao,
    private val clock: Clock,
    private val plannerUpdater: PlannerUpdater,
) {
    private val seeding = Mutex()

    // ---- Categories ---------------------------------------------------------------------------

    /**
     * The categories, seeded on first use: an empty table (a new Planner, or a restored file from before the day
     * record) gets the defaults, and the flow emits again with them.
     */
    fun observeCategories(): Flow<List<TimeCategory>> =
        dayDao.observeCategories().onEach { if (it.isEmpty()) ensureCategoriesSeeded() }.map { list -> list.map { it.toModel() } }

    /** The categories as stored, without seeding: for readers that run before the Planner is used (the reminders). */
    fun observeStoredCategories(): Flow<List<TimeCategory>> = dayDao.observeCategories().map { list -> list.map { it.toModel() } }

    suspend fun getCategories(): List<TimeCategory> = dayDao.getCategories().map { it.toModel() }

    /** Adds the default categories that are missing (all of them on first use). Safe to call any number of times. */
    suspend fun ensureCategoriesSeeded() = seeding.withLock {
        val missing = DefaultTimeCategories.missing(getCategories())
        if (missing.isNotEmpty()) dayDao.insertCategories(missing.map { it.toEntity() })
    }

    /** Adds [category] (id 0) or saves it. Returns its id. */
    suspend fun saveCategory(category: TimeCategory): Long =
        if (category.id == 0L) dayDao.insertCategory(category.toEntity()) else category.id.also { dayDao.updateCategory(category.toEntity()) }

    // ---- Records and plans --------------------------------------------------------------------

    fun observeRecords(range: Interval): Flow<List<RecordChunk>> =
        dayDao.observeRecords(range.start.toEpochMilli(), range.end.toEpochMilli()).map { list -> list.map { it.toModel() } }

    suspend fun getRecords(range: Interval): List<RecordChunk> =
        dayDao.getRecords(range.start.toEpochMilli(), range.end.toEpochMilli()).map { it.toModel() }

    fun observePlans(range: Interval): Flow<List<PlanBlock>> =
        dayDao.observePlans(range.start.toEpochMilli(), range.end.toEpochMilli()).map { list -> list.map { it.toModel() } }

    suspend fun getPlans(range: Interval): List<PlanBlock> =
        dayDao.getPlans(range.start.toEpochMilli(), range.end.toEpochMilli()).map { it.toModel() }

    /** Minutes recorded per task, for deadlines' work left. */
    fun observeMinutesByTask(): Flow<Map<Long, Int>> =
        dayDao.observeTimeByTask().map { rows -> rows.associate { it.taskId to (it.millis / 60_000).toInt() } }

    /** Applies [plan] to the records; returns it as applied, for Undo. New rows are stamped now. */
    suspend fun applyRecords(plan: EditPlan<RecordChunk>): EditPlan<RecordChunk> {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val inserts = plan.inserts.map { if (it.createdAt == Instant.EPOCH) it.copy(createdAt = now) else it }
        val ids = dayDao.applyRecords(inserts.map { it.toEntity() }, plan.updates.map { it.second.toEntity() }, plan.deletes.map { it.id })
        plannerUpdater.changed()
        return plan.copy(inserts = inserts.zip(ids) { row, id -> row.copy(id = id) })
    }

    suspend fun applyPlans(plan: EditPlan<PlanBlock>): EditPlan<PlanBlock> {
        val now = Instant.ofEpochMilli(clock.nowMillis())
        val inserts = plan.inserts.map { if (it.createdAt == Instant.EPOCH) it.copy(createdAt = now) else it }
        val ids = dayDao.applyPlans(inserts.map { it.toEntity() }, plan.updates.map { it.second.toEntity() }, plan.deletes.map { it.id })
        plannerUpdater.changed()
        return plan.copy(inserts = inserts.zip(ids) { row, id -> row.copy(id = id) })
    }

    /** A deleted revision topic's blocks stay, no longer naming it. */
    suspend fun clearTopic(topicId: Long) = dayDao.clearTopic(topicId)

    // ---- Reviews ------------------------------------------------------------------------------

    fun observeReviews(from: LocalDate, to: LocalDate): Flow<List<DayReview>> =
        dayDao.observeReviews(from.toEpochDay(), to.toEpochDay()).map { list -> list.map { it.toModel() } }

    suspend fun getReviews(from: LocalDate, to: LocalDate): List<DayReview> =
        dayDao.getReviews(from.toEpochDay(), to.toEpochDay()).map { it.toModel() }

    /** *Finish day*, with an optional rating (1 to 5) and note. */
    suspend fun finishDay(date: LocalDate, rating: Int?, note: String) {
        dayDao.upsertReview(DayReview(date, Instant.ofEpochMilli(clock.nowMillis()), rating?.takeIf { it in 1..5 }, note).toEntity())
        plannerUpdater.changed()
    }

    /** Opens a finished day again (its rating and note stay). */
    suspend fun reopenDay(date: LocalDate) {
        val review = getReviews(date, date).firstOrNull() ?: return
        dayDao.upsertReview(review.copy(finishedAt = null).toEntity())
        plannerUpdater.changed()
    }
}
