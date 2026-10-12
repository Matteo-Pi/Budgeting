package io.github.matteopi.budgeting.core.backup

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.BudgetSettings
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.ImportBatch
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Prediction
import io.github.matteopi.budgeting.core.model.PredictionMode
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.Product
import io.github.matteopi.budgeting.core.model.ProductAliasRecord
import io.github.matteopi.budgeting.core.model.Receipt
import io.github.matteopi.budgeting.core.model.ReceiptItem
import io.github.matteopi.budgeting.core.model.Reconciliation
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Regime
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScenarioItem
import io.github.matteopi.budgeting.core.model.ScenarioItemType
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.SinkingFundStatus
import io.github.matteopi.budgeting.core.model.SystemKeys
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.core.model.ZeroPoint
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupCodecTest {

    private fun fullBackupData(): BackupData {
        val account1 = Account(id = 1, name = "Current", type = AccountType.CURRENT, overdraftLimit = Money.pounds(500), sortOrder = 0)
        val account2 = Account(id = 2, name = "Savings", type = AccountType.SAVINGS, includeInSafeToSpend = false, sortOrder = 1)

        val groceries = Category(id = 1, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED, colorArgb = 0xFF2E7D32, sortOrder = 0)
        val untracked = Category(
            id = 2, name = "Untracked", flow = Flow.BOTH, forecastMode = ForecastMode.LEARNED,
            isSystem = true, systemKey = SystemKeys.UNTRACKED, colorArgb = 0xFF9E9E9E, sortOrder = 99,
        )

        val transactions = listOf(
            Transaction(
                id = 1, accountId = 1, amount = Money(-1250), date = LocalDate.of(2026, 9, 1),
                createdAtEpochMillis = 111L, categoryId = groceries.id, label = "Tesco", note = "weekly shop",
                kind = TxKind.NORMAL, importKey = "abc123", receiptId = 1,
            ),
            Transaction(
                id = 2, accountId = 1, amount = Money(-500), date = LocalDate.of(2026, 9, 2),
                categoryId = untracked.id, kind = TxKind.RECONCILIATION,
            ),
            Transaction(
                id = 3, accountId = 1, amount = Money(-1000), date = LocalDate.of(2026, 9, 3),
                categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "grp-1",
            ),
            Transaction(
                id = 4, accountId = 2, amount = Money(1000), date = LocalDate.of(2026, 9, 3),
                categoryId = null, kind = TxKind.TRANSFER, transferGroupId = "grp-1",
            ),
            Transaction(
                id = 5, accountId = 1, amount = Money.pounds(2000), date = LocalDate.of(2026, 1, 1),
                categoryId = null, kind = TxKind.OPENING_BALANCE,
            ),
        )

        val recurringRules = listOf(
            RecurringRule(
                id = 1, label = "Rent", accountId = 1, categoryId = groceries.id, amount = Money.pounds(-500),
                frequency = Frequency.MONTHLY, interval = 1, startDate = LocalDate.of(2026, 1, 1),
                endDate = LocalDate.of(2027, 1, 1), postingMode = PostingMode.AUTO,
                postedThrough = LocalDate.of(2026, 9, 1), isMajorIncome = false, active = true,
            ),
        )

        val scheduledItems = listOf(
            ScheduledItem(
                id = 1, label = "Student loan", accountId = 1, categoryId = groceries.id, amount = Money.pounds(3000),
                date = LocalDate.of(2027, 1, 10), postingMode = PostingMode.CONFIRM, isMajorIncome = true,
            ),
        )

        val termPeriods = listOf(TermPeriod(id = 1, name = "Autumn", startDate = LocalDate.of(2026, 9, 28), endDate = LocalDate.of(2026, 12, 12)))

        val predictions = listOf(
            Prediction(id = 1, categoryId = groceries.id, regime = Regime.TERM, mode = PredictionMode.ADJUST_PERCENT, adjustPercent = -20),
        )

        val sinkingFunds = listOf(
            SinkingFund(
                id = 1, name = "Interrailing", target = Money.pounds(600), startDate = LocalDate.of(2026, 9, 1),
                targetDate = LocalDate.of(2027, 7, 1), categoryId = groceries.id, status = SinkingFundStatus.ACTIVE,
            ),
        )

        val scenarios = listOf(
            Scenario(
                id = 1, name = "What if rent goes up",
                items = listOf(
                    ScenarioItem(id = 1, scenarioId = 1, type = ScenarioItemType.ONE_OFF, label = "Deposit", amount = Money.pounds(-200), date = LocalDate.of(2026, 10, 1)),
                    ScenarioItem(id = 2, scenarioId = 1, type = ScenarioItemType.CATEGORY_ADJUST, categoryId = groceries.id, percent = 15, regime = Regime.HOLIDAY),
                ),
            ),
        )

        val presets = listOf(
            Preset(id = 1, label = "Meal deal", amount = Money(385), flow = Flow.OUT, categoryId = groceries.id, accountId = 1, sortOrder = 0),
        )

        val merchantRules = listOf(MerchantRule(id = 1, merchantKey = "TESCO STORES", categoryId = groceries.id, label = "Tesco"))

        val reconciliations = listOf(
            Reconciliation(id = 1, accountId = 1, date = LocalDate.of(2026, 9, 2), expected = Money.pounds(100), actual = Money.pounds(95), transactionId = 2, periodStart = LocalDate.of(2026, 8, 27)),
        )

        val importBatches = listOf(
            ImportBatch(id = 1, accountId = 1, importedAtEpochMillis = 222L, fileName = "statement.csv", rowCount = 42, firstDate = LocalDate.of(2026, 8, 1), lastDate = LocalDate.of(2026, 8, 31)),
        )

        val balanceSnapshots = listOf(
            BalanceSnapshot(id = 1, createdOn = LocalDate.of(2026, 9, 20), targetDate = LocalDate.of(2026, 9, 27), tier = 1, p10 = null, p50 = Money.pounds(150), p90 = null),
            BalanceSnapshot(id = 2, createdOn = LocalDate.of(2026, 9, 20), targetDate = LocalDate.of(2026, 9, 27), tier = 3, p10 = Money.pounds(100), p50 = Money.pounds(150), p90 = Money.pounds(200)),
        )

        val categoryWeekSnapshots = listOf(
            CategoryWeekSnapshot(id = 1, createdOn = LocalDate.of(2026, 9, 20), weekStart = LocalDate.of(2026, 9, 21), categoryId = groceries.id, tier = 1, predicted = Money.pounds(-40)),
        )

        val products = listOf(
            Product(id = 1, name = "Semi-skimmed milk 2 pints", categoryId = groceries.id),
            Product(id = 2, name = "Mystery item", categoryId = null),
        )
        val productAliases = listOf(
            ProductAliasRecord(id = 1, store = "TESCO", rawNameKey = "ts semi skmd mlk 2pt", productId = 1),
        )
        val receipts = listOf(
            Receipt(
                id = 1, store = "TESCO", date = LocalDate.of(2026, 9, 1), total = Money(1250), source = "PHOTO",
                transactionId = 1, createdAtEpochMillis = 333L,
            ),
        )
        val receiptItems = listOf(
            ReceiptItem(
                id = 1, receiptId = 1, productId = 1, rawName = "TS SEMI SKMD MLK 2PT", quantity = 2,
                unitPrice = Money(150), price = Money(300), discount = Money(-50), categoryId = groceries.id,
            ),
            ReceiptItem(
                id = 2, receiptId = 1, productId = null, rawName = "BANANAS LOOSE", weightGrams = 452,
                unitPrice = null, price = Money(100), categoryId = null,
            ),
        )

        val settings = BudgetSettings(
            zeroPoint = ZeroPoint.OVERDRAFT_LIMIT, buffer = Money.pounds(50), defaultAccountId = 1,
            historyStartDate = LocalDate.of(2026, 1, 1), simulations = 500, forecastHorizonDays = 90,
        )

        return BackupData(
            accounts = listOf(account1, account2),
            categories = listOf(groceries, untracked),
            transactions = transactions,
            recurringRules = recurringRules,
            scheduledItems = scheduledItems,
            termPeriods = termPeriods,
            predictions = predictions,
            sinkingFunds = sinkingFunds,
            scenarios = scenarios,
            presets = presets,
            merchantRules = merchantRules,
            reconciliations = reconciliations,
            importBatches = importBatches,
            balanceSnapshots = balanceSnapshots,
            categoryWeekSnapshots = categoryWeekSnapshots,
            receipts = receipts,
            receiptItems = receiptItems,
            products = products,
            productAliases = productAliases,
            settings = settings,
        )
    }

    @Test
    fun `round trips every model type through encode and decode`() {
        val data = fullBackupData()
        val backup = BackupMapper.toBackup(data, exportedAt = "2026-09-28T12:00:00Z", appSettings = mapOf("hide_widget_amounts" to "true"))
        val json = BackupCodec.encode(backup)
        val result = BackupCodec.decode(json)

        val success = assertIs<BackupDecodeResult.Success>(result)
        assertEquals(data, success.data)
        assertEquals(mapOf("hide_widget_amounts" to "true"), success.appSettings)
        assertEquals("2026-09-28T12:00:00Z", success.exportedAt)
    }

    @Test
    fun `round trip through BackupMapper alone matches (no JSON involved)`() {
        val data = fullBackupData()
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        assertEquals(data, BackupMapper.fromBackup(backup))
    }

    @Test
    fun `empty backup round trips`() {
        val data = BackupData()
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        val success = assertIs<BackupDecodeResult.Success>(result)
        assertEquals(data, success.data)
    }

    @Test
    fun `current version is 2`() {
        assertEquals(2, BackupV1.CURRENT_VERSION)
        assertEquals(2, BackupMapper.toBackup(BackupData(), exportedAt = "now").version)
    }

    @Test
    fun `a version 1 file still decodes with no receipts`() {
        val json = """
            {
              "format": "budgeting-backup",
              "version": 1,
              "exportedAt": "2026-09-28T12:00:00Z",
              "accounts": [
                {"id": 1, "name": "Current", "type": "CURRENT", "overdraftLimitPence": 0,
                 "includeInSafeToSpend": true, "archived": false, "sortOrder": 0}
              ],
              "transactions": [
                {"id": 1, "accountId": 1, "amountPence": -250, "date": "2026-09-01", "createdAtEpochMillis": 5,
                 "categoryId": null, "label": "Coffee", "note": "", "kind": "NORMAL", "transferGroupId": null,
                 "recurringRuleId": null, "scheduledItemId": null, "importBatchId": null, "importKey": null}
              ],
              "settings": {"zeroPoint": "ACCOUNT_BALANCE", "bufferPence": 0, "defaultAccountId": null,
                           "historyStartDate": null, "simulations": 500, "forecastHorizonDays": 90}
            }
        """.trimIndent()
        val success = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json))
        val tx = success.data.transactions.single()
        assertEquals(null, tx.receiptId)
        assertEquals(Money(-250), tx.amount)
        assertTrue(success.data.receipts.isEmpty())
        assertTrue(success.data.receiptItems.isEmpty())
        assertTrue(success.data.products.isEmpty())
        assertTrue(success.data.productAliases.isEmpty())
    }

    @Test
    fun `decode rejects a receipt item pointing at a missing receipt`() {
        val data = BackupData(
            receiptItems = listOf(ReceiptItem(id = 1, receiptId = 9, rawName = "MILK", price = Money(100))),
        )
        val failure = assertIs<BackupDecodeResult.Failure>(
            BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))),
        )
        assertTrue(failure.errors.any { it.contains("receipt") })
    }

    @Test
    fun `decode rejects a product alias pointing at a missing product`() {
        val data = BackupData(
            productAliases = listOf(ProductAliasRecord(id = 1, store = "TESCO", rawNameKey = "milk", productId = 9)),
        )
        val failure = assertIs<BackupDecodeResult.Failure>(
            BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))),
        )
        assertTrue(failure.errors.any { it.contains("product") })
    }

    @Test
    fun `decode rejects invalid JSON`() {
        val result = BackupCodec.decode("not json at all {{{")
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.isNotEmpty())
    }

    @Test
    fun `decode rejects the wrong format`() {
        val json = """{"format":"some-other-app","version":1,"exportedAt":"now","settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}"""
        val result = BackupCodec.decode(json)
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.single().contains("Budgeting backup", ignoreCase = true))
    }

    @Test
    fun `decode rejects a newer version`() {
        val data = BackupData()
        val backup = BackupMapper.toBackup(data, exportedAt = "now").copy(version = 3)
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.single().contains("newer version", ignoreCase = true))
    }

    @Test
    fun `decode rejects a transaction pointing at a missing account`() {
        val data = BackupData(
            transactions = listOf(
                Transaction(id = 1, accountId = 99, amount = Money.pounds(-1), date = LocalDate.of(2026, 1, 1), categoryId = null, kind = TxKind.OPENING_BALANCE),
            ),
        )
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.any { it.contains("account") })
    }

    @Test
    fun `decode rejects a preset pointing at a missing category`() {
        val data = BackupData(
            accounts = listOf(Account(id = 1, name = "Current", type = AccountType.CURRENT)),
            presets = listOf(Preset(id = 1, label = "Coffee", amount = Money.pounds(-3), flow = Flow.OUT, categoryId = 42, accountId = 1)),
        )
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.any { it.contains("category") })
    }

    @Test
    fun `decode rejects a reconciliation pointing at a missing transaction`() {
        val data = BackupData(
            accounts = listOf(Account(id = 1, name = "Current", type = AccountType.CURRENT)),
            reconciliations = listOf(
                Reconciliation(id = 1, accountId = 1, date = LocalDate.of(2026, 1, 1), expected = Money.ZERO, actual = Money.ZERO, transactionId = 55, periodStart = LocalDate.of(2026, 1, 1)),
            ),
        )
        val backup = BackupMapper.toBackup(data, exportedAt = "now")
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        val failure = assertIs<BackupDecodeResult.Failure>(result)
        assertTrue(failure.errors.any { it.contains("transaction") })
    }

    private fun jsonWithDate(date: String): String {
        val data = BackupData(
            accounts = listOf(Account(id = 1, name = "Current", type = AccountType.CURRENT)),
            transactions = listOf(
                Transaction(id = 1, accountId = 1, amount = Money.pounds(5), date = LocalDate.of(2026, 1, 1), categoryId = null, kind = TxKind.OPENING_BALANCE),
            ),
            settings = BudgetSettings(historyStartDate = LocalDate.of(2026, 1, 1)),
        )
        val json = BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))
        assertTrue("\"2026-01-01\"" in json)
        return json.replace("\"2026-01-01\"", "\"$date\"")
    }

    @Test
    fun `decode reports an unparseable date as a failure instead of throwing`() {
        for (bad in listOf("2026-13-01", "2026-02-30", "not-a-date", "", "2026/01/01", "20260101")) {
            val failure = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(jsonWithDate(bad)), "date '$bad'")
            assertTrue(failure.errors.isNotEmpty())
        }
    }
}
