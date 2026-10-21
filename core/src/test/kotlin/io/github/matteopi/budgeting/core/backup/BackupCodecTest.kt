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
import io.github.matteopi.budgeting.core.recipes.BaseUnit
import io.github.matteopi.budgeting.core.recipes.FoodGroup
import io.github.matteopi.budgeting.core.recipes.Ingredient
import io.github.matteopi.budgeting.core.recipes.IngredientAliasRecord
import io.github.matteopi.budgeting.core.recipes.Nutrition
import io.github.matteopi.budgeting.core.recipes.NutritionSource
import io.github.matteopi.budgeting.core.recipes.OwnedItem
import io.github.matteopi.budgeting.core.recipes.Portion
import io.github.matteopi.budgeting.core.recipes.Recipe
import io.github.matteopi.budgeting.core.recipes.RecipeBundle
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeSource
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {

    /** Version-3 data: two ingredients (one linked to product 1), aliases, a recipe of every step kind and a shopping list. */
    private fun foodData(): BackupData {
        val onion = Ingredient(
            id = 1, name = "Onion", unit = BaseUnit.G, packSize = 1_000.0, packLabel = "1 kg",
            price = Money(95), priceDate = LocalDate.of(2026, 10, 15), store = "Tesco", productId = 1,
            productName = "Tesco Brown Onions 1kg", sourceUrl = "https://example.invalid/onions",
            barcode = "5000000000001",
            per100 = Nutrition(
                kcal = 36.0, fat = 0.1, saturates = 0.0, carbs = 7.9, sugars = 5.6, fibre = 1.4, protein = 0.9, salt = 0.01,
                addedSugars = 0.0,
            ),
            nutritionSource = NutritionSource.LABEL, vegKind = VegKind.VEG, shelf = Shelf.FRUIT_VEG, note = "Brown",
            createdAtEpochMillis = 111L, portions = listOf(Portion("each", 150.0), Portion("small", 90.5)),
        )
        val stock = Ingredient(
            id = 2, name = "Stock cube", unit = BaseUnit.ML, packSize = 12.0, packLabel = "12 cubes",
            per100 = Nutrition(kcal = 250.0), nutritionSource = NutritionSource.ESTIMATE,
            shelf = Shelf.CUPBOARD, archived = true, createdAtEpochMillis = 222L, foodGroup = FoodGroup.NONE,
        )
        val soup = Recipe(
            id = 1, title = "Onion soup", shortTitle = "Soup", tagline = "Cheap and warm", style = "Hob · one pot",
            serves = 4, prepMins = 10, cookMins = 40, timeNote = "plus cooling", servedWith = "Bread",
            tags = listOf("Quick", "Veggie"), rating = 4, notes = "Add more pepper.", source = RecipeSource.CLAUDE,
            createdAtEpochMillis = 333L, updatedAtEpochMillis = 444L,
            lines = listOf(
                RecipeLine(group = "Soup", text = "4 onions, sliced", ingredientId = 1, quantity = 600.0),
                RecipeLine(group = null, text = "1 stock cube", ingredientId = 2, quantity = 10.0, optional = true),
                RecipeLine(text = "salt to taste"),
            ),
            steps = listOf(
                RecipeStep(StepKind.STEP, "Soften", "~10 min", "Cook the onions.\n\nReady when: golden."),
                RecipeStep(StepKind.STEP, null, null, "Add the stock."),
                RecipeStep(StepKind.TIP, body = "Salt early."),
                RecipeStep(StepKind.EQUIPMENT, body = "A big pan."),
                RecipeStep(StepKind.STORAGE, body = "Fridge 3 days."),
            ),
        )
        val plain = Recipe(id = 2, title = "Toast", serves = 1, archived = true)
        return BackupData(
            products = listOf(Product(id = 1, name = "Brown onions", categoryId = null)),
            ingredients = listOf(onion, stock),
            ingredientAliases = listOf(
                IngredientAliasRecord(id = 1, nameKey = "brown onion", ingredientId = 1),
                IngredientAliasRecord(id = 2, nameKey = "bouillon", ingredientId = 2),
            ),
            recipes = listOf(soup, plain),
            shoppingEntries = listOf(
                ShoppingEntry(id = 1, recipeId = 1, servings = 8, addedAtEpochMillis = 555L),
                ShoppingEntry(id = 2, ingredientId = 1, quantity = 250.0, addedAtEpochMillis = 556L),
                ShoppingEntry(id = 3, freeText = "Bin bags", checked = true, addedAtEpochMillis = 557L),
            ),
            shoppingMarks = listOf(
                ShoppingMark(ingredientId = 1, checked = true, haveIt = false),
                ShoppingMark(ingredientId = 2, checked = false, haveIt = true),
            ),
            ownedItems = listOf(OwnedItem(1, 450.0, 777L), OwnedItem(2, null, 778L)),
        )
    }

    private fun roundTrip(data: BackupData): BackupData {
        val result = BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now")))
        return assertIs<BackupDecodeResult.Success>(result).data
    }

    private fun failureOf(data: BackupData): List<String> =
        assertIs<BackupDecodeResult.Failure>(
            BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(data, exportedAt = "now"))),
        ).errors

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

        val food = foodData()
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
            ingredients = food.ingredients,
            ingredientAliases = food.ingredientAliases,
            recipes = food.recipes,
            shoppingEntries = food.shoppingEntries,
            shoppingMarks = food.shoppingMarks,
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
    fun `current version is 8`() {
        assertEquals(8, BackupV1.CURRENT_VERSION)
        assertEquals(8, BackupMapper.toBackup(BackupData(), exportedAt = "now").version)
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
        assertNoFood(success.data)
    }

    private fun assertNoFood(data: BackupData) {
        assertTrue(data.ingredients.isEmpty())
        assertTrue(data.ingredientAliases.isEmpty())
        assertTrue(data.recipes.isEmpty())
        assertTrue(data.shoppingEntries.isEmpty())
        assertTrue(data.shoppingMarks.isEmpty())
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
        val backup = BackupMapper.toBackup(data, exportedAt = "now").copy(version = BackupV1.CURRENT_VERSION + 1)
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

    // ---- Version 3: food ---------------------------------------------------------------------

    @Test
    fun `food data round trips through JSON`() {
        val data = foodData()
        val back = roundTrip(data)
        assertEquals(data, back)
        val soup = back.recipes.first()
        assertEquals(
            listOf(StepKind.STEP, StepKind.STEP, StepKind.TIP, StepKind.EQUIPMENT, StepKind.STORAGE),
            soup.steps.map { it.kind },
        )
        assertEquals(listOf("Soup", null, null), soup.lines.map { it.group })
        assertEquals(listOf(Portion("each", 150.0), Portion("small", 90.5)), back.ingredients.first().portions)
        assertEquals(listOf("Quick", "Veggie"), soup.tags)
    }

    @Test
    fun `lines and steps keep their order however the file lists them`() {
        val backup = BackupMapper.toBackup(foodData(), exportedAt = "now")
        val shuffled = backup.copy(
            recipeLines = backup.recipeLines.reversed(),
            recipeSteps = backup.recipeSteps.reversed(),
        )
        val back = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(BackupCodec.encode(shuffled))).data
        assertEquals(foodData().recipes, back.recipes)
    }

    @Test
    fun `the starter book survives a backup`() {
        val models = RecipeBundle.starter().toModels()
        val data = BackupData(ingredients = models.ingredients, recipes = models.recipes)
        val back = roundTrip(data)
        assertEquals(40, back.ingredients.size)
        assertEquals(4, back.recipes.size)
        assertEquals(data, back)
    }

    @Test
    fun `the food tables are written under their own names`() {
        val json = BackupCodec.encode(BackupMapper.toBackup(foodData(), exportedAt = "now"))
        for (name in listOf(
            "ingredients", "ingredientPortions", "ingredientAliases", "recipes", "recipeLines", "recipeSteps",
            "shoppingEntries", "shoppingMarks", "ownedItems",
        )) {
            assertTrue("\"$name\"" in json, "the file has a \"$name\" list")
        }
    }

    @Test
    fun `a version 2 file still decodes with its receipts and no food data`() {
        val json = """
            {
              "format": "budgeting-backup",
              "version": 2,
              "exportedAt": "2026-10-14T12:00:00Z",
              "products": [{"id": 1, "name": "Milk", "categoryId": null}],
              "productAliases": [{"id": 1, "store": "TESCO", "rawNameKey": "milk", "productId": 1}],
              "settings": {"zeroPoint": "ACCOUNT_BALANCE", "bufferPence": 0, "defaultAccountId": null,
                           "historyStartDate": null, "simulations": 500, "forecastHorizonDays": 90}
            }
        """.trimIndent()
        val success = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json))
        assertEquals(1, success.data.products.size)
        assertEquals(1, success.data.productAliases.size)
        assertNoFood(success.data)
    }

    @Test
    fun `a file with the food lists left out decodes as having none`() {
        val json = """
            {"format":"budgeting-backup","version":3,"exportedAt":"now",
             "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}
        """.trimIndent()
        assertNoFood(assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data)
    }

    @Test
    fun `decode rejects a recipe line pointing at a missing ingredient`() {
        val data = BackupData(
            recipes = listOf(Recipe(id = 1, title = "Soup", lines = listOf(RecipeLine(text = "an onion", ingredientId = 9, quantity = 100.0)))),
        )
        assertTrue(failureOf(data).any { it.contains("ingredient 9") })
    }

    @Test
    fun `decode rejects a portion pointing at a missing ingredient`() {
        val backup = BackupMapper.toBackup(foodData(), exportedAt = "now")
        val broken = backup.copy(ingredientPortions = backup.ingredientPortions + IngredientPortionDto(77, "each", 10.0))
        val failure = assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(broken)))
        assertTrue(failure.errors.any { it.contains("ingredient 77") })
    }

    @Test
    fun `decode rejects an ingredient alias pointing at a missing ingredient`() {
        val data = BackupData(ingredientAliases = listOf(IngredientAliasRecord(1, "onion", 5)))
        assertTrue(failureOf(data).any { it.contains("ingredient 5") })
    }

    @Test
    fun `decode rejects a recipe line or step pointing at a missing recipe`() {
        val backup = BackupMapper.toBackup(foodData(), exportedAt = "now")
        val lineless = backup.copy(recipeLines = backup.recipeLines + RecipeLineDto(42, 0, null, "x", null, null, false))
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(lineless))).errors.any { it.contains("recipe 42") })
        val stepless = backup.copy(recipeSteps = backup.recipeSteps + RecipeStepDto(42, 0, "STEP", null, null, "x"))
        assertTrue(assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(BackupCodec.encode(stepless))).errors.any { it.contains("recipe 42") })
    }

    @Test
    fun `decode rejects shopping entries and marks pointing at a missing recipe or ingredient`() {
        assertTrue(failureOf(BackupData(shoppingEntries = listOf(ShoppingEntry(id = 1, recipeId = 3, servings = 2)))).any { it.contains("recipe 3") })
        assertTrue(failureOf(BackupData(shoppingEntries = listOf(ShoppingEntry(id = 1, ingredientId = 4, quantity = 1.0)))).any { it.contains("ingredient 4") })
        assertTrue(failureOf(BackupData(shoppingMarks = listOf(ShoppingMark(ingredientId = 6, checked = true)))).any { it.contains("ingredient 6") })
    }

    @Test
    fun `decode rejects an ingredient pointing at a missing product`() {
        val data = BackupData(ingredients = listOf(Ingredient(id = 1, name = "Milk", unit = BaseUnit.ML, productId = 8)))
        assertTrue(failureOf(data).any { it.contains("product 8") })
    }

    @Test
    fun `decode rejects food rows that would break a unique rule`() {
        val onion = Ingredient(id = 1, name = "Onion", barcode = "123")
        val garlic = Ingredient(id = 2, name = "Garlic", barcode = "123")
        assertTrue(failureOf(BackupData(ingredients = listOf(onion, garlic))).any { it.contains("barcode") })
        assertTrue(failureOf(BackupData(ingredients = listOf(onion, onion.copy(barcode = null)))).any { it.contains("same id") })
        assertTrue(failureOf(BackupData(recipes = listOf(Recipe(id = 1, title = "A"), Recipe(id = 1, title = "B")))).any { it.contains("same id") })
        assertTrue(
            failureOf(
                BackupData(
                    ingredients = listOf(onion),
                    ingredientAliases = listOf(IngredientAliasRecord(1, "red onion", 1), IngredientAliasRecord(2, "red onion", 1)),
                ),
            ).any { it.contains("alias") },
        )
        assertTrue(
            failureOf(BackupData(ingredients = listOf(onion.copy(portions = listOf(Portion("each", 1.0), Portion("each", 2.0)))))).any { it.contains("portions") },
        )
        assertTrue(
            failureOf(
                BackupData(
                    ingredients = listOf(onion),
                    shoppingMarks = listOf(ShoppingMark(1, checked = true), ShoppingMark(1, haveIt = true)),
                ),
            ).any { it.contains("marks") },
        )
    }

    // ---- Version 4: what the kitchen has, added sugars, food groups ---------------------------

    @Test
    fun `owned items, added sugars and food groups round trip`() {
        val back = roundTrip(foodData())
        assertEquals(listOf(OwnedItem(1, 450.0, 777L), OwnedItem(2, null, 778L)), back.ownedItems)
        assertEquals(0.0, back.ingredients.first().per100.addedSugars)
        assertEquals(FoodGroup.NONE, back.ingredients.last().foodGroup)
        assertNull(back.ingredients.first().foodGroup)
    }

    @Test
    fun `a version 3 ingredient has no added sugars, no group and nothing owned`() {
        val json = """
            {"format":"budgeting-backup","version":3,"exportedAt":"now",
             "ingredients":[{"id":1,"name":"Onion","unit":"G","packSize":1000.0,"packLabel":"1 kg","pricePence":95,
               "priceDate":null,"store":null,"productId":null,"productName":null,"sourceUrl":null,"barcode":null,
               "kcal":36.0,"fat":null,"saturates":null,"carbs":null,"sugars":5.6,"fibre":null,"protein":null,"salt":null,
               "nutritionSource":"TYPED","vegKind":"VEG","shelf":"FRUIT_VEG","note":"","archived":false,"createdAtEpochMillis":1}],
             "settings":{"zeroPoint":"ACCOUNT_BALANCE","bufferPence":0,"defaultAccountId":null,"historyStartDate":null,"simulations":500,"forecastHorizonDays":90}}
        """.trimIndent()
        val data = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        val onion = data.ingredients.single()
        assertNull(onion.per100.addedSugars)
        assertNull(onion.foodGroup)
        assertEquals(5.6, onion.per100.sugars)
        assertTrue(data.ownedItems.isEmpty())
    }

    @Test
    fun `decode rejects owned items that point at a missing ingredient, repeat one or are below zero`() {
        val data = foodData()
        assertTrue(failureOf(data.copy(ownedItems = listOf(OwnedItem(9, 1.0, 1L)))).any { it.contains("missing ingredient 9") })
        assertTrue(failureOf(data.copy(ownedItems = listOf(OwnedItem(1, 1.0, 1L), OwnedItem(1, 2.0, 2L)))).any { it.contains("twice") })
        assertTrue(failureOf(data.copy(ownedItems = listOf(OwnedItem(1, -1.0, 1L)))).any { it.contains("below zero") })
    }

    @Test
    fun `an unknown food group reads as guessed instead of failing`() {
        val json = BackupCodec.encode(BackupMapper.toBackup(foodData(), exportedAt = "now"))
            .replace(Regex(""""foodGroup":\s*"NONE""""), """"foodGroup": "MYSTERY"""")
        val data = assertIs<BackupDecodeResult.Success>(BackupCodec.decode(json)).data
        assertNull(data.ingredients.last().foodGroup)
    }

    @Test
    fun `decode reports a food enum that does not exist as a failure instead of throwing`() {
        val backup = BackupMapper.toBackup(foodData(), exportedAt = "now")
        val json = BackupCodec.encode(backup).replace("\"CUPBOARD\"", "\"SOMEWHERE\"")
        assertIs<BackupDecodeResult.Failure>(BackupCodec.decode(json))
    }
}
