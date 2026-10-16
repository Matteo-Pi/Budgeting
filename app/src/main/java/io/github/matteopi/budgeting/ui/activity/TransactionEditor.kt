package io.github.matteopi.budgeting.ui.activity

import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Carries out [TransactionEdit]s through [TransactionRepository]. Shared by Activity, an account's page and
 * its history screen, so an edit means the same thing wherever the sheet was opened.
 */
class TransactionEditor(private val repository: TransactionRepository) {

    /**
     * Applies [edit]. Returns a [SimilarPrompt] when [edit] changed the category of a NORMAL transaction and
     * other NORMAL transactions share its merchant (and would change), else null.
     */
    suspend fun apply(edit: TransactionEdit): SimilarPrompt? {
        when (edit) {
            is TransactionEdit.Single -> {
                repository.update(
                    edit.original.copy(
                        amount = edit.amount,
                        categoryId = edit.categoryId,
                        accountId = edit.accountId,
                        date = edit.date,
                        label = edit.label,
                        note = edit.note,
                    ),
                )
                return similarPrompt(edit)
            }
            is TransactionEdit.ToTransfer -> {
                repository.convertToTransfer(
                    edited = edit.original.copy(
                        amount = edit.amount,
                        categoryId = null,
                        accountId = edit.accountId,
                        date = edit.date,
                        label = edit.label,
                        note = edit.note,
                    ),
                    otherAccountId = edit.otherAccountId,
                )
            }
            is TransactionEdit.Transfer -> repository.updateTransfer(
                legId = edit.legId,
                fromAccountId = edit.fromAccountId,
                toAccountId = edit.toAccountId,
                amount = edit.amount,
                date = edit.date,
                label = edit.label,
            )
            is TransactionEdit.ToNormal -> repository.convertToNormal(edit.keepId, edit.categoryId)
            is TransactionEdit.Delete -> repository.delete(edit.transactionId)
        }
        return null
    }

    /** Answers a [SimilarPrompt]: change the other transactions too ([changeAll]) and/or remember the merchant's category for imports. */
    suspend fun resolve(prompt: SimilarPrompt, changeAll: Boolean, rememberRule: Boolean) {
        if (changeAll) {
            repository.recategorise(prompt.merchantKey, prompt.categoryId, prompt.exceptId, rememberRule)
        } else if (rememberRule) {
            repository.rememberMerchantRule(prompt.merchantKey, prompt.categoryId)
        }
    }

    private suspend fun similarPrompt(edit: TransactionEdit.Single): SimilarPrompt? {
        val original = edit.original
        val categoryId = edit.categoryId ?: return null
        if (original.kind != TxKind.NORMAL || EditRules.isReceiptPart(original) || categoryId == original.categoryId) return null
        val key = MerchantKey.normalise(edit.label)
        if (key.isBlank()) return null
        val count = repository.countRecategorisable(key, categoryId, original.id)
        return if (count > 0) SimilarPrompt(key, categoryId, original.id, count) else null
    }
}

/**
 * What a screen's view model keeps to host the edit sheet: runs edits on [scope] and holds the "change similar"
 * question until the user answers it. The shared [TransactionEditHost] composable reads [prompt] and calls
 * [submit] / [answer] / [dismissPrompt].
 */
class TransactionEditController(
    private val editor: TransactionEditor,
    private val scope: CoroutineScope,
) {
    private val promptState = MutableStateFlow<SimilarPrompt?>(null)

    /** The open "change similar" question, or null. */
    val prompt: StateFlow<SimilarPrompt?> = promptState.asStateFlow()

    fun submit(edit: TransactionEdit) {
        scope.launch { promptState.value = editor.apply(edit) }
    }

    fun answer(changeAll: Boolean, rememberRule: Boolean) {
        val open = promptState.value ?: return
        promptState.value = null
        scope.launch { editor.resolve(open, changeAll, rememberRule) }
    }

    fun dismissPrompt() {
        promptState.value = null
    }
}
