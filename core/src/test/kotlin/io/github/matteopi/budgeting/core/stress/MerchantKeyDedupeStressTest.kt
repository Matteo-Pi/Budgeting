package io.github.matteopi.budgeting.core.stress

import io.github.matteopi.budgeting.core.importer.ImportDedupe
import io.github.matteopi.budgeting.core.importer.MerchantKey
import io.github.matteopi.budgeting.core.importer.ParsedRow
import io.github.matteopi.budgeting.core.importer.RowStatus
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Stress tests of merchant-key normalisation and the statement-import dedupe keys. Replay one
 * case with `STRESS_SEED=<seed> STRESS_COUNT=1`.
 */
class MerchantKeyDedupeStressTest {
    private val seedBase = env("STRESS_SEED", 1L)

    private fun scaled(default: Int): Int {
        val requested = System.getenv("STRESS_COUNT")?.toIntOrNull() ?: return default
        return if (requested == 1) 1 else maxOf(default, requested)
    }

    // --- MerchantKey -----------------------------------------------------------------------------------------

    @Test
    fun normaliseNeverThrowsIsIdempotentAndKeepsItsShape() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(20_000)) {
            val seed = seedBase + i
            val text = Descriptions.random(Random(seed))
            try {
                val once = MerchantKey.normalise(text)
                val twice = MerchantKey.normalise(once)
                if (twice != once) failures += CaseFailure(seed, "merchantKey.idempotent", "normalise('${text.take(160).escaped()}') = '${once.escaped()}' but normalising that again gives '${twice.escaped()}'")
                if (once.length > 40) failures += CaseFailure(seed, "merchantKey.length", "${once.length} chars: '${once.escaped()}'")
                if (once != once.trim()) failures += CaseFailure(seed, "merchantKey.trimmed", "'${once.escaped()}' has surrounding whitespace")
                if ("  " in once) failures += CaseFailure(seed, "merchantKey.spaces", "'${once.escaped()}' has a double space")
                if (once != once.uppercase(Locale.UK)) failures += CaseFailure(seed, "merchantKey.uppercase", "'${once.escaped()}' is not upper case")
                if (MerchantKey.normalise(text) != once) failures += CaseFailure(seed, "merchantKey.deterministic", "two calls differ")
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "merchantKey.exception", "${t::class.simpleName}: ${t.message} on '${text.take(120).escaped()}'")
            }
        }
        report("MerchantKey.normalise", failures) { seed -> Descriptions.random(Random(seed)).take(400).escaped() }
    }

    @Test
    fun normaliseIgnoresCaseForPlainAscii() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(5_000)) {
            val seed = seedBase + i
            val text = Descriptions.random(Random(seed), asciiOnly = true)
            try {
                val expected = MerchantKey.normalise(text)
                if (MerchantKey.normalise(text.lowercase()) != expected) failures += CaseFailure(seed, "merchantKey.lower", "lower-cased input gives a different key for '${text.take(120).escaped()}'")
                if (MerchantKey.normalise(text.uppercase()) != expected) failures += CaseFailure(seed, "merchantKey.upper", "upper-cased input gives a different key for '${text.take(120).escaped()}'")
                if (MerchantKey.normalise("  $text\t ") != expected) failures += CaseFailure(seed, "merchantKey.padding", "padding changes the key for '${text.take(120).escaped()}'")
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "merchantKey.exception", "${t::class.simpleName}: ${t.message}")
            }
        }
        report("MerchantKey case", failures) { seed -> Descriptions.random(Random(seed), asciiOnly = true).take(400).escaped() }
    }

    // --- ImportDedupe --------------------------------------------------------------------------------------------

    @Test
    fun importKeysAreUniqueStableAndPrefixStable() {
        val failures = mutableListOf<CaseFailure>()
        for (i in 0 until scaled(4_000)) {
            val seed = seedBase + i
            val random = Random(seed)
            try {
                val rows = rows(random)
                val accountId = random.nextLong(1, 20)
                val keys = ImportDedupe.importKeys(accountId, rows)
                if (keys.size != rows.size) failures += CaseFailure(seed, "dedupe.size", "${keys.size} keys for ${rows.size} rows")
                if (keys.toSet().size != keys.size) failures += CaseFailure(seed, "dedupe.unique", "${keys.size - keys.toSet().size} duplicate key(s) within one file")
                if (keys.any { !Regex("[0-9a-f]{64}").matches(it) }) failures += CaseFailure(seed, "dedupe.format", "a key is not 64 hex characters")
                if (ImportDedupe.importKeys(accountId, rows) != keys) failures += CaseFailure(seed, "dedupe.stable", "two runs give different keys")
                // A later, longer export of the same account keeps the keys of the rows it repeats.
                val cut = random.nextInt(0, rows.size + 1)
                if (ImportDedupe.importKeys(accountId, rows.take(cut)) != keys.take(cut)) failures += CaseFailure(seed, "dedupe.prefixStable", "keys of the first $cut rows change when the file grows")
                // Only the tuple counts: row index and balance are not part of a key.
                val relabelled = rows.mapIndexed { idx, row -> row.copy(rowIndex = idx + 100, balance = Money(idx.toLong())) }
                if (ImportDedupe.importKeys(accountId, relabelled) != keys) failures += CaseFailure(seed, "dedupe.ignoresRowIndexAndBalance", "row index or balance changed a key")
                if (rows.isNotEmpty()) {
                    val other = ImportDedupe.importKeys(accountId + 1, rows)
                    if (other.toSet().intersect(keys.toSet()).isNotEmpty()) failures += CaseFailure(seed, "dedupe.accountScoped", "a key repeats across two accounts")
                }
                checkClassify(seed, random, accountId, rows, keys, failures)
            } catch (t: Throwable) {
                failures += CaseFailure(seed, "dedupe.exception", "${t::class.simpleName}: ${t.message}")
            }
        }
        report("ImportDedupe", failures) { seed -> rows(Random(seed)).take(8).joinToString("\n") { "${it.date} ${it.amount} '${it.description.escaped()}'" } }
    }

    private fun checkClassify(seed: Long, random: Random, accountId: Long, rows: List<ParsedRow>, keys: List<String>, failures: MutableList<CaseFailure>) {
        var nextId = 1L
        val existing = buildList {
            for ((idx, row) in rows.withIndex()) {
                when (random.nextInt(6)) {
                    0 -> add(existingTx(nextId++, accountId, row.amount, row.date.plusDays(random.nextLong(-3, 4)), null, TxKind.NORMAL))
                    1 -> add(existingTx(nextId++, accountId, row.amount, row.date, keys[idx], TxKind.NORMAL))
                    2 -> add(existingTx(nextId++, accountId + 1, row.amount, row.date, null, TxKind.NORMAL))
                    3 -> add(existingTx(nextId++, accountId, row.amount, row.date, null, TxKind.TRANSFER))
                    else -> Unit
                }
            }
        }
        val statuses = ImportDedupe.classify(accountId, rows, keys, existing)
        if (statuses.size != rows.size) failures += CaseFailure(seed, "classify.size", "${statuses.size} statuses for ${rows.size} rows")
        if (statuses != ImportDedupe.classify(accountId, rows, keys, existing)) failures += CaseFailure(seed, "classify.deterministic", "two runs differ")
        val existingKeys = existing.mapNotNull { it.importKey }.toSet()
        for ((idx, status) in statuses.withIndex()) {
            val known = keys[idx] in existingKeys
            if (known != (status == RowStatus.ALREADY_IMPORTED)) {
                failures += CaseFailure(seed, "classify.alreadyImported", "row $idx: key known=$known but status $status")
                break
            }
        }
        val candidates = existing.count {
            it.accountId == accountId && (it.kind == TxKind.NORMAL || it.kind == TxKind.TRANSFER) && it.importKey == null
        }
        val possible = statuses.count { it == RowStatus.POSSIBLE_DUPLICATE }
        if (possible > candidates) failures += CaseFailure(seed, "classify.eachCandidateOnce", "$possible possible duplicates from only $candidates candidate transactions")
    }

    private fun existingTx(id: Long, accountId: Long, amount: Money, date: LocalDate, importKey: String?, kind: TxKind) =
        Transaction(id = id, accountId = accountId, amount = amount, date = date, categoryId = 1, kind = kind, importKey = importKey)

    /** Rows drawn from a tiny pool of dates, amounts and merchant spellings, so identical and near-identical rows abound. */
    private fun rows(r: Random): List<ParsedRow> {
        val base = LocalDate.of(2026, 1, 1).plusDays(r.nextLong(0, 300))
        val amounts = listOf(-350L, -1250L, 4000L, 0L, r.nextLong(-5000, 5000))
        val merchants = listOf("TESCO STORES 3012", "Tesco Stores 4477", "CARD PAYMENT TO TESCO STORES 3012 ON 15-09-2026", "GREGGS", "GREGGS PLC", "J SMITH REF RENT", "", "SPOTIFY")
        return List(r.nextInt(0, 40)) { idx ->
            ParsedRow(
                date = base.plusDays(r.nextLong(0, 3)),
                amount = Money(amounts[r.nextInt(amounts.size)]),
                description = merchants[r.nextInt(merchants.size)],
                balance = if (r.nextBoolean()) Money(r.nextLong(-1000, 1000)) else null,
                rowIndex = idx,
            )
        }
    }

    private fun report(title: String, failures: List<CaseFailure>, describe: (Long) -> String) {
        if (failures.isEmpty()) return
        fail(StressReport.render(title, failures, describe, "STRESS_SEED=<seed> STRESS_COUNT=1 ./gradlew -p core test --tests '*MerchantKeyDedupeStressTest*'"))
    }
}

/** Statement descriptions: realistic prefix/merchant/tail combinations, and plain noise. */
internal object Descriptions {
    private val prefixes = listOf(
        "CARD PAYMENT TO ", "CONTACTLESS PAYMENT TO ", "DIRECT DEBIT PAYMENT TO ", "STANDING ORDER TO ", "BILL PAYMENT VIA FASTER PAYMENT TO ",
        "FASTER PAYMENTS RECEIPT FROM ", "PURCHASE ", "purchase ", "Card Payment To ", "PURCHASE CARD PAYMENT TO ", "PURCHASE PURCHASE PURCHASE ", "CARD  PAYMENT TO ", "", "", "",
    )
    private val merchants = listOf(
        "TESCO STORES", "Tesco", "AMAZON.CO.UK", "AMZN MKTP UK", "UBER *EATS", "J SMITH", "M&S SIMPLY FOOD", "CO-OP", "H&M", "Cafe Nero",
        "SPOTIFY", "GREGGS PLC", "SLC STUDENT LOANS", "A VERY LONG MERCHANT NAME THAT GOES ON AND ON", "", " ",
    )
    private val unicodeMerchants = listOf("Café Nero", "ÉCLAIR BAKERY", "ßtore", "İSTANBUL KEBAB", "日本語 SHOP", "😀 EMOJI CAFE", "ǅ TITLE")
    private val tails = listOf(
        " ON 15-09-2026", ", ON 15/09/26", " ON **** 15-09-2026", " REF 12345", " REF: RENT", " REFERENCE", " 3012", " 12.50", " £3.20", " GBP", " ****1234",
        " XX1234", " xxxx", " 1234567890", " 123", " MANDATE NO 0012", ",", ",,", "   ", " 12345X", " -", " 10-2026",
    )
    private val noise = "abcXYZ 0123456789 ,.-/*£&()'\"\t"

    fun random(r: Random, asciiOnly: Boolean = false): String {
        if (r.nextInt(6) == 0) return buildString { repeat(r.nextInt(0, 80)) { append(noise[r.nextInt(noise.length)]) } }
        val sb = StringBuilder()
        sb.append(prefixes[r.nextInt(prefixes.size)])
        val pool = if (asciiOnly) merchants else merchants + unicodeMerchants
        sb.append(pool[r.nextInt(pool.size)])
        repeat(r.nextInt(0, 5)) { sb.append(tails[r.nextInt(tails.size)]) }
        if (r.nextInt(8) == 0) repeat(r.nextInt(1, 30)) { sb.append(" ").append(pool[r.nextInt(pool.size)]) }
        if (r.nextInt(4) == 0) repeat(r.nextInt(1, 4)) { sb.insert(r.nextInt(sb.length + 1), noise[r.nextInt(noise.length)]) }
        return sb.toString()
    }
}
