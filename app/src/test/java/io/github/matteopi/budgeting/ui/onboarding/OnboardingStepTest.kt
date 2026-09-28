package io.github.matteopi.budgeting.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OnboardingStepTest {
    @Test
    fun stepsAreInPlanOrder() {
        assertEquals(10, OnboardingStep.count)
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.entries.first())
        assertEquals(OnboardingStep.DONE, OnboardingStep.entries.last())
        assertEquals(0, OnboardingStep.WELCOME.index)
        assertEquals(9, OnboardingStep.DONE.index)
    }

    @Test
    fun nextAdvancesThroughEveryStepToDone() {
        var step = OnboardingStep.WELCOME
        repeat(OnboardingStep.count - 1) { step = step.next() }
        assertSame(OnboardingStep.DONE, step)
    }

    @Test
    fun nextOnTheLastStepStaysPut() {
        assertSame(OnboardingStep.DONE, OnboardingStep.DONE.next())
    }

    @Test
    fun previousOnTheFirstStepStaysPut() {
        assertSame(OnboardingStep.WELCOME, OnboardingStep.WELCOME.previous())
    }

    @Test
    fun previousUndoesNext() {
        val step = OnboardingStep.ACCOUNTS
        assertEquals(step, step.next().previous())
    }
}
