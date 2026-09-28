package io.github.matteopi.budgeting.backup

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Transaction

/** Formats transactions as CSV: date, account, category, label, note, amount, kind (docs/PLAN.md §6.10). */
object CsvExport {
    private const val HEADER = "Date,Account,Category,Label,Note,Amount,Kind"

    fun transactionsToCsv(
        transactions: List<Transaction>,
        accounts: List<Account>,
        categories: List<Category>,
    ): String {
        val accountNames = accounts.associate { it.id to it.name }
        val categoryNames = categories.associate { it.id to it.name }
        val lines = mutableListOf(HEADER)
        transactions
            .sortedWith(compareBy({ it.date }, { it.createdAtEpochMillis }))
            .forEach { tx ->
                val fields = listOf(
                    tx.date.toString(),
                    accountNames[tx.accountId].orEmpty(),
                    tx.categoryId?.let { categoryNames[it] }.orEmpty(),
                    tx.label,
                    tx.note,
                    tx.amount.format(),
                    tx.kind.name,
                )
                lines += fields.joinToString(",") { csvField(it) }
            }
        return lines.joinToString("\n")
    }

    /** RFC-4180 quoting: wraps in quotes (doubling internal quotes) when the field needs it. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
