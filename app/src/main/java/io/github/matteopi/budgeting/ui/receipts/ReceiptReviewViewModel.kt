package io.github.matteopi.budgeting.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.receipts.Aliases
import io.github.matteopi.budgeting.core.receipts.DraftItem
import io.github.matteopi.budgeting.core.receipts.ProductAlias
import io.github.matteopi.budgeting.core.receipts.ProductNames
import io.github.matteopi.budgeting.core.receipts.ReceiptDraft
import io.github.matteopi.budgeting.core.receipts.ReceiptSource
import io.github.matteopi.budgeting.core.receipts.ReceiptSplit
import io.github.matteopi.budgeting.core.receipts.SplitItem
import io.github.matteopi.budgeting.core.receipts.Store
import io.github.matteopi.budgeting.data.repo.AccountRepository
import io.github.matteopi.budgeting.data.repo.CategoryRepository
import io.github.matteopi.budgeting.data.repo.DefaultAccount
import io.github.matteopi.budgeting.data.repo.ReceiptRepository
import io.github.matteopi.budgeting.data.repo.TransactionRepository
import io.github.matteopi.budgeting.data.settings.SettingsStore
import io.github.matteopi.budgeting.domain.Clock
import io.github.matteopi.budgeting.ui.activity.toInputText
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives [ReceiptReviewScreen] (docs/PLAN.md §10.4): reads the receipt with [loadDraft] (OCR or
 * text parsing, which the tests replace), fills in names and categories from learned aliases,
 * lets the user fix everything, and on [save] stores the receipt, its items and the per-category
 * transactions, learning product names for next time.
 *
 * The raw fields are in [edit], a plain `MutableStateFlow` so typing is never delayed; everything
 * worked out from them and the stored data is in [derived].
 */
class ReceiptReviewViewModel(
    private val receiptRepository: ReceiptRepository,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    private val loadDraft: suspend () -> ReceiptDraft,
    /** The cache file the receipt was read from; deleted once the receipt is saved. Null when there is none. */
    private val sourceFile: File? = null,
) : ViewModel() {

    /** Today, for limiting the date field (a receipt cannot be from the future). */
    val today: LocalDate get() = clock.today()

    private val mutableEdit = MutableStateFlow(ReviewEdit(date = clock.today()))
    val edit: StateFlow<ReviewEdit> = mutableEdit.asStateFlow()

    private val mutablePhase = MutableStateFlow<ReviewPhase>(ReviewPhase.Loading)
    val phase: StateFlow<ReviewPhase> = mutablePhase.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    private val mutableSaveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = mutableSaveError.asStateFlow()

    val derived: StateFlow<ReviewDerived> = combine(
        mutableEdit,
        accountRepository.observeAccounts(),
        categoryRepository.observeCategories(),
        transactionRepository.observeAll(),
    ) { edit, accounts, categories, transactions ->
        ReceiptReviewRules.derive(edit, accounts, categories, transactions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewDerived())

    // Loaded once with the receipt; used to suggest names and categories again if the store changes.
    private var aliases = Aliases(emptyList())
    private var mostUsedCategoryByStore: Map<String, Long> = emptyMap()
    private var itemCategoryIds: Set<Long> = emptySet()
    private var fallbackCategoryId: Long? = null
    private var nextKey = 1L

    init {
        load()
    }

    /** Reads the receipt again (the Retry button). */
    fun load() {
        mutablePhase.value = ReviewPhase.Loading
        viewModelScope.launch {
            try {
                val draft = loadDraft()
                prepareSuggestions()
                mutableEdit.value = buildEdit(draft)
                mutablePhase.value = ReviewPhase.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoTextException) {
                mutablePhase.value = ReviewPhase.Failed(e.message ?: ReceiptReader.NO_TEXT_PHOTO)
            } catch (e: Exception) {
                mutablePhase.value = ReviewPhase.Failed("Couldn't read this receipt — try a sharper photo")
            }
        }
    }

    /** From the error screen: skip reading and type the receipt in by hand. */
    fun startManually() {
        viewModelScope.launch {
            prepareSuggestions()
            val accounts = accountRepository.observeAccounts().first()
            val defaultId = DefaultAccount.resolveId(accounts, settingsStore.budgetSettings.first().defaultAccountId)
            nextKey = 1L
            mutableEdit.value = ReviewEdit(date = clock.today(), selectedAccountId = defaultId, source = ReceiptSource.PHOTO)
            mutablePhase.value = ReviewPhase.Ready
        }
    }

    private suspend fun prepareSuggestions() {
        val categories = categoryRepository.observeCategories().first()
        val valid = ReceiptReviewRules.itemCategories(categories)
        itemCategoryIds = valid.map { it.id }.toSet()
        fallbackCategoryId = (valid.firstOrNull { it.name == "Groceries" } ?: valid.firstOrNull())?.id

        val products = receiptRepository.observeProducts().first().associateBy { it.id }
        aliases = Aliases(
            receiptRepository.observeAliases().first().mapNotNull { record ->
                val product = products[record.productId] ?: return@mapNotNull null
                val store = Store.entries.firstOrNull { it.name == record.store } ?: return@mapNotNull null
                ProductAlias(store, record.rawNameKey, product.id, product.name, product.categoryId)
            },
        )

        val storeByReceipt = receiptRepository.observeReceipts().first().associate { it.id to it.store }
        val counts = HashMap<String, HashMap<Long, Int>>()
        for (item in receiptRepository.observeAllItems().first()) {
            val categoryId = item.categoryId ?: continue
            val store = storeByReceipt[item.receiptId] ?: continue
            val byCategory = counts.getOrPut(store) { HashMap() }
            byCategory[categoryId] = (byCategory[categoryId] ?: 0) + 1
        }
        mostUsedCategoryByStore = counts.mapNotNull { (store, byCategory) ->
            val best = byCategory.entries
                .filter { it.key in itemCategoryIds }
                .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
            best?.let { store to it.key }
        }.toMap()
    }

    private suspend fun buildEdit(draft: ReceiptDraft): ReviewEdit {
        val today = clock.today()
        val warnings = draft.warnings.toMutableList()
        val draftDate = draft.date
        val date = when {
            draftDate == null -> {
                warnings += "No date found on the receipt, so today's date is used."
                today
            }
            draftDate.isAfter(today) -> {
                warnings += "The date on the receipt looked wrong (in the future), so today's date is used."
                today
            }
            else -> draftDate
        }
        val accounts = accountRepository.observeAccounts().first()
        val defaultId = DefaultAccount.resolveId(accounts, settingsStore.budgetSettings.first().defaultAccountId)
        nextKey = 1L
        val items = draft.items.map { suggest(rawItem(it), draft.store) }
        return ReviewEdit(
            store = draft.store,
            date = date,
            totalText = draft.total?.toInputText().orEmpty(),
            items = items,
            selectedAccountId = defaultId,
            source = draft.source,
            warnings = warnings,
        )
    }

    private fun rawItem(item: DraftItem): ReviewItem = ReviewItem(
        key = nextKey++,
        rawName = item.rawName,
        name = item.rawName,
        nameEdited = false,
        fromAlias = false,
        quantity = item.quantity.toString(),
        price = item.price.toInputText(),
        discount = if (item.discount.isZero) "" else item.discount.abs().toInputText(),
        categoryId = null,
        categorySource = CategorySource.NONE,
        weightGrams = item.weightGrams,
        unitPrice = item.unitPrice,
        confidence = item.confidence,
    )

    private fun guessCategory(store: Store): Long? = mostUsedCategoryByStore[store.name] ?: fallbackCategoryId

    /** Fills in the name and category of an untouched item from a learned alias, else from the shop's usual category. */
    private fun suggest(item: ReviewItem, store: Store): ReviewItem {
        if (item.nameEdited || item.categorySource == CategorySource.USER) return item
        val alias = if (item.rawName.isBlank()) null else aliases.suggest(item.rawName, store)
        val aliasCategory = alias?.categoryId?.takeIf { it in itemCategoryIds }
        val guess = guessCategory(store)
        return item.copy(
            name = alias?.productName ?: item.rawName,
            fromAlias = alias != null,
            categoryId = aliasCategory ?: guess,
            categorySource = when {
                aliasCategory != null -> CategorySource.ALIAS
                guess != null -> CategorySource.GUESS
                else -> CategorySource.NONE
            },
        )
    }

    // ---- editing --------------------------------------------------------------------------

    fun setStore(store: Store) {
        mutableEdit.update { edit ->
            edit.copy(store = store, items = edit.items.map { suggest(it, store) })
        }
    }

    fun setDate(date: LocalDate) = mutableEdit.update { it.copy(date = date) }

    fun setTotal(text: String) = mutableEdit.update { it.copy(totalText = text) }

    fun setAccount(accountId: Long) = mutableEdit.update { it.copy(selectedAccountId = accountId, accountPinned = true) }

    fun setReplace(replace: Boolean) = mutableEdit.update { it.copy(replace = replace) }

    private fun updateItem(key: Long, change: (ReviewItem) -> ReviewItem) {
        mutableEdit.update { edit -> edit.copy(items = edit.items.map { if (it.key == key) change(it) else it }) }
    }

    fun setName(key: Long, name: String) = updateItem(key) { it.copy(name = name, nameEdited = true) }

    fun setQuantity(key: Long, text: String) = updateItem(key) { it.copy(quantity = text) }

    fun setPrice(key: Long, text: String) = updateItem(key) { it.copy(price = text) }

    fun setDiscount(key: Long, text: String) = updateItem(key) { it.copy(discount = text) }

    fun setCategory(key: Long, categoryId: Long) =
        updateItem(key) { it.copy(categoryId = categoryId, categorySource = CategorySource.USER) }

    /** Gives [categoryId] to every item whose category is only a guess (or missing). */
    fun setCategoryForAllUnset(categoryId: Long) {
        mutableEdit.update { edit ->
            edit.copy(
                items = edit.items.map {
                    if (it.categorySource == CategorySource.NONE || it.categorySource == CategorySource.GUESS) {
                        it.copy(categoryId = categoryId, categorySource = CategorySource.USER)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun deleteItem(key: Long) = mutableEdit.update { edit -> edit.copy(items = edit.items.filter { it.key != key }) }

    fun addItem() {
        val store = mutableEdit.value.store
        val guess = guessCategory(store)
        val item = ReviewItem(
            key = nextKey++,
            rawName = "",
            name = "",
            nameEdited = true,
            fromAlias = false,
            quantity = "1",
            price = "",
            discount = "",
            categoryId = guess,
            categorySource = if (guess != null) CategorySource.GUESS else CategorySource.NONE,
        )
        mutableEdit.update { it.copy(items = it.items + item) }
    }

    // ---- saving ---------------------------------------------------------------------------

    /**
     * Saves the receipt: learns product names, then stores the receipt with its items and one
     * transaction per category (replacing the matched transaction when asked), and calls
     * [onSaved] on success. Does nothing while the receipt cannot be saved.
     */
    fun save(onSaved: () -> Unit = {}) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        mutableSaveError.value = null
        viewModelScope.launch {
            try {
                val edit = mutableEdit.value
                val categories = categoryRepository.observeCategories().first()
                val derivedNow = ReceiptReviewRules.derive(
                    edit,
                    accountRepository.observeAccounts().first(),
                    categories,
                    transactionRepository.observeAll().first(),
                )
                val problem = derivedNow.problem
                if (problem != null) {
                    mutableSaveError.value = problem
                } else {
                    persist(edit, derivedNow, categories)
                    sourceFile?.let { runCatching { it.delete() } }
                    onSaved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableSaveError.value = "Couldn't save the receipt: ${e.message ?: "unknown error"}"
            } finally {
                mutableSaving.value = false
            }
        }
    }

    private suspend fun persist(edit: ReviewEdit, derivedNow: ReviewDerived, categories: List<Category>) {
        val store = edit.store
        val storeName = receiptStoreName(store)
        val accountId = requireNotNull(derivedNow.accountId)
        val total = requireNotNull(ReceiptReviewRules.parseTotal(edit.totalText))
        val now = clock.nowMillis()

        // Learn first, so the receipt's items can point at their products.
        val productIds = HashMap<Long, Long>()
        for (item in edit.items) {
            val name = item.name.trim()
            val key = ProductNames.normalise(item.rawName.ifBlank { name })
            val learn = item.nameEdited || item.fromAlias || item.categorySource == CategorySource.USER
            if (learn && key.isNotEmpty() && name.isNotEmpty()) {
                val categoryForProduct = when (item.categorySource) {
                    CategorySource.USER, CategorySource.ALIAS -> item.categoryId
                    CategorySource.GUESS, CategorySource.NONE -> null
                }
                productIds[item.key] = receiptRepository.learnProduct(store.name, key, name, categoryForProduct)
            }
        }

        val receiptItems = edit.items.map { item ->
            ReceiptItem(
                receiptId = 0,
                productId = productIds[item.key],
                rawName = item.rawName.ifBlank { item.name.trim() },
                quantity = ReceiptReviewRules.parseQuantity(item.quantity) ?: 1,
                weightGrams = item.weightGrams,
                unitPrice = item.unitPrice,
                price = requireNotNull(ReceiptReviewRules.parsePrice(item.price)),
                discount = -requireNotNull(ReceiptReviewRules.parseDiscount(item.discount)),
                categoryId = item.categoryId,
            )
        }
        val splitItems = receiptItems.map { SplitItem(it.categoryId, it.price, it.discount) }
        val parts = ReceiptSplit.byCategory(splitItems, total)
        val categoryNames = categories.associate { it.id to it.name }
        val transactions = parts.map { part ->
            val label = if (parts.size == 1) {
                storeName
            } else {
                "$storeName · ${part.categoryId?.let { categoryNames[it] } ?: "Other"}"
            }
            Transaction(
                accountId = accountId,
                amount = part.amount,
                date = edit.date,
                createdAtEpochMillis = now,
                categoryId = part.categoryId,
                label = label,
                kind = TxKind.NORMAL,
            )
        }

        receiptRepository.saveReceipt(
            receipt = Receipt(
                store = store.name,
                date = edit.date,
                total = total,
                source = edit.source.name,
                createdAtEpochMillis = now,
            ),
            items = receiptItems,
            splitTransactions = transactions,
            replaceTransactionId = if (edit.replace) derivedNow.match?.transactionId else null,
        )
    }

    companion object {
        fun factory(path: String, kind: ReceiptInputKind): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BudgetApp
                val container = app.container
                val file = File(path)
                ReceiptReviewViewModel(
                    receiptRepository = container.receiptRepository,
                    transactionRepository = container.transactionRepository,
                    accountRepository = container.accountRepository,
                    categoryRepository = container.categoryRepository,
                    settingsStore = container.settingsStore,
                    clock = container.clock,
                    loadDraft = { ReceiptReader(app).read(file, kind) },
                    sourceFile = file,
                )
            }
        }
    }
}
