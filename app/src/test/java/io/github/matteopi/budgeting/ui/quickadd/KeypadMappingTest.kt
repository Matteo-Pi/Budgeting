package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.entry.KeypadKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeypadMappingTest {
    @Test
    fun digits() {
        for (d in 0..9) {
            assertEquals(KeypadKey.Digit(d), KeypadMapping.toKeypadKey(d.toString()))
        }
    }

    @Test
    fun dot() {
        assertEquals(KeypadKey.Dot, KeypadMapping.toKeypadKey("."))
    }

    @Test
    fun backspace() {
        assertEquals(KeypadKey.Backspace, KeypadMapping.toKeypadKey("back"))
    }

    @Test
    fun unknownKeysAreNull() {
        assertNull(KeypadMapping.toKeypadKey("x"))
        assertNull(KeypadMapping.toKeypadKey(""))
        assertNull(KeypadMapping.toKeypadKey("10"))
    }
}
