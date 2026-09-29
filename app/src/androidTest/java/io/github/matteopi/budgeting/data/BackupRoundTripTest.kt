package io.github.matteopi.budgeting.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.backup.AppSettingsCodec
import io.github.matteopi.budgeting.backup.BackupViewModel
import io.github.matteopi.budgeting.core.backup.BackupCodec
import io.github.matteopi.budgeting.core.backup.BackupData
import io.github.matteopi.budgeting.core.backup.BackupDecodeResult
import io.github.matteopi.budgeting.core.backup.BackupMapper
import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.DefaultCategories
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.repo.SelectedImportRow
import io.github.matteopi.budgeting.data.settings.AccountView
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import io.github.matteopi.budgeting.testing.DataTestSupport.transactions
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Backup export and restore end to end: [io.github.matteopi.budgeting.data.repo.BackupRepository]
 * -> `BackupMapper` -> `BackupCodec` (JSON) -> decode -> `replaceAll`, against the real database.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    // ---- populating and comparing -------------------------------------------------------------

    private class Populated(
        val current: Account,
        val savings: Account,
        val cash: Account,
        val oldCard: Account,
        val importBatchId: Long,
    )

    private fun statementRows(): List<ParsedRow> = listOf(
        ParsedRow(today.minusDays(90), Money(-2_340), "CARD PAYMENT TO TESCO STORES 5421", null, 0),
        ParsedRow(today.minusDays(80), Money(10_000), "FASTER PAYMENTS RECEIPT FROM MUM", null, 1),
        ParsedRow(today.minusDays(79), Money(-385), "CARD PAYMENT TO GREGGS", null, 2),
    )

    /** Imports [rows] into [accountId] the way the wizard does (rows before the opening balance move it). */
    private suspend fun importStatement(
        accountId: Long,
        rows: List<ParsedRow>,
        categoryIds: List<Long?>,
        rules: List<MerchantRule>,
    ): Long {
        val keys = ImportDedupe.importKeys(accountId, rows)
        val selected = rows.mapIndexed { i, row -> SelectedImportRow(row, keys[i], categoryIds[i]) }
        val opening = transactions()
            .filter { it.accountId == accountId && it.kind == TxKind.OPENING_BALANCE }
            .minByOrNull { it.date }
        return container.importRepository.importRows(
            accountId = accountId,
            fileName = "santander.csv",
            rows = selected,
            rulesToRemember = rules,
            openingBalanceTransaction = opening,
            nowMillis = container.clock.nowMillis(),
        ).id
    }

    /** Puts something in every table, plus every app setting. */
    private suspend fun populateEverything(): Populated {
        val c = container
        val groceries = DataTestSupport.category("Groceries")
        val eatingOut = DataTestSupport.category("Eating out")
        val transport = DataTestSupport.category("Transport")
        val shopping = DataTestSupport.category("Shopping")
        val rent = DataTestSupport.category("Rent & bills")
        val loan = DataTestSupport.category("Student loan")
        val wages = DataTestSupport.category("Wages")
        val refunds = DataTestSupport.category("Refunds")

        // Accounts (one archived, one outside the pool) and categories (one custom, one archived).
        val current = createAccount(
            "Current", AccountType.CURRENT, overdraft = Money.pounds(500),
            opening = Money.pounds(1_200), date = today.minusDays(60), sortOrder = 0,
        )
        val savings = createAccount(
            "Savings", AccountType.SAVINGS, opening = Money.pounds(800), date = today.minusDays(60), sortOrder = 1,
        )
        val cash = createAccount(
            "Cash", AccountType.CASH, opening = Money.pounds(40), date = today.minusDays(60),
            includeInPool = false, sortOrder = 2,
        )
        val oldCard = createAccount(
            "Old card", AccountType.CREDIT_CARD, overdraft = Money.pounds(300),
            opening = Money(-5_000), date = today.minusDays(60), sortOrder = 3,
        )
        c.accountRepository.archive(oldCard.id)
        c.categoryRepository.add(
            Category(
                name = "Pets", flow = CategoryFlow.OUT, forecastMode = ForecastMode.LEARNED,
                colorArgb = 0xFF123456, sortOrder = 20,
            ),
        )
        c.categoryRepository.archive(refunds.id)

        // Plan: recurring rules (AUTO expense, CONFIRM major income with an end date, a transfer, an inactive one).
        val rentRuleId = c.planRepository.addRecurringRule(
            RecurringRule(
                label = "Rent", accountId = current.id, categoryId = rent.id, amount = Money.pounds(-450),
                frequency = Frequency.MONTHLY, startDate = today.minusDays(20), postingMode = PostingMode.AUTO,
            ),
        )
        c.planRepository.addRecurringRule(
            RecurringRule(
                label = "Shifts", accountId = current.id, categoryId = wages.id, amount = Money.pounds(200),
                frequency = Frequency.WEEKLY, interval = 2, startDate = today.plusDays(3), endDate = today.plusDays(300),
                postingMode = PostingMode.CONFIRM, isMajorIncome = true,
            ),
        )
        c.planRepository.addRecurringRule(
            RecurringRule(
                label = "Top up savings", accountId = current.id, categoryId = null, amount = Money.pounds(25),
                frequency = Frequency.MONTHLY, startDate = today.plusDays(10), postingMode = PostingMode.AUTO,
                toAccountId = savings.id,
            ),
        )
        c.planRepository.addRecurringRule(
            RecurringRule(
                label = "Old gym", accountId = current.id, categoryId = null, amount = Money.pounds(-20),
                frequency = Frequency.YEARLY, startDate = today.plusDays(40), postingMode = PostingMode.AUTO,
                active = false,
            ),
        )

        // Scheduled items: CONFIRM major income, one already posted, one skipped, a transfer.
        c.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Student loan 1", accountId = current.id, categoryId = loan.id, amount = Money.pounds(1_000),
                date = today.plusDays(20), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
            ),
        )
        val postedItemId = c.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Deposit refund", accountId = current.id, categoryId = null, amount = Money.pounds(150),
                date = today.minusDays(2), postingMode = PostingMode.AUTO, posted = true,
            ),
        )
        c.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Cancelled trip", accountId = current.id, categoryId = shopping.id, amount = Money.pounds(-80),
                date = today.plusDays(5), postingMode = PostingMode.CONFIRM, skipped = true,
            ),
        )
        c.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Move to savings", accountId = current.id, categoryId = null, amount = Money.pounds(60),
                date = today.plusDays(7), postingMode = PostingMode.AUTO, toAccountId = savings.id,
            ),
        )

        // Terms, predictions (all three modes), sinking funds (two statuses).
        c.planRepository.addTermPeriod(TermPeriod(name = "Autumn", startDate = today.minusDays(30), endDate = today.plusDays(60)))
        c.planRepository.addTermPeriod(TermPeriod(name = "Spring", startDate = today.plusDays(120), endDate = today.plusDays(200)))
        c.planRepository.upsertPrediction(
            Prediction(categoryId = groceries.id, regime = Regime.TERM, mode = PredictionMode.PRIOR, weeklyAmount = Money.pounds(45)),
        )
        c.planRepository.upsertPrediction(
            Prediction(categoryId = eatingOut.id, regime = Regime.HOLIDAY, mode = PredictionMode.REPLACE, weeklyAmount = Money.pounds(20)),
        )
        c.planRepository.upsertPrediction(
            Prediction(categoryId = transport.id, regime = Regime.TERM, mode = PredictionMode.ADJUST_PERCENT, adjustPercent = -20),
        )
        c.planRepository.addSinkingFund(
            SinkingFund(
                name = "Interrail", target = Money.pounds(600), startDate = today, targetDate = today.plusDays(120),
                categoryId = transport.id, status = SinkingFundStatus.ACTIVE,
            ),
        )
        c.planRepository.addSinkingFund(
            SinkingFund(
                name = "Holiday (done)", target = Money.pounds(300), startDate = today.minusDays(100),
                targetDate = today.minusDays(10), categoryId = null, status = SinkingFundStatus.COMPLETED,
            ),
        )

        // Scenarios: one with every item type, one empty.
        c.scenarioRepository.addScenario(
            Scenario(
                name = "Buy a laptop",
                items = listOf(
                    ScenarioItem(
                        type = ScenarioItemType.ONE_OFF, label = "Laptop", accountId = current.id, categoryId = shopping.id,
                        amount = Money.pounds(-700), date = today.plusDays(14),
                    ),
                    ScenarioItem(
                        type = ScenarioItemType.RECURRING, label = "Gym", amount = Money.pounds(-20),
                        date = today.plusDays(7), frequency = Frequency.MONTHLY, interval = 1, endDate = today.plusDays(200),
                    ),
                    ScenarioItem(
                        type = ScenarioItemType.CATEGORY_ADJUST, categoryId = eatingOut.id, percent = -25, regime = Regime.TERM,
                    ),
                ),
            ),
        )
        c.scenarioRepository.addScenario(Scenario(name = "Nothing changes"))

        // Presets, one on a specific account.
        c.presetRepository.add(
            Preset(label = "Meal deal", amount = Money(385), flow = CategoryFlow.OUT, categoryId = eatingOut.id, sortOrder = 0),
        )
        c.presetRepository.add(
            Preset(
                label = "Bus", amount = Money(220), flow = CategoryFlow.OUT, categoryId = transport.id,
                accountId = current.id, showOnWidget = false, sortOrder = 1,
            ),
        )

        // The ledger: spending, income, a transfer, transactions posted from a rule and from an item, a note.
        DataTestSupport.addNormal(current.id, groceries.id, Money(-4_235), today.minusDays(5), "Tesco", "weekly shop")
        DataTestSupport.addNormal(current.id, wages.id, Money(15_000), today.minusDays(4), "Cafe shift")
        DataTestSupport.addNormal(cash.id, eatingOut.id, Money(-850), today.minusDays(1), "Lunch")
        c.transactionRepository.addTransfer(current.id, savings.id, Money.pounds(100), today.minusDays(3), "Save")
        c.transactionRepository.add(
            Transaction(
                accountId = current.id, amount = Money.pounds(-450), date = today.minusDays(19),
                createdAtEpochMillis = c.clock.nowMillis(), categoryId = rent.id, label = "Rent",
                kind = TxKind.NORMAL, recurringRuleId = rentRuleId,
            ),
        )
        c.transactionRepository.add(
            Transaction(
                accountId = current.id, amount = Money.pounds(150), date = today.minusDays(2),
                createdAtEpochMillis = c.clock.nowMillis(), categoryId = null, label = "Deposit refund",
                kind = TxKind.NORMAL, scheduledItemId = postedItemId,
            ),
        )

        // A statement import: a batch, imported transactions with keys, a moved opening balance, merchant rules.
        val rows = statementRows()
        val importBatchId = importStatement(
            current.id,
            rows,
            categoryIds = listOf(groceries.id, null, eatingOut.id),
            rules = listOf(
                MerchantRule(merchantKey = MerchantKey.normalise(rows[0].description), categoryId = groceries.id, label = "Tesco"),
                MerchantRule(merchantKey = MerchantKey.normalise(rows[2].description), categoryId = eatingOut.id),
            ),
        )

        // Reconciliations: one with a difference (a transaction), one without.
        c.accountRepository.reconcile(current.id, Money.pounds(1_000))
        c.accountRepository.reconcile(savings.id, DataTestSupport.balance(savings.id))

        // Snapshots.
        c.snapshotRepository.saveSnapshots(
            balances = listOf(
                BalanceSnapshot(createdOn = today, targetDate = today.plusDays(7), tier = 1, p10 = null, p50 = Money.pounds(1_900), p90 = null),
                BalanceSnapshot(
                    createdOn = today, targetDate = today.plusDays(7), tier = 3,
                    p10 = Money.pounds(1_700), p50 = Money.pounds(1_900), p90 = Money.pounds(2_100),
                ),
            ),
            categories = listOf(
                CategoryWeekSnapshot(createdOn = today, weekStart = today.plusDays(3), categoryId = groceries.id, tier = 1, predicted = Money(-4_500)),
                CategoryWeekSnapshot(createdOn = today, weekStart = today.plusDays(3), categoryId = groceries.id, tier = 3, predicted = Money(-4_100)),
            ),
        )

        // Settings: every budget setting and every app setting away from its default.
        c.settingsStore.setZeroPoint(ZeroPoint.OVERDRAFT_LIMIT)
        c.settingsStore.setBuffer(Money.pounds(250))
        c.settingsStore.setDefaultAccountId(savings.id)
        c.settingsStore.setHistoryStartDate(today.minusDays(90))
        c.settingsStore.setSimulations(500)
        c.settingsStore.setForecastHorizonDays(91)
        c.settingsStore.setHideWidgetAmounts(true)
        c.settingsStore.setAppLockEnabled(true)
        c.settingsStore.setSecureScreen(true)
        c.settingsStore.setReconcileReminderEnabled(true)
        c.settingsStore.setReconcileReminderDay(DayOfWeek.WEDNESDAY)
        c.settingsStore.setOnboardingDone(true)
        c.settingsStore.setTheme(AppTheme.SLATE)
        c.settingsStore.setDarkMode(DarkMode.DARK)
        c.settingsStore.setChartAccountView(AccountView.Single(savings.id))
        c.settingsStore.setBalanceView(AccountView.Single(current.id))

        return Populated(current, savings, cash, oldCard, importBatchId)
    }

    /** Guards the test itself: the fixture really does put something in every table. */
    private fun assertEveryTableHasData(data: BackupData) {
        assertTrue(data.accounts.size >= 4, "accounts")
        assertTrue(data.accounts.any { it.archived } && data.accounts.any { !it.includeInSafeToSpend }, "archived and non-pool accounts")
        assertTrue(data.categories.size > DefaultCategories.all.size, "categories, including a custom one")
        assertTrue(data.categories.any { it.archived }, "an archived category")
        assertTrue(data.transactions.isNotEmpty(), "transactions")
        assertEquals(2, data.transactions.count { it.kind == TxKind.TRANSFER }, "one transfer, two legs")
        assertTrue(data.transactions.any { it.kind == TxKind.RECONCILIATION }, "a reconciliation transaction")
        assertTrue(data.transactions.any { it.recurringRuleId != null }, "a transaction posted from a rule")
        assertTrue(data.transactions.any { it.scheduledItemId != null }, "a transaction posted from an item")
        assertTrue(
            data.transactions.any { it.importBatchId != null && it.importKey != null },
            "imported transactions (the case that broke restore)",
        )
        assertTrue(
            data.transactions.any { it.importBatchId != null && it.kind == TxKind.OPENING_BALANCE },
            "the opening balance entries an import moved",
        )
        assertTrue(data.recurringRules.size >= 4, "recurring rules")
        assertTrue(data.scheduledItems.size >= 4, "scheduled items")
        assertEquals(2, data.termPeriods.size, "terms")
        assertEquals(3, data.predictions.size, "predictions")
        assertEquals(2, data.sinkingFunds.size, "sinking funds")
        assertEquals(2, data.scenarios.size, "scenarios")
        assertEquals(3, data.scenarios.sumOf { it.items.size }, "scenario items")
        assertEquals(2, data.presets.size, "presets")
        assertEquals(2, data.merchantRules.size, "merchant rules")
        assertTrue(data.reconciliations.size >= 2, "reconciliations")
        assertTrue(data.reconciliations.any { it.transactionId == null }, "a reconciliation without a difference")
        assertEquals(1, data.importBatches.size, "an import batch")
        assertEquals(2, data.balanceSnapshots.size, "balance snapshots")
        assertEquals(2, data.categoryWeekSnapshots.size, "category snapshots")
        assertNotEquals(BudgetSettings(), data.settings, "settings away from their defaults")
    }

    private fun BackupData.sortedForComparison(): BackupData = copy(
        accounts = accounts.sortedBy { it.id },
        categories = categories.sortedBy { it.id },
        transactions = transactions.sortedBy { it.id },
        recurringRules = recurringRules.sortedBy { it.id },
        scheduledItems = scheduledItems.sortedBy { it.id },
        termPeriods = termPeriods.sortedBy { it.id },
        predictions = predictions.sortedBy { it.id },
        sinkingFunds = sinkingFunds.sortedBy { it.id },
        scenarios = scenarios.sortedBy { it.id }.map { scenario -> scenario.copy(items = scenario.items.sortedBy { it.id }) },
        presets = presets.sortedBy { it.id },
        merchantRules = merchantRules.sortedBy { it.id },
        reconciliations = reconciliations.sortedBy { it.id },
        importBatches = importBatches.sortedBy { it.id },
        balanceSnapshots = balanceSnapshots.sortedBy { it.id },
        categoryWeekSnapshots = categoryWeekSnapshots.sortedBy { it.id },
    )

    /** Table by table (so a failure names the table), ignoring row order but not ids. */
    private fun assertSameBackup(expected: BackupData, actual: BackupData) {
        val e = expected.sortedForComparison()
        val a = actual.sortedForComparison()
        assertEquals(e.accounts, a.accounts, "accounts")
        assertEquals(e.categories, a.categories, "categories")
        assertEquals(e.transactions, a.transactions, "transactions")
        assertEquals(e.recurringRules, a.recurringRules, "recurring rules")
        assertEquals(e.scheduledItems, a.scheduledItems, "scheduled items")
        assertEquals(e.termPeriods, a.termPeriods, "term periods")
        assertEquals(e.predictions, a.predictions, "predictions")
        assertEquals(e.sinkingFunds, a.sinkingFunds, "sinking funds")
        assertEquals(e.scenarios, a.scenarios, "scenarios and their items")
        assertEquals(e.presets, a.presets, "presets")
        assertEquals(e.merchantRules, a.merchantRules, "merchant rules")
        assertEquals(e.reconciliations, a.reconciliations, "reconciliations")
        assertEquals(e.importBatches, a.importBatches, "import batches")
        assertEquals(e.balanceSnapshots, a.balanceSnapshots, "balance snapshots")
        assertEquals(e.categoryWeekSnapshots, a.categoryWeekSnapshots, "category week snapshots")
        assertEquals(e.settings, a.settings, "budget settings")
    }

    /** Decodes backup JSON, failing the test (with the reasons) if the app cannot read its own file. */
    private fun decodeOrFail(json: String): BackupDecodeResult.Success {
        val decoded = BackupCodec.decode(json)
        return decoded as? BackupDecodeResult.Success
            ?: throw AssertionError("The app's own backup did not decode: $decoded")
    }

    /** The real export path: models -> DTOs -> JSON text -> decoded. */
    private fun exportAndDecode(data: BackupData, appSettings: Map<String, String> = emptyMap()): BackupDecodeResult.Success =
        decodeOrFail(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "2026-09-29T12:00:00Z", appSettings = appSettings)))

    /** An empty database and default budget settings, as after clearing the app's data. */
    private suspend fun clearDatabase() {
        withContext(Dispatchers.IO) { container.database.clearAllTables() }
        container.settingsStore.replaceBudgetSettings(BudgetSettings())
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    fun everyTableSurvivesTheJsonRoundTripWithIdsPreserved() = runBlocking<Unit> {
        populateEverything()
        val original = container.backupRepository.readAll()
        assertEveryTableHasData(original)
        val appSettings = container.settingsStore.appSettings.first()

        val success = exportAndDecode(original, AppSettingsCodec.encode(appSettings))
        assertSameBackup(original, success.data)
        assertEquals(AppSettingsCodec.encode(appSettings), success.appSettings)

        clearDatabase()
        assertTrue(container.backupRepository.readAll().accounts.isEmpty(), "the database really is empty")
        container.backupRepository.replaceAll(success.data)

        assertSameBackup(original, container.backupRepository.readAll())
    }

    @Test
    fun restoreWorksWhenImportedTransactionsExist() = runBlocking<Unit> {
        // Regression: restore inserted transactions before their import batches and failed the foreign key.
        val groceries = DataTestSupport.category("Groceries")
        val account = createAccount("Current", opening = Money(50_000), date = today.minusDays(10))
        val rows = statementRows().map { it.copy(date = it.date.plusDays(50)) }
        importStatement(
            account.id, rows,
            categoryIds = listOf(groceries.id, groceries.id, null),
            rules = listOf(MerchantRule(merchantKey = "TESCO STORES", categoryId = groceries.id)),
        )
        val original = container.backupRepository.readAll()
        assertTrue(original.transactions.any { it.importBatchId != null && it.importKey != null })
        assertEquals(1, original.importBatches.size)

        val success = exportAndDecode(original)
        clearDatabase()
        container.backupRepository.replaceAll(success.data)

        assertSameBackup(original, container.backupRepository.readAll())
        assertEquals(
            original.transactions.count { it.importBatchId != null },
            transactions().count { it.importBatchId != null },
        )
    }

    @Test
    fun restoringOverExistingDataReplacesItCompletely() = runBlocking<Unit> {
        val p = populateEverything()
        val original = container.backupRepository.readAll()
        val success = exportAndDecode(original)

        // Change something in every corner after the backup was taken.
        val groceries = DataTestSupport.category("Groceries")
        val extra = createAccount("Extra", opening = Money(99_900), date = today.minusDays(3))
        DataTestSupport.addNormal(extra.id, groceries.id, Money(-1_111), today, "Extra spending")
        DataTestSupport.addNormal(p.current.id, groceries.id, Money(-2_222), today, "Extra on current")
        container.transactionRepository.addTransfer(extra.id, p.current.id, Money(500), today, "Extra move")
        container.accountRepository.delete(p.cash.id)
        container.categoryRepository.add(
            Category(name = "Extra category", flow = CategoryFlow.OUT, forecastMode = ForecastMode.LEARNED, sortOrder = 30),
        )
        container.planRepository.addRecurringRule(
            RecurringRule(
                label = "Extra rule", accountId = extra.id, categoryId = groceries.id, amount = Money(-100),
                frequency = Frequency.WEEKLY, startDate = today.plusDays(2), postingMode = PostingMode.AUTO,
            ),
        )
        container.planRepository.addScheduledItem(
            ScheduledItem(
                label = "Extra item", accountId = extra.id, categoryId = null, amount = Money(700),
                date = today.plusDays(4), postingMode = PostingMode.CONFIRM,
            ),
        )
        container.planRepository.addTermPeriod(TermPeriod(name = "Extra term", startDate = today.plusDays(300), endDate = today.plusDays(320)))
        container.planRepository.upsertPrediction(
            Prediction(categoryId = groceries.id, regime = Regime.HOLIDAY, mode = PredictionMode.REPLACE, weeklyAmount = Money(999)),
        )
        container.planRepository.addSinkingFund(
            SinkingFund(name = "Extra fund", target = Money(1_000), startDate = today, targetDate = today.plusDays(50)),
        )
        container.scenarioRepository.addScenario(Scenario(name = "Extra scenario"))
        container.presetRepository.add(
            Preset(label = "Extra preset", amount = Money(100), flow = CategoryFlow.OUT, categoryId = groceries.id),
        )
        container.importRepository.addMerchantRule(MerchantRule(merchantKey = "EXTRA MERCHANT", categoryId = groceries.id))
        container.snapshotRepository.saveSnapshots(
            balances = listOf(BalanceSnapshot(createdOn = today.minusDays(1), targetDate = today.plusDays(6), tier = 1, p10 = null, p50 = Money(1), p90 = null)),
            categories = emptyList(),
        )
        container.accountRepository.reconcile(extra.id, Money(1))
        container.settingsStore.setZeroPoint(ZeroPoint.ACCOUNT_BALANCE)
        container.settingsStore.setBuffer(Money(1))
        container.settingsStore.setSimulations(2_000)
        container.settingsStore.setHistoryStartDate(null)
        container.settingsStore.setDefaultAccountId(extra.id)
        val changed = container.backupRepository.readAll()
        assertNotEquals(original.accounts, changed.accounts)
        assertNotEquals(original.settings, changed.settings)

        container.backupRepository.replaceAll(success.data)

        val restored = container.backupRepository.readAll()
        assertSameBackup(original, restored)
        assertTrue(restored.accounts.none { it.name == "Extra" }, "the account added afterwards is gone")
        assertTrue(restored.accounts.any { it.name == "Cash" }, "the account deleted afterwards is back")
        assertTrue(restored.merchantRules.none { it.merchantKey == "EXTRA MERCHANT" })
        assertTrue(restored.categories.none { it.name == "Extra category" })
    }

    @Test
    fun restoringASmallBackupLeavesNothingOfTheOldData() = runBlocking<Unit> {
        populateEverything()
        val categories = DefaultCategories.all.mapIndexed { i, category -> category.copy(id = i + 1L) }
        val small = BackupData(
            accounts = listOf(Account(id = 1, name = "Restored", type = AccountType.CURRENT, overdraftLimit = Money.pounds(100))),
            categories = categories,
            transactions = listOf(
                Transaction(
                    id = 1, accountId = 1, amount = Money(12_345), date = today.minusDays(1),
                    createdAtEpochMillis = 1_700_000_000_000L, categoryId = null, label = "Opening balance",
                    kind = TxKind.OPENING_BALANCE,
                ),
            ),
            settings = BudgetSettings(
                zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, buffer = Money.pounds(50), defaultAccountId = 1,
                historyStartDate = null, simulations = 500, forecastHorizonDays = 91,
            ),
        )

        container.backupRepository.replaceAll(exportAndDecode(small).data)

        val restored = container.backupRepository.readAll()
        assertSameBackup(small, restored)
        assertEquals(1, restored.accounts.size)
        assertEquals(1, restored.transactions.size)
        assertTrue(restored.recurringRules.isEmpty())
        assertTrue(restored.scheduledItems.isEmpty())
        assertTrue(restored.termPeriods.isEmpty())
        assertTrue(restored.predictions.isEmpty())
        assertTrue(restored.sinkingFunds.isEmpty())
        assertTrue(restored.scenarios.isEmpty())
        assertTrue(restored.presets.isEmpty())
        assertTrue(restored.merchantRules.isEmpty())
        assertTrue(restored.reconciliations.isEmpty())
        assertTrue(restored.importBatches.isEmpty())
        assertTrue(restored.balanceSnapshots.isEmpty())
        assertTrue(restored.categoryWeekSnapshots.isEmpty())
        assertEquals(Money(12_345), DataTestSupport.balance(1))
    }

    @Test
    fun aRestoredDatabaseKeepsWorkingForNewWrites() = runBlocking<Unit> {
        // Explicit ids were inserted, so the next auto-generated ids must not collide with them.
        val p = populateEverything()
        val original = container.backupRepository.readAll()
        val success = exportAndDecode(original)
        clearDatabase()
        container.backupRepository.replaceAll(success.data)

        val groceries = DataTestSupport.category("Groceries")
        val fresh = DataTestSupport.addNormal(p.current.id, groceries.id, Money(-100), today, "After restore")
        val extra = createAccount("After restore", opening = Money(1_000))

        assertTrue(original.transactions.none { it.id == fresh.id }, "a new transaction gets a new id")
        assertTrue(original.accounts.none { it.id == extra.id }, "a new account gets a new id")
        assertEquals(original.transactions.size + 2, transactions().size)
    }

    @Test
    fun theExportedFileIsValidBackupJson() = runBlocking<Unit> {
        populateEverything()
        val vm = BackupViewModel(container)

        val json = vm.buildExportJson()

        val success = decodeOrFail(json)
        assertSameBackup(container.backupRepository.readAll(), success.data)
        assertTrue(success.exportedAt.isNotEmpty())
    }

    @Test
    fun exportAndRestoreThroughTheBackupScreenKeepsDataAndPrivacySettings() = runBlocking<Unit> {
        populateEverything()
        val original = container.backupRepository.readAll()
        val settingsBefore = container.settingsStore.appSettings.first()
        val vm = BackupViewModel(container)
        val json = vm.buildExportJson()

        // A different phone: no data, everything back at its default.
        clearDatabase()
        container.settingsStore.setHideWidgetAmounts(false)
        container.settingsStore.setAppLockEnabled(false)
        container.settingsStore.setSecureScreen(false)
        container.settingsStore.setReconcileReminderEnabled(false)
        container.settingsStore.setReconcileReminderDay(DayOfWeek.SUNDAY)
        container.settingsStore.setOnboardingDone(false)
        container.settingsStore.setTheme(AppTheme.PAPER)
        container.settingsStore.setDarkMode(DarkMode.SYSTEM)

        vm.confirmRestore(decodeOrFail(json))

        assertSameBackup(original, container.backupRepository.readAll())
        val after = container.settingsStore.appSettings.first()
        assertEquals(settingsBefore.hideWidgetAmounts, after.hideWidgetAmounts)
        assertEquals(settingsBefore.appLockEnabled, after.appLockEnabled)
        assertEquals(settingsBefore.secureScreen, after.secureScreen)
        assertEquals(settingsBefore.reconcileReminderEnabled, after.reconcileReminderEnabled)
        assertEquals(DayOfWeek.WEDNESDAY, after.reconcileReminderDay)
        assertEquals(settingsBefore.onboardingDone, after.onboardingDone)
        assertEquals(AppTheme.SLATE, after.theme)
        assertEquals(DarkMode.DARK, after.darkMode)
    }

    @Test
    fun restoreAlsoBringsBackTheChartAndBalanceAccountViews() = runBlocking<Unit> {
        val p = populateEverything()
        val settingsBefore = container.settingsStore.appSettings.first()
        assertEquals(AccountView.Single(p.savings.id), settingsBefore.chartAccountView)
        assertEquals(AccountView.Single(p.current.id), settingsBefore.balanceView)
        val vm = BackupViewModel(container)
        val json = vm.buildExportJson()

        clearDatabase()
        container.settingsStore.setChartAccountView(AccountView.Total)
        container.settingsStore.setBalanceView(AccountView.Total)
        vm.confirmRestore(decodeOrFail(json))

        val after = container.settingsStore.appSettings.first()
        assertEquals(settingsBefore.chartAccountView, after.chartAccountView, "the chart's account view is part of the backup")
        assertEquals(settingsBefore.balanceView, after.balanceView, "Home's balance view is part of the backup")
    }
}
