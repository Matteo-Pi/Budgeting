package io.github.matteopi.budgeting.ui.onboarding

/**
 * The ordered steps of first-run setup (docs/PLAN.md §6.5). [entries] gives the display order;
 * [index] and [count] drive the stepper's progress bar. Pure enum logic — no Android imports —
 * so it's covered by JVM unit tests.
 */
enum class OnboardingStep {
    WELCOME,
    ACCOUNTS,
    MONEY_DISPLAY,
    TERM_DATES,
    MONEY_IN,
    REGULAR_COSTS,
    SPENDING_ESTIMATES,
    STATEMENT_IMPORT,
    WIDGET,
    DONE,
    ;

    /** 0-based position, for "step N of count" and the progress bar. */
    val index: Int get() = ordinal

    /** The step after this one, or itself if this is already the last step. */
    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { this }

    /** The step before this one, or itself if this is already the first step. */
    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { this }

    companion object {
        val count: Int get() = entries.size
    }
}
