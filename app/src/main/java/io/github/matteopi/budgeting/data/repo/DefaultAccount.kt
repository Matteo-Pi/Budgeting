package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Account

/**
 * The one place that decides which account is "the default" (quick add, presets, the widget's
 * preset buttons, new plan items, onboarding forms, the forecast's default account).
 *
 * The rule: the account chosen in Settings if it still exists and isn't archived, otherwise the
 * earliest-created (smallest id) account that isn't archived. So with no choice made, the default
 * is simply the first account the user created.
 *
 * This matches `AccountSplit.defaultAccountId` in `core` (which the forecast uses to place learned
 * spending) whenever the chosen account is a live pool account or nothing is chosen and the first
 * account is in the pool. The two differ only if the user picks a non-pool account or the oldest
 * account is outside the pool. [AccountRepository] keeps the persisted setting pointing at
 * whatever this resolves to, so the forecast sees the same account.
 */
object DefaultAccount {
    /** The id of the default account, or null when there is no account that isn't archived. */
    fun resolveId(accounts: List<Account>, configuredId: Long?): Long? {
        val live = accounts.filter { !it.archived }
        if (configuredId != null && live.any { it.id == configuredId }) return configuredId
        return live.minOfOrNull { it.id }
    }

    /** The default account itself, or null when there is no account that isn't archived. */
    fun resolve(accounts: List<Account>, configuredId: Long?): Account? {
        val id = resolveId(accounts, configuredId) ?: return null
        return accounts.firstOrNull { it.id == id }
    }
}
