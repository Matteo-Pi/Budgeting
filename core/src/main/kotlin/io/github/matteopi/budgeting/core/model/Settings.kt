package io.github.matteopi.budgeting.core.model

import java.time.LocalDate

/** Settings that affect calculations. (Privacy/UI-only settings live in the app.) */
data class BudgetSettings(
    val zeroPoint: ZeroPoint = ZeroPoint.ACCOUNT_BALANCE,
    /** Safety margin kept above the zero point when working out safe-to-spend. */
    val buffer: Money = Money.pounds(100),
    val defaultAccountId: Long? = null,
    /** Data before this date is ignored by the forecast (logging wasn't complete). Null = first transaction. */
    val historyStartDate: LocalDate? = null,
    val simulations: Int = 1000,
    val forecastHorizonDays: Int = 182,
)
