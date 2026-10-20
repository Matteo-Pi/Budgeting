package io.github.matteopi.budgeting.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import io.github.matteopi.budgeting.core.planner.model.EntryException
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TimetableEntry
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.planner.components.PlannerTags
import io.github.matteopi.budgeting.ui.planner.timetable.TimetableTags
import java.time.LocalDate

/*
 * Seeds and helpers for the Planner's device tests (docs/PLAN.md §13.11). Dates are the device's real ones, so tests
 * work out what they expect from today rather than naming days.
 */

/** Onboarding done and the app opening in the Planner, as if the user had last been there. */
fun seedPlannerSection() = blocking {
    appContainer.settingsStore.setOnboardingDone(true)
    appContainer.settingsStore.setAppSection(AppSection.PLANNER)
}

fun seedModule(code: String, name: String = ""): Long = blocking { appContainer.moduleRepository.save(Module(code = code, name = name))!! }

fun seedTask(task: Task): Long = blocking { appContainer.taskRepository.save(task) }

fun plannerToday(): LocalDate = appContainer.clock.today()

fun openTasks(): List<Task> = blocking { appContainer.taskRepository.getOpenTasks() }

fun taskById(id: Long): Task? = blocking { appContainer.taskRepository.getTask(id) }

/** A task row (on To-do, Today or a deadline's page) showing [title]. */
fun taskRow(title: String): SemanticsMatcher = hasTestTag(PlannerTags.TASK_ROW) and hasText(title)

/** Ticks (or unticks) the task row showing [title]. */
fun UiTest.tickTask(title: String) = tap(isToggleable() and hasAnyAncestor(taskRow(title)), "the tick box of '$title'")

fun seedEntry(entry: TimetableEntry): Long = blocking { appContainer.timetableRepository.save(entry) }

fun timetableExceptions(): List<EntryException> = blocking { appContainer.timetableRepository.getExceptions() }

/** A block on the timetable's week grid showing [title]. */
fun timetableBlock(title: String): SemanticsMatcher = hasTestTag(TimetableTags.ITEM) and hasText(title)
