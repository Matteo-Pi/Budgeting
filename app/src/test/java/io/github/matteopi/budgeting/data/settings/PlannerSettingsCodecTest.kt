package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplate
import io.github.matteopi.budgeting.core.planner.todo.BreakdownTemplates
import io.github.matteopi.budgeting.core.planner.todo.TemplateStep
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Planner's JSON settings (docs/PLAN.md §13.6): anything that doesn't parse gives the defaults. */
class PlannerSettingsCodecTest {

    @Test
    fun `reminder settings round trip and fall back to 09 00`() {
        val settings = PlannerReminderSettings(taskMinute = 8 * 60 + 30)
        assertEquals(settings, PlannerSettingsCodec.decodeReminders(PlannerSettingsCodec.encodeReminders(settings)))
        assertEquals(LocalTime.of(8, 30), settings.taskTime)
        for (bad in listOf(null, "", "not json", "{\"taskMinute\": 5000}", "[]")) {
            assertEquals(LocalTime.of(9, 0), PlannerSettingsCodec.decodeReminders(bad).taskTime, "$bad")
        }
        assertEquals(PlannerReminderSettings(), PlannerSettingsCodec.decodeReminders("{\"later\": true}"), "unknown keys are ignored")
    }

    @Test
    fun `templates round trip, and none means the defaults`() {
        val mine = listOf(BreakdownTemplate("Poster", listOf(TemplateStep("Sketch", 40), TemplateStep("Print", 60))))
        assertEquals(mine, PlannerSettingsCodec.decodeTemplates(PlannerSettingsCodec.encodeTemplates(mine)))
        assertEquals(BreakdownTemplates.defaults, PlannerSettingsCodec.decodeTemplates(null))
        assertEquals(BreakdownTemplates.defaults, PlannerSettingsCodec.decodeTemplates("[]"))
        assertEquals(BreakdownTemplates.defaults, PlannerSettingsCodec.decodeTemplates("{oops"))
        assertEquals(BreakdownTemplates.defaults, PlannerSettingsCodec.decodeTemplates(PlannerSettingsCodec.encodeTemplates(listOf(BreakdownTemplate(" ", emptyList())))))
    }
}
