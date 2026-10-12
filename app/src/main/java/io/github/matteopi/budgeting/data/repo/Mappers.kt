package io.github.matteopi.budgeting.data.repo

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.BalanceSnapshot
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.CategoryWeekSnapshot
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
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
import io.github.matteopi.budgeting.data.db.entities.AccountEntity
import io.github.matteopi.budgeting.data.db.entities.BalanceSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryEntity
import io.github.matteopi.budgeting.data.db.entities.CategoryWeekSnapshotEntity
import io.github.matteopi.budgeting.data.db.entities.ImportBatchEntity
import io.github.matteopi.budgeting.data.db.entities.MerchantRuleEntity
import io.github.matteopi.budgeting.data.db.entities.PredictionEntity
import io.github.matteopi.budgeting.data.db.entities.PresetEntity
import io.github.matteopi.budgeting.data.db.entities.ProductAliasEntity
import io.github.matteopi.budgeting.data.db.entities.ProductEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptEntity
import io.github.matteopi.budgeting.data.db.entities.ReceiptItemEntity
import io.github.matteopi.budgeting.data.db.entities.ReconciliationEntity
import io.github.matteopi.budgeting.data.db.entities.RecurringRuleEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioEntity
import io.github.matteopi.budgeting.data.db.entities.ScenarioItemEntity
import io.github.matteopi.budgeting.data.db.entities.ScheduledItemEntity
import io.github.matteopi.budgeting.data.db.entities.SinkingFundEntity
import io.github.matteopi.budgeting.data.db.entities.TermPeriodEntity
import io.github.matteopi.budgeting.data.db.entities.TransactionEntity
import java.time.LocalDate

/**
 * Entity <-> core model mapping. Room entities use only primitive columns (dates as epoch-day
 * Long, money as pence Long, enums as their name String); these functions are the single place
 * that translates between that storage shape and the shared `core.model` types.
 */

// Account

fun AccountEntity.toModel(): Account = Account(
    id = id,
    name = name,
    type = AccountType.valueOf(type),
    overdraftLimit = Money(overdraftLimit),
    includeInSafeToSpend = includeInPool,
    archived = archived,
    sortOrder = sortOrder,
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type.name,
    overdraftLimit = overdraftLimit.pence,
    includeInPool = includeInSafeToSpend,
    archived = archived,
    sortOrder = sortOrder,
)

// Category

fun CategoryEntity.toModel(): Category = Category(
    id = id,
    name = name,
    flow = CategoryFlow.valueOf(flow),
    forecastMode = ForecastMode.valueOf(forecastMode),
    colorArgb = colorArgb,
    isSystem = isSystem,
    systemKey = systemKey,
    isMajorIncomeDefault = isMajorIncomeDefault,
    archived = archived,
    sortOrder = sortOrder,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    flow = flow.name,
    forecastMode = forecastMode.name,
    colorArgb = colorArgb,
    isSystem = isSystem,
    systemKey = systemKey,
    isMajorIncomeDefault = isMajorIncomeDefault,
    archived = archived,
    sortOrder = sortOrder,
)

// Transaction

fun TransactionEntity.toModel(): Transaction = Transaction(
    id = id,
    accountId = accountId,
    amount = Money(amount),
    date = LocalDate.ofEpochDay(date),
    createdAtEpochMillis = createdAt,
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

fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    accountId = accountId,
    amount = amount.pence,
    date = date.toEpochDay(),
    createdAt = createdAtEpochMillis,
    categoryId = categoryId,
    label = label,
    note = note,
    kind = kind.name,
    transferGroupId = transferGroupId,
    recurringRuleId = recurringRuleId,
    scheduledItemId = scheduledItemId,
    importBatchId = importBatchId,
    importKey = importKey,
    receiptId = receiptId,
)

// RecurringRule

fun RecurringRuleEntity.toModel(): RecurringRule = RecurringRule(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amount),
    frequency = Frequency.valueOf(frequency),
    interval = interval,
    startDate = LocalDate.ofEpochDay(startDate),
    endDate = endDate?.let(LocalDate::ofEpochDay),
    postingMode = PostingMode.valueOf(postingMode),
    postedThrough = postedThrough?.let(LocalDate::ofEpochDay),
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    active = active,
)

fun RecurringRule.toEntity(): RecurringRuleEntity = RecurringRuleEntity(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = amount.pence,
    frequency = frequency.name,
    interval = interval,
    startDate = startDate.toEpochDay(),
    endDate = endDate?.toEpochDay(),
    postingMode = postingMode.name,
    postedThrough = postedThrough?.toEpochDay(),
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    active = active,
)

// ScheduledItem

fun ScheduledItemEntity.toModel(): ScheduledItem = ScheduledItem(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amount),
    date = LocalDate.ofEpochDay(date),
    postingMode = PostingMode.valueOf(postingMode),
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    posted = posted,
    skipped = skipped,
)

fun ScheduledItem.toEntity(): ScheduledItemEntity = ScheduledItemEntity(
    id = id,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = amount.pence,
    date = date.toEpochDay(),
    postingMode = postingMode.name,
    toAccountId = toAccountId,
    isMajorIncome = isMajorIncome,
    posted = posted,
    skipped = skipped,
)

// TermPeriod

fun TermPeriodEntity.toModel(): TermPeriod = TermPeriod(
    id = id,
    name = name,
    startDate = LocalDate.ofEpochDay(startDate),
    endDate = LocalDate.ofEpochDay(endDate),
)

fun TermPeriod.toEntity(): TermPeriodEntity = TermPeriodEntity(
    id = id,
    name = name,
    startDate = startDate.toEpochDay(),
    endDate = endDate.toEpochDay(),
)

// Prediction

fun PredictionEntity.toModel(): Prediction = Prediction(
    id = id,
    categoryId = categoryId,
    regime = Regime.valueOf(regime),
    mode = PredictionMode.valueOf(mode),
    weeklyAmount = Money(weeklyAmount),
    adjustPercent = adjustPercent,
)

fun Prediction.toEntity(): PredictionEntity = PredictionEntity(
    id = id,
    categoryId = categoryId,
    regime = regime.name,
    mode = mode.name,
    weeklyAmount = weeklyAmount.pence,
    adjustPercent = adjustPercent,
)

// SinkingFund

fun SinkingFundEntity.toModel(): SinkingFund = SinkingFund(
    id = id,
    name = name,
    target = Money(target),
    startDate = LocalDate.ofEpochDay(startDate),
    targetDate = LocalDate.ofEpochDay(targetDate),
    categoryId = categoryId,
    status = SinkingFundStatus.valueOf(status),
)

fun SinkingFund.toEntity(): SinkingFundEntity = SinkingFundEntity(
    id = id,
    name = name,
    target = target.pence,
    startDate = startDate.toEpochDay(),
    targetDate = targetDate.toEpochDay(),
    categoryId = categoryId,
    status = status.name,
)

// Scenario (createdAt is app/Room-only; the core model doesn't carry it).

fun ScenarioEntity.toModel(): Scenario = Scenario(id = id, name = name)

fun Scenario.toEntity(createdAt: Long): ScenarioEntity = ScenarioEntity(id = id, name = name, createdAt = createdAt)

// ScenarioItem

fun ScenarioItemEntity.toModel(): ScenarioItem = ScenarioItem(
    id = id,
    scenarioId = scenarioId,
    type = ScenarioItemType.valueOf(type),
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = Money(amount),
    date = date?.let(LocalDate::ofEpochDay),
    frequency = frequency?.let(Frequency::valueOf),
    interval = interval,
    endDate = endDate?.let(LocalDate::ofEpochDay),
    percent = percent,
    regime = regime?.let(Regime::valueOf),
)

fun ScenarioItem.toEntity(): ScenarioItemEntity = ScenarioItemEntity(
    id = id,
    scenarioId = scenarioId,
    type = type.name,
    label = label,
    accountId = accountId,
    categoryId = categoryId,
    amount = amount.pence,
    date = date?.toEpochDay(),
    frequency = frequency?.name,
    interval = interval,
    endDate = endDate?.toEpochDay(),
    percent = percent,
    regime = regime?.name,
)

// Preset

fun PresetEntity.toModel(): Preset = Preset(
    id = id,
    label = label,
    amount = Money(amount),
    flow = CategoryFlow.valueOf(flow),
    categoryId = categoryId,
    accountId = accountId,
    showOnWidget = showOnWidget,
    sortOrder = sortOrder,
)

fun Preset.toEntity(): PresetEntity = PresetEntity(
    id = id,
    label = label,
    amount = amount.pence,
    flow = flow.name,
    categoryId = categoryId,
    accountId = accountId,
    showOnWidget = showOnWidget,
    sortOrder = sortOrder,
)

// MerchantRule

fun MerchantRuleEntity.toModel(): MerchantRule = MerchantRule(id = id, merchantKey = merchantKey, categoryId = categoryId, label = label)

fun MerchantRule.toEntity(): MerchantRuleEntity = MerchantRuleEntity(id = id, merchantKey = merchantKey, categoryId = categoryId, label = label)

// Reconciliation

fun ReconciliationEntity.toModel(): Reconciliation = Reconciliation(
    id = id,
    accountId = accountId,
    date = LocalDate.ofEpochDay(date),
    expected = Money(expected),
    actual = Money(actual),
    transactionId = transactionId,
    periodStart = LocalDate.ofEpochDay(periodStart),
)

fun Reconciliation.toEntity(): ReconciliationEntity = ReconciliationEntity(
    id = id,
    accountId = accountId,
    date = date.toEpochDay(),
    expected = expected.pence,
    actual = actual.pence,
    transactionId = transactionId,
    periodStart = periodStart.toEpochDay(),
)

// ImportBatch

fun ImportBatchEntity.toModel(): ImportBatch = ImportBatch(
    id = id,
    accountId = accountId,
    importedAtEpochMillis = importedAt,
    fileName = fileName,
    rowCount = rowCount,
    firstDate = firstDate?.let(LocalDate::ofEpochDay),
    lastDate = lastDate?.let(LocalDate::ofEpochDay),
)

fun ImportBatch.toEntity(): ImportBatchEntity = ImportBatchEntity(
    id = id,
    accountId = accountId,
    importedAt = importedAtEpochMillis,
    fileName = fileName,
    rowCount = rowCount,
    firstDate = firstDate?.toEpochDay(),
    lastDate = lastDate?.toEpochDay(),
)

// BalanceSnapshot

fun BalanceSnapshotEntity.toModel(): BalanceSnapshot = BalanceSnapshot(
    id = id,
    createdOn = LocalDate.ofEpochDay(createdOn),
    targetDate = LocalDate.ofEpochDay(targetDate),
    tier = tier,
    p10 = p10?.let(::Money),
    p50 = Money(p50),
    p90 = p90?.let(::Money),
)

fun BalanceSnapshot.toEntity(): BalanceSnapshotEntity = BalanceSnapshotEntity(
    id = id,
    createdOn = createdOn.toEpochDay(),
    targetDate = targetDate.toEpochDay(),
    tier = tier,
    p10 = p10?.pence,
    p50 = p50.pence,
    p90 = p90?.pence,
)

// CategoryWeekSnapshot

fun CategoryWeekSnapshotEntity.toModel(): CategoryWeekSnapshot = CategoryWeekSnapshot(
    id = id,
    createdOn = LocalDate.ofEpochDay(createdOn),
    weekStart = LocalDate.ofEpochDay(weekStart),
    categoryId = categoryId,
    tier = tier,
    predicted = Money(predicted),
)

fun CategoryWeekSnapshot.toEntity(): CategoryWeekSnapshotEntity = CategoryWeekSnapshotEntity(
    id = id,
    createdOn = createdOn.toEpochDay(),
    weekStart = weekStart.toEpochDay(),
    categoryId = categoryId,
    tier = tier,
    predicted = predicted.pence,
)

// Receipt

fun ReceiptEntity.toModel(): Receipt = Receipt(
    id = id,
    store = store,
    date = LocalDate.ofEpochDay(date),
    total = Money(total),
    source = source,
    transactionId = transactionId,
    createdAtEpochMillis = createdAt,
)

fun Receipt.toEntity(): ReceiptEntity = ReceiptEntity(
    id = id,
    store = store,
    date = date.toEpochDay(),
    total = total.pence,
    source = source,
    transactionId = transactionId,
    createdAt = createdAtEpochMillis,
)

// ReceiptItem

fun ReceiptItemEntity.toModel(): ReceiptItem = ReceiptItem(
    id = id,
    receiptId = receiptId,
    productId = productId,
    rawName = rawName,
    quantity = quantity,
    weightGrams = weightGrams,
    unitPrice = unitPrice?.let { Money(it) },
    price = Money(price),
    discount = Money(discount),
    categoryId = categoryId,
)

fun ReceiptItem.toEntity(): ReceiptItemEntity = ReceiptItemEntity(
    id = id,
    receiptId = receiptId,
    productId = productId,
    rawName = rawName,
    quantity = quantity,
    weightGrams = weightGrams,
    unitPrice = unitPrice?.pence,
    price = price.pence,
    discount = discount.pence,
    categoryId = categoryId,
)

// Product

fun ProductEntity.toModel(): Product = Product(id = id, name = name, categoryId = categoryId)

fun Product.toEntity(): ProductEntity = ProductEntity(id = id, name = name, categoryId = categoryId)

// ProductAliasRecord

fun ProductAliasEntity.toModel(): ProductAliasRecord = ProductAliasRecord(
    id = id,
    store = store,
    rawNameKey = rawNameKey,
    productId = productId,
)

fun ProductAliasRecord.toEntity(): ProductAliasEntity = ProductAliasEntity(
    id = id,
    store = store,
    rawNameKey = rawNameKey,
    productId = productId,
)
