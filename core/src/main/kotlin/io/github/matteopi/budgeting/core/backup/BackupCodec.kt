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

/** JSON encode/decode for [BackupV1] (versions 1 and 2), with validation. See docs/PLAN.md §5.17. */
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

        b.settings.defaultAccountId?.let {
            check(it in accountIds, "Settings refer to a missing default account $it.")
        }

        return errors
    }
}
