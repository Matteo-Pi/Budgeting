package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.model.DayReview
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Outcome
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.RecordChunk
import io.github.matteopi.budgeting.core.planner.model.RecordOrigin
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimeCategory
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup version 7: the day record's categories, plan blocks, records and reviews (docs/PLAN.md §13.5). */
class DayRecordBackupTest {

    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) } +
        TimeCategory(id = 20, name = "Band practice", colorArgb = 0xFF123456, sortOrder = 11, archived = true)
    private val module = Module(id = 3, code = "EC101")
    private val task = Task(id = 5, title = "Essay")
    private fun ms(n: Long) = Instant.ofEpochMilli(n)
    private val blocks = listOf(
        PlanBlock(id = 1, start = ms(1_000_000), end = ms(2_000_000), categoryId = 1, moduleId = 3, taskId = 5, origin = PlanOrigin.SUGGESTION,
            note = "Draft", createdAt = ms(10)),
        PlanBlock(id = 2, start = ms(3_000_000), end = ms(4_000_000), categoryId = 3, habitId = 7, origin = PlanOrigin.HABIT, outcomeOverride = Outcome.SKIPPED),
    )
    private val records = listOf(
        RecordChunk(id = 1, start = ms(1_000_000), end = ms(1_600_000), categoryId = 1, moduleId = 3, taskId = 5, planBlockId = 1, focus = 4,
            origin = RecordOrigin.CONFIRMED, createdAt = ms(20)),
        RecordChunk(id = 2, start = ms(5_000_000), end = ms(6_000_000), categoryId = 20, sourceKey = "event:9:5000000", note = "Gig", origin = RecordOrigin.TIMER),
    )
    private val reviews = listOf(DayReview(LocalDate.of(2026, 10, 19), finishedAt = ms(7_000_000), rating = 4, note = "Good day"), DayReview(LocalDate.of(2026, 10, 20)))
    private val data = BackupData(plannerModules = listOf(module), plannerTasks = listOf(task), plannerCategories = categories,
        plannerPlanBlocks = blocks, plannerRecords = records, plannerDayReviews = reviews)

    private fun encode(d: BackupData) = BackupCodec.encode(BackupMapper.toBackup(d, exportedAt = "now"))

    private fun errors(d: BackupData) = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(encode(d))).errors

    @Test
    fun `the day record round trips`() {
        assertEquals(data, assertIs<BackupDecodeResult.Success>(BackupCodec.decode(encode(data))).data)
        assertTrue(encode(data).contains("\"systemKey\": \"STUDY\""))
    }

    @Test
    fun `a version 6 file has no day record`() {
        val json = """{"format":"budgeting-backup","version":6,"exportedAt":"now",
            "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(back.plannerCategories.isEmpty() && back.plannerPlanBlocks.isEmpty() && back.plannerRecords.isEmpty() && back.plannerDayReviews.isEmpty())
    }

    @Test
    fun `a key or origin this version doesn't know reads as the plain kind`() {
        val json = encode(data).replace("\"STUDY\"", "\"LEARNING\"").replace("\"TIMER\"", "\"WATCH\"")
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertNull(back.plannerCategories.first().systemKey)
        assertEquals(RecordOrigin.MANUAL, back.plannerRecords.last().origin)
    }

    @Test
    fun `broken references, duplicates and bad values fail cleanly`() {
        assertTrue(errors(data.copy(plannerCategories = categories.dropLast(1))).any { it.contains("missing category 20") })
        assertTrue(errors(data.copy(plannerModules = emptyList())).any { it.contains("Plan block 1 refers to a missing module 3") })
        assertTrue(errors(data.copy(plannerTasks = emptyList())).any { it.contains("Record 1 refers to a missing task 5") })
        assertTrue(errors(data.copy(plannerPlanBlocks = blocks.drop(1))).any { it.contains("missing plan block 1") })
        assertTrue(errors(data.copy(plannerCategories = categories + categories[0].copy(id = 30))).any { it.contains("same meaning") })
        assertTrue(errors(data.copy(plannerDayReviews = reviews + reviews[0].copy(note = "again"))).any { it.contains("two reviews") })
        assertTrue(errors(data.copy(plannerRecords = records.map { it.copy(end = it.start) })).any { it.contains("ends before it starts") })
        assertTrue(errors(data.copy(plannerRecords = records.map { it.copy(focus = 9) })).any { it.contains("focus outside") })
        assertTrue(errors(data.copy(plannerDayReviews = reviews.map { it.copy(rating = 0) })).any { it.contains("rating outside") })
    }
}
