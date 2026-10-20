package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Backup version 5: the Planner's modules, tasks, checklists and Done log (docs/PLAN.md §13.5). */
class PlannerBackupTest {

    private val modules = listOf(
        Module(id = 1, code = "EC101", name = "Economics", colorArgb = 0xFF336699, weeklyTargetMinutes = 300, weight = 3, sortOrder = 0),
        Module(id = 2, code = "MA102", archived = true, sortOrder = 1),
    )

    private val essay = Task(
        id = 10, title = "EC101 essay", notes = "2,000 words", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.ASSIGNMENT,
        dueDate = LocalDate.of(2026, 10, 23), dueTime = LocalTime.of(17, 0), estimateMinutes = 360, priority = Priority.HIGH,
        moduleId = 1, tags = listOf("coursework"), reminderMinutesBefore = 1440, createdAt = Instant.ofEpochMilli(1_000), sortOrder = 2,
    )
    private val step = Task(id = 11, title = "Research", parentId = 10, dueDate = LocalDate.of(2026, 10, 21), estimateMinutes = 105,
        completedAt = Instant.ofEpochMilli(5_000), createdAt = Instant.ofEpochMilli(2_000))
    private val laundry = Task(
        id = 12, title = "Laundry", dueDate = LocalDate.of(2026, 10, 25),
        repeat = TaskRepeat(RepeatKind.WEEKDAYS, weekdays = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)),
        checklist = listOf(ChecklistItem("Whites"), ChecklistItem("Darks", done = true), ChecklistItem("Towels")),
        location = "Halls", createdAt = Instant.ofEpochMilli(3_000),
    )
    private val exam = Task(
        id = 13, title = "MA102 exam", kind = TaskKind.DEADLINE, deadlineType = DeadlineType.EXAM, dueDate = LocalDate.of(2027, 1, 12),
        dueTime = LocalTime.of(9, 30), lengthMinutes = 120, moduleId = 2, repeat = null, createdAt = Instant.ofEpochMilli(4_000),
    )
    private val completions = listOf(
        TaskCompletion(1, 12, LocalDate.of(2026, 10, 18), Instant.ofEpochMilli(6_000)),
        TaskCompletion(2, 11, LocalDate.of(2026, 10, 21), Instant.ofEpochMilli(5_000)),
    )

    private val data = BackupData(plannerModules = modules, plannerTasks = listOf(essay, step, laundry, exam), plannerCompletions = completions)

    private fun roundTrip(data: BackupData): BackupData =
        assertIs<BackupDecodeResult.Success>(BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now")))).data

    private fun failureOf(data: BackupData): List<String> =
        assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now")))).errors

    @Test
    fun `everything round trips through JSON`() {
        assertEquals(data, roundTrip(data))
    }

    @Test
    fun `checklists keep their order however the file lists them`() {
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val shuffled = backup.copy(plannerChecklist = backup.plannerChecklist.reversed())
        assertEquals(listOf("Whites", "Darks", "Towels"), BackupMapper.fromBackup(shuffled).plannerTasks.first { it.id == 12L }.checklist.map { it.text })
        assertEquals(3, backup.plannerChecklist.size)
    }

    @Test
    fun `the tables are written under their own names`() {
        val json = BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))
        for (name in listOf("plannerModules", "plannerTasks", "plannerChecklist", "plannerCompletions")) {
            assertTrue(json.contains("\"$name\""), name)
        }
        assertTrue(json.contains("\"dueMinute\": 1020"), "17:00 as minutes after midnight")
        assertTrue(json.contains("\"repeatDays\": 68"), "Wednesday (bit 2) and Sunday (bit 6)")
    }

    @Test
    fun `a version 4 file decodes with no Planner data`() {
        val json = """{"format":"budgeting-backup","version":4,"exportedAt":"now",
            "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(back.plannerModules.isEmpty() && back.plannerTasks.isEmpty() && back.plannerCompletions.isEmpty())
    }

    @Test
    fun `decode rejects broken references and duplicates`() {
        assertTrue(failureOf(data.copy(plannerTasks = listOf(essay.copy(moduleId = 9)))).any { it.contains("missing module 9") })
        assertTrue(failureOf(data.copy(plannerTasks = listOf(step))).any { it.contains("missing deadline 10") })
        assertTrue(failureOf(data.copy(plannerTasks = listOf(essay, step, step.copy(id = 14, parentId = 11)))).any { it.contains("step of a step") })
        assertTrue(failureOf(data.copy(plannerTasks = listOf(essay.copy(parentId = 10)))).any { it.contains("step of a step") }, "its own step")
        assertTrue(failureOf(data.copy(plannerTasks = listOf(essay, essay))).any { it.contains("same id") })
        assertTrue(failureOf(data.copy(plannerModules = modules + Module(id = 3, code = "ec101 "))).any { it.contains("same code") })
        assertTrue(failureOf(data.copy(plannerCompletions = listOf(TaskCompletion(1, 99, null, Instant.EPOCH)))).any { it.contains("missing task 99") })
    }

    @Test
    fun `a corrupted time or enum is a failure, not a crash`() {
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val badMinute = backup.copy(plannerTasks = backup.plannerTasks.map { it.copy(dueMinute = if (it.id == 10L) 1440 else it.dueMinute) })
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(badMinute))).errors.any { it.contains("outside the day") })
        val json = BackupCodec.encode(backup).replace("\"DEADLINE\"", "\"SOMEDAY\"")
        assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(json))
        val badChecklist = backup.copy(plannerChecklist = backup.plannerChecklist + PlannerChecklistItemDto(99, 0, "x", false))
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(badChecklist))).errors.any { it.contains("missing task 99") })
    }
}
