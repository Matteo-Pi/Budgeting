package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Backup version 6: the timetable, its one-date changes and the calendar links (docs/PLAN.md §13.5). */
class TimetableBackupTest {

    private val term = TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 11))
    private val module = Module(id = 3, code = "EC101")
    private val entries = listOf(
        TimetableEntry(id = 1, title = "EC101 Lecture", kind = EntryKind.LECTURE, moduleId = 3, location = "Campus", room = "LT1",
            contact = "Dr Smith", colorArgb = 0xFF3F7CAC, note = "Bring notes", weekday = DayOfWeek.MONDAY, start = LocalTime.of(10, 0),
            end = LocalTime.of(11, 0), pattern = WeekPattern.TeachingWeeks(setOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11)), termId = 1,
            createdAt = Instant.ofEpochMilli(1_000)),
        TimetableEntry(id = 2, title = "Football", kind = EntryKind.SPORT, weekday = DayOfWeek.WEDNESDAY, pattern = WeekPattern.EveryWeek, inHolidays = true),
        TimetableEntry(id = 3, title = "Seminar", kind = EntryKind.SEMINAR, pattern = WeekPattern.OddWeeks, archived = true),
        TimetableEntry(id = 4, title = "Tutorial", kind = EntryKind.TUTORIAL, pattern = WeekPattern.EvenWeeks),
        TimetableEntry(id = 5, title = "Open day", kind = EntryKind.OTHER, pattern = WeekPattern.Once(LocalDate.of(2026, 12, 19))),
    )
    private val exceptions = listOf(
        EntryException(1, 1, LocalDate.of(2026, 10, 5), cancelled = true),
        EntryException(2, 1, LocalDate.of(2026, 10, 12), start = LocalTime.of(14, 0), end = LocalTime.of(15, 0), room = "LT2"),
    )
    private val links = listOf(CalendarLink(1, "entry:1:2026-10-19", 7, 100, "planner-entry-1-2026-10-19@budgeting", "abc"))
    private val data = BackupData(termPeriods = listOf(term), plannerModules = listOf(module), plannerEntries = entries,
        plannerEntryExceptions = exceptions, plannerCalendarLinks = links)

    private fun encode(d: BackupData) = BackupCodec.encode(BackupMapper.toBackup(d, exportedAt = "now"))

    @Test
    fun `everything round trips`() {
        assertEquals(data, assertIs<BackupDecodeResult.Success>(BackupCodec.decode(encode(data))).data)
        assertTrue(encode(data).contains("\"weeks\": \"1–5, 7–11\""))
    }

    @Test
    fun `a version 5 file has no timetable`() {
        val json = """{"format":"budgeting-backup","version":5,"exportedAt":"now",
            "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertTrue(back.plannerEntries.isEmpty() && back.plannerEntryExceptions.isEmpty() && back.plannerCalendarLinks.isEmpty())
    }

    private fun errors(d: BackupData) = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(encode(d))).errors

    @Test
    fun `broken references, duplicates and corrupted values fail cleanly`() {
        assertTrue(errors(data.copy(termPeriods = emptyList())).any { it.contains("missing term 1") })
        assertTrue(errors(data.copy(plannerModules = emptyList())).any { it.contains("missing module 3") })
        assertTrue(errors(data.copy(plannerEntries = entries.drop(1))).any { it.contains("missing entry 1") })
        assertTrue(errors(data.copy(plannerEntryExceptions = exceptions + exceptions[0].copy(id = 9))).any { it.contains("same date") })
        assertTrue(errors(data.copy(plannerCalendarLinks = links + links[0].copy(id = 2))).any { it.contains("same item") })
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val badMinute = backup.copy(plannerEntries = backup.plannerEntries.map { it.copy(endMinute = 2000) })
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(badMinute))).errors.any { it.contains("outside the day") })
        val badPattern = BackupCodec.encode(backup).replace("\"ODD\"", "\"SOMETIMES\"")
        assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(badPattern))
        val noDate = backup.copy(plannerEntries = backup.plannerEntries.map { if (it.pattern == "ONCE") it.copy(onceDate = null) else it })
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(noDate))).errors.any { it.contains("no date") })
    }
}
