package io.github.matteopi.budgeting.data.repo

import androidx.room.withTransaction
import io.github.matteopi.budgeting.core.nutrition.Batch
import io.github.matteopi.budgeting.core.nutrition.Batches
import io.github.matteopi.budgeting.core.nutrition.FoodDay
import io.github.matteopi.budgeting.core.nutrition.LogItem
import io.github.matteopi.budgeting.core.nutrition.LogKind
import io.github.matteopi.budgeting.core.nutrition.Place
import io.github.matteopi.budgeting.core.nutrition.Taken
import io.github.matteopi.budgeting.data.db.BudgetDatabase
import io.github.matteopi.budgeting.data.db.dao.DiaryDao
import io.github.matteopi.budgeting.data.db.entities.FoodDayEntity
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.widget.food.FoodUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** A portion eaten from a batch: the diary row it became and what it took, so it can be undone exactly. */
data class BatchEaten(val rowId: Long, val batchId: Long, val taken: Taken, val row: LogItem)

/** A deleted diary row with its sides, and the portion put back into its batch, so Undo can bring it all back. */
data class DeletedRows(val rows: List<LogItem>, val batchId: Long?, val putBack: Taken?)

/**
 * The food diary, its days and the kitchen's batches (docs/PLAN.md §14.3). One user action is one transaction:
 * eating from a batch writes the row, its sides and the batch together; deleting a row from a batch puts the portion
 * back. Every write refreshes the Food widget through [foodUpdater].
 */
class DiaryRepository(
    private val database: BudgetDatabase,
    private val diaryDao: DiaryDao,
    private val clock: Clock,
    private val foodUpdater: FoodUpdater,
) {
    // ---- Reads -----------------------------------------------------------------------------------

    fun observeDay(date: LocalDate): Flow<List<LogItem>> =
        diaryDao.observeLogOn(date.toEpochDay()).map { rows -> rows.map { it.toModel() } }

    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<LogItem>> =
        diaryDao.observeLogBetween(from.toEpochDay(), to.toEpochDay()).map { rows -> rows.map { it.toModel() } }

    /** The latest [limit] rows, newest first. */
    fun observeRecent(limit: Int = RECENT): Flow<List<LogItem>> =
        diaryDao.observeRecentLog(limit).map { rows -> rows.map { it.toModel() } }

    suspend fun recent(limit: Int = RECENT): List<LogItem> = diaryDao.getRecentLog(limit).map { it.toModel() }

    suspend fun row(id: Long): LogItem? = diaryDao.getLog(id)?.toModel()

    /** Every batch, newest first (finished ones included: their rows point at them). */
    fun observeBatches(): Flow<List<Batch>> = diaryDao.observeBatches().map { list -> list.map { it.toModel() } }

    suspend fun batch(id: Long): Batch? = diaryDao.getBatch(id)?.toModel()

    suspend fun batches(): List<Batch> = diaryDao.getBatches().map { it.toModel() }

    fun observeFoodDay(date: LocalDate): Flow<FoodDay> =
        diaryDao.observeDay(date.toEpochDay()).map { it?.toModel() ?: FoodDay(date) }

    fun observeFoodDays(from: LocalDate, to: LocalDate): Flow<List<FoodDay>> =
        diaryDao.observeDays(from.toEpochDay(), to.toEpochDay()).map { list -> list.map { it.toModel() } }

    // ---- The diary ---------------------------------------------------------------------------------

    /**
     * Logs [main] (a food, a recipe portion or a quick entry) with its [sides] in one transaction; returns its id. A
     * zero creation time becomes now.
     */
    suspend fun log(main: LogItem, sides: List<LogItem> = emptyList()): Long {
        val now = clock.nowMillis()
        fun stamp(item: LogItem) = item.copy(id = 0, createdAtEpochMillis = item.createdAtEpochMillis.takeIf { it > 0 } ?: now)
        val id = diaryDao.logWithSides(stamp(main).toEntity(), sides.map { stamp(it).copy(sideOf = null).toEntity() }, null)
        foodUpdater.changed()
        return id
    }

    /**
     * Eats [portions] of batch [batchId] (from the fridge first unless [from] is the freezer) as [template] says
     * (date, meal, time) with its [sides], in one transaction: the row gets the batch's name, recipe and per-portion
     * numbers times what was taken. Null when the batch is gone or has nothing left.
     */
    suspend fun eatFromBatch(
        batchId: Long,
        portions: Double,
        template: LogItem,
        sides: List<LogItem> = emptyList(),
        from: Place = Place.FRIDGE,
    ): BatchEaten? {
        val now = clock.nowMillis()
        val result = database.withTransaction {
            val batch = diaryDao.getBatch(batchId)?.toModel() ?: return@withTransaction null
            val change = Batches.eat(batch, portions, from)
            val taken = change.taken.total
            if (taken < Batches.EPSILON) return@withTransaction null
            val row = template.copy(
                id = 0,
                kind = LogKind.BATCH,
                name = batch.name,
                amount = taken,
                amountText = Batches.portionsText(taken),
                recipeId = batch.recipeId,
                batchId = batch.id,
                ingredientId = null,
                sideOf = null,
                costPence = batch.costPence?.let { it * taken },
                createdAtEpochMillis = now,
                intake = batch.intake * taken,
            )
            val stamped = sides.map { it.copy(id = 0, sideOf = null, createdAtEpochMillis = now).toEntity() }
            val id = diaryDao.logWithSides(row.toEntity(), stamped, change.batch.toEntity())
            BatchEaten(id, batch.id, change.taken, row.copy(id = id))
        }
        if (result != null) foodUpdater.changed()
        return result
    }

    /** Undoes [eaten]: its row and sides go, and the portion goes back where it came from. */
    suspend fun undoEat(eaten: BatchEaten) {
        database.withTransaction {
            val batch = diaryDao.getBatch(eaten.batchId)?.toModel()
            diaryDao.deleteWithBatch(eaten.rowId, batch?.let { Batches.putBack(it, eaten.taken).toEntity() })
        }
        foodUpdater.changed()
    }

    /**
     * Deletes row [id] with its sides. A portion from the kitchen goes back into its batch (the fridge, unless only
     * the freezer has some left). Returns what Undo needs, or null when the row is gone.
     */
    suspend fun delete(id: Long): DeletedRows? {
        val deleted = database.withTransaction {
            val row = diaryDao.getLog(id)?.toModel() ?: return@withTransaction null
            val sides = diaryDao.getSides(id).map { it.toModel() }
            val batch = row.batchId?.takeIf { row.kind == LogKind.BATCH && row.sideOf == null }?.let { diaryDao.getBatch(it)?.toModel() }
            var putBack: Taken? = null
            val updated = batch?.let {
                val place = Batches.returnPlace(it)
                putBack = if (place == Place.FRIDGE) Taken(row.amount, 0.0) else Taken(0.0, row.amount)
                Batches.putBack(it, row.amount, place)
            }
            diaryDao.deleteWithBatch(id, updated?.toEntity())
            DeletedRows(listOf(row) + sides, batch?.id, putBack)
        }
        if (deleted != null) foodUpdater.changed()
        return deleted
    }

    /** Undo of [delete]: the rows come back with their own ids and the portion is taken out of the batch again. */
    suspend fun restore(deleted: DeletedRows) {
        database.withTransaction {
            val batch = deleted.batchId?.let { diaryDao.getBatch(it)?.toModel() }
            val taken = deleted.putBack
            val updated = if (batch != null && taken != null) {
                batch.copy(
                    fridgeLeft = (batch.fridgeLeft - taken.fromFridge).coerceAtLeast(0.0),
                    freezerLeft = (batch.freezerLeft - taken.fromFreezer).coerceAtLeast(0.0),
                )
            } else {
                null
            }
            diaryDao.restoreRows(deleted.rows.map { it.toEntity() }, updated?.toEntity())
        }
        foodUpdater.changed()
    }

    /**
     * Saves an edited row (its amount, meal, time or numbers). A portion from the kitchen whose amount changes takes
     * the difference from its batch, or puts it back.
     */
    suspend fun update(row: LogItem) {
        database.withTransaction {
            val old = diaryDao.getLog(row.id)?.toModel() ?: return@withTransaction
            val batch = row.batchId?.takeIf { row.kind == LogKind.BATCH && row.sideOf == null }?.let { diaryDao.getBatch(it)?.toModel() }
            val delta = row.amount - old.amount
            val updated = when {
                batch == null || Math.abs(delta) < Batches.EPSILON -> null
                delta > 0 -> Batches.eat(batch, delta).batch
                else -> Batches.putBack(batch, -delta, Batches.returnPlace(batch))
            }
            diaryDao.updateWithBatch(row.toEntity(), updated?.toEntity())
        }
        foodUpdater.changed()
    }

    // ---- The kitchen -------------------------------------------------------------------------------

    /** Adds a batch (a zero creation time becomes now); returns its id. */
    suspend fun addBatch(batch: Batch): Long {
        val id = diaryDao.insertBatch(
            batch.copy(id = 0, createdAtEpochMillis = batch.createdAtEpochMillis.takeIf { it > 0 } ?: clock.nowMillis()).toEntity(),
        )
        foodUpdater.changed()
        return id
    }

    suspend fun updateBatch(batch: Batch) {
        diaryDao.updateBatch(batch.toEntity())
        foodUpdater.changed()
    }

    /** Moves [amount] portions of batch [id] into [to] (see [Batches.move]). */
    suspend fun move(id: Long, amount: Double, to: Place) {
        database.withTransaction {
            val batch = diaryDao.getBatch(id)?.toModel() ?: return@withTransaction
            diaryDao.updateBatch(Batches.move(batch, amount, to, clock.today()).toEntity())
            Unit
        }
        foodUpdater.changed()
    }

    /** Throws away [amount] portions of batch [id]; returns what was taken, for Undo. */
    suspend fun throwAway(id: Long, amount: Double, from: Place = Place.FRIDGE): Taken? {
        val taken = database.withTransaction {
            val batch = diaryDao.getBatch(id)?.toModel() ?: return@withTransaction null
            val change = Batches.throwAway(batch, amount, from)
            diaryDao.updateBatch(change.batch.toEntity())
            change.taken
        }
        foodUpdater.changed()
        return taken
    }

    suspend fun undoThrowAway(id: Long, taken: Taken) {
        database.withTransaction {
            val batch = diaryDao.getBatch(id)?.toModel() ?: return@withTransaction
            diaryDao.updateBatch(Batches.putBack(batch, taken, wasThrownAway = true).toEntity())
            Unit
        }
        foodUpdater.changed()
    }

    /** Deletes a batch; its diary rows stay, no longer pointing at it. */
    suspend fun deleteBatch(id: Long) {
        diaryDao.deleteBatch(id)
        foodUpdater.changed()
    }

    // ---- Days ----------------------------------------------------------------------------------

    /** Adds [ml] of water to [date] (a negative amount takes some off, never below zero). */
    suspend fun addWater(date: LocalDate, ml: Int) {
        database.withTransaction {
            val day = diaryDao.getDay(date.toEpochDay())
            val water = ((day?.waterMl ?: 0) + ml).coerceAtLeast(0)
            diaryDao.upsertDay(FoodDayEntity(date.toEpochDay(), water, day?.note.orEmpty()))
        }
        foodUpdater.changed()
    }

    suspend fun setNote(date: LocalDate, note: String) {
        database.withTransaction {
            val day = diaryDao.getDay(date.toEpochDay())
            diaryDao.upsertDay(FoodDayEntity(date.toEpochDay(), day?.waterMl ?: 0, note.trim()))
        }
    }

    companion object {
        /** How many of the latest rows *Log food* and the sides' suggestions look at. */
        const val RECENT = 400
    }
}
