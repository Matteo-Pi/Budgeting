package io.github.matteopi.budgeting.core.recipes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrafficLightsTest {

    private class Limits(val nutrient: Nutrient, val green: Double, val red: Double, val portionRed: Double)

    private val limits = listOf(
        Limits(Nutrient.FAT, 3.0, 17.5, 21.0),
        Limits(Nutrient.SATURATES, 1.5, 5.0, 6.0),
        Limits(Nutrient.SUGARS, 5.0, 22.5, 27.0),
        Limits(Nutrient.SALT, 0.3, 1.5, 1.8),
    )

    @Test
    fun `per 100 g is green up to and including the green limit`() {
        for (l in limits) {
            assertEquals(Light.GREEN, TrafficLights.per100(l.nutrient, 0.0), "${l.nutrient} 0")
            assertEquals(Light.GREEN, TrafficLights.per100(l.nutrient, l.green), "${l.nutrient} at ${l.green}")
            assertEquals(Light.AMBER, TrafficLights.per100(l.nutrient, l.green + 0.01), "${l.nutrient} just above ${l.green}")
        }
    }

    @Test
    fun `per 100 g is red only above the red limit`() {
        for (l in limits) {
            assertEquals(Light.AMBER, TrafficLights.per100(l.nutrient, l.red), "${l.nutrient} at ${l.red}")
            assertEquals(Light.RED, TrafficLights.per100(l.nutrient, l.red + 0.01), "${l.nutrient} just above ${l.red}")
            assertEquals(Light.AMBER, TrafficLights.per100(l.nutrient, (l.green + l.red) / 2), "${l.nutrient} between")
        }
    }

    @Test
    fun `per portion is red only above 30 percent of the reference intake`() {
        for (l in limits) {
            assertFalse(TrafficLights.redPerPortion(l.nutrient, l.portionRed), "${l.nutrient} at ${l.portionRed}")
            assertTrue(TrafficLights.redPerPortion(l.nutrient, l.portionRed + 0.01), "${l.nutrient} just above ${l.portionRed}")
        }
        assertEquals(21.0, ReferenceIntakes.FAT * 0.3, 1e-9)
        assertEquals(6.0, ReferenceIntakes.SATURATES * 0.3, 1e-9)
        assertEquals(27.0, ReferenceIntakes.SUGARS * 0.3, 1e-9)
        assertEquals(1.8, ReferenceIntakes.SALT * 0.3, 1e-9)
    }

    @Test
    fun `the colour is red if either rule says red, else the per 100 g colour`() {
        // Salt: green per 100 g, but 2 g a portion.
        assertEquals(Light.RED, TrafficLights.colour(Nutrient.SALT, 0.2, 2.0))
        // Red per 100 g, small portion.
        assertEquals(Light.RED, TrafficLights.colour(Nutrient.SALT, 2.0, 0.5))
        // Amber per 100 g, portion under the limit.
        assertEquals(Light.AMBER, TrafficLights.colour(Nutrient.SALT, 0.8, 1.8))
        // Green both ways.
        assertEquals(Light.GREEN, TrafficLights.colour(Nutrient.SALT, 0.3, 1.8))
        assertEquals(Light.GREEN, TrafficLights.colour(Nutrient.FAT, 3.0, 21.0))
        assertEquals(Light.RED, TrafficLights.colour(Nutrient.FAT, 3.0, 21.01))
        assertEquals(Light.RED, TrafficLights.colour(Nutrient.FAT, 17.51, 5.0))
    }

    @Test
    fun `values that are the limit up to rounding error count as on it`() {
        assertEquals(Light.GREEN, TrafficLights.per100(Nutrient.SALT, 0.1 + 0.2))
        assertEquals(Light.AMBER, TrafficLights.per100(Nutrient.SATURATES, 5.0 + 1e-12))
        assertFalse(TrafficLights.redPerPortion(Nutrient.SALT, 0.6 * 3))
    }

    @Test
    fun `only fat, saturates, sugars and salt have a colour`() {
        assertEquals(listOf(Nutrient.FAT, Nutrient.SATURATES, Nutrient.SUGARS, Nutrient.SALT), TrafficLights.coloured)
        for (n in listOf(Nutrient.KCAL, Nutrient.CARBS, Nutrient.FIBRE, Nutrient.PROTEIN)) {
            assertNull(TrafficLights.per100(n, 100.0), "$n")
            assertNull(TrafficLights.colour(n, 100.0, 1000.0), "$n")
            assertFalse(TrafficLights.redPerPortion(n, 1000.0), "$n")
        }
    }
}
