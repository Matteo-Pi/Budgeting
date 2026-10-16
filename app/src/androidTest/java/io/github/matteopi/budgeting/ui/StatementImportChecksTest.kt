package io.github.matteopi.budgeting.ui

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.importer.AmountMismatch
import io.github.matteopi.budgeting.core.importer.BalanceGap
import io.github.matteopi.budgeting.core.importer.CheckAnswer
import io.github.matteopi.budgeting.core.importer.ImportQuestion
import io.github.matteopi.budgeting.core.importer.NotOnStatement
import io.github.matteopi.budgeting.core.importer.PossibleDuplicate
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.SantanderPdfFixture
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.seedTransaction
import io.github.matteopi.budgeting.testing.testApp
import io.github.matteopi.budgeting.ui.importer.ASKED_ABOUT_THIS
import io.github.matteopi.budgeting.ui.importer.CheckTags
import io.github.matteopi.budgeting.ui.importer.CheckText
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The import wizard's check step on screen (docs/PLAN.md §6.5): the invented Santander statement is shared into the
 * app, the account holds hand-entered transactions that disagree with it, and the user walks the "N things to
 * check" cards, answering each by tapping its buttons. The answers are saved when the import is, and the summary
 * says what they changed.
 */
@RunWith(AndroidJUnit4::class)
class StatementImportChecksTest : UiTest() {
    private val shared = File(ReceiptFiles.directory(testApp), "ui-test-check-statement.pdf")

    @After
    fun deleteSharedFile() {
        shared.delete()
    }

    private fun sharedStatement(): Intent {
        SantanderPdfFixture.write(testApp, shared)
        return Intent(Intent.ACTION_SEND)
            .setClass(testApp, MainActivity::class.java)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, ReceiptFiles.uriFor(testApp, shared))
    }

    private fun date(day: Int) = LocalDate.of(2026, 10, day)

    // The wording of a button does not depend on which transaction a question is about.
    private val someTransaction = Transaction(id = 1, accountId = 1, amount = Money(-1), date = date(1), categoryId = null)
    private val duplicate = PossibleDuplicate(0, someTransaction)
    private val mismatch = AmountMismatch(0, someTransaction)
    private val missing = NotOnStatement(someTransaction, null, mayNotHaveReachedBank = true, moveTargets = emptyList())
    private val gapTitle = CheckText.title(BalanceGap(date(10), Money.ZERO, Money(1)))

    private fun label(question: ImportQuestion, answer: CheckAnswer) = CheckText.label(question, answer)

    private fun ledger(): List<Transaction> = blocking { appContainer.transactionRepository.observeAll().first() }

    /** The account the statement goes into ("Santander current", opened on the statement's first day, so the default) and a second one. */
    private fun seedAccounts(): Long {
        val current = seedAccount("Santander current", AccountType.CURRENT, 0, openedOn = LocalDate.of(2026, 9, 10))
        seedAccount("Second current", AccountType.CURRENT, 100_000, openedOn = LocalDate.of(2026, 9, 1))
        return current
    }

    @Test
    fun theCheckStepAsksAboutEachInconsistencyAndTheAnswersAreApplied() {
        seedOnboardingDone()
        val current = seedAccounts()
        // The bank has £1.83 on the 8th (Tesco), £2.20 on the 7th (Corner cafe) and no lunch.
        val tesco = seedTransaction(current, -183, date(9), "Groceries", "Tesco")
        val cafe = seedTransaction(current, -230, date(6), "Eating out", "Corner cafe")
        val lunch = seedTransaction(current, -1_234, date(9), "Eating out", "Lunch")
        launchMain(sharedStatement())

        // The review no longer unticks possible duplicates: the rows it will ask about say so.
        waitForText("Review rows", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Santander statement · 10 Sep – 10 Oct 2026 · 19 transactions · closing balance £14.79")
        scrollIntoView(txt(ASKED_ABOUT_THIS))
        waitForExists(txt(ASKED_ABOUT_THIS), "the hint on a row the check step will ask about")
        assertTrue(!textExists("Possible duplicate"))
        shot("import-check-01-review")

        // The check step: four cards, both sides of each pair, nothing answered yet.
        tapText("Next")
        waitForText("4 things to check", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText(CheckText.title(duplicate))
        waitForText("TESCO STORES 1234")
        waitForText("Tesco")
        assertDisabled(hasTestTag(CheckTags.NEXT), "Next before anything is answered")
        waitForText(CheckText.ANSWER_ALL)
        shot("import-check-02-questions")

        tapText(label(duplicate, CheckAnswer.SameUseBank))
        waitFor("the duplicate's answer to be selected") { isSelectedChip(label(duplicate, CheckAnswer.SameUseBank)) }
        scrollIntoView(txt(CheckText.title(mismatch)))
        waitForText(CheckText.title(mismatch))
        waitForText("SQ *CORNER CAFE")
        waitForText("Corner cafe")
        tapText(label(mismatch, CheckAnswer.FixMine))
        waitFor("the mismatch's answer to be selected") { isSelectedChip(label(mismatch, CheckAnswer.FixMine)) }

        // The entry the statement doesn't have, with the hint, and the balance card with what the app would show.
        scrollIntoView(txt(CheckText.title(missing)))
        waitForText(CheckText.title(missing))
        waitForText("Lunch")
        waitForText(CheckText.MAY_NOT_HAVE_REACHED_BANK)
        scrollIntoView(txt(gapTitle))
        waitForText(gapTitle)
        waitForText(CheckText.balanceGap(BalanceGap(date(10), Money(245), Money(1_479))))
        shot("import-check-03-answered")

        // Moving the lunch to the other account makes the balances agree, so the balance card goes.
        tapText(CheckText.MOVE_TO_ANOTHER)
        tapMenuItem("Second current")
        waitForText(CheckText.movedTo("Second current"))
        waitForNoText(gapTitle)
        assertEnabled(hasTestTag(CheckTags.NEXT), "Next once every question has an answer")
        shot("import-check-04-moved")

        // Categorise as usual, import, and read the summary.
        tapText("Next")
        waitForText("Categorise")
        tapText("Import")
        waitForText("Import complete", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Matches your statement: £14.79 on 10 Oct ✓")
        waitForText("What the checks changed: 1 matched to your own entry, 1 amount corrected, 1 moved to Second current")
        waitForText(CheckText.NOT_UNDONE)
        shot("import-check-05-summary")

        val ledger = ledger()
        val ownTesco = ledger.single { it.id == tesco }
        assertEquals(date(8), ownTesco.date)
        assertEquals("Tesco", ownTesco.label)
        assertNotNull(ownTesco.importKey)
        val ownCafe = ledger.single { it.id == cafe }
        assertEquals(Money(-220), ownCafe.amount)
        assertEquals(date(7), ownCafe.date)
        assertNotNull(ownCafe.importKey)
        val movedLunch = ledger.single { it.id == lunch }
        assertTrue(movedLunch.accountId != current)
        assertEquals(0, ledger.count { it.label == "TESCO STORES 1234" || it.label == "SQ *CORNER CAFE" })
        assertEquals(17, ledger.count { it.importBatchId != null })
        assertTrue(ledger.none { it.kind == TxKind.RECONCILIATION })
    }

    @Test
    fun recordingTheDifferenceMakesTheBalanceMatchAndGoingBackKeepsTheAnswers() {
        seedOnboardingDone()
        val current = seedAccounts()
        val tesco = seedTransaction(current, -183, date(9), "Groceries", "Tesco")
        seedTransaction(current, -1_234, date(9), "Eating out", "Lunch")
        launchMain(sharedStatement())

        waitForText("Review rows", timeoutMs = HEAVY_TIMEOUT_MS)
        tapText("Next")
        waitForText("3 things to check", timeoutMs = HEAVY_TIMEOUT_MS)

        tapText(label(duplicate, CheckAnswer.SameKeepMine))
        tapText(label(missing, CheckAnswer.Keep))
        scrollIntoView(txt(gapTitle))
        waitForText(CheckText.balanceGap(BalanceGap(date(10), Money(245), Money(1_479))))
        waitForText(CheckText.balanceGapHelp(BalanceGap(date(10), Money(245), Money(1_479))))
        assertDisabled(hasTestTag(CheckTags.NEXT), "Next while the balance card is open")
        tapText(label(BalanceGap(date(10), Money.ZERO, Money(1)), CheckAnswer.RecordDifference))
        waitFor("the balance answer to be selected") {
            isSelectedChip(label(BalanceGap(date(10), Money.ZERO, Money(1)), CheckAnswer.RecordDifference))
        }
        assertEnabled(hasTestTag(CheckTags.NEXT), "Next once everything is answered")
        shot("import-check-06-record-difference")

        // Back from the categorise step returns to the check step with the answers still there.
        tapText("Next")
        waitForText("Categorise")
        tapBack()
        waitForText("3 things to check")
        waitFor("the answers to be kept") {
            isSelectedChip(label(duplicate, CheckAnswer.SameKeepMine)) && isSelectedChip(label(missing, CheckAnswer.Keep))
        }
        tapText("Next")
        waitForText("Categorise")
        tapText("Import")
        waitForText("Import complete", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Matches your statement: £14.79 on 10 Oct ✓")
        waitForText("What the checks changed: 1 matched to your own entry, difference of £12.34 recorded")
        shot("import-check-07-summary")

        val ledger = ledger()
        assertNotNull(ledger.single { it.id == tesco }.importKey)
        val update = ledger.single { it.kind == TxKind.RECONCILIATION }
        assertEquals(Money(1_234), update.amount)
        assertEquals(date(10), update.date)
        val records = blocking { appContainer.accountRepository.observeReconciliationsForAccount(current).first() }
        assertEquals(1, records.size)
        assertEquals(update.id, records.single().transactionId)
        assertNull(ledger.firstOrNull { it.label == "TESCO STORES 1234" })
    }
}
