package io.github.matteopi.budgeting.widget.planner

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.widget.MessageContent
import io.github.matteopi.budgeting.widget.WidgetTheme
import io.github.matteopi.budgeting.work.planner.PlannerNotifications
import java.time.Instant
import kotlinx.coroutines.flow.first

private val SMALL_SIZE = DpSize(110.dp, 48.dp)
private val WIDE_SIZE = DpSize(250.dp, 110.dp)

/** At least this wide and tall, the widget adds today's tasks and the timer chip. */
private val WIDE_WIDTH: Dp = 180.dp
private val WIDE_HEIGHT: Dp = 90.dp

/**
 * The Planner home-screen widget (docs/PLAN.md §13.7): small shows the class on now or next and the free time left;
 * wide adds up to three of today's tasks with ticks and a timer chip. Redrawn after Planner writes and when a class
 * starts or ends.
 */
class PlannerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, WIDE_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BudgetApp).container
        val appSettings = container.settingsStore.appSettings.first()
        val state = runCatching {
            val service = container.plannerService
            val day = service.currentDay.first()
            val model = service.dayFlow(day).first()
            PlannerWidgetState.of(
                now = Instant.ofEpochMilli(container.clock.nowMillis()),
                today = day,
                zone = container.clock.zone(),
                occurrences = model.occurrences,
                freeLeftMinutes = model.freeLeftMinutes,
                tasks = container.taskRepository.getOpenTasks(),
                categories = model.categories,
                modules = container.moduleRepository.getModules(),
                timer = container.plannerTimer.state.first(),
            )
        }.getOrNull()
        provideContent {
            WidgetTheme(theme = appSettings.theme, darkMode = appSettings.darkMode) {
                Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background)) {
                    if (state == null) MessageContent(text = "Open the Planner") else PlannerWidgetContent(state, openToday(context))
                }
            }
        }
    }

    companion object {
        /** Redraws every placed Planner widget; nothing to do (and no error) when none is placed. */
        suspend fun refresh(context: Context) {
            runCatching { PlannerWidget().updateAll(context) }
        }

        fun openToday(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            action = PlannerNotifications.ACTION_OPEN_PLANNER
            putExtra(PlannerNotifications.EXTRA_TODAY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}

@Composable
internal fun PlannerWidgetContent(state: PlannerWidgetState, open: Intent) {
    val size = LocalSize.current
    val wide = size.width >= WIDE_WIDTH && size.height >= WIDE_HEIGHT
    val text = GlanceTheme.colors.onBackground
    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open))) {
            Text(
                text = state.classLine ?: "No more classes today",
                style = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 2,
            )
            Text(text = state.freeLine, style = TextStyle(color = text, fontSize = 12.sp))
            if (state.timerLine != null) Text(text = "Timer: ${state.timerLine}", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp))
        }
        if (wide) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            state.tasks.forEach { task ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .width(28.dp)
                            .height(28.dp)
                            .background(GlanceTheme.colors.secondaryContainer)
                            .clickable(actionRunCallback<TickTaskAction>(actionParametersOf(TaskIdKey to task.id))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✓", style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 14.sp))
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = task.title, style = TextStyle(color = text, fontSize = 12.sp), maxLines = 1)
                        Text(text = task.detail, style = TextStyle(color = text, fontSize = 10.sp), maxLines = 1)
                    }
                }
            }
            if (state.tasks.isEmpty()) Text(text = "Nothing due today", style = TextStyle(color = text, fontSize = 12.sp))
            Spacer(modifier = GlanceModifier.height(6.dp))
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primary)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(actionRunCallback<TimerAction>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.timerLine != null) "Stop timer" else "Start studying",
                    style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
