package io.github.matteopi.budgeting.widget.planner

import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Planner widget's layouts (docs/PLAN.md §13.7) with the Glance testing library: small shows the next class and
 * the free time; wide adds today's tasks with ticks and the timer chip. The state itself is covered on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class PlannerWidgetContentTest {

    private val state = PlannerWidgetState(
        classLine = "Next: EC101 Lecture at 14:00 · LT1",
        freeLine = "3 h 20 m free left today",
        tasks = listOf(WidgetTask(1, "Hand in form", "Due today at 17:00"), WidgetTask(2, "Laundry", "Overdue by 2 days")),
        timerLine = null,
        startCategoryId = 1,
    )

    @Test
    fun theSmallWidgetShowsTheNextClassAndTheFreeTime() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { GlanceTheme { PlannerWidgetContent(state, Intent()) } }

            onNode(hasText("Next: EC101 Lecture at 14:00 · LT1")).assertExists()
            onNode(hasText("3 h 20 m free left today")).assertExists()
            onNode(hasText("Hand in form")).assertDoesNotExist()
            onNode(hasText("Start studying")).assertDoesNotExist()
        }
    }

    @Test
    fun theWideWidgetAddsTodaysTasksAndTheTimer() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { GlanceTheme { PlannerWidgetContent(state, Intent()) } }

            onNode(hasText("Hand in form")).assertExists()
            onNode(hasText("Overdue by 2 days")).assertExists()
            onNode(hasText("Start studying")).assertExists()
        }
    }

    @Test
    fun aRunningTimerShowsAndItsChipStopsIt() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { GlanceTheme { PlannerWidgetContent(state.copy(classLine = null, tasks = emptyList(), timerLine = "Study · 35 m"), Intent()) } }

            onNode(hasText("No more classes today")).assertExists()
            onNode(hasText("Nothing due today")).assertExists()
            onNode(hasText("Timer: Study · 35 m")).assertExists()
            onNode(hasText("Stop timer")).assertExists()
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 48.dp)
        val WIDE = DpSize(250.dp, 110.dp)
    }
}
