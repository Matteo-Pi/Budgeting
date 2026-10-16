package io.github.matteopi.budgeting.data.settings

import io.github.matteopi.budgeting.core.model.Account

/**
 * Which account(s) a chart or balance number is about. Persisted as a string, so the encoding
 * (`"all"`, `"pool"`, `"each"`, `"account:12"`) must never change. `"total"` is legacy: it was
 * always shown to the user as "All accounts", so it is read as [All] (and never written again).
 *
 * Two settings use it: `chartAccountView` (any of the four) and `balanceView` ([Each] makes no
 * sense for a single number, so parsing and resolving it as a balance view turns it into [All]).
 */
sealed interface AccountView {
    /** Every non-archived account, whether or not it is included in safe-to-spend — the default. */
    data object All : AccountView

    /** The accounts included in safe-to-spend (the pool). */
    data object Pool : AccountView

    /** One line per account (charts only). */
    data object Each : AccountView

    /** Just this account. */
    data class Single(val accountId: Long) : AccountView

    companion object {
        private const val ALL = "all"
        private const val POOL = "pool"
        private const val LEGACY_TOTAL = "total"
        private const val EACH = "each"
        private const val ACCOUNT_PREFIX = "account:"

        fun encode(view: AccountView): String = when (view) {
            All -> ALL
            Pool -> POOL
            Each -> EACH
            is Single -> ACCOUNT_PREFIX + view.accountId
        }

        /** The view [raw] describes, or null when it is missing or not understood (e.g. from a newer app version). */
        fun parseOrNull(raw: String?): AccountView? {
            if (raw == null) return null
            return when {
                raw == ALL || raw == LEGACY_TOTAL -> All
                raw == POOL -> Pool
                raw == EACH -> Each
                raw.startsWith(ACCOUNT_PREFIX) -> raw.removePrefix(ACCOUNT_PREFIX).toLongOrNull()?.let { Single(it) }
                else -> null
            }
        }

        /** For the chart setting: anything unknown becomes [All]. */
        fun parseChart(raw: String?): AccountView = parseOrNull(raw) ?: All

        /** For the balance setting: [Each] is not allowed, and anything unknown becomes [All]. */
        fun parseBalance(raw: String?): AccountView {
            val view = parseOrNull(raw)
            return if (view == null || view == Each) All else view
        }
    }
}

/**
 * This view made safe for the accounts that exist now: a [AccountView.Single] pointing at a
 * missing or archived account becomes [AccountView.All], and so does [AccountView.Each] unless
 * [allowEach].
 */
fun AccountView.resolve(accounts: List<Account>, allowEach: Boolean): AccountView = when (this) {
    AccountView.All -> AccountView.All
    AccountView.Pool -> AccountView.Pool
    AccountView.Each -> if (allowEach) AccountView.Each else AccountView.All
    is AccountView.Single ->
        if (accounts.any { it.id == accountId && !it.archived }) this else AccountView.All
}

/**
 * True when some non-archived account is left out of safe-to-spend, so [AccountView.Pool] shows
 * something different from [AccountView.All]. When it is false the two are identical and the
 * selectors only offer "All accounts".
 */
fun poolDiffersFromAll(accounts: List<Account>): Boolean = accounts.any { !it.archived && !it.includeInSafeToSpend }

/**
 * [AccountView.Pool] shown as [AccountView.All] when they are the same ([poolDiffersFromAll] is
 * false) or when there are fewer than two accounts, so no selector is offered to get out of it;
 * any other view is unchanged.
 */
fun AccountView.collapsePool(accounts: List<Account>): AccountView =
    if (this == AccountView.Pool && (accounts.count { !it.archived } < 2 || !poolDiffersFromAll(accounts))) {
        AccountView.All
    } else {
        this
    }

/** [resolve] for the chart setting. */
fun AccountView.resolveForChart(accounts: List<Account>): AccountView = resolve(accounts, allowEach = true)

/** [resolve] for the balance setting. */
fun AccountView.resolveForBalance(accounts: List<Account>): AccountView = resolve(accounts, allowEach = false)

/** The text for this view in a selector: "All accounts", "Safe-to-spend accounts", "Each account", or the account's name. */
fun AccountView.displayName(accounts: List<Account>): String = when (this) {
    AccountView.All -> "All accounts"
    AccountView.Pool -> "Safe-to-spend accounts"
    AccountView.Each -> "Each account"
    is AccountView.Single -> accounts.firstOrNull { it.id == accountId }?.name ?: "All accounts"
}
