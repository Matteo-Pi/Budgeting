package io.github.matteopi.budgeting.ui

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.HEAVY_TIMEOUT_MS
import io.github.matteopi.budgeting.testing.SantanderPdfFixture
import io.github.matteopi.budgeting.testing.UiTest
import io.github.matteopi.budgeting.testing.appContainer
import io.github.matteopi.budgeting.testing.blocking
import io.github.matteopi.budgeting.testing.seedAccount
import io.github.matteopi.budgeting.testing.seedOnboardingDone
import io.github.matteopi.budgeting.testing.testApp
import io.github.matteopi.budgeting.ui.receipts.ReceiptFiles
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Santander statement PDF shared into the app (an invented one, drawn with `PdfDocument`; see
 * [SantanderPdfFixture]) opens the statement import instead of the receipt review, goes straight to the
 * review with the statement's card, flags the moves between accounts, and imports with the balance check.
 * The system file picker is not opened here: the share target is how the PDF gets in.
 */
@RunWith(AndroidJUnit4::class)
class StatementImportPdfTest : UiTest() {
    private val shared = File(ReceiptFiles.directory(testApp), "ui-test-statement.pdf")

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

    @Test
    fun aSharedSantanderStatementIsReviewedAndImportedWithTheBalanceCheck() {
        seedOnboardingDone()
        val current = seedAccount("Santander current", AccountType.CURRENT, 0, openedOn = LocalDate.of(2026, 9, 10))
        seedAccount("Second current", AccountType.CURRENT, 100_000, openedOn = LocalDate.of(2026, 9, 1))
        launchMain(sharedStatement())

        // Straight to the review (no column mapping), with the statement's card and what did not need a second look.
        waitForText("Review rows", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Santander statement · 10 Sep – 10 Oct 2026 · 19 transactions · closing balance £14.79")
        assertTrue("a statement that adds up has no warning", !textExists("Check these against your statement:"))
        waitForExists(hasText("Import into"), "the account picker for the statement")
        waitForExists(hasText("Looks like a move between your accounts"), "the hint on a move between accounts")
        shot("statement-import-01-review")

        // Categorise: moves between accounts get the switch, on by default.
        tapText("Next")
        waitForText("Categorise")
        waitForExists(hasText("Move between my accounts"), "the move-between-accounts switch")
        waitForExists(hasText("Other account"), "the picker for the other account")
        shot("statement-import-02-categorise")

        // Import with the defaults (the other account is "Not in the app"): moves become single transfer legs.
        tapText("Import")
        waitForText("Import complete", timeoutMs = HEAVY_TIMEOUT_MS)
        waitForText("Matches your statement: £14.79 on 10 Oct ✓")
        shot("statement-import-03-summary")

        val ledger = blocking { appContainer.transactionRepository.observeAll().first() }.filter { it.accountId == current }
        assertEquals(4, ledger.count { it.kind == TxKind.TRANSFER })
        assertEquals(15, ledger.count { it.kind == TxKind.NORMAL })
        assertTrue(ledger.filter { it.kind == TxKind.TRANSFER }.all { it.categoryId == null })
        assertTrue(ledger.filter { it.kind != TxKind.OPENING_BALANCE }.all { it.importBatchId != null })
    }

    @Test
    fun aPdfThatIsNotAStatementStillGoesToTheReceiptReview() {
        seedOnboardingDone()
        seedAccount("Santander current", AccountType.CURRENT, 50_000)
        val invoice = File(ReceiptFiles.directory(testApp), "ui-test-invoice.pdf")
        try {
            SantanderPdfFixture.writeOther(testApp, invoice)
            launchMain(
                Intent(Intent.ACTION_SEND)
                    .setClass(testApp, MainActivity::class.java)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, ReceiptFiles.uriFor(testApp, invoice)),
            )

            // It is opened as a receipt (whatever the receipt reader makes of it), not as a statement.
            waitForText("Review receipt", timeoutMs = HEAVY_TIMEOUT_MS)
            assertTrue(!textExists("Review rows"))
            assertTrue(!exists(hasText("Import into")))
        } finally {
            invoice.delete()
        }
    }
}
