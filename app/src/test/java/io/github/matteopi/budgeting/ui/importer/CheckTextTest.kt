package io.github.matteopi.budgeting.ui.importer

import io.github.matteopi.budgeting.core.importer.AmountMismatch
import io.github.matteopi.budgeting.core.importer.BalanceGap
import io.github.matteopi.budgeting.core.importer.BeforeOpening
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.ImportChanges
import io.github.matteopi.budgeting.core.importer.ImportPlan
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.NotOnStatement
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
import io.github.matteopi.budgeting.core.importer.StatementProblem
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckTextTest {
    private val date = LocalDate.of(2026, 10, 10)
    private val tx = Transaction(id = 1, accountId = 1, amount = Money(-183), date = date, categoryId = null, label = "Tesco")
    private val leg = tx.copy(id = 2, kind = TxKind.TRANSFER, transferGroupId = "g")
    private val cash = Account(id = 3, name = "Cash", type = AccountType.CASH)

    private val duplicate = PossibleDuplicate(0, tx)
    private val mismatch = AmountMismatch(1, tx)
    private val missing = NotOnStatement(tx, transferPartner = null, mayNotHaveReachedBank = false, moveTargets = listOf(cash))
    private val missingLeg = NotOnStatement(leg, transferPartner = leg.copy(id = 3, accountId = 2), mayNotHaveReachedBank = false, moveTargets = emptyList())
    private val problem = StatementProblem(0, "Row 7 (7 Oct, UNION SHOP): the balance doesn't follow from the amount", 6, canSkipRow = true)
    private val before = BeforeOpening(5, LocalDate.of(2026, 10, 5))
    private val gap = BalanceGap(date, appBalance = Money(5_000), statementBalance = Money(1_479))

    @Test
    fun `the heading counts the things to check`() {
        assertEquals("1 thing to check", CheckText.heading(1))
        assertEquals("2 things to check", CheckText.heading(2))
        assertEquals("12 things to check", CheckText.heading(12))
    }

    @Test
    fun `each question has its own plain title`() {
        assertEquals("This might be the same as something you entered", CheckText.title(duplicate))
        assertEquals("Same purchase, different amount?", CheckText.title(mismatch))
        assertEquals("In the app but not on this statement", CheckText.title(missing))
        assertEquals("Your balance won't match the statement", CheckText.title(gap))
        assertTrue(CheckText.title(problem).isNotBlank())
        assertTrue(CheckText.title(before).isNotBlank())
    }

    @Test
    fun `every answer a question accepts has its own label`() {
        val questions: List<ImportQuestion> = listOf(duplicate, mismatch, missing, missingLeg, problem, before, gap)
        for (question in questions) {
            val labels = question.choices.filter { it !is CheckAnswer.MoveTo }.map { CheckText.label(question, it) }
            assertTrue(labels.all { it.isNotBlank() }, "$question")
            assertEquals(labels.size, labels.toSet().size, "labels of $question are distinct: $labels")
        }
    }

    @Test
    fun `the labels say what the answer does`() {
        assertEquals("Same, use the bank's date", CheckText.label(duplicate, CheckAnswer.SameUseBank))
        assertEquals("Same, keep mine as it is", CheckText.label(duplicate, CheckAnswer.SameKeepMine))
        assertEquals("Yes, use the bank's amount and date", CheckText.label(mismatch, CheckAnswer.FixMine))
        assertEquals("Delete it", CheckText.label(missing, CheckAnswer.Delete))
        assertEquals("Delete both sides", CheckText.label(missingLeg, CheckAnswer.Delete))
        assertEquals("Leave this row out", CheckText.label(problem, CheckAnswer.SkipRow))
        assertEquals("Record the difference so they match", CheckText.label(gap, CheckAnswer.RecordDifference))
        assertEquals("Move to Cash", CheckText.movedTo("Cash"))
    }

    @Test
    fun `the balance card says what the app would show against the statement`() {
        assertEquals(
            "On 10 Oct 2026 the app would show £50.00, but your statement says £14.79: a difference of £35.21.",
            CheckText.balanceGap(gap),
        )
        assertEquals(
            "On 10 Oct 2026 the app would show £1.00, but your statement says £2.50: a difference of £1.50.",
            CheckText.balanceGap(BalanceGap(date, Money(100), Money(250))),
        )
    }

    @Test
    fun `the opening balance card counts the rows`() {
        assertEquals(
            "5 rows on this statement are dated before 5 Oct 2026, when this account's starting balance was set. " +
                "Its starting balance will be adjusted so they aren't counted twice.",
            CheckText.beforeOpening(before),
        )
        assertTrue(CheckText.beforeOpening(BeforeOpening(1, date)).startsWith("1 row on this statement is dated before"))
    }

    @Test
    fun `deleting one side of a move says both sides go`() {
        assertEquals(
            "This is one side of a move between accounts (the other side is in Savings). Deleting it deletes both sides.",
            CheckText.transferWarning("Savings"),
        )
        assertEquals(
            "This is one side of a move between accounts. Deleting it deletes both sides.",
            CheckText.transferWarning(null),
        )
    }

    private fun plan(
        matched: Int = 0,
        corrected: Int = 0,
        deleted: Int = 0,
        skipped: Int = 0,
        movedTo: Map<Long, Int> = emptyMap(),
    ) = ImportPlan(emptyList(), ImportChanges.NONE, matched, corrected, deleted, movedTo, skipped)

    @Test
    fun `the summary lists what the checks changed`() {
        val summary = CheckText.changesSummary(
            plan(matched = 2, deleted = 1, movedTo = mapOf(3L to 1)),
            listOf(cash),
            Money(320),
        )

        assertEquals("2 matched to your own entries, 1 deleted, 1 moved to Cash, difference of £3.20 recorded", summary)
    }

    @Test
    fun `the summary uses the singular, covers every kind of change and ignores the sign of the difference`() {
        assertEquals("1 matched to your own entry", CheckText.changesSummary(plan(matched = 1), emptyList(), null))
        assertEquals("1 amount corrected", CheckText.changesSummary(plan(corrected = 1), emptyList(), null))
        assertEquals("3 amounts corrected, 2 rows left out", CheckText.changesSummary(plan(corrected = 3, skipped = 2), emptyList(), null))
        assertEquals("difference of £3.20 recorded", CheckText.changesSummary(plan(), emptyList(), Money(-320)))
        assertEquals("2 moved to another account", CheckText.changesSummary(plan(movedTo = mapOf(9L to 2)), listOf(cash), null))
    }

    @Test
    fun `the summary is empty when the checks changed nothing`() {
        assertNull(CheckText.changesSummary(plan(), listOf(cash), null))
    }
}
