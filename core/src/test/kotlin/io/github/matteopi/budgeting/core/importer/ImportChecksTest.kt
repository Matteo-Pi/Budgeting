package io.github.matteopi.budgeting.core.importer

import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Category
import io.github.matteopi.budgeting.core.model.Flow
import io.github.matteopi.budgeting.core.model.ForecastMode
import io.github.matteopi.budgeting.core.model.MerchantRule
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImportChecksTest {

    private val account = 1L

    private fun d(day: Int, month: Int = 10) = LocalDate.of(2026, month, day)

    private fun row(day: Int, pence: Long, description: String, balance: Long? = null, month: Int = 10) =
        ParsedRow(d(day, month), Money(pence), description, balance?.let { Money(it) }, rowIndex = 0)

    private fun tx(
        id: Long,
        day: Int,
        pence: Long,
        label: String = "",
        accountId: Long = account,
        categoryId: Long? = null,
        kind: TxKind = TxKind.NORMAL,
        importKey: String? = null,
        group: String? = null,
        batch: Long? = null,
        month: Int = 10,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = Money(pence),
        date = d(day, month),
        categoryId = categoryId,
        label = label,
        kind = kind,
        transferGroupId = group,
        importBatchId = batch,
        importKey = importKey,
    )

    private fun acct(id: Long, name: String, archived: Boolean = false) =
        Account(id = id, name = name, type = AccountType.CURRENT, archived = archived)

    private val groceries = Category(id = 5, name = "Groceries", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)

    private fun input(
        rows: List<ParsedRow>,
        existing: List<Transaction> = emptyList(),
        statuses: List<RowStatus> = rows.map { RowStatus.NEW },
        selected: List<Boolean> = rows.map { true },
        accounts: List<Account> = listOf(acct(1, "Current"), acct(2, "Savings"), acct(3, "Cash")),
        coverage: Pair<LocalDate, LocalDate>? = null,
        closing: Money? = null,
        problems: List<String> = emptyList(),
        categories: List<Category> = emptyList(),
        rules: List<MerchantRule> = emptyList(),
    ) = ImportCheckInput(
        accountId = account,
        rows = rows,
        importKeys = ImportDedupe.importKeys(account, rows),
        statuses = statuses,
        selected = selected,
        existing = existing,
        accounts = accounts,
        coverageStart = coverage?.first,
        coverageEnd = coverage?.second,
        closingBalance = closing,
        problems = problems,
        categories = categories,
        merchantRules = rules,
    )

    private inline fun <reified T : ImportQuestion> List<ImportQuestion>.only(): List<T> = filterIsInstance<T>()

    private fun List<ImportQuestion>.dupes() = only<PossibleDuplicate>()
    private fun List<ImportQuestion>.mismatches() = only<AmountMismatch>()
    private fun List<ImportQuestion>.missing() = only<NotOnStatement>()

    // ---- 1. possible duplicates --------------------------------------------------------------------------------

    @Test
    fun `a row and an entry with the same amount within three days are a possible duplicate`() {
        val existing = tx(1, 12, -450, "Tesco")
        val questions = ImportChecks.questions(input(listOf(row(10, -450, "TESCO STORES")), listOf(existing)))

        assertEquals(listOf<ImportQuestion>(PossibleDuplicate(0, existing)), questions)
        assertEquals(
            listOf(CheckAnswer.SameUseBank, CheckAnswer.SameKeepMine, CheckAnswer.Different),
            questions.single().choices,
        )
    }

    @Test
    fun `four days apart is too far, and so is a different amount`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))

        assertEquals(PossibleDuplicate(0, tx(1, 13, -450)), ImportChecks.questions(input(rows, listOf(tx(1, 13, -450)))).single())
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 14, -450)))).dupes())
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 10, -451)))).dupes())
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 10, 450)))).dupes(), "money in is not money out")
    }

    @Test
    fun `only entries on the same account without an import key, of kind normal or transfer, can be duplicates`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))
        val ignored = listOf(
            tx(1, 10, -450, accountId = 2),
            tx(2, 10, -450, importKey = "already-imported"),
            tx(3, 10, -450, kind = TxKind.RECONCILIATION),
            tx(4, 10, -450, kind = TxKind.OPENING_BALANCE),
        )
        assertEquals(emptyList(), ImportChecks.questions(input(rows, ignored)).dupes())

        val leg = tx(5, 11, -450, kind = TxKind.TRANSFER, group = "g")
        assertEquals(listOf(PossibleDuplicate(0, leg)), ImportChecks.questions(input(rows, listOf(leg))).dupes())
    }

    @Test
    fun `each row takes at most one entry and each entry at most one row, the closer date first`() {
        // Two identical amounts on the 10th and 11th, one entry on the 11th: the 11th is the exact day.
        val rows = listOf(row(10, -450, "SHOP A"), row(11, -450, "SHOP B"))
        val entry = tx(1, 11, -450, "x")

        val dupes = ImportChecks.questions(input(rows, listOf(entry))).dupes()

        assertEquals(listOf(PossibleDuplicate(1, entry)), dupes)
    }

    @Test
    fun `of two entries the closer one is paired and the other is left over`() {
        val far = tx(1, 8, -450, "far")
        val near = tx(2, 11, -450, "near")
        val questions = ImportChecks.questions(
            input(listOf(row(10, -450, "SHOP")), listOf(far, near), coverage = d(1) to d(31)),
        )

        assertEquals(listOf(PossibleDuplicate(0, near)), questions.dupes())
        assertEquals(listOf(far), questions.missing().map { it.existing })
    }

    @Test
    fun `on equal dates the entry whose label shares more words with the row wins`() {
        val boots = tx(1, 9, -450, "Boots")
        val greggs = tx(2, 11, -450, "Greggs bakery")

        val dupes = ImportChecks.questions(input(listOf(row(10, -450, "GREGGS")), listOf(boots, greggs))).dupes()

        assertEquals(listOf(PossibleDuplicate(0, greggs)), dupes)
    }

    @Test
    fun `otherwise ties go to the earlier entry so the result never depends on list order`() {
        val first = tx(1, 9, -450, "a")
        val second = tx(2, 11, -450, "b")
        val rows = listOf(row(10, -450, "SHOP"))

        assertEquals(listOf(PossibleDuplicate(0, first)), ImportChecks.questions(input(rows, listOf(first, second))).dupes())
        assertEquals(listOf(PossibleDuplicate(0, first)), ImportChecks.questions(input(rows, listOf(second, first))).dupes())
    }

    @Test
    fun `the same row twice pairs with two entries, one each`() {
        val rows = listOf(row(10, -350, "GREGGS"), row(10, -350, "GREGGS"))
        val a = tx(1, 10, -350, "Greggs")
        val b = tx(2, 11, -350, "Greggs")

        val dupes = ImportChecks.questions(input(rows, listOf(a, b))).dupes()

        assertEquals(listOf(PossibleDuplicate(0, a), PossibleDuplicate(1, b)), dupes)
    }

    @Test
    fun `an unticked row still takes its entry, so the entry is not reported as missing`() {
        val entry = tx(1, 10, -450, "Tesco")
        val questions = ImportChecks.questions(
            input(listOf(row(10, -450, "TESCO")), listOf(entry), selected = listOf(false), coverage = d(1) to d(31)),
        )

        assertEquals(listOf(PossibleDuplicate(0, entry)), questions.dupes())
        assertEquals(emptyList(), questions.missing())
    }

    // ---- 2. a different amount for the same purchase ---------------------------------------------------------------

    @Test
    fun `the same merchant a little apart with a different amount is a mismatch`() {
        val entry = tx(1, 11, -430, "Tesco")
        val questions = ImportChecks.questions(input(listOf(row(10, -450, "TESCO STORES")), listOf(entry)))

        assertEquals(listOf<ImportQuestion>(AmountMismatch(0, entry)), questions)
        assertEquals(listOf(CheckAnswer.FixMine, CheckAnswer.Different), questions.single().choices)
    }

    @Test
    fun `a mismatch needs the same direction, two days at most and a normal entry`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))

        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 10, 430, "Tesco")))).mismatches())
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 13, -430, "Tesco")))).mismatches(), "3 days")
        assertEquals(1, ImportChecks.questions(input(rows, listOf(tx(1, 12, -430, "Tesco")))).mismatches().size, "2 days")
        assertEquals(
            emptyList(),
            ImportChecks.questions(input(rows, listOf(tx(1, 10, -430, "Tesco", kind = TxKind.TRANSFER, group = "g")))).mismatches(),
        )
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 10, -430, "Tesco", importKey = "k")))).mismatches())
    }

    @Test
    fun `an unrelated entry is not a mismatch`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))

        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(tx(1, 10, -430, "Rent")))).mismatches())
    }

    @Test
    fun `an entry in the category the importer suggests for the row is a mismatch whatever its label`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))
        val entry = tx(1, 10, -430, "Weekly shop", categoryId = groceries.id)

        val withCategories = ImportChecks.questions(input(rows, listOf(entry), categories = listOf(groceries)))
        val elsewhere = ImportChecks.questions(input(listOf(row(10, -450, "UNION SHOP")), listOf(entry), categories = listOf(groceries)))

        assertEquals(listOf(AmountMismatch(0, entry)), withCategories.mismatches())
        assertEquals(emptyList(), elsewhere.mismatches(), "UNION SHOP is not suggested as Groceries")
    }

    @Test
    fun `a merchant rule decides the suggested category too`() {
        val shopping = Category(id = 6, name = "Shopping", flow = Flow.OUT, forecastMode = ForecastMode.LEARNED)
        val rules = listOf(MerchantRule(merchantKey = "UNION SHOP", categoryId = shopping.id))
        val entry = tx(1, 10, -430, "Snacks", categoryId = shopping.id)

        val questions = ImportChecks.questions(
            input(listOf(row(10, -450, "UNION SHOP")), listOf(entry), categories = listOf(groceries, shopping), rules = rules),
        )

        assertEquals(listOf(AmountMismatch(0, entry)), questions.mismatches())
    }

    @Test
    fun `a shared word needs four letters and must name a merchant`() {
        val entry = { label: String -> tx(1, 10, -430, label) }

        // "KFC" is three letters; "Stores" says nothing about who was paid.
        assertEquals(emptyList(), ImportChecks.questions(input(listOf(row(10, -450, "KFC LEEDS")), listOf(entry("KFC")))).mismatches())
        assertEquals(emptyList(), ImportChecks.questions(input(listOf(row(10, -450, "TESCO STORES")), listOf(entry("Corner stores")))).mismatches())
        assertEquals(1, ImportChecks.questions(input(listOf(row(10, -450, "COSTA COFFEE")), listOf(entry("Costa")))).mismatches().size)
    }

    @Test
    fun `apostrophes and card noise do not hide a shared merchant`() {
        val rows = listOf(row(10, -450, "SAINSBURYS S/MKTS (VIA GOOGLE PAY), ON 09-10-2026"))

        assertEquals(1, ImportChecks.questions(input(rows, listOf(tx(1, 10, -430, "Sainsbury's")))).mismatches().size)
    }

    @Test
    fun `an entry already paired as a duplicate is not offered again as a mismatch`() {
        val exact = tx(1, 10, -450, "Tesco")
        val other = tx(2, 10, -430, "Tesco")
        val questions = ImportChecks.questions(input(listOf(row(10, -450, "TESCO STORES")), listOf(exact, other)))

        assertEquals(listOf(PossibleDuplicate(0, exact)), questions.dupes())
        assertEquals(emptyList(), questions.mismatches())
    }

    @Test
    fun `mismatches are one to one as well, the closer date first`() {
        val rows = listOf(row(10, -450, "TESCO STORES"), row(12, -500, "TESCO STORES"))
        val entry = tx(1, 12, -430, "Tesco")

        val mismatches = ImportChecks.questions(input(rows, listOf(entry))).mismatches()

        assertEquals(listOf(AmountMismatch(1, entry)), mismatches)
    }

    // ---- 3. in the app but not on the statement -------------------------------------------------------------------

    @Test
    fun `an unmatched entry inside the coverage is reported, inclusive of both ends`() {
        val inside = listOf(tx(1, 1, -100), tx(2, 15, -200), tx(3, 31, -300))
        val outside = listOf(tx(4, 30, -100, month = 9), tx(5, 1, -100, month = 11))
        val questions = ImportChecks.questions(input(emptyList(), inside + outside, coverage = d(1) to d(31)))

        assertEquals(inside, questions.missing().map { it.existing })
        assertTrue(questions.all { it is NotOnStatement })
    }

    @Test
    fun `without a coverage nothing is reported as missing`() {
        assertEquals(emptyList(), ImportChecks.questions(input(emptyList(), listOf(tx(1, 15, -200)))))
    }

    @Test
    fun `entries that cannot be on a statement are not reported`() {
        val skipped = listOf(
            tx(1, 15, -200, accountId = 2),
            tx(2, 15, -200, importKey = "imported"),
            tx(3, 15, -200, kind = TxKind.RECONCILIATION),
            tx(4, 15, -200, kind = TxKind.OPENING_BALANCE),
        )

        assertEquals(emptyList(), ImportChecks.questions(input(emptyList(), skipped, coverage = d(1) to d(31))))
    }

    @Test
    fun `the last three days are flagged as possibly not at the bank yet`() {
        val entries = listOf(tx(1, 28, -100), tx(2, 29, -100), tx(3, 30, -100), tx(4, 31, -100))
        val missing = ImportChecks.questions(input(emptyList(), entries, coverage = d(1) to d(31))).missing()

        assertEquals(listOf(false, true, true, true), missing.map { it.mayNotHaveReachedBank })
    }

    @Test
    fun `a transfer leg is reported with its other leg and cannot be moved to that leg's account`() {
        val leg = tx(1, 15, -5_000, "To savings", kind = TxKind.TRANSFER, group = "g")
        val other = tx(2, 15, 5_000, "To savings", accountId = 2, kind = TxKind.TRANSFER, group = "g")
        val accounts = listOf(acct(1, "Current"), acct(2, "Savings"), acct(3, "Cash"), acct(4, "Old", archived = true))

        val missing = ImportChecks.questions(input(emptyList(), listOf(leg, other), accounts = accounts, coverage = d(1) to d(31))).missing()

        val question = missing.single()
        assertEquals(leg, question.existing)
        assertEquals(other, question.transferPartner)
        assertEquals(listOf(3L), question.moveTargets.map { it.id })
        assertEquals(listOf(CheckAnswer.Keep, CheckAnswer.Delete, CheckAnswer.MoveTo(3)), question.choices)
    }

    @Test
    fun `an ordinary entry can be moved to any other live account`() {
        val accounts = listOf(acct(1, "Current"), acct(2, "Savings"), acct(3, "Cash"), acct(4, "Old", archived = true))
        val question = ImportChecks.questions(input(emptyList(), listOf(tx(1, 15, -200)), accounts = accounts, coverage = d(1) to d(31)))
            .missing().single()

        assertNull(question.transferPartner)
        assertEquals(listOf(2L, 3L), question.moveTargets.map { it.id })
    }

    // ---- 4. statement problems -----------------------------------------------------------------------------------

    private val rowProblem = "Row 2 (7 Oct, UNION SHOP): the balance doesn't follow from the amount"

    @Test
    fun `every statement problem is a question and one that names a row can skip it`() {
        val rows = listOf(row(6, -100, "A"), row(7, -200, "UNION SHOP"))
        val total = "The money out on the rows adds up to £3.00, but the statement says £4.00"
        val questions = ImportChecks.questions(input(rows, problems = listOf(rowProblem, total)))

        val problems = questions.only<StatementProblem>()
        assertEquals(listOf(rowProblem, total), problems.map { it.text })
        assertEquals(listOf(1, null), problems.map { it.rowIndex })
        assertEquals(listOf(CheckAnswer.SkipRow, CheckAnswer.Ok), problems[0].choices)
        assertEquals(listOf(CheckAnswer.Ok), problems[1].choices)
    }

    @Test
    fun `a problem on a row that is not being imported can only be acknowledged, and one on an already imported row is not asked`() {
        val rows = listOf(row(6, -100, "A"), row(7, -200, "UNION SHOP"))

        val unticked = ImportChecks.questions(input(rows, selected = listOf(true, false), problems = listOf(rowProblem)))
        assertEquals(listOf(CheckAnswer.Ok), unticked.only<StatementProblem>().single().choices)

        val imported = ImportChecks.questions(
            input(rows, statuses = listOf(RowStatus.NEW, RowStatus.ALREADY_IMPORTED), problems = listOf(rowProblem)),
        )
        assertEquals(emptyList(), imported.only<StatementProblem>())
    }

    @Test
    fun `the row a Santander problem names is found from its text`() {
        assertEquals(6, ImportChecks.problemRow("Row 7 (7 Oct, UNION SHOP): the balance doesn't follow from the amount"))
        assertNull(ImportChecks.problemRow("The transactions don't add up: £0.00 at the start plus the rows comes to £1.00"))
        assertNull(ImportChecks.problemRow("Couldn't read the date on the row \"5th Oct SHOP\""))
        assertNull(ImportChecks.problemRow("Row 0 (x)"))
    }

    @Test
    fun `a problem the statement reader really reports points at the right row`() {
        val tampered = SantanderStatementFixture.cells().map { if (it.page == 2 && it.text == "24.19") it.copy(text = "24.18") else it }
        val statement = (SantanderStatement.parse(PdfCells.merge(tampered)) as? SantanderParseResult.Success)?.statement
            ?: fail("the tampered statement should still read")
        // The next row's balance follows from the real one, so it is reported too; row 17 is the tampered one.
        val problem = statement.problems.single { it.startsWith("Row 17 ") }

        val rowIndex = assertNotNull(ImportChecks.problemRow(problem))
        assertEquals("UNION SHOP", statement.lines[rowIndex].row.description)
        assertEquals(Money(-77), statement.lines[rowIndex].row.amount)
    }

    // ---- 5. rows before the opening balance ---------------------------------------------------------------------

    private fun opening(id: Long, pence: Long, day: Int, batch: Long? = null, month: Int = 10) =
        tx(id, day, pence, "Opening balance", kind = TxKind.OPENING_BALANCE, batch = batch, month = month)

    @Test
    fun `rows dated before the opening balance are reported once, as information`() {
        val rows = listOf(row(2, -100, "A"), row(4, -100, "B"), row(5, -100, "C"), row(9, -100, "D"))
        val question = ImportChecks.questions(input(rows, listOf(opening(1, 10_000, 5)))).only<BeforeOpening>().single()

        assertEquals(BeforeOpening(2, d(5)), question)
        assertEquals(listOf(CheckAnswer.Ok), question.choices)
        assertEquals(mapOf(question.id to CheckAnswer.Ok), ImportChecks.preAnswers(listOf(question)))
    }

    @Test
    fun `only rows that will be imported count, and the move entries an earlier import made are not the opening balance`() {
        val rows = listOf(row(2, -100, "A"), row(3, -100, "B"), row(4, -100, "C"))
        val questions = ImportChecks.questions(
            input(
                rows,
                existing = listOf(opening(1, 10_000, 5), opening(2, 0, 1, batch = 7)),
                statuses = listOf(RowStatus.NEW, RowStatus.ALREADY_IMPORTED, RowStatus.NEW),
                selected = listOf(true, true, false),
            ),
        )

        assertEquals(BeforeOpening(1, d(5)), questions.only<BeforeOpening>().single())
    }

    @Test
    fun `no opening balance or no earlier row means no question`() {
        val rows = listOf(row(6, -100, "A"))

        assertEquals(emptyList(), ImportChecks.questions(input(rows)).only<BeforeOpening>())
        assertEquals(emptyList(), ImportChecks.questions(input(rows, listOf(opening(1, 10_000, 5)))).only<BeforeOpening>())
    }

    // ---- 6. the balance gap ----------------------------------------------------------------------------------------

    private val coverage = d(1) to d(31)

    @Test
    fun `an account that already matches the statement has no gap`() {
        val rows = listOf(row(10, -500, "SHOP"))
        val existing = listOf(opening(1, 10_000, 1))

        val questions = ImportChecks.questions(input(rows, existing, coverage = coverage, closing = Money(9_500)))

        assertEquals(emptyList(), questions)
    }

    @Test
    fun `a gap says what the app would show against the statement`() {
        val rows = listOf(row(10, -500, "SHOP"))
        val existing = listOf(opening(1, 10_000, 1))

        val gap = ImportChecks.questions(input(rows, existing, coverage = coverage, closing = Money(9_000))).only<BalanceGap>().single()

        assertEquals(BalanceGap(d(31), appBalance = Money(9_500), statementBalance = Money(9_000)), gap)
        assertEquals(Money(-500), gap.difference)
        assertEquals(listOf(CheckAnswer.RecordDifference, CheckAnswer.Leave), gap.choices)
    }

    @Test
    fun `there is no gap question without a closing balance or a last day`() {
        val rows = listOf(row(10, -500, "SHOP"))
        val existing = listOf(opening(1, 10_000, 1))

        assertEquals(emptyList(), ImportChecks.questions(input(rows, existing, coverage = coverage, closing = null)))
        assertEquals(emptyList(), ImportChecks.questions(input(rows, existing, coverage = null, closing = Money(1))))
    }

    @Test
    fun `the gap counts only the rows that will be imported`() {
        val rows = listOf(row(10, -500, "SHOP"), row(11, -700, "UNTICKED"), row(12, -900, "OLD"))
        val existing = listOf(opening(1, 10_000, 1))
        val statuses = listOf(RowStatus.NEW, RowStatus.NEW, RowStatus.ALREADY_IMPORTED)

        val gap = ImportChecks.questions(
            input(rows, existing, statuses = statuses, selected = listOf(true, false, true), coverage = coverage, closing = Money(0)),
        ).only<BalanceGap>().single()

        assertEquals(Money(9_500), gap.appBalance)
    }

    @Test
    fun `answers change the gap as they are given`() {
        val rows = listOf(row(10, -500, "TESCO STORES"))
        val entry = tx(2, 10, -500, "Tesco")
        val extra = tx(3, 20, -1_000, "Cinema")
        val existing = listOf(opening(1, 10_000, 1), entry, extra)
        val check = input(rows, existing, coverage = coverage, closing = Money(8_500))
        val duplicate = PossibleDuplicate(0, entry)
        val missing = NotOnStatement(extra, null, false, emptyList())

        // Previewed with the likeliest answers: same as mine, keep the cinema. 10000 - 500 - 1000 = 8500: no gap.
        assertEquals(emptyList(), ImportChecks.questions(check).only<BalanceGap>())
        // Not the same: the row comes in as well, so 500 more is spent.
        val different = ImportChecks.questions(check, mapOf(duplicate.id to CheckAnswer.Different)).only<BalanceGap>().single()
        assertEquals(Money(8_000), different.appBalance)
        // Delete the cinema: the app holds 1000 more than the statement says.
        val deleted = ImportChecks.questions(check, mapOf(missing.id to CheckAnswer.Delete)).only<BalanceGap>().single()
        assertEquals(Money(9_500), deleted.appBalance)
        assertEquals(Money(-1_000), deleted.difference)
        // Moved to another account it is gone from this one too: the same 1000 more than the statement.
        val moved = ImportChecks.questions(check, mapOf(missing.id to CheckAnswer.MoveTo(3))).only<BalanceGap>().single()
        assertEquals(Money(9_500), moved.appBalance)
        // An account that was never offered as a target is not an answer, so the cinema counts as kept.
        val unknown = ImportChecks.questions(check, mapOf(missing.id to CheckAnswer.MoveTo(99))).only<BalanceGap>()
        assertEquals(emptyList(), unknown)
    }

    @Test
    fun `moving an entry to a target that was offered takes it out of this account's balance`() {
        val extra = tx(3, 20, -1_000, "Cinema")
        val existing = listOf(opening(1, 10_000, 1), extra)
        val check = input(emptyList(), existing, coverage = coverage, closing = Money(10_000))
        val missing = ImportChecks.questions(check).missing().single()

        val gap = ImportChecks.questions(check).only<BalanceGap>().single()
        assertEquals(Money(9_000), gap.appBalance)
        assertTrue(CheckAnswer.MoveTo(2) in missing.choices)

        val afterMove = ImportChecks.questions(check, mapOf(missing.id to CheckAnswer.MoveTo(2)))
        assertEquals(emptyList(), afterMove.only<BalanceGap>(), "10000 on the 31st with the cinema gone")
    }

    @Test
    fun `an entry moved to the bank's date across the last day changes the balance on it`() {
        val entry = tx(2, 2, -500, "Tesco", month = 11)
        val check = input(
            listOf(row(31, -500, "TESCO STORES")),
            listOf(opening(1, 10_000, 1), entry),
            coverage = d(1) to d(31),
            closing = Money(9_500),
        )
        val duplicate = PossibleDuplicate(0, entry)

        val keepMine = ImportChecks.questions(check, mapOf(duplicate.id to CheckAnswer.SameKeepMine)).only<BalanceGap>().single()
        val useBank = ImportChecks.questions(check, mapOf(duplicate.id to CheckAnswer.SameUseBank)).only<BalanceGap>()

        assertEquals(Money(10_000), keepMine.appBalance, "dated 2 Nov, it is not in the balance on 31 Oct")
        assertEquals(emptyList(), useBank, "dated 31 Oct, it is")
    }

    @Test
    fun `fixing an entry's amount makes the balance the statement's`() {
        val entry = tx(2, 10, -430, "Tesco")
        val check = input(
            listOf(row(10, -450, "TESCO STORES")),
            listOf(opening(1, 10_000, 1), entry),
            coverage = coverage,
            closing = Money(9_550),
        )
        val mismatch = AmountMismatch(0, entry)

        val kept = ImportChecks.questions(check, mapOf(mismatch.id to CheckAnswer.Different)).only<BalanceGap>().single()
        val fixed = ImportChecks.questions(check, mapOf(mismatch.id to CheckAnswer.FixMine))

        assertEquals(Money(9_120), kept.appBalance, "both the entry and the row")
        assertEquals(emptyList(), fixed.only<BalanceGap>())
        assertEquals(emptyList(), ImportChecks.questions(check).only<BalanceGap>(), "fix mine is previewed until answered")
    }

    @Test
    fun `the preview moves the opening balance for rows dated before it`() {
        // Opened on the 5th with 100.00; the rows from before it (2 and 4 Oct, 30.00 together) are already in it.
        val rows = listOf(row(2, -1_000, "A"), row(4, -2_000, "B"), row(6, -500, "C"))
        val check = input(rows, listOf(opening(1, 10_000, 5)), coverage = coverage, closing = Money(9_500))

        val questions = ImportChecks.questions(check)

        assertEquals(emptyList(), questions.only<BalanceGap>(), "the balance after the 6th is 100.00 - 5.00")
        assertEquals(BeforeOpening(2, d(5)), questions.only<BeforeOpening>().single())
    }

    @Test
    fun `an opening balance an earlier import already moved is re-derived, not stacked`() {
        // Earlier import: row on the 2nd (10.00) made the opening 90.00 on the 1st, plus the cancelling pair.
        val earlier = tx(5, 2, -1_000, "A", batch = 7, importKey = "k1")
        val existing = listOf(
            opening(1, 10_000, 5),
            opening(2, 11_000, 1, batch = 7),
            opening(3, -10_000, 5, batch = 7),
            earlier,
        )
        val check = input(listOf(row(6, -500, "C")), existing, coverage = coverage, closing = Money(9_500))

        assertEquals(emptyList(), ImportChecks.questions(check).only<BalanceGap>())
    }

    @Test
    fun `rows already imported never produce a question`() {
        val rows = listOf(row(10, -450, "TESCO STORES"), row(10, -430, "TESCO STORES"), row(2, -100, "OLD"))
        val existing = listOf(tx(1, 10, -450, "Tesco"), tx(2, 10, -400, "Tesco"), opening(3, 10_000, 5))
        val statuses = rows.map { RowStatus.ALREADY_IMPORTED }

        val questions = ImportChecks.questions(input(rows, existing, statuses = statuses, problems = listOf(rowProblem)))

        assertEquals(emptyList(), questions.dupes())
        assertEquals(emptyList(), questions.mismatches())
        assertEquals(emptyList(), questions.only<StatementProblem>())
        assertEquals(emptyList(), questions.only<BeforeOpening>())
    }

    @Test
    fun `a row whose key is already in the app counts as already imported whatever its status says`() {
        val rows = listOf(row(10, -450, "TESCO STORES"))
        val key = ImportDedupe.importKeys(account, rows).single()
        val existing = listOf(tx(1, 10, -450, "Tesco"), tx(2, 10, -450, "Tesco again", importKey = key))

        val questions = ImportChecks.questions(input(rows, existing))

        assertEquals(emptyList(), questions.dupes())
        assertEquals(emptyList(), ImportChecks.plan(input(rows, existing), questions, emptyMap()).importRows)
    }

    // ---- answering ----------------------------------------------------------------------------------------------

    @Test
    fun `the questions are complete once each has one of its own answers`() {
        val entry = tx(1, 10, -450, "Tesco")
        val questions = ImportChecks.questions(input(listOf(row(10, -450, "TESCO")), listOf(entry), coverage = coverage))
        val duplicate = questions.single()

        assertFalse(ImportChecks.isComplete(questions, emptyMap()))
        assertFalse(ImportChecks.isComplete(questions, mapOf(duplicate.id to CheckAnswer.Keep)), "Keep is not an answer to a duplicate")
        assertTrue(ImportChecks.isComplete(questions, mapOf(duplicate.id to CheckAnswer.Different)))
        assertTrue(ImportChecks.isComplete(emptyList(), emptyMap()))
    }

    @Test
    fun `answers stay with their question when the questions are worked out again`() {
        val entry = tx(1, 10, -450, "Tesco")
        val check = input(listOf(row(10, -450, "TESCO")), listOf(entry), coverage = coverage, closing = Money(0))

        val first = ImportChecks.questions(check)
        val second = ImportChecks.questions(check, mapOf(first.first().id to CheckAnswer.Different))

        assertEquals(listOf("dup:0:1", "gap"), first.map { it.id })
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    private fun duplicateInput(): Triple<ImportCheckInput, Transaction, Transaction> {
        val rows = listOf(row(10, -450, "TESCO STORES"), row(12, -700, "BOOTS"))
        val mine = tx(1, 11, -450, "Tesco")
        val boots = tx(2, 12, -690, "Boots")
        return Triple(input(rows, listOf(mine, boots), coverage = coverage), mine, boots)
    }

    @Test
    fun `same, use the bank's date, updates the date and key and does not import the row`() {
        val (check, mine, _) = duplicateInput()
        val questions = ImportChecks.questions(check)
        val duplicate = questions.dupes().single()

        val plan = ImportChecks.plan(check, questions, mapOf(duplicate.id to CheckAnswer.SameUseBank))

        assertEquals(listOf(ExistingUpdate(mine.id, date = d(10), importKey = check.importKeys[0])), plan.changes.updates)
        assertEquals(listOf(1), plan.importRows, "the other row, which has no answer yet, still comes in")
        assertEquals(1, plan.matched)
    }

    @Test
    fun `same, keep mine, only sets the key and does not import the row`() {
        val (check, mine, _) = duplicateInput()
        val questions = ImportChecks.questions(check)

        val plan = ImportChecks.plan(check, questions, mapOf(questions.dupes().single().id to CheckAnswer.SameKeepMine))

        assertEquals(listOf(ExistingUpdate(mine.id, importKey = check.importKeys[0])), plan.changes.updates)
        assertEquals(listOf(1), plan.importRows)
        assertEquals(1, plan.matched)
    }

    @Test
    fun `different imports the row as well and changes nothing`() {
        val (check, _, _) = duplicateInput()
        val questions = ImportChecks.questions(check)

        val plan = ImportChecks.plan(check, questions, mapOf(questions.dupes().single().id to CheckAnswer.Different))

        assertEquals(listOf(0, 1), plan.importRows)
        assertEquals(ImportChanges.NONE, plan.changes)
        assertEquals(0, plan.matched)
    }

    @Test
    fun `fix mine sets the amount, date and key and does not import the row`() {
        val (check, _, boots) = duplicateInput()
        val questions = ImportChecks.questions(check)
        val mismatch = questions.mismatches().single()

        val plan = ImportChecks.plan(check, questions, mapOf(mismatch.id to CheckAnswer.FixMine))

        assertEquals(
            listOf(ExistingUpdate(boots.id, date = d(12), amount = Money(-700), importKey = check.importKeys[1])),
            plan.changes.updates,
        )
        assertEquals(listOf(0), plan.importRows)
        assertEquals(1, plan.corrected)
        assertEquals(0, plan.matched)
    }

    @Test
    fun `using the bank's date on a transfer leg moves the other leg too`() {
        val rows = listOf(row(10, -5_000, "TRANSFER TO SAVINGS"))
        val leg = tx(1, 11, -5_000, "To savings", kind = TxKind.TRANSFER, group = "g")
        val other = tx(2, 11, 5_000, "To savings", accountId = 2, kind = TxKind.TRANSFER, group = "g")
        val check = input(rows, listOf(leg, other))
        val questions = ImportChecks.questions(check)

        val plan = ImportChecks.plan(check, questions, mapOf(questions.single().id to CheckAnswer.SameUseBank))

        assertEquals(
            listOf(ExistingUpdate(1, date = d(10), importKey = check.importKeys[0]), ExistingUpdate(2, date = d(10))),
            plan.changes.updates,
        )
    }

    @Test
    fun `delete removes the entry, and both legs of a transfer`() {
        val plain = tx(1, 15, -200, "Cinema")
        val leg = tx(2, 16, -5_000, "To savings", kind = TxKind.TRANSFER, group = "g")
        val other = tx(3, 16, 5_000, "To savings", accountId = 2, kind = TxKind.TRANSFER, group = "g")
        val check = input(emptyList(), listOf(plain, leg, other), coverage = coverage)
        val questions = ImportChecks.questions(check)
        val answers = questions.missing().associate { it.id to CheckAnswer.Delete }

        val plan = ImportChecks.plan(check, questions, answers)

        assertEquals(listOf(1L, 2L, 3L), plan.changes.deletions)
        assertEquals(2, plan.deleted, "a transfer counts once")
    }

    @Test
    fun `move to takes the entry to the chosen account and counts per account`() {
        val check = input(emptyList(), listOf(tx(1, 15, -200), tx(2, 16, -300), tx(3, 17, -400)), coverage = coverage)
        val questions = ImportChecks.questions(check)
        val (a, b, c) = questions.missing()

        val plan = ImportChecks.plan(
            check,
            questions,
            mapOf(a.id to CheckAnswer.MoveTo(3), b.id to CheckAnswer.MoveTo(3), c.id to CheckAnswer.Keep),
        )

        assertEquals(listOf(ExistingMove(1, 3), ExistingMove(2, 3)), plan.changes.moves)
        assertEquals(mapOf(3L to 2), plan.movedTo)
        assertEquals(emptyList(), plan.changes.deletions)
    }

    @Test
    fun `skip row leaves the row out, ok does not`() {
        val rows = listOf(row(6, -100, "A"), row(7, -200, "UNION SHOP"))
        val check = input(rows, problems = listOf(rowProblem))
        val questions = ImportChecks.questions(check)
        val problem = questions.only<StatementProblem>().single()

        assertEquals(listOf(0), ImportChecks.plan(check, questions, mapOf(problem.id to CheckAnswer.SkipRow)).importRows)
        assertEquals(1, ImportChecks.plan(check, questions, mapOf(problem.id to CheckAnswer.SkipRow)).skipped)
        assertEquals(listOf(0, 1), ImportChecks.plan(check, questions, mapOf(problem.id to CheckAnswer.Ok)).importRows)
    }

    @Test
    fun `record difference becomes a reconciliation for the last day`() {
        val rows = listOf(row(10, -500, "SHOP"))
        val check = input(rows, listOf(opening(1, 10_000, 1)), coverage = coverage, closing = Money(9_000))
        val questions = ImportChecks.questions(check)
        val gap = questions.only<BalanceGap>().single()

        val record = ImportChecks.plan(check, questions, mapOf(gap.id to CheckAnswer.RecordDifference))
        val leave = ImportChecks.plan(check, questions, mapOf(gap.id to CheckAnswer.Leave))

        assertEquals(ImportReconcile(d(31), Money(9_000)), record.changes.reconcile)
        assertNull(leave.changes.reconcile)
        assertNull(ImportChecks.plan(check, questions, emptyMap()).changes.reconcile)
    }

    @Test
    fun `unanswered and unacceptable answers change nothing`() {
        val (check, _, _) = duplicateInput()
        val questions = ImportChecks.questions(check)
        val duplicate = questions.dupes().single()

        val none = ImportChecks.plan(check, questions, emptyMap())
        val wrong = ImportChecks.plan(check, questions, mapOf(duplicate.id to CheckAnswer.Delete))

        assertEquals(listOf(0, 1), none.importRows)
        assertEquals(ImportChanges.NONE, none.changes)
        assertEquals(none, wrong)
    }

    @Test
    fun `rows in a pair are decided by their answer, ticked or not, and other unticked rows stay out`() {
        val entry = tx(1, 10, -450, "Tesco")
        val rows = listOf(row(10, -450, "TESCO"), row(11, -100, "UNTICKED"), row(12, -200, "OLD"))
        val check = input(
            rows,
            listOf(entry),
            statuses = listOf(RowStatus.NEW, RowStatus.NEW, RowStatus.ALREADY_IMPORTED),
            selected = listOf(false, false, true),
        )
        val questions = ImportChecks.questions(check)
        val duplicate = questions.dupes().single()

        val different = ImportChecks.plan(check, questions, mapOf(duplicate.id to CheckAnswer.Different))

        assertEquals(listOf(0), different.importRows, "the pair's row is imported on Different; unticked and already imported rows are not")
        assertEquals(setOf(0), ImportChecks.flaggedRows(check))
    }

    // ---- coverage and closing balance of a CSV --------------------------------------------------------------------

    @Test
    fun `a CSV's coverage runs from its first to its last day, whatever the order`() {
        val rows = listOf(row(20, -1, "A"), row(3, -1, "B"), row(12, -1, "C"))

        assertEquals(d(3) to d(20), ImportChecks.coverageOf(rows))
        assertNull(ImportChecks.coverageOf(emptyList()))
    }

    @Test
    fun `a CSV's closing balance is the balance after its last row, oldest first or newest first`() {
        val oldestFirst = listOf(row(1, -100, "A", balance = 900), row(2, -100, "B", balance = 800), row(3, -100, "C", balance = 700))

        assertEquals(Money(700), ImportChecks.closingBalanceOf(oldestFirst))
        assertEquals(Money(700), ImportChecks.closingBalanceOf(oldestFirst.reversed()))
    }

    @Test
    fun `of several rows on the last day the one at the end of the file in its direction is taken`() {
        val ascending = listOf(row(1, -100, "A", balance = 900), row(3, -100, "B", balance = 800), row(3, -100, "C", balance = 700))
        val descending = listOf(row(3, -100, "C", balance = 700), row(3, -100, "B", balance = 800), row(1, -100, "A", balance = 900))

        assertEquals(Money(700), ImportChecks.closingBalanceOf(ascending))
        assertEquals(Money(700), ImportChecks.closingBalanceOf(descending))
    }

    @Test
    fun `no balance column means no closing balance`() {
        assertNull(ImportChecks.closingBalanceOf(listOf(row(1, -100, "A"), row(2, -100, "B"))))
        assertNull(ImportChecks.closingBalanceOf(emptyList()))
    }

    // ---- the whole invented Santander statement --------------------------------------------------------------------

    private class Invented(val check: ImportCheckInput, val statement: ParsedStatement)

    private fun invented(existing: List<Transaction>): Invented {
        val statement = (SantanderStatement.parse(PdfCells.merge(SantanderStatementFixture.cells())) as? SantanderParseResult.Success)
            ?.statement ?: fail("the invented statement should read")
        val rows = statement.lines.map { it.row }
        val keys = ImportDedupe.importKeys(account, rows)
        val statuses = ImportDedupe.classify(account, rows, keys, existing)
        val check = ImportCheckInput(
            accountId = account,
            rows = rows,
            importKeys = keys,
            statuses = statuses,
            selected = rows.map { true },
            existing = existing,
            accounts = listOf(acct(1, "Current"), acct(2, "Savings"), acct(3, "Cash")),
            coverageStart = statement.periodStart,
            coverageEnd = statement.periodEnd,
            closingBalance = statement.closingBalance,
            problems = statement.problems,
            categories = listOf(groceries),
        )
        return Invented(check, statement)
    }

    private fun opened(day: Int, month: Int, pence: Long) = opening(100, pence, day, month = month)

    @Test
    fun `an empty account importing the whole statement has nothing to ask`() {
        val invented = invented(listOf(opened(10, 9, 0)))

        assertEquals(emptyList(), ImportChecks.questions(invented.check))
    }

    @Test
    fun `hand-entered transactions around the statement produce each kind of question`() {
        val hand = listOf(
            tx(1, 8, -183, "Tesco"), // = TESCO STORES 1234, 8 Oct
            tx(2, 7, -230, "Corner cafe"), // ~ SQ *CORNER CAFE £2.20 on 7 Oct
            tx(3, 3, -999, "Cinema"), // nothing on the statement
            tx(4, 9, -1_234, "Lunch"), // nothing on the statement, in the last three days
            tx(5, 11, -777, "After the statement"),
        )
        val invented = invented(listOf(opened(10, 9, 0)) + hand)

        val questions = ImportChecks.questions(invented.check)

        val duplicate = questions.dupes().single()
        assertEquals(12, duplicate.rowIndex)
        assertEquals("TESCO STORES 1234", invented.check.rows[duplicate.rowIndex].description)
        assertEquals(hand[0], duplicate.existing)
        val mismatch = questions.mismatches().single()
        assertEquals(10, mismatch.rowIndex)
        assertEquals(hand[1], mismatch.existing)
        assertEquals(listOf(hand[2], hand[3]), questions.missing().map { it.existing })
        assertEquals(listOf(false, true), questions.missing().map { it.mayNotHaveReachedBank })
        assertEquals(emptyList(), questions.only<StatementProblem>())

        // Previewed as same-as-mine, fix mine, keep both: the statement's 14.79 less 9.99 and 12.34 the bank doesn't know.
        val gap = questions.only<BalanceGap>().single()
        assertEquals(BalanceGap(d(10), Money(-754), Money(1_479)), gap)

        // Delete both and the app agrees with the statement; the gap goes.
        val deletes = questions.missing().associate { it.id to CheckAnswer.Delete }
        assertEquals(emptyList(), ImportChecks.questions(invented.check, deletes).only<BalanceGap>())
        // Keep one and it is that one's amount.
        val keepLunch = mapOf(questions.missing()[0].id to CheckAnswer.Delete, questions.missing()[1].id to CheckAnswer.Keep)
        assertEquals(Money(1_479 - 1_234), ImportChecks.questions(invented.check, keepLunch).only<BalanceGap>().single().appBalance)
    }

    @Test
    fun `an account opened mid statement is told its starting balance will be adjusted and then matches`() {
        // Opened on 5 Oct with the £67.83 it held after the 4th (the five rows before it come to £67.83).
        val invented = invented(listOf(opened(5, 10, 6_783)))

        val questions = ImportChecks.questions(invented.check)

        assertEquals(listOf<ImportQuestion>(BeforeOpening(5, d(5))), questions)
    }

    @Test
    fun `a statement that does not add up asks about the row it names`() {
        val tampered = SantanderStatementFixture.cells().map { if (it.page == 2 && it.text == "24.19") it.copy(text = "24.18") else it }
        val statement = (SantanderStatement.parse(PdfCells.merge(tampered)) as? SantanderParseResult.Success)?.statement
            ?: fail("the tampered statement should still read")
        val rows = statement.lines.map { it.row }
        val keys = ImportDedupe.importKeys(account, rows)
        val check = ImportCheckInput(
            account, rows, keys, rows.map { RowStatus.NEW }, rows.map { true }, listOf(opened(10, 9, 0)),
            listOf(acct(1, "Current")), statement.periodStart, statement.periodEnd, statement.closingBalance, statement.problems,
        )

        val problems = ImportChecks.questions(check).only<StatementProblem>()

        // Row 17 is the one changed; the balance printed after it then no longer fits row 18 either.
        assertEquals(listOf(16, 17), problems.map { it.rowIndex })
        assertEquals(listOf(CheckAnswer.SkipRow, CheckAnswer.Ok), problems[0].choices)
    }
}
