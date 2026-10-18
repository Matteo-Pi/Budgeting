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
import io.github.matteopi.budgeting.core.recipes.RecipeLine
import io.github.matteopi.budgeting.core.recipes.RecipeSource
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.Shelf
import io.github.matteopi.budgeting.core.recipes.ShoppingEntry
import io.github.matteopi.budgeting.core.recipes.ShoppingMark
import io.github.matteopi.budgeting.core.recipes.StepKind
import io.github.matteopi.budgeting.core.recipes.VegKind
import java.time.LocalDate

/** Converts between the app's core models ([BackupData]) and the JSON DTOs ([BackupV1]). */
object BackupMapper {

    fun toBackup(data: BackupData, exportedAt: String, appSettings: Map<String, String> = emptyMap()): BackupV1 =
        BackupV1(
            exportedAt = exportedAt,
            accounts = data.accounts.map { it.toDto() },
            categories = data.categories.map { it.toDto() },
            transactions = data.transactions.map { it.toDto() },
            recurringRules = data.recurringRules.map { it.toDto() },
            scheduledItems = data.scheduledItems.map { it.toDto() },
            termPeriods = data.termPeriods.map { it.toDto() },
            predictions = data.predictions.map { it.toDto() },
            sinkingFunds = data.sinkingFunds.map { it.toDto() },
            scenarios = data.scenarios.map { it.toDto() },
            presets = data.presets.map { it.toDto() },
            merchantRules = data.merchantRules.map { it.toDto() },
            reconciliations = data.reconciliations.map { it.toDto() },
            importBatches = data.importBatches.map { it.toDto() },
            balanceSnapshots = data.balanceSnapshots.map { it.toDto() },
            categoryWeekSnapshots = data.categoryWeekSnapshots.map { it.toDto() },
            receipts = data.receipts.map { it.toDto() },
            receiptItems = data.receiptItems.map { it.toDto() },
            products = data.products.map { it.toDto() },
            productAliases = data.productAliases.map { it.toDto() },
            ingredients = data.ingredients.map { it.toDto() },
            ingredientPortions = data.ingredients.flatMap { ingredient ->
                ingredient.portions.map { IngredientPortionDto(ingredient.id, it.name, it.amount) }
            },
            ingredientAliases = data.ingredientAliases.map { IngredientAliasDto(it.id, it.nameKey, it.ingredientId) },
            recipes = data.recipes.map { it.toDto() },
            recipeLines = data.recipes.flatMap { recipe ->
                recipe.lines.mapIndexed { index, line ->
                    RecipeLineDto(
                        recipeId = recipe.id,
                        position = index,
                        group = line.group,
                        text = line.text,
                        ingredientId = line.ingredientId,
                        quantity = line.quantity,
                        optional = line.optional,
                    )
                }
            },
            recipeSteps = data.recipes.flatMap { recipe ->
                recipe.steps.mapIndexed { index, step ->
                    RecipeStepDto(recipe.id, index, step.kind.name, step.title, step.time, step.body)
                }
            },
            shoppingEntries = data.shoppingEntries.map { it.toDto() },
            shoppingMarks = data.shoppingMarks.map { ShoppingMarkDto(it.ingredientId, it.checked, it.haveIt) },
            ownedItems = data.ownedItems.map { OwnedItemDto(it.ingredientId, it.quantity, it.updatedAtEpochMillis) },
            settings = data.settings.toDto(),
            appSettings = appSettings,
        )

    fun fromBackup(backup: BackupV1): BackupData {
        val portionsByIngredient = backup.ingredientPortions.groupBy { it.ingredientId }
        val linesByRecipe = backup.recipeLines.groupBy { it.recipeId }
        val stepsByRecipe = backup.recipeSteps.groupBy { it.recipeId }
        return BackupData(
            accounts = backup.accounts.map { it.toModel() },
            categories = backup.categories.map { it.toModel() },
            transactions = backup.transactions.map { it.toModel() },
            recurringRules = backup.recurringRules.map { it.toModel() },
            scheduledItems = backup.scheduledItems.map { it.toModel() },
            termPeriods = backup.termPeriods.map { it.toModel() },
            predictions = backup.predictions.map { it.toModel() },
            sinkingFunds = backup.sinkingFunds.map { it.toModel() },
            scenarios = backup.scenarios.map { it.toModel() },
            presets = backup.presets.map { it.toModel() },
            merchantRules = backup.merchantRules.map { it.toModel() },
            reconciliations = backup.reconciliations.map { it.toModel() },
            importBatches = backup.importBatches.map { it.toModel() },
            balanceSnapshots = backup.balanceSnapshots.map { it.toModel() },
            categoryWeekSnapshots = backup.categoryWeekSnapshots.map { it.toModel() },
            receipts = backup.receipts.map { it.toModel() },
            receiptItems = backup.receiptItems.map { it.toModel() },
            products = backup.products.map { it.toModel() },
            productAliases = backup.productAliases.map { it.toModel() },
            ingredients = backup.ingredients.map { dto ->
                dto.toModel(portionsByIngredient[dto.id].orEmpty().map { Portion(it.name, it.amount) })
            },
            ingredientAliases = backup.ingredientAliases.map { IngredientAliasRecord(it.id, it.nameKey, it.ingredientId) },
            recipes = backup.recipes.map { dto ->
                dto.toModel(
                    lines = linesByRecipe[dto.id].orEmpty().sortedBy { it.position }.map { it.toModel() },
                    steps = stepsByRecipe[dto.id].orEmpty().sortedBy { it.position }.map { it.toModel() },
                )
            },
            shoppingEntries = backup.shoppingEntries.map { it.toModel() },
            shoppingMarks = backup.shoppingMarks.map { ShoppingMark(it.ingredientId, it.checked, it.haveIt) },
            ownedItems = backup.ownedItems.map { OwnedItem(it.ingredientId, it.quantity, it.updatedAtEpochMillis) },
            settings = backup.settings.toModel(),
        )
    }
}

private fun Account.toDto() = AccountDto(id, name, type.name, overdraftLimit.pence, includeInSafeToSpend, archived, sortOrder)
private fun AccountDto.toModel() = Account(
    id = id,
    name = name,
    type = AccountType.valueOf(type),
    overdraftLimit = Money(overdraftLimitPence),
    includeInSafeToSpend = includeInSafeToSpend,
    archived = archived,
    sortOrder = sortOrder,
)

private fun Category.toDto() = CategoryDto(
    id, name, flow.name, forecastMode.name, colorArgb, isSystem, systemKey, isMajorIncomeDefault, archived, sortOrder,
)
private fun CategoryDto.toModel() = Category(
    id = id,
    name = name,
    flow = Flow.valueOf(flow),
    forecastMode = ForecastMode.valueOf(forecastMode),
    colorArgb = colorArgb,
    isSystem = isSystem,
    systemKey = systemKey,
    isMajorIncomeDefault = isMajorIncomeDefault,
    archived = archived,
    sortOrder = sortOrder,
)

private fun Transaction.toDto() = TransactionDto(
    id, accountId, amount.pence, date.toString(), createdAtEpochMillis, categoryId, label, note, kind.name,
    transferGroupId, recurringRuleId, scheduledItemId, importBatchId, importKey, receiptId,
)
private fun TransactionDto.toModel() = Transaction(
    id = id,
    accountId = accountId,
    amount = Money(amountPence),
    date = LocalDate.parse(date),
    createdAtEpochMillis = createdAtEpochMillis,
    categoryId = categoryId,
    label = label,
    note = note,
    kind = TxKind.valueOf(kind),
    transferGroupId = transferGroupId,
    recurringRuleId = recurringRuleId,
    scheduledItemId = scheduledItemId,
    importBatchId = importBatchId,
    importKey = importKey,
    receiptId = receiptId,
)

private fun RecurringRule.toDto() = RecurringRuleDto(
    id, label, accountId, categoryId, amount.pence, frequency.name, interval, startDate.toString(),
    endDate?.toString(), postingMode.name, postedThrough?.toString(), toAccountId, isMajorIncome, active,
)
private fun RecurringRuleDto.toModel() = RecurringRule(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amountPence),
    frequency = Frequency.valueOf(frequency),
    interval = interval,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let { LocalDate.parse(it) },
    postingMode = PostingMode.valueOf(postingMode),
    postedThrough = postedThrough?.let { LocalDate.parse(it) },
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    active = active,
)

private fun ScheduledItem.toDto() = ScheduledItemDto(
    id, label, accountId, categoryId, amount.pence, date.toString(), postingMode.name, toAccountId,
    isMajorIncome, posted, skipped,
)
private fun ScheduledItemDto.toModel() = ScheduledItem(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amountPence),
    date = LocalDate.parse(date),
    postingMode = PostingMode.valueOf(postingMode),
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    posted = posted,
    skipped = skipped,
)

private fun TermPeriod.toDto() = TermPeriodDto(id, name, startDate.toString(), endDate.toString())
private fun TermPeriodDto.toModel() = TermPeriod(id, name, LocalDate.parse(startDate), LocalDate.parse(endDate))

private fun Prediction.toDto() = PredictionDto(id, categoryId, regime.name, mode.name, weeklyAmount.pence, adjustPercent)
private fun PredictionDto.toModel() = Prediction(
    id = id,
    categoryId = categoryId,
    regime = Regime.valueOf(regime),
    mode = PredictionMode.valueOf(mode),
    weeklyAmount = Money(weeklyAmountPence),
    adjustPercent = adjustPercent,
)

private fun SinkingFund.toDto() = SinkingFundDto(
    id, name, target.pence, startDate.toString(), targetDate.toString(), categoryId, status.name,
)
private fun SinkingFundDto.toModel() = SinkingFund(
    id = id,
    name = name,
    target = Money(targetPence),
    startDate = LocalDate.parse(startDate),
    targetDate = LocalDate.parse(targetDate),
    categoryId = categoryId,
    status = SinkingFundStatus.valueOf(status),
)

private fun Scenario.toDto() = ScenarioDto(id, name, items.map { it.toDto() })
private fun ScenarioDto.toModel() = Scenario(id, name, items.map { it.toModel() })

private fun ScenarioItem.toDto() = ScenarioItemDto(
    id, scenarioId, type.name, label, accountId, categoryId, amount.pence, date?.toString(),
    frequency?.name, interval, endDate?.toString(), percent, regime?.name,
)
private fun ScenarioItemDto.toModel() = ScenarioItem(
    id = id,
    scenarioId = scenarioId,
    type = ScenarioItemType.valueOf(type),
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amountPence),
    date = date?.let { LocalDate.parse(it) },
    frequency = frequency?.let { Frequency.valueOf(it) },
    interval = interval,
    endDate = endDate?.let { LocalDate.parse(it) },
    percent = percent,
    regime = regime?.let { Regime.valueOf(it) },
)

private fun Preset.toDto() = PresetDto(id, label, amount.pence, flow.name, categoryId, accountId, showOnWidget, sortOrder)
private fun PresetDto.toModel() = Preset(
    id = id,
    label = label,
    amount = Money(amountPence),
    flow = Flow.valueOf(flow),
    categoryId = categoryId,
    accountId = accountId,
    showOnWidget = showOnWidget,
    sortOrder = sortOrder,
)

private fun MerchantRule.toDto() = MerchantRuleDto(id, merchantKey, categoryId, label)
private fun MerchantRuleDto.toModel() = MerchantRule(id, merchantKey, categoryId, label)

private fun Reconciliation.toDto() = ReconciliationDto(
    id, accountId, date.toString(), expected.pence, actual.pence, transactionId, periodStart.toString(),
)
private fun ReconciliationDto.toModel() = Reconciliation(
    id = id,
    accountId = accountId,
    date = LocalDate.parse(date),
    expected = Money(expectedPence),
    actual = Money(actualPence),
    transactionId = transactionId,
    periodStart = LocalDate.parse(periodStart),
)

private fun ImportBatch.toDto() = ImportBatchDto(
    id, accountId, importedAtEpochMillis, fileName, rowCount, firstDate?.toString(), lastDate?.toString(),
)
private fun ImportBatchDto.toModel() = ImportBatch(
    id = id,
    accountId = accountId,
    importedAtEpochMillis = importedAtEpochMillis,
    fileName = fileName,
    rowCount = rowCount,
    firstDate = firstDate?.let { LocalDate.parse(it) },
    lastDate = lastDate?.let { LocalDate.parse(it) },
)

private fun BalanceSnapshot.toDto() = BalanceSnapshotDto(
    id, createdOn.toString(), targetDate.toString(), tier, p10?.pence, p50.pence, p90?.pence,
)
private fun BalanceSnapshotDto.toModel() = BalanceSnapshot(
    id = id,
    createdOn = LocalDate.parse(createdOn),
    targetDate = LocalDate.parse(targetDate),
    tier = tier,
    p10 = p10Pence?.let { Money(it) },
    p50 = Money(p50Pence),
    p90 = p90Pence?.let { Money(it) },
)

private fun CategoryWeekSnapshot.toDto() = CategoryWeekSnapshotDto(
    id, createdOn.toString(), weekStart.toString(), categoryId, tier, predicted.pence,
)
private fun CategoryWeekSnapshotDto.toModel() = CategoryWeekSnapshot(
    id = id,
    createdOn = LocalDate.parse(createdOn),
    weekStart = LocalDate.parse(weekStart),
    categoryId = categoryId,
    tier = tier,
    predicted = Money(predictedPence),
)

private fun Receipt.toDto() = ReceiptDto(
    id, store, date.toString(), total.pence, source, transactionId, createdAtEpochMillis,
)
private fun ReceiptDto.toModel() = Receipt(
    id = id,
    store = store,
    date = LocalDate.parse(date),
    total = Money(totalPence),
    source = source,
    transactionId = transactionId,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ReceiptItem.toDto() = ReceiptItemDto(
    id, receiptId, productId, rawName, quantity, weightGrams, unitPrice?.pence, price.pence, discount.pence, categoryId,
)
private fun ReceiptItemDto.toModel() = ReceiptItem(
    id = id,
    receiptId = receiptId,
    productId = productId,
    rawName = rawName,
    quantity = quantity,
    weightGrams = weightGrams,
    unitPrice = unitPricePence?.let { Money(it) },
    price = Money(pricePence),
    discount = Money(discountPence),
    categoryId = categoryId,
)

private fun Product.toDto() = ProductDto(id, name, categoryId)
private fun ProductDto.toModel() = Product(id, name, categoryId)

private fun ProductAliasRecord.toDto() = ProductAliasDto(id, store, rawNameKey, productId)
private fun ProductAliasDto.toModel() = ProductAliasRecord(id, store, rawNameKey, productId)

private fun BudgetSettings.toDto() = SettingsDto(
    zeroPoint.name, buffer.pence, defaultAccountId, historyStartDate?.toString(), simulations, forecastHorizonDays,
)
private fun SettingsDto.toModel() = BudgetSettings(
    zeroPoint = ZeroPoint.valueOf(zeroPoint),
    buffer = Money(bufferPence),
    defaultAccountId = defaultAccountId,
    historyStartDate = historyStartDate?.let { LocalDate.parse(it) },
    simulations = simulations,
    forecastHorizonDays = forecastHorizonDays,
)

private fun Ingredient.toDto() = IngredientDto(
    id = id,
    name = name,
    unit = unit.name,
    packSize = packSize,
    packLabel = packLabel,
    pricePence = price?.pence,
    priceDate = priceDate?.toString(),
    store = store,
    productId = productId,
    productName = productName,
    sourceUrl = sourceUrl,
    barcode = barcode,
    kcal = per100.kcal,
    fat = per100.fat,
    saturates = per100.saturates,
    carbs = per100.carbs,
    sugars = per100.sugars,
    fibre = per100.fibre,
    protein = per100.protein,
    salt = per100.salt,
    nutritionSource = nutritionSource.name,
    vegKind = vegKind.name,
    shelf = shelf.name,
    note = note,
    archived = archived,
    createdAtEpochMillis = createdAtEpochMillis,
    addedSugars = per100.addedSugars,
    foodGroup = foodGroup?.name,
)
private fun IngredientDto.toModel(portions: List<Portion>) = Ingredient(
    id = id,
    name = name,
    unit = BaseUnit.valueOf(unit),
    packSize = packSize,
    packLabel = packLabel,
    price = pricePence?.let { Money(it) },
    priceDate = priceDate?.let { LocalDate.parse(it) },
    store = store,
    productId = productId,
    productName = productName,
    sourceUrl = sourceUrl,
    barcode = barcode,
    per100 = Nutrition(kcal, fat, saturates, carbs, sugars, fibre, protein, salt, addedSugars),
    nutritionSource = NutritionSource.valueOf(nutritionSource),
    vegKind = VegKind.valueOf(vegKind),
    shelf = Shelf.valueOf(shelf),
    note = note,
    archived = archived,
    createdAtEpochMillis = createdAtEpochMillis,
    portions = portions,
    foodGroup = foodGroup?.let { name -> FoodGroup.entries.firstOrNull { it.name == name } },
)

private fun Recipe.toDto() = RecipeDto(
    id = id,
    title = title,
    shortTitle = shortTitle,
    tagline = tagline,
    style = style,
    serves = serves,
    prepMins = prepMins,
    cookMins = cookMins,
    timeNote = timeNote,
    servedWith = servedWith,
    tags = tags,
    rating = rating,
    notes = notes,
    source = source.name,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    archived = archived,
)
private fun RecipeDto.toModel(lines: List<RecipeLine>, steps: List<RecipeStep>) = Recipe(
    id = id,
    title = title,
    shortTitle = shortTitle,
    tagline = tagline,
    style = style,
    serves = serves,
    prepMins = prepMins,
    cookMins = cookMins,
    timeNote = timeNote,
    servedWith = servedWith,
    tags = tags,
    rating = rating,
    notes = notes,
    source = RecipeSource.valueOf(source),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    archived = archived,
    lines = lines,
    steps = steps,
)

private fun RecipeLineDto.toModel() = RecipeLine(
    group = group,
    text = text,
    ingredientId = ingredientId,
    quantity = quantity,
    optional = optional,
)

private fun RecipeStepDto.toModel() = RecipeStep(
    kind = StepKind.valueOf(kind),
    title = title,
    time = time,
    body = body,
)

private fun ShoppingEntry.toDto() = ShoppingEntryDto(
    id, recipeId, servings, ingredientId, quantity, freeText, checked, addedAtEpochMillis,
)
private fun ShoppingEntryDto.toModel() = ShoppingEntry(
    id = id,
    recipeId = recipeId,
    servings = servings,
    ingredientId = ingredientId,
    quantity = quantity,
    freeText = freeText,
    checked = checked,
    addedAtEpochMillis = addedAtEpochMillis,
)
