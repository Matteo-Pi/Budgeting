package io.github.matteopi.budgeting.core.planner.todo

import io.github.matteopi.budgeting.core.planner.model.Task
import io.github.matteopi.budgeting.core.planner.model.TaskKind
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/** One step of a template: its title and its share of the work, in percent. */
@Serializable
data class TemplateStep(val title: String, val percent: Int)

/** A way of splitting a deadline into steps (docs/PLAN.md §12.3), such as "Essay". */
@Serializable
data class BreakdownTemplate(val name: String, val steps: List<TemplateStep>)

/**
 * The default templates, and turning one into dated steps (docs/PLAN.md §13.4). The user's own templates are
 * kept in settings; these are used while there are none.
 */
object BreakdownTemplates {

    val defaults: List<BreakdownTemplate> = listOf(
        BreakdownTemplate(
            "Essay",
            listOf(
                TemplateStep("Research", 30),
                TemplateStep("Outline", 10),
                TemplateStep("First draft", 40),
                TemplateStep("Edit", 15),
                TemplateStep("References and submit", 5),
            ),
        ),
        BreakdownTemplate(
            "Lab report",
            listOf(
                TemplateStep("Analyse the data", 30),
                TemplateStep("Methods and results", 30),
                TemplateStep("Discussion", 25),
                TemplateStep("Check and submit", 15),
            ),
        ),
        BreakdownTemplate(
            "Presentation",
            listOf(
                TemplateStep("Research", 30),
                TemplateStep("Slides", 35),
                TemplateStep("Script", 15),
                TemplateStep("Practise", 20),
            ),
        ),
        BreakdownTemplate(
            "Exam revision",
            listOf(
                TemplateStep("Gather notes", 15),
                TemplateStep("Summarise the topics", 35),
                TemplateStep("Practice questions", 35),
                TemplateStep("Final review", 15),
            ),
        ),
    )

    /** Estimates are rounded to this many minutes, and a step with an estimate gets at least this much. */
    const val ROUND_MINUTES = 15

    /**
     * The steps of [template] for [deadline], as new tasks (id 0) pointing at it:
     * - each step is due when its share of the work so far is done, spread over the days from [today] to the
     *   deadline's date: step i is due on today + ⌊(sum of shares up to i) × days⌋, so the last is due with the
     *   deadline;
     * - its estimate is its share of the deadline's estimate, to the nearest 15 minutes (at least 15), or none.
     * Shares are taken relative to their total, so a template whose percentages don't add up to 100 still works.
     */
    fun apply(template: BreakdownTemplate, deadline: Task, today: LocalDate, now: Instant = Instant.EPOCH): List<Task> {
        val steps = template.steps.filter { it.title.isNotBlank() && it.percent > 0 }
        val total = steps.sumOf { it.percent }.takeIf { it > 0 } ?: return emptyList()
        val due = deadline.dueDate
        val days = if (due == null || due.isBefore(today)) 0L else ChronoUnit.DAYS.between(today, due)
        var cumulative = 0
        return steps.mapIndexed { index, step ->
            cumulative += step.percent
            val share = step.percent.toDouble() / total
            val stepDate = when {
                due == null -> null
                index == steps.lastIndex -> due
                else -> {
                    val day = today.plusDays(Math.floor(cumulative.toDouble() / total * days).toLong())
                    if (day.isAfter(due)) due else day
                }
            }
            Task(
                title = step.title.trim(),
                kind = TaskKind.TASK,
                dueDate = stepDate,
                estimateMinutes = deadline.estimateMinutes?.let { roundEstimate(it * share) },
                moduleId = deadline.moduleId,
                parentId = deadline.id,
                createdAt = now,
                sortOrder = index,
            )
        }
    }

    private fun roundEstimate(minutes: Double): Int =
        (Math.round(minutes / ROUND_MINUTES) * ROUND_MINUTES).toInt().coerceAtLeast(ROUND_MINUTES)
}
