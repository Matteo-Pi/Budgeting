package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.backup.BackupCodec
import io.github.matteopi.budgeting.core.backup.BackupData
import io.github.matteopi.budgeting.core.backup.BackupDecodeResult
import io.github.matteopi.budgeting.core.backup.BackupMapper
import io.github.matteopi.budgeting.core.backup.BackupV1
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Stress tests of the backup file: random [BackupData] round-trips exactly through
 * `BackupMapper` + `BackupCodec`, and random damage to a valid backup is answered with a
 * `Failure` (or a self-consistent `Success`), never an exception.
 */
class BackupStressTest {
    private val seedBase = env("STRESS_SEED", 1L)

    private fun scaled(default: Int): Int {
        val requested = System.getenv("STRESS_COUNT")?.toIntOrNull() ?: return default
        return if (requested == 1) 1 else maxOf(default, requested)
    }

    @Test
    fun randomBackupDataRoundTripsExactly() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(1000)) {
            val seed = seedBase + i
            try {
                val random = Random(seed)
                val data = BackupGenerator.data(random)
                val appSettings = BackupGenerator.appSettings(random)
                val exportedAt = BackupGenerator.text(random)
                val json = BackupCodec.encode(BackupMapper.toBackup(data, exportedAt, appSettings))
                when (val decoded = BackupCodec.decode(json)) {
                    is BackupDecodeResult.Failure -> failures += CaseFailure(seed, "backup.roundTrip.failure", decoded.errors.take(3).toString())
                    is BackupDecodeResult.Success -> {
                        if (decoded.data != data) failures += CaseFailure(seed, "backup.roundTrip.data", firstDifference(data, decoded.data))
                        if (decoded.appSettings != appSettings) failures += CaseFailure(seed, "backup.roundTrip.appSettings", "app settings differ")
                        if (decoded.exportedAt != exportedAt) failures += CaseFailure(seed, "backup.roundTrip.exportedAt", "exportedAt differs")
                        val again = BackupCodec.encode(BackupMapper.toBackup(decoded.data, decoded.exportedAt, decoded.appSettings))
                        if (again != json) failures += CaseFailure(seed, "backup.roundTrip.canonical", "re-encoding the decoded data gives different JSON")
                    }
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "backup.roundTrip.exception", "${t::class.simpleName}: ${t.message}")
            }
        }
        report("Backup round trip", failures) { seed -> describeData(BackupGenerator.data(Random(seed))) }
    }

    @Test
    fun damagedBackupsFailCleanlyAndNeverThrow() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(3000)) {
            val seed = seedBase + i
            val random = Random(seed)
            val valid = BackupCodec.encode(BackupMapper.toBackup(BackupGenerator.data(random, small = true), "2026-09-28T12:00:00Z", mapOf("k" to "v")))
            val mutation = Mutations.pick(random, valid)
            val damaged = mutation.apply()
            try {
                when (val decoded = BackupCodec.decode(damaged)) {
                    is BackupDecodeResult.Failure -> {
                        if (decoded.errors.isEmpty() || decoded.errors.any { it.isBlank() }) {
                            failures += CaseFailure(seed, "backup.mutation.failureMessage", "Failure without a readable message after '${mutation.name}'")
                        }
                    }
                    is BackupDecodeResult.Success -> {
                        if (mutation.mustFail) {
                            failures += CaseFailure(seed, "backup.mutation.${mutation.name}.shouldFail", "decoded successfully although '${mutation.name}' breaks the file")
                        } else {
                            // The damage happened to leave a valid backup: it must then be self-consistent.
                            val again = BackupCodec.decode(BackupCodec.encode(BackupMapper.toBackup(decoded.data, decoded.exportedAt, decoded.appSettings)))
                            if (again !is BackupDecodeResult.Success || again.data != decoded.data) {
                                failures += CaseFailure(seed, "backup.mutation.selfConsistency", "a decoded damaged backup does not survive another round trip")
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "backup.mutation.${mutation.name}.exception", "${t::class.simpleName}: ${t.message} for ${damaged.length} chars")
            }
        }
        report("Backup mutations", failures) { seed ->
            val random = Random(seed)
            val valid = BackupCodec.encode(BackupMapper.toBackup(BackupGenerator.data(random, small = true), "2026-09-28T12:00:00Z", mapOf("k" to "v")))
            val mutation = Mutations.pick(random, valid)
            "mutation '${mutation.name}': " + mutation.describe()
        }
    }

    @Test
    fun garbageNeverThrows() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(3000)) {
            val seed = seedBase + i
            val random = Random(seed)
            val text = when (random.nextInt(4)) {
                0 -> ""
                1 -> BackupGenerator.text(random, 400)
                2 -> "{" + BackupGenerator.text(random, 200) + "}"
                else -> Garbage.text(random)
            }
            try {
                when (BackupCodec.decode(text)) {
                    is BackupDecodeResult.Failure -> Unit
                    is BackupDecodeResult.Success -> failures += CaseFailure(seed, "backup.garbage.success", "garbage decoded as a backup: ${text.take(80).escaped()}")
                }
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "backup.garbage.exception", "${t::class.simpleName}: ${t.message} on ${text.take(80).escaped()}")
            }
        }
        report("Backup garbage", failures) { seed -> "garbage text of case $seed" }
    }

    private fun report(title: String, failures: List<CaseFailure>, describe: (Long) -> String) {
        if (failures.isEmpty()) return
        fail(StressReport.render(title, failures, describe, "STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*BackupStressTest*'"))
    }

    private fun describeData(data: BackupData): String =
        "accounts=${data.accounts.size} categories=${data.categories.size} transactions=${data.transactions.size} rules=${data.recurringRules.size} " +
            "items=${data.scheduledItems.size} scenarios=${data.scenarios.size} settings=${data.settings}"

    private fun firstDifference(a: BackupData, b: BackupData): String {
        fun <T> differs(name: String, x: List<T>, y: List<T>): String? =
            if (x == y) null else "$name: ${x.zip(y).firstOrNull { (l, r) -> l != r } ?: "sizes ${x.size} vs ${y.size}"}"
        return listOfNotNull(
            differs("accounts", a.accounts, b.accounts), differs("categories", a.categories, b.categories),
            differs("transactions", a.transactions, b.transactions), differs("recurringRules", a.recurringRules, b.recurringRules),
            differs("scheduledItems", a.scheduledItems, b.scheduledItems), differs("termPeriods", a.termPeriods, b.termPeriods),
            differs("predictions", a.predictions, b.predictions), differs("sinkingFunds", a.sinkingFunds, b.sinkingFunds),
            differs("scenarios", a.scenarios, b.scenarios), differs("presets", a.presets, b.presets),
            differs("merchantRules", a.merchantRules, b.merchantRules), differs("reconciliations", a.reconciliations, b.reconciliations),
            differs("importBatches", a.importBatches, b.importBatches), differs("balanceSnapshots", a.balanceSnapshots, b.balanceSnapshots),
            differs("categoryWeekSnapshots", a.categoryWeekSnapshots, b.categoryWeekSnapshots),
            if (a.settings != b.settings) "settings: ${a.settings} vs ${b.settings}" else null,
        ).joinToString("; ").ifEmpty { "no difference found" }
    }
}

/** Random [BackupData] with every reference valid (so the codec accepts it), awkward strings and extreme numbers. */
internal object BackupGenerator {
    private val stringPieces = listOf(
        "a", "Z", "0", " ", "  ", "\"", "\\", "/", "\n", "\r", "\t", "\u0000", "\u001f", "\u007f", "é", "日本語", "😀", "👨‍👩", "</script>", "{", "}",
        "[", "]", ",", ":", "null", "true", "\\u0041", "%", "&", "'", "£", "﻿", " ", " ",
    )

    fun text(r: Random, maxPieces: Int = 12): String = buildString { repeat(r.nextInt(0, maxPieces + 1)) { append(stringPieces[r.nextInt(stringPieces.size)]) } }

    fun appSettings(r: Random): Map<String, String> = (0 until r.nextInt(0, 5)).associate { text(r, 6) to text(r, 8) }

    private fun long(r: Random): Long = when (r.nextInt(12)) {
        0 -> Long.MAX_VALUE
        1 -> Long.MIN_VALUE
        2 -> 0L
        3 -> -1L
        4 -> r.nextLong()
        else -> r.nextLong(-1_000_000, 1_000_000)
    }

    private fun int(r: Random): Int = when (r.nextInt(8)) {
        0 -> Int.MAX_VALUE
        1 -> Int.MIN_VALUE
        2 -> 0
        else -> r.nextInt(-1000, 1000)
    }

    private fun date(r: Random): LocalDate = when (r.nextInt(30)) {
        0 -> LocalDate.MIN
        1 -> LocalDate.MAX
        2 -> LocalDate.of(-r.nextInt(1, 9999), 1 + r.nextInt(12), 1 + r.nextInt(28))
        3 -> LocalDate.of(10_000 + r.nextInt(1000), 1 + r.nextInt(12), 1 + r.nextInt(28))
        4 -> LocalDate.of(2024, 2, 29)
        else -> LocalDate.ofEpochDay(r.nextLong(-800_000, 800_000))
    }

    private fun <T> orNull(r: Random, p: Double = 0.3, value: () -> T): T? = if (r.nextDouble() < p) null else value()

    fun data(r: Random, small: Boolean = false): BackupData {
        val max = if (small) 3 else 8
        val accounts = List(r.nextInt(0, 6)) { i ->
            Account(i + 1L + r.nextInt(3) * 10, text(r), AccountType.entries[r.nextInt(AccountType.entries.size)], Money(long(r)), r.nextBoolean(), r.nextBoolean(), int(r))
        }.distinctBy { it.id }
        val categories = List(r.nextInt(0, 8)) { i ->
            Category(
                id = i + 1L, name = text(r), flow = Flow.entries[r.nextInt(3)], forecastMode = ForecastMode.entries[r.nextInt(2)],
                colorArgb = orNull(r) { long(r) }, isSystem = r.nextBoolean(), systemKey = orNull(r) { text(r) },
                isMajorIncomeDefault = r.nextBoolean(), archived = r.nextBoolean(), sortOrder = int(r),
            )
        }
        val accountIds = accounts.map { it.id }
        val categoryIds = categories.map { it.id }
        fun accountId(): Long? = if (accountIds.isEmpty()) null else accountIds[r.nextInt(accountIds.size)]
        fun categoryId(): Long? = if (categoryIds.isEmpty()) null else categoryIds[r.nextInt(categoryIds.size)]
        fun optionalCategory(): Long? = if (r.nextInt(3) == 0) null else categoryId()

        val rules = if (accountIds.isEmpty()) emptyList() else List(r.nextInt(0, max)) { i ->
            RecurringRule(
                id = i + 1L, label = text(r), accountId = accountId()!!, categoryId = optionalCategory(), amount = Money(long(r)),
                frequency = Frequency.entries[r.nextInt(4)], interval = int(r), startDate = date(r), endDate = orNull(r) { date(r) },
                postingMode = PostingMode.entries[r.nextInt(2)], postedThrough = orNull(r) { date(r) },
                toAccountId = if (r.nextInt(4) == 0) accountId() else null, isMajorIncome = r.nextBoolean(), active = r.nextBoolean(),
            )
        }
        val items = if (accountIds.isEmpty()) emptyList() else List(r.nextInt(0, max)) { i ->
            ScheduledItem(
                id = i + 1L, label = text(r), accountId = accountId()!!, categoryId = optionalCategory(), amount = Money(long(r)), date = date(r),
                postingMode = PostingMode.entries[r.nextInt(2)], toAccountId = if (r.nextInt(4) == 0) accountId() else null,
                isMajorIncome = r.nextBoolean(), posted = r.nextBoolean(), skipped = r.nextBoolean(),
            )
        }
        val batches = if (accountIds.isEmpty()) emptyList() else List(r.nextInt(0, 3)) { i ->
            ImportBatch(i + 1L, accountId()!!, long(r), text(r), int(r), orNull(r) { date(r) }, orNull(r) { date(r) })
        }
        val transactions = if (accountIds.isEmpty()) emptyList() else List(r.nextInt(0, max * 3)) { i ->
            Transaction(
                id = i + 1L, accountId = accountId()!!, amount = Money(long(r)), date = date(r), createdAtEpochMillis = long(r),
                categoryId = optionalCategory(), label = text(r), note = text(r), kind = TxKind.entries[r.nextInt(4)],
                transferGroupId = orNull(r) { text(r) }, recurringRuleId = if (rules.isNotEmpty() && r.nextInt(4) == 0) rules[r.nextInt(rules.size)].id else null,
                scheduledItemId = if (items.isNotEmpty() && r.nextInt(4) == 0) items[r.nextInt(items.size)].id else null,
                importBatchId = if (batches.isNotEmpty() && r.nextInt(4) == 0) batches[r.nextInt(batches.size)].id else null,
                importKey = orNull(r) { text(r) },
            )
        }
        val terms = List(r.nextInt(0, 4)) { i -> TermPeriod(i + 1L, text(r), date(r), date(r)) }
        val predictions = if (categoryIds.isEmpty()) emptyList() else List(r.nextInt(0, max)) { i ->
            Prediction(i + 1L, categoryId()!!, Regime.entries[r.nextInt(2)], PredictionMode.entries[r.nextInt(3)], Money(long(r)), int(r))
        }
        val funds = List(r.nextInt(0, 3)) { i ->
            SinkingFund(i + 1L, text(r), Money(long(r)), date(r), date(r), optionalCategory(), SinkingFundStatus.entries[r.nextInt(3)])
        }
        val scenarios = List(r.nextInt(0, 3)) { s ->
            Scenario(
                id = s + 1L, name = text(r),
                items = List(r.nextInt(0, 4)) { i ->
                    ScenarioItem(
                        id = s * 10L + i + 1, scenarioId = long(r), type = ScenarioItemType.entries[r.nextInt(3)], label = text(r),
                        accountId = if (r.nextInt(3) == 0) accountId() else null, categoryId = optionalCategory(), amount = Money(long(r)),
                        date = orNull(r) { date(r) }, frequency = orNull(r) { Frequency.entries[r.nextInt(4)] }, interval = int(r),
                        endDate = orNull(r) { date(r) }, percent = int(r), regime = orNull(r) { Regime.entries[r.nextInt(2)] },
                    )
                },
            )
        }
        val presets = if (categoryIds.isEmpty()) emptyList() else List(r.nextInt(0, 3)) { i ->
            Preset(i + 1L, text(r), Money(long(r)), Flow.entries[r.nextInt(3)], categoryId()!!, if (r.nextBoolean()) accountId() else null, r.nextBoolean(), int(r))
        }
        val merchantRules = if (categoryIds.isEmpty()) emptyList() else List(r.nextInt(0, 3)) { i ->
            MerchantRule(i + 1L, text(r), categoryId()!!, orNull(r) { text(r) })
        }
        val reconciliations = if (accountIds.isEmpty()) emptyList() else List(r.nextInt(0, 3)) { i ->
            Reconciliation(
                i + 1L, accountId()!!, date(r), Money(long(r)), Money(long(r)),
                if (transactions.isNotEmpty() && r.nextBoolean()) transactions[r.nextInt(transactions.size)].id else null, date(r),
            )
        }
        val balanceSnapshots = List(r.nextInt(0, 3)) { i ->
            BalanceSnapshot(i + 1L, date(r), date(r), int(r), orNull(r) { Money(long(r)) }, Money(long(r)), orNull(r) { Money(long(r)) })
        }
        val weekSnapshots = if (categoryIds.isEmpty()) emptyList() else List(r.nextInt(0, 3)) { i ->
            CategoryWeekSnapshot(i + 1L, date(r), date(r), categoryId()!!, int(r), Money(long(r)))
        }
        val settings = BudgetSettings(
            zeroPoint = ZeroPoint.entries[r.nextInt(2)], buffer = Money(long(r)), defaultAccountId = if (r.nextBoolean()) accountId() else null,
            historyStartDate = orNull(r) { date(r) }, simulations = int(r), forecastHorizonDays = int(r),
        )
        return BackupData(
            accounts, categories, transactions, rules, items, terms, predictions, funds, scenarios, presets, merchantRules,
            reconciliations, batches, balanceSnapshots, weekSnapshots, settings,
        )
    }
}

/** One way of damaging a backup, with what it should lead to. */
internal class Mutation(val name: String, val mustFail: Boolean, private val text: String, private val description: String) {
    fun apply(): String = text
    fun describe(): String = description
}

internal object Mutations {
    private val junk = listOf("\"", "{", "}", "[", "]", ",", ":", "x", "9", "-", "\\", "\u0000", "null", "\n", " ", "é", "😀", "\"a\":", "1e999", "99999999999999999999")
    private val dateRegex = Regex("""^-?\+?\d{4,9}-\d{2}-\d{2}$""")
    private val enumKeys = setOf("type", "flow", "forecastMode", "kind", "frequency", "postingMode", "regime", "mode", "status", "zeroPoint")
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun pick(r: Random, valid: String): Mutation {
        val root = Json.parseToJsonElement(valid)
        return when (r.nextInt(16)) {
            0 -> truncate(r, valid)
            1, 2 -> chars(r, valid, "delete") { sb -> sb.deleteCharAt(r.nextInt(sb.length)) }
            3, 4 -> chars(r, valid, "insert") { sb -> sb.insert(r.nextInt(sb.length + 1), junk[r.nextInt(junk.size)]) }
            5 -> chars(r, valid, "replace") { sb -> val at = r.nextInt(sb.length); sb.replace(at, at + 1, junk[r.nextInt(junk.size)]) }
            6 -> chars(r, valid, "swap") { sb ->
                val at = r.nextInt(sb.length - 1)
                val a = sb[at]
                sb.setCharAt(at, sb[at + 1])
                sb.setCharAt(at + 1, a)
            }
            7 -> chars(r, valid, "duplicateSlice") { sb ->
                val from = r.nextInt(sb.length)
                val to = minOf(sb.length, from + r.nextInt(1, 40))
                sb.insert(r.nextInt(sb.length + 1), sb.substring(from, to))
            }
            8 -> replacePrimitive(r, root)
            9 -> removeKey(r, root)
            10 -> addUnknownKey(r, root)
            11 -> invalidDate(r, root)
            12 -> invalidEnum(r, root)
            13 -> header(r, root)
            14 -> dangling(r, root)
            else -> shuffleArray(r, root)
        }
    }

    private fun truncate(r: Random, valid: String): Mutation {
        val lastBrace = valid.lastIndexOf('}')
        val cut = r.nextInt(0, lastBrace + 1)
        return Mutation("truncate", true, valid.substring(0, cut), "cut at $cut of ${valid.length}")
    }

    private fun chars(r: Random, valid: String, name: String, edit: (StringBuilder) -> Unit): Mutation {
        val sb = StringBuilder(valid)
        repeat(1 + r.nextInt(3)) { if (sb.length > 2) edit(sb) }
        return Mutation(name, false, sb.toString(), "$name x?; ${sb.length} chars")
    }

    // --- JSON-aware damage ----------------------------------------------------------------------------------

    private fun paths(element: JsonElement, prefix: List<Any> = emptyList(), out: MutableList<List<Any>> = mutableListOf()): MutableList<List<Any>> {
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> paths(v, prefix + k, out) }
            is JsonArray -> element.forEachIndexed { i, v -> paths(v, prefix + i, out) }
            else -> out += prefix
        }
        return out
    }

    private fun get(element: JsonElement, path: List<Any>): JsonElement =
        path.fold(element) { e, key -> if (key is String) (e as JsonObject).getValue(key) else (e as JsonArray)[key as Int] }

    private fun replaceAt(element: JsonElement, path: List<Any>, newValue: JsonElement?): JsonElement {
        if (path.isEmpty()) return newValue ?: JsonNull
        val key = path.first()
        val rest = path.drop(1)
        return when (element) {
            is JsonObject -> {
                val k = key as String
                val map = LinkedHashMap(element)
                if (rest.isEmpty() && newValue == null) map.remove(k) else map[k] = replaceAt(element.getValue(k), rest, newValue)
                JsonObject(map)
            }
            is JsonArray -> {
                val i = key as Int
                val list = element.toMutableList()
                if (rest.isEmpty() && newValue == null) list.removeAt(i) else list[i] = replaceAt(element[i], rest, newValue)
                JsonArray(list)
            }
            else -> element
        }
    }

    private fun mutation(name: String, mustFail: Boolean, root: JsonElement, description: String) =
        Mutation(name, mustFail, json.encodeToString(JsonElement.serializer(), root), description)

    private fun replacePrimitive(r: Random, root: JsonElement): Mutation {
        val all = paths(root)
        val path = all[r.nextInt(all.size)]
        val replacement: JsonElement = when (r.nextInt(9)) {
            0 -> JsonNull
            1 -> JsonPrimitive(0)
            2 -> JsonPrimitive(-1)
            3 -> JsonPrimitive("")
            4 -> JsonPrimitive("junk")
            5 -> JsonPrimitive(true)
            6 -> JsonArray(emptyList())
            7 -> JsonObject(emptyMap())
            else -> JsonPrimitive(Long.MAX_VALUE)
        }
        return mutation("replacePrimitive", false, replaceAt(root, path, replacement), "$path -> $replacement")
    }

    private fun removeKey(r: Random, root: JsonElement): Mutation {
        val all = paths(root)
        val path = all[r.nextInt(all.size)]
        return mutation("removeKey", false, replaceAt(root, path, null), "removed $path")
    }

    private fun addUnknownKey(r: Random, root: JsonElement): Mutation {
        // Unknown keys are ignored (ignoreUnknownKeys): the file stays valid and must decode to the same data.
        val obj = root as JsonObject
        val map = LinkedHashMap(obj)
        map["futureField${r.nextInt(100)}"] = JsonPrimitive(r.nextInt())
        return mutation("addUnknownKey", false, JsonObject(map), "added a top-level key")
    }

    private fun invalidDate(r: Random, root: JsonElement): Mutation {
        val dates = paths(root).filter { p -> (get(root, p) as? JsonPrimitive)?.let { it.isString && dateRegex.matches(it.content) } == true }
        if (dates.isEmpty()) return header(r, root)
        val path = dates[r.nextInt(dates.size)]
        val bad = listOf("2026-02-30", "2026-13-01", "not-a-date", "", "2026/09/28", "20260928", "2026-9-8", "0000-00-00", "2026-09-28T10:00", "٢٠٢٦-٠٩-٢٨")[r.nextInt(10)]
        return mutation("invalidDate", true, replaceAt(root, path, JsonPrimitive(bad)), "$path -> '$bad'")
    }

    private fun invalidEnum(r: Random, root: JsonElement): Mutation {
        val enums = paths(root).filter { p -> (p.lastOrNull() as? String) in enumKeys && (get(root, p) as? JsonPrimitive)?.isString == true }
        if (enums.isEmpty()) return header(r, root)
        val path = enums[r.nextInt(enums.size)]
        val bad = listOf("NOPE", "", "current", "Normal", "OUT ")[r.nextInt(5)]
        return mutation("invalidEnum", true, replaceAt(root, path, JsonPrimitive(bad)), "$path -> '$bad'")
    }

    private fun header(r: Random, root: JsonElement): Mutation = when (r.nextInt(4)) {
        0 -> mutation("newerVersion", true, replaceAt(root, listOf("version"), JsonPrimitive(BackupV1.CURRENT_VERSION + 1 + r.nextInt(50))), "version raised")
        1 -> mutation("zeroVersion", true, replaceAt(root, listOf("version"), JsonPrimitive(-r.nextInt(5))), "version lowered")
        2 -> mutation("wrongFormat", true, replaceAt(root, listOf("format"), JsonPrimitive("other")), "format changed")
        else -> mutation("missingSettings", true, replaceAt(root, listOf("settings"), null), "settings removed")
    }

    /** A record that points at an id nothing has. */
    private fun dangling(r: Random, root: JsonElement): Mutation {
        val obj = root as JsonObject
        val transactions = (obj["transactions"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val extra = JsonObject(
            mapOf(
                "id" to JsonPrimitive(9_999_999), "accountId" to JsonPrimitive(8_888_888), "amountPence" to JsonPrimitive(1), "date" to JsonPrimitive("2026-01-01"),
                "createdAtEpochMillis" to JsonPrimitive(0), "categoryId" to JsonNull, "label" to JsonPrimitive(""), "note" to JsonPrimitive(""),
                "kind" to JsonPrimitive("OPENING_BALANCE"), "transferGroupId" to JsonNull, "recurringRuleId" to JsonNull,
                "scheduledItemId" to JsonNull, "importBatchId" to JsonNull, "importKey" to JsonNull,
            ),
        )
        transactions.add(r.nextInt(transactions.size + 1), extra)
        return mutation("danglingReference", true, replaceAt(root, listOf("transactions"), JsonArray(transactions)), "a transaction on missing account 8888888")
    }

    private fun shuffleArray(r: Random, root: JsonElement): Mutation {
        // Reordering the records of a list (or duplicating one) leaves a self-consistent file.
        val arrays = allArrays(root).filter { it.second.size >= 2 }
        if (arrays.isEmpty()) return addUnknownKey(r, root)
        val (path, array) = arrays[r.nextInt(arrays.size)]
        val list = array.toMutableList()
        list.shuffle(r)
        return mutation("shuffleArray", false, replaceAt(root, path, JsonArray(list)), "shuffled $path")
    }

    private fun allArrays(element: JsonElement, prefix: List<Any> = emptyList(), out: MutableList<Pair<List<Any>, JsonArray>> = mutableListOf()): MutableList<Pair<List<Any>, JsonArray>> {
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> allArrays(v, prefix + k, out) }
            is JsonArray -> {
                out += prefix to element
                element.forEachIndexed { i, v -> allArrays(v, prefix + i, out) }
            }
            else -> Unit
        }
        return out
    }
}
