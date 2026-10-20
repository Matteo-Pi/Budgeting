package io.github.matteopi.budgeting.widget.planner

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.planner.model.CategoryKey
import kotlinx.coroutines.flow.first

/** The task a widget tick is for. */
val TaskIdKey = ActionParameters.Key<Long>("planner_task_id")

/** A tick on the widget: the task is done (a repeating one moves on), as from the To-do tab. */
class TickTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TaskIdKey] ?: return
        val container = (context.applicationContext as BudgetApp).container
        val task = container.taskRepository.getTask(taskId) ?: return
        if (task.isOpen) container.taskRepository.complete(task)
        PlannerWidget.refresh(context)
    }
}

/** The widget's timer chip: stops the running timer, or starts recording study. */
class TimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = (context.applicationContext as BudgetApp).container
        val timer = container.plannerTimer
        if (timer.state.first() != null) {
            timer.stop()
        } else {
            val categories = container.dayRepository.observeCategories().first()
            val study = categories.firstOrNull { it.systemKey == CategoryKey.STUDY && !it.archived } ?: categories.firstOrNull { !it.archived }
            if (study != null) timer.start(study.id)
        }
        PlannerWidget.refresh(context)
    }
}

class PlannerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlannerWidget()
}
