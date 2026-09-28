package io.github.matteopi.budgeting.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.ui.theme.LocalMoneyColors

/**
 * Shows a [Money] amount with the app's colour convention (docs/PLAN.md §6.12): income green,
 * negative balances red, ordinary spending in the normal text colour. The green and red come
 * from the active theme's [LocalMoneyColors].
 *
 * @param isBalance true for an account/pool balance (a negative balance is shown in red); false
 * for a transaction amount (a positive amount, i.e. income, is shown in green).
 */
@Composable
fun MoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    isBalance: Boolean = false,
    showPlus: Boolean = false,
) {
    val money = LocalMoneyColors.current
    val color = when {
        isBalance && amount.isNegative -> money.negative
        !isBalance && amount.isPositive -> money.income
        else -> LocalContentColor.current
    }
    Text(text = amount.format(showPlus = showPlus), modifier = modifier, style = style, color = color)
}
