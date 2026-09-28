package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.Money

/**
 * Parsing for onboarding's optional amount fields (weekly spending estimates, quick-add costs
 * and income): blank means "not entered yet", as opposed to £0, and anything else must parse as
 * a non-negative [Money].
 */
object AmountText {
    /** The entered amount, or null if [text] is blank or not a valid non-negative amount. */
    fun parsePositiveOrNull(text: String): Money? = Money.parse(text)?.takeIf { !it.isNegative }

    /** True for blank text (nothing entered yet) or a valid non-negative amount. */
    fun isValidOrBlank(text: String): Boolean = text.isBlank() || parsePositiveOrNull(text) != null
}
