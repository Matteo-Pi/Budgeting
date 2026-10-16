package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.forecast.Balances
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** What the user can say about one [ImportQuestion]. Which of these a question accepts is its [ImportQuestion.choices]. */
sealed interface CheckAnswer {
    /** The same thing: keep the app's entry, but take the bank's date, and remember the bank's row as imported. */
    data object SameUseBank : CheckAnswer

    /** The same thing: keep the app's entry exactly as it is, and remember the bank's row as imported. */
    data object SameKeepMine : CheckAnswer

    /** Not the same thing: import the statement's row as well. */
    data object Different : CheckAnswer

    /** The same purchase: set the app's amount and date to the bank's. */
    data object FixMine : CheckAnswer

    data object Keep : CheckAnswer

    data object Delete : CheckAnswer

    data class MoveTo(val accountId: Long) : CheckAnswer

    /** Leave the statement row named by a problem out of the import. */
    data object SkipRow : CheckAnswer

    data object Ok : CheckAnswer

    /** Save a reconciliation for the statement's last day, so the app's balance there is the statement's. */
    data object RecordDifference : CheckAnswer

    data object Leave : CheckAnswer
}

/**
 * Something about an import the user should be asked about before anything is saved (docs/PLAN.md §5.16).
 * [id] is stable while the pairing it describes is, so an answer stays with its question when the questions
 * are worked out again after another answer changed.
 */
sealed interface ImportQuestion {
    val id: String

    /** The answers [ImportChecks.plan] accepts for this question. */
    val choices: List<CheckAnswer>
}

/**
 * Statement row [rowIndex] (a position in [ImportCheckInput.rows]) has the same amount as [existing], an
 * entry already in the app, a few days either side. Both sides are paired one-to-one, see [ImportChecks].
 */
data class PossibleDuplicate(val rowIndex: Int, val existing: Transaction) : ImportQuestion {
    override val id: String get() = "dup:$rowIndex:${existing.id}"
    override val choices: List<CheckAnswer>
        get() = listOf(CheckAnswer.SameUseBank, CheckAnswer.SameKeepMine, CheckAnswer.Different)
}

/** Statement row [rowIndex] looks like the same purchase as [existing] but the amounts differ. */
data class AmountMismatch(val rowIndex: Int, val existing: Transaction) : ImportQuestion {
    override val id: String get() = "amt:$rowIndex:${existing.id}"
    override val choices: List<CheckAnswer>
        get() = listOf(CheckAnswer.FixMine, CheckAnswer.Different)
}

/**
 * [existing] is dated inside the statement but no row of it matches. [transferPartner] is the other leg when
 * [existing] is one side of a move between accounts (deleting it deletes both). [mayNotHaveReachedBank] is true in
 * the statement's last three days, when the bank may simply not have processed it yet. [moveTargets] are the
 * accounts it could be moved to.
 */
data class NotOnStatement(
    val existing: Transaction,
    val transferPartner: Transaction?,
    val mayNotHaveReachedBank: Boolean,
    val moveTargets: List<Account>,
) : ImportQuestion {
    override val id: String get() = "not:${existing.id}"
    override val choices: List<CheckAnswer>
        get() = listOf(CheckAnswer.Keep, CheckAnswer.Delete) + moveTargets.map { CheckAnswer.MoveTo(it.id) }
}

/**
 * A problem the statement reader found ([text] is a sentence for the user). [rowIndex] is the row it names, if
 * any; [canSkipRow] is true when that row would otherwise be imported, so it can be left out.
 */
data class StatementProblem(
    val index: Int,
    val text: String,
    val rowIndex: Int?,
    val canSkipRow: Boolean,
) : ImportQuestion {
    override val id: String get() = "problem:$index"
    override val choices: List<CheckAnswer>
        get() = if (canSkipRow) listOf(CheckAnswer.SkipRow, CheckAnswer.Ok) else listOf(CheckAnswer.Ok)
}

/** [count] rows to import are dated before the account's starting balance ([openingDate]). Information only. */
data class BeforeOpening(val count: Int, val openingDate: LocalDate) : ImportQuestion {
    override val id: String get() = "before"
    override val choices: List<CheckAnswer> get() = listOf(CheckAnswer.Ok)
}

/**
 * With every other answer applied, the app's balance on [date] (the statement's last day) would be [appBalance]
 * while the statement says [statementBalance].
 */
data class BalanceGap(val date: LocalDate, val appBalance: Money, val statementBalance: Money) : ImportQuestion {
    override val id: String get() = "gap"
    override val choices: List<CheckAnswer> get() = listOf(CheckAnswer.RecordDifference, CheckAnswer.Leave)

    /** What recording would add to the account: positive when the statement holds more than the app. */
    val difference: Money get() = statementBalance - appBalance
}

/**
 * Everything [ImportChecks] looks at. [rows], [importKeys], [statuses] and [selected] are parallel lists; a
 * "row index" everywhere in this file is a position in them (not [ParsedRow.rowIndex]). [selected] says which
 * rows the user ticked in the review. [existing] is every transaction in the app, all accounts. The coverage
 * is the span of days the statement speaks for (a PDF's period; a CSV's first to last row), and
 * [closingBalance] what it says the account held at the end of it, when it says.
 */
data class ImportCheckInput(
    val accountId: Long,
    val rows: List<ParsedRow>,
    val importKeys: List<String>,
    val statuses: List<RowStatus>,
    val selected: List<Boolean>,
    val existing: List<Transaction>,
    val accounts: List<Account>,
    val coverageStart: LocalDate?,
    val coverageEnd: LocalDate?,
    val closingBalance: Money?,
    /** The statement reader's problems, as sentences. */
    val problems: List<String> = emptyList(),
    /** For the "same purchase" test: the category the importer would suggest for a row. */
    val categories: List<Category> = emptyList(),
    val merchantRules: List<MerchantRule> = emptyList(),
) {
    init {
        require(importKeys.size == rows.size && statuses.size == rows.size && selected.size == rows.size) {
            "rows, importKeys, statuses and selected must be the same length"
        }
    }
}

/** A change to a transaction already in the app. Null fields are left as they are. */
data class ExistingUpdate(
    val id: Long,
    val date: LocalDate? = null,
    val amount: Money? = null,
    val importKey: String? = null,
)

data class ExistingMove(val id: Long, val toAccountId: Long)

/** Save a reconciliation for [date]: the account's balance then is [statementBalance]. */
data class ImportReconcile(val date: LocalDate, val statementBalance: Money)

/** What the answers do to transactions that were already in the app, applied together with the import. */
data class ImportChanges(
    val updates: List<ExistingUpdate> = emptyList(),
    /** Ids to delete; both legs of a transfer are listed. */
    val deletions: List<Long> = emptyList(),
    val moves: List<ExistingMove> = emptyList(),
    val reconcile: ImportReconcile? = null,
) {
    val isEmpty: Boolean get() = updates.isEmpty() && deletions.isEmpty() && moves.isEmpty() && reconcile == null

    companion object {
        val NONE = ImportChanges()
    }
}

/**
 * The effect of a set of answers. [importRows] are the rows (positions in [ImportCheckInput.rows], ascending) to
 * bring in as new transactions. The counts are what the answers did, for the summary: [matched] rows tied to
 * the user's own entries, [corrected] amounts fixed, [deleted] entries removed (a transfer counts once),
 * [movedTo] entries moved per destination account, [skipped] rows left out because of a statement problem.
 */
data class ImportPlan(
    val importRows: List<Int>,
    val changes: ImportChanges,
    val matched: Int = 0,
    val corrected: Int = 0,
    val deleted: Int = 0,
    val movedTo: Map<Long, Int> = emptyMap(),
    val skipped: Int = 0,
)

/**
 * The questions an import asks before anything is saved (docs/PLAN.md §5.16), and what the answers do. Pure.
 *
 * Rows with status [RowStatus.ALREADY_IMPORTED] (or whose key is already in the app) never produce a question.
 *
 * 1. [PossibleDuplicate]: an entry on the same account with no import key, kind NORMAL or TRANSFER, the same
 *    amount, within three days of a row.
 * 2. [AmountMismatch]: of what is left, a NORMAL entry within two days, in the same direction, with a different
 *    amount, that looks like the same purchase: the merchant of its label shares a word of four or more letters
 *    with the row's, or its category is the one the importer would suggest for the row.
 * 3. [NotOnStatement]: an unmatched NORMAL entry or transfer leg dated inside the coverage.
 * 4. [StatementProblem], 5. [BeforeOpening], 6. [BalanceGap].
 *
 * Both pairings are one-to-one: closest date first, then the more words the label and the description share, so
 * with two identical rows and one entry, the row nearer in date gets it. They do not depend on which rows the
 * user ticked, and a row in a pair is imported unless its answer says it is the same thing: the answers decide.
 * An unticked row still takes its partner, so the partner is not reported as missing from the statement.
 */
object ImportChecks {
    /** How far apart (days) a row and an entry with the same amount may be. */
    const val DUPLICATE_DAYS = 3L

    /** How far apart (days) a row and an entry with a different amount may be. */
    const val MISMATCH_DAYS = 2L

    /** The last this many days of the coverage are the ones where an entry may not have reached the bank yet. */
    const val RECENT_DAYS = 3L

    private const val PREVIEW_BATCH = Long.MAX_VALUE

    /** Words that say nothing about which merchant it was. */
    private val GENERIC_WORDS = setOf(
        "STORE", "STORES", "SHOP", "SHOPS", "LIMITED", "PAYMENT", "PAYMENTS", "TRANSFER", "CARD", "ONLINE",
    )

    private val WORD = Regex("[A-Z0-9]+")
    private val LETTER_WORD = Regex("[A-Z]{4,}")
    private val PROBLEM_ROW = Regex("""^Row (\d+) \(""")

    // ---- the questions ------------------------------------------------------------------------------------

    /**
     * Every question, in the order listed above. The [BalanceGap], if there is one, is last and is worked out from
     * a preview that applies [answers]; a question with no answer yet counts as its [defaultAnswer] there, so the
     * gap is always the one the user would get by accepting the likeliest answers.
     */
    fun questions(input: ImportCheckInput, answers: Map<String, CheckAnswer> = emptyMap()): List<ImportQuestion> {
        val ctx = Context(input)
        val result = ArrayList<ImportQuestion>()
        val pairing = ctx.pairing

        pairing.duplicates.entries.sortedBy { it.key }.forEach { (row, tx) -> result += PossibleDuplicate(row, tx) }
        pairing.mismatches.entries.sortedBy { it.key }.forEach { (row, tx) -> result += AmountMismatch(row, tx) }
        result += notOnStatement(ctx)
        result += statementProblems(ctx, pairing.rows)
        beforeOpening(ctx, pairing.rows)?.let { result += it }

        val closing = input.closingBalance
        val end = input.coverageEnd
        if (closing != null && end != null) {
            val preview = buildPlan(input, result, answers, withDefaults = true)
            val app = appBalance(input, preview, end)
            if (app != closing) result += BalanceGap(end, app, closing)
        }
        return result
    }

    /** The rows that would be in a [PossibleDuplicate] or [AmountMismatch], whatever the user ticked. */
    fun flaggedRows(input: ImportCheckInput): Set<Int> = Context(input).pairing.rows

    /** The answer a question is previewed with until the user gives one: what is most often right. */
    fun defaultAnswer(question: ImportQuestion): CheckAnswer = when (question) {
        is PossibleDuplicate -> CheckAnswer.SameKeepMine
        is AmountMismatch -> CheckAnswer.FixMine
        is NotOnStatement -> CheckAnswer.Keep
        is StatementProblem -> CheckAnswer.Ok
        is BeforeOpening -> CheckAnswer.Ok
        is BalanceGap -> CheckAnswer.Leave
    }

    /** Answers that need no thought from the user: the information-only questions are simply acknowledged. */
    fun preAnswers(questions: List<ImportQuestion>): Map<String, CheckAnswer> =
        questions.filterIsInstance<BeforeOpening>().associate { it.id to CheckAnswer.Ok }

    /** True when every question has one of its [ImportQuestion.choices] as its answer. */
    fun isComplete(questions: List<ImportQuestion>, answers: Map<String, CheckAnswer>): Boolean =
        questions.all { q -> answers[q.id]?.let { it in q.choices } == true }

    // ---- what the answers do ------------------------------------------------------------------------------

    /**
     * What [answers] to [questions] (from [questions]) do: which rows to import and the changes to entries already
     * in the app. A question without an answer (or with one it does not accept) changes nothing: its row is imported.
     */
    fun plan(input: ImportCheckInput, questions: List<ImportQuestion>, answers: Map<String, CheckAnswer>): ImportPlan =
        buildPlan(input, questions, answers, withDefaults = false)

    /**
     * The balance of the account on [asOf] once [plan] has been applied: the rows that will be imported, the
     * entries updated, deleted or moved, and the opening-balance move the import makes for rows dated before the
     * account's starting balance (the same arithmetic as the app's `rebuildOpeningBalanceMove`).
     */
    fun appBalance(input: ImportCheckInput, plan: ImportPlan, asOf: LocalDate): Money {
        val ledger = LinkedHashMap<Long, Transaction>()
        input.existing.forEach { ledger[it.id] = it }
        for (update in plan.changes.updates) {
            val tx = ledger[update.id] ?: continue
            ledger[update.id] = tx.copy(
                date = update.date ?: tx.date,
                amount = update.amount ?: tx.amount,
                importKey = update.importKey ?: tx.importKey,
            )
        }
        plan.changes.deletions.forEach { ledger.remove(it) }
        for (move in plan.changes.moves) {
            val tx = ledger[move.id] ?: continue
            ledger[move.id] = tx.copy(accountId = move.toAccountId)
        }

        val onAccount = ledger.values.filter { it.accountId == input.accountId }.toMutableList()
        plan.importRows.forEachIndexed { n, i ->
            val row = input.rows[i]
            onAccount += Transaction(
                id = -(n + 1L),
                accountId = input.accountId,
                amount = row.amount,
                date = row.date,
                categoryId = null,
                label = row.description,
                kind = TxKind.NORMAL,
                importBatchId = PREVIEW_BATCH,
                importKey = input.importKeys[i],
            )
        }
        return Balances.accountBalance(input.accountId, withOpeningMoves(input.accountId, onAccount), asOf)
    }

    // ---- statement facts ----------------------------------------------------------------------------------

    /** First to last day of [rows], or null for none: the coverage of a CSV statement. */
    fun coverageOf(rows: List<ParsedRow>): Pair<LocalDate, LocalDate>? {
        if (rows.isEmpty()) return null
        return rows.minOf { it.date } to rows.maxOf { it.date }
    }

    /**
     * The balance after the last row of a CSV statement, from its balance column; null when the column is not
     * mapped or that row has none. Files list rows oldest first or newest first: which end is the last is told
     * by the first and last dates, and of several rows on the latest day the one at that end is taken.
     */
    fun closingBalanceOf(rows: List<ParsedRow>): Money? {
        if (rows.isEmpty()) return null
        val latest = rows.maxOf { it.date }
        val onLatest = rows.filter { it.date == latest }
        val newestFirst = rows.first().date.isAfter(rows.last().date)
        val closing = if (newestFirst) onLatest.first() else onLatest.last()
        return closing.balance
    }

    /** The statement row a reader problem names ("Row 7 (7 Oct, UNION SHOP): ..." is row index 6), if it names one. */
    fun problemRow(text: String): Int? =
        PROBLEM_ROW.find(text)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

    // ---- the pairing --------------------------------------------------------------------------------------

    private class Pairing(val duplicates: Map<Int, Transaction>, val mismatches: Map<Int, Transaction>) {
        val rows: Set<Int> = duplicates.keys + mismatches.keys
        val transactionIds: Set<Long> = (duplicates.values + mismatches.values).mapTo(HashSet()) { it.id }
    }

    private class Edge(val row: Int, val tx: Transaction, val days: Long, val similarity: Int, val amountGap: Long)

    private class Context(val input: ImportCheckInput) {
        private val existingKeys: Set<String> = input.existing.mapNotNullTo(HashSet()) { it.importKey }

        val alreadyImported: List<Boolean> = input.rows.indices.map { i ->
            input.statuses[i] == RowStatus.ALREADY_IMPORTED || input.importKeys[i] in existingKeys
        }

        /** Rows that can be asked about. */
        val live: List<Int> = input.rows.indices.filter { !alreadyImported[it] }

        /** Entries a row could be the bank's version of. */
        val candidates: List<Transaction> = input.existing
            .filter {
                it.accountId == input.accountId && it.importKey == null &&
                    (it.kind == TxKind.NORMAL || it.kind == TxKind.TRANSFER)
            }
            .sortedWith(compareBy<Transaction>({ it.date }, { it.id }))

        private val rowWords = HashMap<Int, Set<String>>()
        private val txWords = HashMap<Long, Set<String>>()
        private val suggested = HashMap<Int, Long?>()

        fun wordsOfRow(row: Int): Set<String> = rowWords.getOrPut(row) { wordsOf(input.rows[row].description) }

        fun wordsOfTx(tx: Transaction): Set<String> = txWords.getOrPut(tx.id) { wordsOf(tx.label) }

        fun suggestedCategory(row: Int): Long? = suggested.getOrPut(row) {
            CategorySuggester.suggest(
                MerchantKey.normalise(input.rows[row].description),
                input.merchantRules,
                input.categories,
            )
        }

        val pairing: Pairing by lazy { pair() }

        private fun pair(): Pairing {
            val rowsTaken = HashSet<Int>()
            val txTaken = HashSet<Long>()

            val duplicateEdges = ArrayList<Edge>()
            for (i in live) {
                val row = input.rows[i]
                for (tx in candidates) {
                    if (tx.amount != row.amount) continue
                    val days = daysBetween(tx.date, row.date)
                    if (days > DUPLICATE_DAYS) continue
                    duplicateEdges += Edge(i, tx, days, shared(i, tx), 0)
                }
            }
            val duplicates = takeClosest(duplicateEdges, rowsTaken, txTaken)

            val mismatchEdges = ArrayList<Edge>()
            for (i in live) {
                if (i in rowsTaken) continue
                val row = input.rows[i]
                for (tx in candidates) {
                    if (tx.id in txTaken || tx.kind != TxKind.NORMAL) continue
                    if (tx.amount == row.amount || tx.amount.isZero || row.amount.isZero) continue
                    if (tx.amount.isPositive != row.amount.isPositive) continue
                    val days = daysBetween(tx.date, row.date)
                    if (days > MISMATCH_DAYS) continue
                    if (!looksLikeTheSamePurchase(i, tx)) continue
                    mismatchEdges += Edge(i, tx, days, shared(i, tx), abs(tx.amount.pence - row.amount.pence))
                }
            }
            val mismatches = takeClosest(mismatchEdges, rowsTaken, txTaken)
            return Pairing(duplicates, mismatches)
        }

        /** Closest date first, then the most shared words, then the nearest amount, then file order. */
        private fun takeClosest(edges: List<Edge>, rowsTaken: MutableSet<Int>, txTaken: MutableSet<Long>): Map<Int, Transaction> {
            val taken = LinkedHashMap<Int, Transaction>()
            val ordered = edges.sortedWith(
                compareBy<Edge>({ it.days }, { -it.similarity }, { it.amountGap }, { it.row }, { it.tx.date }, { it.tx.id }),
            )
            for (edge in ordered) {
                if (edge.row in rowsTaken || edge.tx.id in txTaken) continue
                rowsTaken += edge.row
                txTaken += edge.tx.id
                taken[edge.row] = edge.tx
            }
            return taken
        }

        private fun shared(row: Int, tx: Transaction): Int = wordsOfRow(row).count { it in wordsOfTx(tx) }

        private fun looksLikeTheSamePurchase(row: Int, tx: Transaction): Boolean {
            val rowMerchantWords = wordsOfRow(row).filter { isMerchantWord(it) }
            if (wordsOfTx(tx).any { it in rowMerchantWords }) return true
            val category = tx.categoryId ?: return false
            return category == suggestedCategory(row)
        }
    }

    private fun wordsOf(text: String): Set<String> {
        val key = MerchantKey.normalise(text).replace("'", "").replace("’", "")
        return WORD.findAll(key).mapTo(LinkedHashSet()) { it.value }
    }

    /** A word of four or more letters that names a merchant rather than a kind of shop. */
    private fun isMerchantWord(word: String): Boolean =
        LETTER_WORD.matches(word) && word !in GENERIC_WORDS

    private fun daysBetween(a: LocalDate, b: LocalDate): Long = abs(ChronoUnit.DAYS.between(a, b))

    // ---- building the questions ---------------------------------------------------------------------------

    private fun notOnStatement(ctx: Context): List<NotOnStatement> {
        val input = ctx.input
        val start = input.coverageStart ?: return emptyList()
        val end = input.coverageEnd ?: return emptyList()
        val matched = ctx.pairing.transactionIds
        return ctx.candidates
            .filter { it.id !in matched && !it.date.isBefore(start) && !it.date.isAfter(end) }
            .map { tx ->
                val partner = tx.transferGroupId?.takeIf { tx.kind == TxKind.TRANSFER }?.let { group ->
                    input.existing.firstOrNull { it.transferGroupId == group && it.id != tx.id }
                }
                NotOnStatement(
                    existing = tx,
                    transferPartner = partner,
                    mayNotHaveReachedBank = tx.date.isAfter(end.minusDays(RECENT_DAYS)),
                    moveTargets = input.accounts.filter {
                        !it.archived && it.id != input.accountId && it.id != partner?.accountId
                    },
                )
            }
    }

    private fun statementProblems(ctx: Context, flagged: Set<Int>): List<StatementProblem> {
        val input = ctx.input
        val result = ArrayList<StatementProblem>()
        input.problems.forEachIndexed { index, text ->
            val row = problemRow(text)?.takeIf { it in input.rows.indices }
            if (row != null && ctx.alreadyImported[row]) return@forEachIndexed
            val canSkip = row != null && (input.selected[row] || row in flagged)
            result += StatementProblem(index, text, row, canSkip)
        }
        return result
    }

    private fun beforeOpening(ctx: Context, flagged: Set<Int>): BeforeOpening? {
        val input = ctx.input
        val opening = originalOpening(input.accountId, input.existing) ?: return null
        val count = ctx.live.count { (input.selected[it] || it in flagged) && input.rows[it].date.isBefore(opening.date) }
        return if (count > 0) BeforeOpening(count, opening.date) else null
    }

    // ---- applying answers ---------------------------------------------------------------------------------

    private fun buildPlan(
        input: ImportCheckInput,
        questions: List<ImportQuestion>,
        answers: Map<String, CheckAnswer>,
        withDefaults: Boolean,
    ): ImportPlan {
        fun answerOf(question: ImportQuestion): CheckAnswer? =
            answers[question.id]?.takeIf { it in question.choices } ?: if (withDefaults) defaultAnswer(question) else null

        val updates = ArrayList<ExistingUpdate>()
        val deletions = LinkedHashSet<Long>()
        val moves = ArrayList<ExistingMove>()
        val movedTo = LinkedHashMap<Long, Int>()
        val excluded = HashSet<Int>()
        val flagged = HashSet<Int>()
        var matched = 0
        var corrected = 0
        var deleted = 0
        var skipped = 0
        var reconcile: ImportReconcile? = null

        fun partnersOf(tx: Transaction): List<Transaction> {
            val group = tx.transferGroupId ?: return emptyList()
            return input.existing.filter { it.transferGroupId == group && it.id != tx.id }
        }

        for (question in questions) {
            val answer = answerOf(question)
            when (question) {
                is PossibleDuplicate -> {
                    flagged += question.rowIndex
                    val row = input.rows[question.rowIndex]
                    val key = input.importKeys[question.rowIndex]
                    when (answer) {
                        CheckAnswer.SameUseBank -> {
                            excluded += question.rowIndex
                            matched++
                            updates += ExistingUpdate(question.existing.id, date = row.date, importKey = key)
                            partnersOf(question.existing).forEach { updates += ExistingUpdate(it.id, date = row.date) }
                        }
                        CheckAnswer.SameKeepMine -> {
                            excluded += question.rowIndex
                            matched++
                            updates += ExistingUpdate(question.existing.id, importKey = key)
                        }
                        else -> Unit
                    }
                }
                is AmountMismatch -> {
                    flagged += question.rowIndex
                    if (answer == CheckAnswer.FixMine) {
                        val row = input.rows[question.rowIndex]
                        excluded += question.rowIndex
                        corrected++
                        updates += ExistingUpdate(
                            question.existing.id,
                            date = row.date,
                            amount = row.amount,
                            importKey = input.importKeys[question.rowIndex],
                        )
                    }
                }
                is NotOnStatement -> when (answer) {
                    CheckAnswer.Delete -> {
                        deleted++
                        deletions += question.existing.id
                        partnersOf(question.existing).forEach { deletions += it.id }
                    }
                    is CheckAnswer.MoveTo -> {
                        moves += ExistingMove(question.existing.id, answer.accountId)
                        movedTo[answer.accountId] = (movedTo[answer.accountId] ?: 0) + 1
                    }
                    else -> Unit
                }
                is StatementProblem -> if (answer == CheckAnswer.SkipRow && question.rowIndex != null) {
                    excluded += question.rowIndex
                    skipped++
                }
                is BalanceGap -> if (answer == CheckAnswer.RecordDifference) {
                    reconcile = ImportReconcile(question.date, question.statementBalance)
                }
                is BeforeOpening -> Unit
            }
        }

        val alreadyImported = Context(input).alreadyImported
        val importRows = input.rows.indices.filter { i ->
            !alreadyImported[i] && (input.selected[i] || i in flagged) && i !in excluded
        }
        return ImportPlan(
            importRows = importRows,
            changes = ImportChanges(updates, deletions.toList(), moves, reconcile),
            matched = matched,
            corrected = corrected,
            deleted = deleted,
            movedTo = movedTo,
            skipped = skipped,
        )
    }

    // ---- the opening balance ------------------------------------------------------------------------------

    /** The account's own opening balance, not the entries an import adds to move it. */
    private fun originalOpening(accountId: Long, ledger: List<Transaction>): Transaction? =
        ledger
            .filter { it.accountId == accountId && it.kind == TxKind.OPENING_BALANCE && it.importBatchId == null }
            .minWithOrNull(compareBy<Transaction>({ it.date }, { it.id }))

    /**
     * [onAccount] with the opening-balance move an import makes: re-derived from the original opening balance and
     * every imported row on the account, replacing the move entries it has, like the app does when it saves.
     */
    private fun withOpeningMoves(accountId: Long, onAccount: List<Transaction>): List<Transaction> {
        val original = originalOpening(accountId, onAccount)
        val withoutMoves = onAccount.filter { !(it.kind == TxKind.OPENING_BALANCE && it.importBatchId != null) }
        val imported = onAccount.filter { (it.kind == TxKind.NORMAL || it.kind == TxKind.TRANSFER) && it.importBatchId != null }
        val adjusted = original?.let { opening ->
            OpeningBalanceAdjuster.adjust(opening, imported.map { ParsedRow(it.date, it.amount, it.label, null, 0) })
        }
        if (original == null || adjusted == null) return withoutMoves
        return withoutMoves + listOf(
            original.copy(
                id = Long.MIN_VALUE,
                amount = adjusted.amount,
                date = adjusted.date,
                importBatchId = PREVIEW_BATCH,
            ),
            original.copy(
                id = Long.MIN_VALUE + 1,
                amount = -original.amount,
                date = original.date,
                importBatchId = PREVIEW_BATCH,
            ),
        )
    }
}
