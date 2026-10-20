package io.github.matteopi.budgeting.core.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DateTimeException

/** The outcome of [BackupCodec.decode]. */
sealed class BackupDecodeResult {
    data class Success(
        val data: BackupData,
        val appSettings: Map<String, String>,
        val exportedAt: String,
    ) : BackupDecodeResult()

    /** Human-readable problems; the file was not restored. */
    data class Failure(val errors: List<String>) : BackupDecodeResult()
}

/** JSON encode/decode for [BackupV1] (versions 1 to 7), with validation. See docs/PLAN.md §5.17. */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(backup: BackupV1): String = json.encodeToString(BackupV1.serializer(), backup)

    fun decode(text: String): BackupDecodeResult {
        val backup = try {
            json.decodeFromString(BackupV1.serializer(), text)
        } catch (e: SerializationException) {
            return BackupDecodeResult.Failure(listOf("This file isn't valid JSON and can't be read."))
        } catch (e: IllegalArgumentException) {
            return BackupDecodeResult.Failure(listOf("This file isn't valid JSON and can't be read."))
        }

        if (backup.format != BackupV1.FORMAT) {
            return BackupDecodeResult.Failure(listOf("This doesn't look like a Budgeting backup file."))
        }
        if (backup.version > BackupV1.CURRENT_VERSION) {
            return BackupDecodeResult.Failure(
                listOf("This backup was made with a newer version of the app and can't be read here."),
            )
        }
        if (backup.version < 1) {
            return BackupDecodeResult.Failure(listOf("This backup file has an unrecognised version."))
        }

        val referenceErrors = validateReferences(backup)
        if (referenceErrors.isNotEmpty()) return BackupDecodeResult.Failure(referenceErrors)

        return try {
            BackupDecodeResult.Success(
                data = BackupMapper.fromBackup(backup),
                appSettings = backup.appSettings,
                exportedAt = backup.exportedAt,
            )
        } catch (e: IllegalArgumentException) {
            // e.g. an enum name that doesn't exist.
            BackupDecodeResult.Failure(listOf("The backup data is corrupted and can't be read."))
        } catch (e: DateTimeException) {
            // A date string that doesn't parse (DateTimeParseException is not an IllegalArgumentException).
            BackupDecodeResult.Failure(listOf("The backup data is corrupted and can't be read."))
        }
    }

    private fun validateReferences(b: BackupV1): List<String> {
        val accountIds = b.accounts.map { it.id }.toSet()
        val categoryIds = b.categories.map { it.id }.toSet()
        val recurringRuleIds = b.recurringRules.map { it.id }.toSet()
        val scheduledItemIds = b.scheduledItems.map { it.id }.toSet()
        val importBatchIds = b.importBatches.map { it.id }.toSet()
        val transactionIds = b.transactions.map { it.id }.toSet()
        val receiptIds = b.receipts.map { it.id }.toSet()
        val productIds = b.products.map { it.id }.toSet()

        val errors = mutableListOf<String>()
        fun check(ok: Boolean, message: String) {
            if (!ok) errors += message
        }

        for (tx in b.transactions) {
            check(tx.accountId in accountIds, "Transaction ${tx.id} refers to a missing account ${tx.accountId}.")
            tx.categoryId?.let { check(it in categoryIds, "Transaction ${tx.id} refers to a missing category $it.") }
            tx.recurringRuleId?.let {
                check(it in recurringRuleIds, "Transaction ${tx.id} refers to a missing recurring rule $it.")
            }
            tx.scheduledItemId?.let {
                check(it in scheduledItemIds, "Transaction ${tx.id} refers to a missing scheduled item $it.")
            }
            tx.importBatchId?.let {
                check(it in importBatchIds, "Transaction ${tx.id} refers to a missing import batch $it.")
            }
            tx.receiptId?.let { check(it in receiptIds, "Transaction ${tx.id} refers to a missing receipt $it.") }
        }

        for (r in b.recurringRules) {
            check(r.accountId in accountIds, "Recurring rule ${r.id} refers to a missing account ${r.accountId}.")
            r.categoryId?.let { check(it in categoryIds, "Recurring rule ${r.id} refers to a missing category $it.") }
            r.toAccountId?.let { check(it in accountIds, "Recurring rule ${r.id} refers to a missing account $it.") }
        }

        for (s in b.scheduledItems) {
            check(s.accountId in accountIds, "Scheduled item ${s.id} refers to a missing account ${s.accountId}.")
            s.categoryId?.let { check(it in categoryIds, "Scheduled item ${s.id} refers to a missing category $it.") }
            s.toAccountId?.let { check(it in accountIds, "Scheduled item ${s.id} refers to a missing account $it.") }
        }

        for (p in b.predictions) {
            check(p.categoryId in categoryIds, "Prediction ${p.id} refers to a missing category ${p.categoryId}.")
        }

        for (f in b.sinkingFunds) {
            f.categoryId?.let { check(it in categoryIds, "Sinking fund ${f.id} refers to a missing category $it.") }
        }

        for (scenario in b.scenarios) {
            for (item in scenario.items) {
                item.accountId?.let {
                    check(it in accountIds, "Scenario item ${item.id} refers to a missing account $it.")
                }
                item.categoryId?.let {
                    check(it in categoryIds, "Scenario item ${item.id} refers to a missing category $it.")
                }
            }
        }

        for (preset in b.presets) {
            check(preset.categoryId in categoryIds, "Preset ${preset.id} refers to a missing category ${preset.categoryId}.")
            preset.accountId?.let { check(it in accountIds, "Preset ${preset.id} refers to a missing account $it.") }
        }

        for (rule in b.merchantRules) {
            check(rule.categoryId in categoryIds, "Merchant rule ${rule.id} refers to a missing category ${rule.categoryId}.")
        }

        for (rec in b.reconciliations) {
            check(rec.accountId in accountIds, "Reconciliation ${rec.id} refers to a missing account ${rec.accountId}.")
            rec.transactionId?.let {
                check(it in transactionIds, "Reconciliation ${rec.id} refers to a missing transaction $it.")
            }
        }

        for (batch in b.importBatches) {
            check(batch.accountId in accountIds, "Import batch ${batch.id} refers to a missing account ${batch.accountId}.")
        }

        for (snap in b.categoryWeekSnapshots) {
            check(
                snap.categoryId in categoryIds,
                "Category week snapshot ${snap.id} refers to a missing category ${snap.categoryId}.",
            )
        }

        for (product in b.products) {
            product.categoryId?.let {
                check(it in categoryIds, "Product ${product.id} refers to a missing category $it.")
            }
        }

        for (alias in b.productAliases) {
            check(alias.productId in productIds, "Product alias ${alias.id} refers to a missing product ${alias.productId}.")
        }

        for (item in b.receiptItems) {
            check(item.receiptId in receiptIds, "Receipt item ${item.id} refers to a missing receipt ${item.receiptId}.")
            item.productId?.let { check(it in productIds, "Receipt item ${item.id} refers to a missing product $it.") }
            item.categoryId?.let { check(it in categoryIds, "Receipt item ${item.id} refers to a missing category $it.") }
        }

        validateFood(b, productIds) { ok, message -> check(ok, message) }
        validatePlanner(b) { ok, message -> check(ok, message) }

        b.settings.defaultAccountId?.let {
            check(it in accountIds, "Settings refer to a missing default account $it.")
        }

        return errors
    }

    /**
     * The Planner tables (versions 5 to 7): references, the unique module codes (without case), steps one level deep,
     * one change per entry and date, one link per item, times inside the day (`LocalTime.of` would otherwise throw on a
     * corrupted minute), chunks that end after they start, and one review per day.
     */
    private fun validatePlanner(b: BackupV1, check: (Boolean, String) -> Unit) {
        val moduleIds = b.plannerModules.map { it.id }.toSet()
        val tasksById = b.plannerTasks.associateBy { it.id }
        check(moduleIds.size == b.plannerModules.size, "Two modules have the same id.")
        check(tasksById.size == b.plannerTasks.size, "Two tasks have the same id.")
        check(
            b.plannerModules.map { it.code.trim().lowercase() }.toSet().size == b.plannerModules.size,
            "Two modules have the same code.",
        )
        for (task in b.plannerTasks) {
            task.moduleId?.let { check(it in moduleIds, "Task ${task.id} refers to a missing module $it.") }
            task.parentId?.let { parentId ->
                val parent = tasksById[parentId]
                check(parent != null, "Task ${task.id} is a step of a missing deadline $parentId.")
                check(parent?.parentId == null, "Task ${task.id} is a step of a step.")
            }
            task.dueMinute?.let { check(it in 0 until 24 * 60, "Task ${task.id} has a time outside the day.") }
        }
        for (item in b.plannerChecklist) {
            check(item.taskId in tasksById, "A checklist item refers to a missing task ${item.taskId}.")
        }
        for (completion in b.plannerCompletions) {
            check(completion.taskId in tasksById, "Done log entry ${completion.id} refers to a missing task ${completion.taskId}.")
        }

        // Version 6: the timetable and the calendar links.
        val termIds = b.termPeriods.map { it.id }.toSet()
        val entryIds = b.plannerEntries.map { it.id }.toSet()
        check(entryIds.size == b.plannerEntries.size, "Two timetable entries have the same id.")
        val day = 0 until 24 * 60
        for (entry in b.plannerEntries) {
            entry.moduleId?.let { check(it in moduleIds, "Timetable entry ${entry.id} refers to a missing module $it.") }
            entry.termId?.let { check(it in termIds, "Timetable entry ${entry.id} refers to a missing term $it.") }
            check(entry.weekday in 1..7, "Timetable entry ${entry.id} has no weekday.")
            check(entry.startMinute in day && entry.endMinute in day, "Timetable entry ${entry.id} has a time outside the day.")
            check(entry.pattern != "ONCE" || entry.onceDate != null, "Timetable entry ${entry.id} is once only but has no date.")
        }
        check(
            b.plannerEntryExceptions.map { it.entryId to it.date }.toSet().size == b.plannerEntryExceptions.size,
            "A timetable entry has two changes on the same date.",
        )
        for (exception in b.plannerEntryExceptions) {
            check(exception.entryId in entryIds, "A timetable change refers to a missing entry ${exception.entryId}.")
            check(
                (exception.startMinute == null || exception.startMinute in day) && (exception.endMinute == null || exception.endMinute in day),
                "A timetable change has a time outside the day.",
            )
        }
        check(
            b.plannerCalendarLinks.map { it.itemKey }.toSet().size == b.plannerCalendarLinks.size,
            "Two calendar links are for the same item.",
        )

        // Version 7: the day record.
        val categoryIds = b.plannerCategories.map { it.id }.toSet()
        check(categoryIds.size == b.plannerCategories.size, "Two day-record categories have the same id.")
        val keys = b.plannerCategories.mapNotNull { it.systemKey }
        check(keys.toSet().size == keys.size, "Two day-record categories have the same meaning.")
        val blockIds = b.plannerPlanBlocks.map { it.id }.toSet()
        check(blockIds.size == b.plannerPlanBlocks.size, "Two plan blocks have the same id.")
        for (block in b.plannerPlanBlocks) {
            check(block.categoryId in categoryIds, "Plan block ${block.id} refers to a missing category ${block.categoryId}.")
            block.moduleId?.let { check(it in moduleIds, "Plan block ${block.id} refers to a missing module $it.") }
            block.taskId?.let { check(it in tasksById, "Plan block ${block.id} refers to a missing task $it.") }
            check(block.endEpochMillis > block.startEpochMillis, "Plan block ${block.id} ends before it starts.")
        }
        check(b.plannerRecords.map { it.id }.toSet().size == b.plannerRecords.size, "Two record chunks have the same id.")
        for (record in b.plannerRecords) {
            check(record.categoryId in categoryIds, "Record ${record.id} refers to a missing category ${record.categoryId}.")
            record.moduleId?.let { check(it in moduleIds, "Record ${record.id} refers to a missing module $it.") }
            record.taskId?.let { check(it in tasksById, "Record ${record.id} refers to a missing task $it.") }
            record.planBlockId?.let { check(it in blockIds, "Record ${record.id} refers to a missing plan block $it.") }
            check(record.endEpochMillis > record.startEpochMillis, "Record ${record.id} ends before it starts.")
            check(record.focus == null || record.focus in 1..5, "Record ${record.id} has a focus outside 1 to 5.")
        }
        check(b.plannerDayReviews.map { it.date }.toSet().size == b.plannerDayReviews.size, "A day has two reviews.")
        for (review in b.plannerDayReviews) {
            check(review.rating == null || review.rating in 1..5, "The review of ${review.date} has a rating outside 1 to 5.")
        }
    }

    /** The version-3 tables: the same reference checks, plus the uniqueness the food tables enforce. */
    private fun validateFood(b: BackupV1, productIds: Set<Long>, check: (Boolean, String) -> Unit) {
        val ingredientIds = b.ingredients.map { it.id }.toSet()
        val recipeIds = b.recipes.map { it.id }.toSet()

        check(ingredientIds.size == b.ingredients.size, "Two ingredients have the same id.")
        check(recipeIds.size == b.recipes.size, "Two recipes have the same id.")
        val barcodes = b.ingredients.mapNotNull { it.barcode }
        check(barcodes.toSet().size == barcodes.size, "Two ingredients have the same barcode.")
        check(
            b.ingredientAliases.map { it.nameKey }.toSet().size == b.ingredientAliases.size,
            "Two ingredient aliases have the same name.",
        )
        check(
            b.ingredientPortions.map { it.ingredientId to it.name }.toSet().size == b.ingredientPortions.size,
            "An ingredient has two portions with the same name.",
        )
        check(
            b.shoppingMarks.map { it.ingredientId }.toSet().size == b.shoppingMarks.size,
            "An ingredient has two shopping list marks.",
        )
        check(
            b.ownedItems.map { it.ingredientId }.toSet().size == b.ownedItems.size,
            "An ingredient is listed twice in what you have.",
        )

        for (ingredient in b.ingredients) {
            ingredient.productId?.let {
                check(it in productIds, "Ingredient ${ingredient.id} refers to a missing product $it.")
            }
        }
        for (portion in b.ingredientPortions) {
            check(
                portion.ingredientId in ingredientIds,
                "Portion \"${portion.name}\" refers to a missing ingredient ${portion.ingredientId}.",
            )
        }
        for (alias in b.ingredientAliases) {
            check(
                alias.ingredientId in ingredientIds,
                "Ingredient alias ${alias.id} refers to a missing ingredient ${alias.ingredientId}.",
            )
        }
        for (line in b.recipeLines) {
            check(line.recipeId in recipeIds, "A recipe line refers to a missing recipe ${line.recipeId}.")
            line.ingredientId?.let {
                check(it in ingredientIds, "A line of recipe ${line.recipeId} refers to a missing ingredient $it.")
            }
        }
        for (step in b.recipeSteps) {
            check(step.recipeId in recipeIds, "A recipe step refers to a missing recipe ${step.recipeId}.")
        }
        for (entry in b.shoppingEntries) {
            entry.recipeId?.let {
                check(it in recipeIds, "Shopping list entry ${entry.id} refers to a missing recipe $it.")
            }
            entry.ingredientId?.let {
                check(it in ingredientIds, "Shopping list entry ${entry.id} refers to a missing ingredient $it.")
            }
        }
        for (mark in b.shoppingMarks) {
            check(
                mark.ingredientId in ingredientIds,
                "A shopping list mark refers to a missing ingredient ${mark.ingredientId}.",
            )
        }
        for (item in b.ownedItems) {
            check(item.ingredientId in ingredientIds, "What you have refers to a missing ingredient ${item.ingredientId}.")
            item.quantity?.let { check(it >= 0.0, "What you have of ingredient ${item.ingredientId} is below zero.") }
        }
    }
}
