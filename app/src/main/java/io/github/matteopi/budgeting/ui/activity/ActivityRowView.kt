package io.github.matteopi.budgeting.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.ui.components.MoneyText

/**
 * One row on Home's recent activity, the Activity list or an account's page: a single transaction
 * or a transfer. A transfer shows the amount received unless [perspectiveAccountId] is the account
 * it left, where it shows the amount leaving.
 */
@Composable
fun ActivityRowView(
    row: ActivityRow,
    categoriesById: Map<Long, Category>,
    accountsById: Map<Long, Account>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    perspectiveAccountId: Long? = null,
) {
    val (title, subtitle, amount, dotColor, badge) = when (row) {
        is ActivityRow.Single -> {
            val category = row.tx.categoryId?.let { categoriesById[it] }
            val title = row.tx.label.ifBlank { category?.name ?: "Untitled" }
            val accountName = accountsById[row.tx.accountId]?.name.orEmpty()
            val badge = when (row.tx.kind) {
                TxKind.RECONCILIATION -> "Reconciliation"
                TxKind.OPENING_BALANCE -> "Opening balance"
                else -> null
            }
            val color = category?.colorArgb?.let { Color(it or 0xFF000000) } ?: Color(0xFF9E9E9E)
            RowContent(title, accountName, row.tx.amount, color, badge)
        }
        is ActivityRow.Transfer -> {
            val fromName = accountsById[row.from.accountId]?.name.orEmpty()
            val toName = accountsById[row.to.accountId]?.name.orEmpty()
            val title = row.from.label.ifBlank { "Transfer" }
            val amount = if (perspectiveAccountId == row.from.accountId) row.from.amount else row.to.amount
            RowContent(title, "$fromName → $toName", amount, Color(0xFF9E9E9E), null)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(10.dp)) {}
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            val subtitleText = if (badge != null) "$subtitle · $badge" else subtitle
            if (subtitleText.isNotBlank()) {
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MoneyText(amount = amount, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class RowContent(
    val title: String,
    val subtitle: String,
    val amount: Money,
    val dotColor: Color,
    val badge: String?,
)
