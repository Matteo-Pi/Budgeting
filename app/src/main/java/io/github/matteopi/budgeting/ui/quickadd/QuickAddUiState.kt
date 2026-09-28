package io.github.matteopi.budgeting.ui.quickadd

import io.github.matteopi.budgeting.core.entry.AmountEntry
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Preset
import java.time.LocalDate

/** State for [QuickAddScreen]. See docs/PLAN.md §6.6. */
data class QuickAddUiState(
    val noAccounts: Boolean = false,
    val flow: CategoryFlow = CategoryFlow.OUT,
    val amountEntry: AmountEntry = AmountEntry(),
    /** Top 8 categories for the current [flow], ranked by [io.github.matteopi.budgeting.core.entry.CategoryRanking]. */
    val rankedCategories: List<Category> = emptyList(),
    /** The rest, shown behind a "More" chip. */
    val moreCategories: List<Category> = emptyList(),
    val selectedCategoryId: Long? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    val presets: List<Preset> = emptyList(),
    val label: String = "",
    val date: LocalDate = LocalDate.MIN,
    val today: LocalDate = LocalDate.MIN,
    val saving: Boolean = false,
) {
    val canSave: Boolean
        get() = !saving && amountEntry.money != null && selectedCategoryId != null && selectedAccountId != null
}
