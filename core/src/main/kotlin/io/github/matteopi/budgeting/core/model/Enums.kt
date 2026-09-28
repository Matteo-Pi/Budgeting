package io.github.matteopi.budgeting.core.model

enum class AccountType { CURRENT, SAVINGS, CASH, CREDIT_CARD, OTHER }

/** Which direction a category is used for. [BOTH] is only used by the system "Untracked" category. */
enum class Flow { OUT, IN, BOTH }

/**
 * How the forecast gets future amounts for a category.
 * LEARNED: estimated from history (and user predictions) — e.g. groceries, nights out, variable wages.
 * SCHEDULED_ONLY: only from recurring rules / scheduled items — e.g. rent, subscriptions, student loan.
 */
enum class ForecastMode { LEARNED, SCHEDULED_ONLY }

enum class TxKind {
    /** Ordinary spending or income. */
    NORMAL,

    /** One leg of a move between two of the user's accounts (two rows share a transferGroupId). */
    TRANSFER,

    /** Created by "Update balance": the gap between the app's balance and the real one. Category = Untracked. */
    RECONCILIATION,

    /** The balance an account started with when it was added. Excluded from all statistics. */
    OPENING_BALANCE,
}

enum class Regime { TERM, HOLIDAY }

/** Recurrence unit; combined with an interval (e.g. WEEKLY x2 = fortnightly). */
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** AUTO: becomes a real transaction on its date (direct debits). CONFIRM: user confirms it arrived (incomes). */
enum class PostingMode { AUTO, CONFIRM }

enum class PredictionMode {
    /** A starting guess. Blended with real data and fades as data accumulates. */
    PRIOR,

    /** Always use this number instead of the automatic estimate. */
    REPLACE,

    /** Use the automatic estimate scaled by adjustPercent (e.g. -20). */
    ADJUST_PERCENT,
}

/** What the app shows as "£0". */
enum class ZeroPoint {
    /** £0 = an empty account (the bank's own view). */
    ACCOUNT_BALANCE,

    /** £0 = the overdraft limit is used up. Displayed balance = real balance + overdraft limit. */
    OVERDRAFT_LIMIT,
}

enum class SinkingFundStatus { ACTIVE, COMPLETED, CANCELLED }

enum class ScenarioItemType { ONE_OFF, RECURRING, CATEGORY_ADJUST }
