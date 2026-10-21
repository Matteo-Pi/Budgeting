package io.github.matteopi.budgeting.core.planner.suggest

import io.github.matteopi.budgeting.core.planner.PlannerText
import io.github.matteopi.budgeting.core.planner.habits.HabitDayState
import io.github.matteopi.budgeting.core.planner.habits.HabitProgress
import io.github.matteopi.budgeting.core.planner.habits.HabitStatus
import io.github.matteopi.budgeting.core.planner.model.GoalKind
import io.github.matteopi.budgeting.core.planner.model.Module
import io.github.matteopi.budgeting.core.planner.model.RevisionTopic
import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.todo.DeadlineStatus
import io.github.matteopi.budgeting.core.planner.todo.TaskRules
import java.time.LocalDate

/**
 * What the planner places (docs/PLAN.md §13.4), in order:
 * 1. deadlines, earliest target date first, for their work left less what is already planned;
 * 2. revision topics that are due (exam mode);
 * 3. habit slots still needed this week;
 * 4. module weekly targets, less what was studied and planned this week.
 */
object Demands {

    /**
     * [tasks] are the open tasks (deadlines and their steps); [studiedByTask] the minutes recorded per task;
     * [plannedByTask] the minutes of plan blocks still to come per task; [studiedByModule] and [plannedByModule] this
     * week's per module. [topicReviews] are each topic's review dates. [habits] are the habits' statuses as of [today],
     * with [plannedByHabit] the blocks already planned for them this week.
     */
    fun from(
        today: LocalDate,
        prefs: StudyPrefs,
        tasks: List<Task>,
        studiedByTask: Map<Long, Int>,
        plannedByTask: Map<Long, Int>,
        modules: List<Module>,
        studiedByModule: Map<Long, Int>,
        plannedByModule: Map<Long, Int>,
        topics: List<RevisionTopic> = emptyList(),
        topicReviews: Map<Long, List<LocalDate>> = emptyMap(),
        habits: List<HabitStatus> = emptyList(),
        plannedByHabit: Map<Long, Int> = emptyMap(),
    ): List<Demand> {
        val off = prefs.offModules.toSet()
        val modulesById = modules.associateBy { it.id }
        val topicsByExam = topics.groupBy { it.examTaskId }
        return deadlines(today, prefs, tasks, studiedByTask, plannedByTask, off, topicsByExam.keys) +
            revision(today, prefs, tasks, topicsByExam, topicReviews, modulesById, off) +
            habitSlots(today, habits, plannedByHabit) +
            moduleTargets(today, modules, studiedByModule, plannedByModule, off)
    }

    private fun deadlines(
        today: LocalDate,
        prefs: StudyPrefs,
        tasks: List<Task>,
        studiedByTask: Map<Long, Int>,
        plannedByTask: Map<Long, Int>,
        off: Set<Long>,
        examsWithTopics: Set<Long>,
    ): List<Demand> {
        val steps = tasks.filter { it.parentId != null }.groupBy { it.parentId }
        return tasks
            .filter { it.isDeadline && it.isOpen && it.parentId == null && it.id !in examsWithTopics }
            .filter { d -> d.dueDate != null && !d.dueDate.isBefore(today) && (d.moduleId == null || d.moduleId !in off) }
            .mapNotNull { deadline ->
                val due = deadline.dueDate!!
                val own = steps[deadline.id].orEmpty()
                val left = DeadlineStatus.of(deadline, own, studiedByTask, today).workLeftMinutes ?: return@mapNotNull null
                val planned = (listOf(deadline) + own).sumOf { plannedByTask[it.id] ?: 0 }
                val minutes = left - planned
                if (minutes <= 0) return@mapNotNull null
                val latest = maxOf(today, due.minusDays(prefs.finishEarlyDays.toLong()))
                Demand(
                    key = "deadline:${deadline.id}",
                    kind = DemandKind.DEADLINE,
                    title = deadline.title,
                    minutes = minutes,
                    latest = latest,
                    moduleId = deadline.moduleId,
                    taskId = deadline.id,
                    // "EC101 essay due Friday"; the planner adds the work left and the free time before then.
                    reason = "${deadline.title} ${PlannerText.due(due, null, today).replaceFirstChar { it.lowercase() }}",
                    deadlineLeft = left,
                ) to (due to deadline.id)
            }
            .sortedWith(compareBy<Pair<Demand, Pair<LocalDate, Long>>> { it.first.latest }.thenBy { it.second.first }.thenBy { it.second.second })
            .map { it.first }
    }

    private fun revision(
        today: LocalDate,
        prefs: StudyPrefs,
        tasks: List<Task>,
        topicsByExam: Map<Long, List<RevisionTopic>>,
        topicReviews: Map<Long, List<LocalDate>>,
        modulesById: Map<Long, Module>,
        off: Set<Long>,
    ): List<Demand> = tasks
        .filter { TaskRules.isExam(it) && it.isOpen && it.dueDate != null && it.dueDate.isAfter(today) && (it.moduleId == null || it.moduleId !in off) }
        .sortedWith(compareBy<Task> { it.dueDate }.thenBy { it.id })
        .flatMap { exam ->
            val examDate = exam.dueDate!!
            val start = examDate.minusDays(prefs.revisionStartDays.toLong())
            if (start.isAfter(today.plusDays(StudyPlanner.HORIZON_DAYS))) return@flatMap emptyList()
            val weight = exam.moduleId?.let { modulesById[it]?.weight } ?: 2
            topicsByExam[exam.id].orEmpty().sortedWith(compareBy<RevisionTopic> { it.sortOrder }.thenBy { it.id }).mapNotNull { topic ->
                val reviews = topicReviews[topic.id].orEmpty()
                val due = maxOf(today, SpacedRepetition.nextDue(topic, reviews, start))
                val latest = examDate.minusDays(1)
                if (due.isAfter(latest)) return@mapNotNull null
                // Harder topics and heavier modules get longer blocks: the base × (difficulty + weight) ÷ 4.
                val minutes = roundUp15(prefs.revisionBlockMinutes * (topic.difficulty.coerceIn(1, 3) + weight.coerceIn(1, 3)) / 4.0)
                Demand(
                    key = "topic:${topic.id}:${reviews.size}",
                    kind = DemandKind.REVISION,
                    title = "Revise ${topic.name}",
                    minutes = minutes,
                    latest = latest,
                    earliest = due,
                    blockMinutes = minutes,
                    moduleId = exam.moduleId,
                    taskId = exam.id,
                    topicId = topic.id,
                    reason = "${exam.title} on ${PlannerText.date(examDate, today)}: review ${reviews.size + 1} of ${topic.name}",
                )
            }
        }

    /**
     * Habits with *Suggest a time* on: for times a week, the times still needed this week; for chosen days, each of this
     * week's chosen days still to do. Planned blocks count as needed times taken.
     */
    private fun habitSlots(today: LocalDate, habits: List<HabitStatus>, plannedByHabit: Map<Long, Int>): List<Demand> =
        habits.filter { it.habit.suggestSlot && !it.habit.archived && (it.habit.slotMinutes ?: 0) > 0 }.flatMap { status ->
            val habit = status.habit
            val minutes = habit.slotMinutes!!
            val window = if (habit.slotStart != null && habit.slotEnd != null) TimeWindow.of(habit.slotStart, habit.slotEnd).takeIf { it.isValid } else null
            val planned = (plannedByHabit[habit.id] ?: 0) / minutes
            val sunday = StudyPlanner.weekEnd(today)
            when (habit.goal.kind) {
                GoalKind.TIMES_PER_WEEK -> {
                    val week = status.week(HabitProgress.weekStart(today))
                    val needed = (week?.target ?: habit.goal.target) - (week?.amount ?: 0) - planned
                    (0 until needed.coerceAtLeast(0)).map { n ->
                        Demand(
                            key = "habit:${habit.id}:$n", kind = DemandKind.HABIT, title = habit.name, minutes = minutes, latest = sunday,
                            blockMinutes = minutes, window = window, study = false, habitId = habit.id, categoryId = habit.categoryId,
                            reason = "${habit.name}: ${week?.amount ?: 0} of ${week?.target ?: habit.goal.target} this week",
                        )
                    }
                }
                GoalKind.ON_DAYS -> generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(sunday) }
                    .filter { status.day(it)?.state == HabitDayState.PENDING }
                    .drop(planned)
                    .map { date ->
                        Demand(
                            key = "habit:${habit.id}:$date", kind = DemandKind.HABIT, title = habit.name, minutes = minutes, latest = date, earliest = date,
                            blockMinutes = minutes, window = window, study = false, habitId = habit.id, categoryId = habit.categoryId,
                            reason = "${habit.name} on ${PlannerText.day(date, today).lowercase()}",
                        )
                    }.toList()
                else -> emptyList()
            }
        }

    private fun moduleTargets(
        today: LocalDate,
        modules: List<Module>,
        studiedByModule: Map<Long, Int>,
        plannedByModule: Map<Long, Int>,
        off: Set<Long>,
    ): List<Demand> = modules.filter { !it.archived && it.id !in off && (it.weeklyTargetMinutes ?: 0) > 0 }.mapNotNull { module ->
        val target = module.weeklyTargetMinutes!!
        val studied = studiedByModule[module.id] ?: 0
        val minutes = target - studied - (plannedByModule[module.id] ?: 0)
        if (minutes <= 0) return@mapNotNull null
        Demand(
            key = "module:${module.id}:${HabitProgress.weekStart(today)}",
            kind = DemandKind.MODULE,
            title = module.code,
            minutes = minutes,
            latest = StudyPlanner.weekEnd(today),
            moduleId = module.id,
            reason = "${module.code}: ${PlannerText.duration(studied)} of ${PlannerText.duration(target)} this week",
        )
    }

    private fun roundUp15(minutes: Double): Int = (Math.ceil(minutes / 15.0) * 15).toInt()
}
