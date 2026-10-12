package io.github.matteopi.budgeting.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations for every screen in docs/PLAN.md §6.5. */
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Activity : Route
    @Serializable data object Forecast : Route
    @Serializable data object Plan : Route
    @Serializable data object Insights : Route
    @Serializable data object Settings : Route

    @Serializable data class AccountDetail(val accountId: Long) : Route
    @Serializable data class AccountEdit(val accountId: Long? = null) : Route

    @Serializable data object Predictions : Route
    @Serializable data object Scenarios : Route
    @Serializable data class ScenarioEdit(val scenarioId: Long? = null) : Route
    @Serializable data object Timeline : Route
    @Serializable data object Accuracy : Route

    @Serializable data object RecurringList : Route
    @Serializable data class RecurringEdit(val ruleId: Long? = null) : Route
    @Serializable data object ScheduledList : Route
    @Serializable data class ScheduledEdit(val itemId: Long? = null) : Route
    @Serializable data object TermDates : Route
    @Serializable data object StudentLoanHelper : Route
    @Serializable data object SinkingFunds : Route
    @Serializable data class SinkingFundEdit(val fundId: Long? = null) : Route

    @Serializable data object Categories : Route
    @Serializable data object Presets : Route
    @Serializable data object Backup : Route
    @Serializable data object StatementImport : Route
    @Serializable data object Onboarding : Route

    /** Receipt capture (docs/PLAN.md §10): choose camera or gallery, then continue to [ReceiptReview]. */
    @Serializable data object ReceiptScan : Route

    /** Review a read receipt before saving: [path] is a file in the app's cache, [kind] a `ReceiptInputKind` name. */
    @Serializable data class ReceiptReview(val path: String, val kind: String) : Route
}
