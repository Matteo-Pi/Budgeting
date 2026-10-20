package io.github.matteopi.budgeting.data.settings

/**
 * The app's three sections (docs/PLAN.md §11.8, §13.8): the budget, the recipe book and the planner. The last one
 * used opens next time. Persisted by [name], so never rename an entry. Not part of backups.
 */
enum class AppSection {
    BUDGETING,
    RECIPES,
    PLANNER,
    ;

    companion object {
        val DEFAULT: AppSection = BUDGETING

        /** The section called [name], or [DEFAULT] when [name] is missing or unknown (e.g. from a newer app version). */
        fun parse(name: String?): AppSection = AppSection.entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
