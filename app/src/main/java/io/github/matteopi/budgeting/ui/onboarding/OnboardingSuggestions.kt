package io.github.matteopi.budgeting.ui.onboarding

import io.github.matteopi.budgeting.core.model.AccountType

/** One quick-add suggestion chip on the Accounts step; tapping it prefills the add-account dialog. */
data class AccountSuggestion(val name: String, val type: AccountType)

val ACCOUNT_SUGGESTIONS: List<AccountSuggestion> = listOf(
    AccountSuggestion("Santander current", AccountType.CURRENT),
    AccountSuggestion("Second current", AccountType.CURRENT),
    AccountSuggestion("Savings", AccountType.SAVINGS),
)

/**
 * One quick-add suggestion chip on the Regular costs step; tapping it prefills the label and,
 * where a category of that name exists, the category. [categoryName] must match a
 * [io.github.matteopi.budgeting.core.model.DefaultCategories] name.
 */
data class RegularCostSuggestion(val label: String, val categoryName: String)

val REGULAR_COST_SUGGESTIONS: List<RegularCostSuggestion> = listOf(
    RegularCostSuggestion("Rent", "Rent & bills"),
    RegularCostSuggestion("Phone", "Rent & bills"),
    RegularCostSuggestion("Spotify", "Subscriptions"),
    RegularCostSuggestion("Netflix", "Subscriptions"),
    RegularCostSuggestion("Gym", "Subscriptions"),
)
