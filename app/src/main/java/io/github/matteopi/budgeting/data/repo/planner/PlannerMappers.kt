package io.github.matteopi.budgeting.data.repo.planner

import io.github.matteopi.budgeting.core.planner.model.CalendarLink
import io.github.matteopi.budgeting.core.planner.model.ChecklistItem
import io.github.matteopi.budgeting.core.planner.model.DeadlineType
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.EntryKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Priority
import io.github.matteopi.budgeting.core.planner.model.RepeatKind
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskCompletion
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import io.github.matteopi.budgeting.core.planner.model.TaskRepeat
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.core.planner.model.WeekPattern
import io.github.matteopi.budgeting.core.planner.model.Weekdays
import io.github.matteopi.budgeting.core.planner.timetable.WeekList
import io.github.matteopi.budgeting.core.planner.timetable.WeekListResult
import io.github.matteopi.budgeting.data.db.dao.TaskWithChecklist
import io.github.matteopi.budgeting.data.db.entities.planner.CalendarLinkEntity
import io.github.matteopi.budgeting.data.db.entities.planner.ChecklistItemEntity
import io.github.matteopi.budgeting.data.db.entities.planner.EntryExceptionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.ModuleEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskCompletionEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TaskEntity
import io.github.matteopi.budgeting.data.db.entities.planner.TimetableEntryEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/*
 * Planner entity ⇄ core model mapping (docs/PLAN.md §13.5). An enum name the app doesn't know (written by a newer
 * version) reads as the default instead of failing, so one odd row never hides the whole list.
 */

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

/** The key that makes module codes unique without case. */
fun moduleCodeKey(code: String): String = code.trim().lowercase(Locale.ROOT)

fun ModuleEntity.toModel() = Module(
    id = id,
    code = code,
    name = name,
    colorArgb = colorArgb,
    weeklyTargetMinutes = weeklyTargetMinutes,
    weight = weight,
    archived = archived,
    sortOrder = sortOrder,
)

fun Module.toEntity() = ModuleEntity(
    id = id,
    code = code.trim(),
    codeKey = moduleCodeKey(code),
    name = name.trim(),
    colorArgb = colorArgb,
    weeklyTargetMinutes = weeklyTargetMinutes,
    weight = weight.coerceIn(1, 3),
    archived = archived,
    sortOrder = sortOrder,
)

private fun minuteOf(time: LocalTime): Int = time.hour * 60 + time.minute

private fun timeOf(minute: Int?): LocalTime? = minute?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) }

private fun parseTags(tags: String): List<String> = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun joinTags(tags: List<String>): String =
    tags.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

fun TaskWithChecklist.toModel(): Task = task.toModel(checklist.sortedBy { it.position }.map { ChecklistItem(it.text, it.done) })

fun TaskEntity.toModel(checklist: List<ChecklistItem> = emptyList()) = Task(
    id = id,
    title = title,
    notes = notes,
    kind = enumOr(kind, TaskKind.TASK),
    deadlineType = enumOrNull<DeadlineType>(deadlineType),
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    dueTime = timeOf(dueMinute),
    lengthMinutes = lengthMinutes,
    estimateMinutes = estimateMinutes,
    priority = enumOr(priority, Priority.NONE),
    moduleId = moduleId,
    tags = parseTags(tags),
    repeat = enumOrNull<RepeatKind>(repeatKind)?.let { TaskRepeat(it, repeatInterval, Weekdays.fromBits(repeatDays)) },
    reminderMinutesBefore = reminderMinutesBefore,
    parentId = parentId,
    location = location,
    completedAt = completedAtMillis?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAtMillis),
    sortOrder = sortOrder,
    checklist = checklist,
)

fun Task.toEntity() = TaskEntity(
    id = id,
    title = title.trim(),
    notes = notes,
    kind = kind.name,
    deadlineType = deadlineType?.name,
    dueDate = dueDate?.toEpochDay(),
    dueMinute = dueTime?.let(::minuteOf),
    lengthMinutes = lengthMinutes,
    estimateMinutes = estimateMinutes,
    priority = priority.name,
    moduleId = moduleId,
    tags = joinTags(tags),
    repeatKind = repeat?.kind?.name,
    repeatInterval = repeat?.interval ?: 1,
    repeatDays = repeat?.let { Weekdays.toBits(it.weekdays) } ?: 0,
    reminderMinutesBefore = reminderMinutesBefore,
    parentId = parentId,
    location = location?.trim()?.takeIf { it.isNotEmpty() },
    completedAtMillis = completedAt?.toEpochMilli(),
    createdAtMillis = createdAt.toEpochMilli(),
    sortOrder = sortOrder,
)

/** The checklist rows of this task, blank lines left out and positions in order; the DAO fills in the task id. */
fun Task.toChecklistEntities(): List<ChecklistItemEntity> =
    checklist.filter { it.text.isNotBlank() }
        .mapIndexed { index, item -> ChecklistItemEntity(taskId = id, position = index, text = item.text.trim(), done = item.done) }

fun TaskCompletionEntity.toModel() = TaskCompletion(
    id = id,
    taskId = taskId,
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    completedAt = Instant.ofEpochMilli(completedAtMillis),
)

fun TaskCompletion.toEntity() = TaskCompletionEntity(
    id = id,
    taskId = taskId,
    dueDate = dueDate?.toEpochDay(),
    completedAtMillis = completedAt.toEpochMilli(),
)

// ---- The timetable (version 6) ------------------------------------------------------------------

private fun patternOf(kind: String, weeks: String, onceDate: Long?): WeekPattern = when (kind) {
    "WEEKS" -> WeekPattern.TeachingWeeks((WeekList.parse(weeks) as? WeekListResult.Ok)?.weeks ?: emptySet())
    "ODD" -> WeekPattern.OddWeeks
    "EVEN" -> WeekPattern.EvenWeeks
    // A once-only entry that lost its date can't be placed; it reads as weekly rather than breaking the list.
    "ONCE" -> onceDate?.let { WeekPattern.Once(LocalDate.ofEpochDay(it)) } ?: WeekPattern.EveryWeek
    else -> WeekPattern.EveryWeek
}

private fun WeekPattern.kindName(): String = when (this) {
    WeekPattern.EveryWeek -> "EVERY"
    is WeekPattern.TeachingWeeks -> "WEEKS"
    WeekPattern.OddWeeks -> "ODD"
    WeekPattern.EvenWeeks -> "EVEN"
    is WeekPattern.Once -> "ONCE"
}

fun TimetableEntryEntity.toModel() = TimetableEntry(
    id = id,
    title = title,
    kind = enumOr(kind, EntryKind.OTHER),
    moduleId = moduleId,
    location = location,
    room = room,
    contact = contact,
    colorArgb = colorArgb,
    note = note,
    weekday = DayOfWeek.of(weekday.coerceIn(1, 7)),
    start = timeOf(startMinute) ?: LocalTime.MIDNIGHT,
    end = timeOf(endMinute) ?: LocalTime.MIDNIGHT,
    pattern = patternOf(pattern, weeks, onceDate),
    termId = termId,
    inHolidays = inHolidays,
    archived = archived,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
)

fun TimetableEntry.toEntity() = TimetableEntryEntity(
    id = id,
    title = title.trim(),
    kind = kind.name,
    moduleId = moduleId,
    location = location.trim(),
    room = room.trim(),
    contact = contact.trim(),
    colorArgb = colorArgb,
    note = note.trim(),
    weekday = weekday.value,
    startMinute = minuteOf(start),
    endMinute = minuteOf(end),
    pattern = pattern.kindName(),
    weeks = (pattern as? WeekPattern.TeachingWeeks)?.let { WeekList.format(it.weeks) } ?: "",
    onceDate = (pattern as? WeekPattern.Once)?.date?.toEpochDay(),
    termId = termId,
    inHolidays = inHolidays,
    archived = archived,
    createdAtMillis = createdAt.toEpochMilli(),
)

fun EntryExceptionEntity.toModel() = EntryException(
    id = id,
    entryId = entryId,
    date = LocalDate.ofEpochDay(date),
    cancelled = cancelled,
    start = timeOf(startMinute),
    end = timeOf(endMinute),
    room = room,
)

fun EntryException.toEntity() = EntryExceptionEntity(
    id = id,
    entryId = entryId,
    date = date.toEpochDay(),
    cancelled = cancelled,
    startMinute = start?.let(::minuteOf),
    endMinute = end?.let(::minuteOf),
    room = room?.trim()?.takeIf { it.isNotEmpty() },
)

fun CalendarLinkEntity.toModel() = CalendarLink(id, itemKey, calendarId, eventId, uid, contentHash)

fun CalendarLink.toEntity() = CalendarLinkEntity(id, itemKey, calendarId, eventId, uid, contentHash)
