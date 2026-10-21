package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.planner.day.DefaultTimeCategories
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.DismissedSuggestion
import io.github.matteopi.budgeting.core.planner.model.PlanBlock
import io.github.matteopi.budgeting.core.planner.model.PlanOrigin
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Backup version 9: revision topics and dismissed suggestions (docs/PLAN.md §13.5). */
class StudyBackupTest {

    private val exam = Task(id = 9, title = "EC101 exam", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueDate = LocalDate.of(2026, 11, 30))
    private val topics = listOf(
        RevisionTopic(id = 1, examTaskId = 9, name = "Elasticity", difficulty = 3, sortOrder = 0),
        RevisionTopic(id = 2, examTaskId = 9, name = "Game theory", difficulty = 1, sortOrder = 1),
    )
    private val dismissed = listOf(DismissedSuggestion("deadline:4@1792500000000", Instant.ofEpochMilli(1_792_400_000_000)))
    private val categories = DefaultTimeCategories.all.mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val block = PlanBlock(id = 1, start = Instant.ofEpochMilli(1_000), end = Instant.ofEpochMilli(2_000), categoryId = 1, taskId = 9, topicId = 1,
        origin = PlanOrigin.SUGGESTION)
    private val data = BackupData(plannerTasks = listOf(exam), plannerRevisionTopics = topics, plannerDismissed = dismissed, plannerCategories = categories,
        plannerPlanBlocks = listOf(block))

    private fun encode(d: BackupData) = BackupCodec.encode(BackupMapper.toBackup(d, exportedAt = "now"))

    private fun errors(d: BackupData) = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(encode(d))).errors

    @Test
    fun `topics and dismissed suggestions round trip`() {
        assertEquals(data, assertIs<BackupDecodeResult.Success>(BackupCodec.decode(encode(data))).data)
    }

    @Test
    fun `a version 8 file has none`() {
        val json = """{"format":"budgeting-backup","version":8,"exportedAt":"now",
            "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(back.plannerRevisionTopics.isEmpty() && back.plannerDismissed.isEmpty())
    }

    @Test
    fun `broken references and duplicates fail cleanly`() {
        assertTrue(errors(data.copy(plannerTasks = emptyList())).any { it.contains("Revision topic 1 refers to a missing exam 9") })
        assertTrue(errors(data.copy(plannerRevisionTopics = topics.drop(1))).any { it.contains("Plan block 1 refers to a missing revision topic 1") })
        assertTrue(errors(data.copy(plannerRevisionTopics = topics.map { it.copy(difficulty = 4) })).any { it.contains("difficulty outside") })
        assertTrue(errors(data.copy(plannerDismissed = dismissed + dismissed)).any { it.contains("dismissed twice") })
    }
}
