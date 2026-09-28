package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.model.Account

/**
 * Which account(s) a chart or balance number is about. Persisted as a string, so the encoding
 * (`"total"`, `"each"`, `"account:12"`) must never change.
 *
 * Two settings use it: `chartAccountView` (any of the three) and `balanceView` ([Each] makes no
 * sense for a single number, so parsing and resolving it as a balance view turns it into [Total]).
 */
sealed interface AccountView {
    /** The pool total (accounts included in safe-to-spend) — the classic view. */
    data object Total : AccountView

    /** One line per account (charts only). */
    data object Each : AccountView

    /** Just this account. */
    data class Single(val accountId: Long) : AccountView

    companion object {
        private const val TOTAL = "total"
        private const val EACH = "each"
        private const val ACCOUNT_PREFIX = "account:"

        fun encode(view: AccountView): String = when (view) {
            Total -> TOTAL
            Each -> EACH
            is Single -> ACCOUNT_PREFIX + view.accountId
        }

        /** The view [raw] describes, or null when it is missing or not understood (e.g. from a newer app version). */
        fun parseOrNull(raw: String?): AccountView? {
            if (raw == null) return null
            return when {
                raw == TOTAL -> Total
                raw == EACH -> Each
                raw.startsWith(ACCOUNT_PREFIX) -> raw.removePrefix(ACCOUNT_PREFIX).toLongOrNull()?.let { Single(it) }
                else -> null
            }
        }

        /** For the chart setting: anything unknown becomes [Total]. */
        fun parseChart(raw: String?): AccountView = parseOrNull(raw) ?: Total

        /** For the balance setting: [Each] is not allowed, and anything unknown becomes [Total]. */
        fun parseBalance(raw: String?): AccountView {
            val view = parseOrNull(raw)
            return if (view == null || view == Each) Total else view
        }
    }
}

/**
 * This view made safe for the accounts that exist now: a [AccountView.Single] pointing at a
 * missing or archived account becomes [AccountView.Total], and so does [AccountView.Each] unless
 * [allowEach].
 */
fun AccountView.resolve(accounts: List<Account>, allowEach: Boolean): AccountView = when (this) {
    AccountView.Total -> AccountView.Total
    AccountView.Each -> if (allowEach) AccountView.Each else AccountView.Total
    is AccountView.Single ->
        if (accounts.any { it.id == accountId && !it.archived }) this else AccountView.Total
}

/** [resolve] for the chart setting. */
fun AccountView.resolveForChart(accounts: List<Account>): AccountView = resolve(accounts, allowEach = true)

/** [resolve] for the balance setting. */
fun AccountView.resolveForBalance(accounts: List<Account>): AccountView = resolve(accounts, allowEach = false)
