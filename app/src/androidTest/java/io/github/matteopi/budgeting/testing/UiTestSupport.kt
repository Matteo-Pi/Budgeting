package io.github.matteopi.budgeting.testing

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.model.Account
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Frequency
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.PostingMode
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.RecurringRule
import io.github.matteopi.budgeting.core.model.Scenario
import io.github.matteopi.budgeting.core.model.ScheduledItem
import io.github.matteopi.budgeting.core.model.SinkingFund
import io.github.matteopi.budgeting.core.model.TermPeriod
import io.github.matteopi.budgeting.core.model.Transaction
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.ui.quickadd.QuickAddActivity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/*
 * Shared helpers for the end-to-end UI tests (the classes in the androidTest "ui" package).
 *
 * Three groups:
 *  1. Access to the real app container and seeding of data (top-level functions, no rule needed).
 *  2. Formatting helpers that deliberately do NOT reuse the app's own formatters, so a bug in the
 *     app's formatting cannot hide itself.
 *  3. [UiTest], the base class every UI test extends: the Compose rule, waiting/scrolling/tapping
 *     helpers, date-picker and drop-down helpers, keypad typing and screenshots.
 */

// ---------------------------------------------------------------------------------------------
// 1. Container access and seeding
// ---------------------------------------------------------------------------------------------

/** How long UI waits may take before a test fails (the emulator can be slow, especially the first frame). */
const val UI_TIMEOUT_MS = 15_000L

/** Waits that depend on the forecast engine (safe-to-spend, charts, scenario comparison). */
const val HEAVY_TIMEOUT_MS = 40_000L

/** The running app's Application object. */
val testApp: BudgetApp get() = ApplicationProvider.getApplicationContext()

/** The real dependency container, exactly what the screens use. */
val appContainer: AppContainer get() = testApp.container

/** Runs [block] to completion on the calling (instrumentation) thread. Never call from the main thread. */
fun <T> blocking(timeoutMs: Long = 60_000, block: suspend () -> T): T =
    runBlocking { withTimeout(timeoutMs) { block() } }

/**
 * Waits for the app's own start-up seeding of the default categories and returns name -> id.
 * (BudgetApp seeds them asynchronously; seeding twice concurrently could duplicate them, so tests
 * wait instead of seeding themselves.)
 */
fun awaitCategories(): Map<String, Long> = blocking(30_000) {
    appContainer.categoryRepository.observeCategories().first { it.isNotEmpty() }.associate { it.name to it.id }
}

/** Marks onboarding as done so the app starts on Home. */
fun seedOnboardingDone() = blocking { appContainer.settingsStore.setOnboardingDone(true) }

private var stampCounter = 0L

/** Strictly increasing creation times, so transactions created in a test keep a stable order within a day. */
private fun nextStamp(): Long {
    stampCounter += 1
    return System.currentTimeMillis() + stampCounter
}

/** Creates an account with an opening balance dated [openedOn]; returns its id. */
fun seedAccount(
    name: String,
    type: AccountType,
    balancePence: Long,
    overdraftPence: Long = 0,
    includeInPool: Boolean = true,
    openedOn: LocalDate = LocalDate.now().minusDays(30),
): Long = blocking {
    awaitCategories()
    appContainer.accountRepository.createAccount(
        account = Account(
            name = name,
            type = type,
            overdraftLimit = Money(overdraftPence),
            includeInSafeToSpend = includeInPool,
        ),
        openingBalance = Money(balancePence),
        date = openedOn,
    )
}

/** Adds a normal transaction (negative pence = money out). [category] is a default category name, or null. */
fun seedTransaction(
    accountId: Long,
    pence: Long,
    date: LocalDate,
    category: String?,
    label: String,
    note: String = "",
): Long {
    val categories = awaitCategories()
    val categoryId = category?.let { requireNotNull(categories[it]) { "No default category called '$it'" } }
    return blocking {
        appContainer.transactionRepository.add(
            Transaction(
                accountId = accountId,
                amount = Money(pence),
                date = date,
                createdAtEpochMillis = nextStamp(),
                categoryId = categoryId,
                label = label,
                note = note,
                kind = TxKind.NORMAL,
            ),
        )
    }
}

/** Moves [pence] (positive) from one account to another as a two-legged transfer. */
fun seedTransfer(fromId: Long, toId: Long, pence: Long, date: LocalDate, label: String) = blocking {
    appContainer.transactionRepository.addTransfer(fromId, toId, Money(pence), date, label)
}

fun seedTerm(name: String, start: LocalDate, end: LocalDate): Long = blocking {
    appContainer.planRepository.addTermPeriod(TermPeriod(name = name, startDate = start, endDate = end))
}

/** A recurring rule; [pence] is signed (negative = out). The repository applies its own "posted through" rule. */
fun seedRecurring(
    label: String,
    accountId: Long,
    category: String?,
    pence: Long,
    frequency: Frequency,
    start: LocalDate,
    postingMode: PostingMode = if (pence < 0) PostingMode.AUTO else PostingMode.CONFIRM,
    isMajorIncome: Boolean = false,
): Long {
    val categoryId = category?.let { requireNotNull(awaitCategories()[it]) }
    return blocking {
        appContainer.planRepository.addRecurringRule(
            RecurringRule(
                label = label,
                accountId = accountId,
                categoryId = categoryId,
                amount = Money(pence),
                frequency = frequency,
                startDate = start,
                postingMode = postingMode,
                isMajorIncome = isMajorIncome,
            ),
        )
    }
}

fun seedScheduled(
    label: String,
    accountId: Long,
    category: String?,
    pence: Long,
    date: LocalDate,
    postingMode: PostingMode = if (pence < 0) PostingMode.AUTO else PostingMode.CONFIRM,
    isMajorIncome: Boolean = false,
): Long {
    val categoryId = category?.let { requireNotNull(awaitCategories()[it]) }
    return blocking {
        appContainer.planRepository.addScheduledItem(
            ScheduledItem(
                label = label,
                accountId = accountId,
                categoryId = categoryId,
                amount = Money(pence),
                date = date,
                postingMode = postingMode,
                isMajorIncome = isMajorIncome,
            ),
        )
    }
}

fun seedSinkingFund(name: String, targetPence: Long, start: LocalDate, targetDate: LocalDate): Long = blocking {
    appContainer.planRepository.addSinkingFund(
        SinkingFund(name = name, target = Money(targetPence), startDate = start, targetDate = targetDate),
    )
}

fun seedPreset(
    label: String,
    pence: Long,
    flow: CategoryFlow,
    category: String,
    accountId: Long? = null,
    sortOrder: Int = 0,
): Long {
    val categoryId = requireNotNull(awaitCategories()[category]) { "No default category called '$category'" }
    return blocking {
        appContainer.presetRepository.add(
            Preset(
                label = label,
                amount = Money(pence),
                flow = flow,
                categoryId = categoryId,
                accountId = accountId,
                showOnWidget = true,
                sortOrder = sortOrder,
            ),
        )
    }
}

fun seedScenario(scenario: Scenario): Long = blocking { appContainer.scenarioRepository.addScenario(scenario) }

/** What [seedSmallLedger] created; every date is relative to [today]. */
class SmallLedger(
    val today: LocalDate,
    val lloydsId: Long,
    val santanderId: Long,
    val lloydsPence: Long,
    val santanderPence: Long,
)

/**
 * A tiny ledger for Activity tests: Lloyds current GBP 1,000 (overdraft 500) and Santander current
 * GBP 250, both opened 30 days ago, then Coffee -3.20 (today), Tesco -24.60 (yesterday), a GBP 50
 * transfer "Top up" Lloyds to Santander (2 days ago), Bus pass -18.00 (3 days ago) and Cafe shifts
 * +100.00 (5 days ago). Balances today: Lloyds GBP 922.20, Santander GBP 382.00.
 */
fun seedSmallLedger(): SmallLedger {
    val today = LocalDate.now()
    seedOnboardingDone()
    val lloyds = seedAccount("Lloyds current", AccountType.CURRENT, 100_000, overdraftPence = 50_000)
    val santander = seedAccount("Santander current", AccountType.CURRENT, 25_000)
    seedTransaction(santander, 10_000, today.minusDays(5), "Wages", "Cafe shifts")
    seedTransaction(santander, -1_800, today.minusDays(3), "Transport", "Bus pass")
    seedTransfer(lloyds, santander, 5_000, today.minusDays(2), "Top up")
    seedTransaction(lloyds, -2_460, today.minusDays(1), "Groceries", "Tesco")
    seedTransaction(lloyds, -320, today, "Eating out", "Coffee")
    return SmallLedger(today, lloyds, santander, 100_000 - 5_000 - 2_460 - 320, 25_000 + 10_000 - 1_800 + 5_000)
}

/** What [seedStandardBudget] created, so tests can assert against exact figures. */
class SeededBudget(
    val today: LocalDate,
    val lloydsId: Long,
    val savingsId: Long,
    val santanderId: Long,
    /** Raw balance of each account today, in pence. */
    val lloydsPence: Long,
    val savingsPence: Long,
    val santanderPence: Long,
) {
    val totalPence: Long get() = lloydsPence + savingsPence + santanderPence
    val total: String get() = gbp(totalPence)
}

/**
 * A realistic 3-account budget with 12 weeks of history, used by most tests:
 *  - Lloyds current (overdraft GBP 1,500) GBP 6,579.30; Santander Savings GBP 400.00 (both untouched);
 *  - Santander current: GBP 500.00 opening, then every week groceries -42.50, eating out -18.00,
 *    transport -12.00, wages +120.00 and, every other week, nights out -25.00 => GBP 920.00 today;
 *  - terms Autumn (today-30..today+45) and Spring (today+90..today+160);
 *  - rent -550 monthly from Lloyds starting in 10 days; two student-loan instalments (+1,600, to be
 *    confirmed) in 50 and 150 days.
 * Total money today: GBP 7,899.30. Onboarding is marked done, so the app starts on Home.
 */
fun seedStandardBudget(): SeededBudget {
    val today = LocalDate.now()
    seedOnboardingDone()
    val start = today.minusDays(84)
    val lloyds = seedAccount("Lloyds current", AccountType.CURRENT, 657_930, overdraftPence = 150_000, openedOn = start)
    val savings = seedAccount("Santander Savings", AccountType.SAVINGS, 40_000, openedOn = start)
    val santander = seedAccount("Santander current", AccountType.CURRENT, 50_000, openedOn = start)

    var santanderPence = 50_000L
    fun spend(pence: Long, date: LocalDate, category: String, label: String) {
        seedTransaction(santander, pence, date, category, label)
        santanderPence += pence
    }
    for (week in 0 until 12) {
        val weekEnd = today.minusDays(7L * week)
        spend(-4_250, weekEnd.minusDays(1), "Groceries", "Tesco")
        spend(-1_800, weekEnd.minusDays(2), "Eating out", "Lunch")
        spend(-1_200, weekEnd.minusDays(3), "Transport", "Bus")
        spend(12_000, weekEnd.minusDays(4), "Wages", "Cafe shifts")
        if (week % 2 == 0) spend(-2_500, weekEnd.minusDays(5), "Nights out", "Pub")
    }

    seedTerm("Autumn", today.minusDays(30), today.plusDays(45))
    seedTerm("Spring", today.plusDays(90), today.plusDays(160))
    seedRecurring("Rent", lloyds, "Rent & bills", -55_000, Frequency.MONTHLY, today.plusDays(10))
    seedScheduled("Student loan", santander, "Student loan", 160_000, today.plusDays(50), isMajorIncome = true)
    seedScheduled("Student loan", santander, "Student loan", 160_000, today.plusDays(150), isMajorIncome = true)

    return SeededBudget(today, lloyds, savings, santander, 657_930, 40_000, santanderPence)
}

// ---------------------------------------------------------------------------------------------
// 2. Formatting helpers (independent of the app's own formatting code)
// ---------------------------------------------------------------------------------------------

/** "1234.5 pounds" as the app shows money: gbp(123_450) == "£1,234.50", gbp(-320) == "-£3.20". */
fun gbp(pence: Long): String {
    val abs = abs(pence)
    val pounds = String.format(Locale.ENGLISH, "%,d", abs / 100)
    val sign = if (pence < 0) "-" else ""
    return "$sign£$pounds.${(abs % 100).toString().padStart(2, '0')}"
}

/** Whole pounds only, pence dropped: gbpWhole(123_450) == "£1,234". */
fun gbpWhole(pence: Long): String {
    val abs = abs(pence)
    val sign = if (pence < 0) "-" else ""
    return "$sign£" + String.format(Locale.ENGLISH, "%,d", abs / 100)
}

private val FIELD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** How a date field shows a date: "Mon 28 Sep 2026". */
fun LocalDate.asFieldText(): String = format(FIELD_DATE)

/** "28 Sep". */
fun LocalDate.asShortText(): String = format(SHORT_DATE)

/** An Activity day header for an older day: "Mon 28 Sep". */
fun LocalDate.asHeaderText(): String = format(HEADER_DATE)

/** A [SemanticsMatcher] for any node whose text fully matches [regex]. */
fun hasTextMatching(regex: Regex): SemanticsMatcher = SemanticsMatcher("text matches $regex") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { regex.matches(it.text) } == true
}

// ---------------------------------------------------------------------------------------------
// 3. The base class for UI tests
// ---------------------------------------------------------------------------------------------

/**
 * Base class for the end-to-end UI tests. Uses an *empty* Compose rule: tests seed data first and
 * then launch [MainActivity] (or [QuickAddActivity]) themselves through [launchMain] /
 * [launchQuickAdd]. On failure a "FAILED-..." screenshot of the screen as it was is saved, and the
 * failure message lists the texts currently on screen.
 */
abstract class UiTest {
    val compose = createEmptyComposeRule()

    private val scenarios = mutableListOf<ActivityScenario<*>>()

    private val lifecycleRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    runCatching {
                        val cls = description.className.substringAfterLast('.')
                        Screenshots.take("FAILED-$cls-${description.methodName}")
                    }
                    throw t
                } finally {
                    scenarios.asReversed().forEach { runCatching { it.close() } }
                    scenarios.clear()
                }
            }
        }
    }

    @get:org.junit.Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(lifecycleRule)

    // ----- launching -----

    fun launchMain(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenarios += scenario
        return scenario
    }

    fun launchQuickAdd(presetId: Long? = null, flow: CategoryFlow? = null): ActivityScenario<QuickAddActivity> {
        val intent = QuickAddActivity.intent(testApp, presetId, flow)
        val scenario = ActivityScenario.launch<QuickAddActivity>(intent)
        scenarios += scenario
        return scenario
    }

    /** Launches the app and waits until Home has appeared (the balance figure may still be settling). */
    fun launchHome() {
        launchMain()
        waitForExists(hasTestTag(YOUR_MONEY_TAG), "Home's 'Your money' figure", HEAVY_TIMEOUT_MS)
    }

    // ----- matchers -----

    fun txt(text: String, substring: Boolean = false): SemanticsMatcher = hasText(text, substring = substring)

    /** A clickable node (button, chip, list row, tab) whose text is exactly [text]. */
    fun clickableText(text: String, substring: Boolean = false): SemanticsMatcher =
        hasText(text, substring = substring) and hasClickAction()

    /** Like [clickableText] but only inside a dialog (so a same-named button behind it is ignored). */
    fun dialogButton(text: String): SemanticsMatcher =
        hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())

    /** A text field or read-only picker field with this label. */
    fun field(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

    /** An item of an open drop-down menu. */
    fun menuItem(text: String): SemanticsMatcher =
        hasText(text) and hasClickAction() and hasAnyAncestor(isPopup())

    // ----- queries -----

    fun count(matcher: SemanticsMatcher): Int =
        runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().size }.getOrDefault(0)

    fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    fun textExists(text: String, substring: Boolean = false): Boolean = exists(txt(text, substring))

    /** The first text of the first node matching [matcher], or null. */
    fun firstText(matcher: SemanticsMatcher): String? = runCatching {
        compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    }.getOrNull()

    /** The top edge (in px, relative to the root) of the first node matching [matcher]; used to check ordering on screen. */
    fun topOf(matcher: SemanticsMatcher): Float =
        compose.onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot.top

    /** The current value of the field with this label, or null. */
    fun fieldValue(label: String, index: Int = 0): String? = runCatching {
        compose.onAllNodes(field(label)).fetchSemanticsNodes().getOrNull(index)
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }.getOrNull()

    /** Every text currently on screen (all windows), one per line, for failure messages. */
    fun screenDump(): String = runCatching {
        val sb = StringBuilder()
        fun walk(node: SemanticsNode, depth: Int) {
            val parts = ArrayList<String>()
            node.config.getOrNull(SemanticsProperties.Text)?.let { list -> parts += list.map { it.text } }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { parts += "value='${it.text}'" }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { list ->
                if (list.isNotEmpty()) parts += "desc=" + list.joinToString("|")
            }
            if (parts.isNotEmpty()) sb.append("  ".repeat(minOf(depth, 4))).append(parts.joinToString(" | ")).append('\n')
            node.children.forEach { walk(it, depth + 1) }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach { walk(it, 0) }
        sb.toString().take(4000)
    }.getOrElse { "(screen dump failed: $it)" }

    // ----- waiting -----

    private fun timeout(what: String, timeoutMs: Long): Nothing =
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what.\nOn screen:\n${screenDump()}")

    /** Waits until [condition] is true (it is polled; exceptions count as false). */
    fun waitFor(what: String, timeoutMs: Long = UI_TIMEOUT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMs) { runCatching(condition).getOrDefault(false) }
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            timeout(what, timeoutMs)
        }
    }

    /**
     * Waits until a node matching [matcher] exists. If it is not there after a couple of seconds, lazy
     * lists on screen are scrolled to look for it (their off-screen items are not composed).
     */
    fun waitForExists(matcher: SemanticsMatcher, what: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        val start = System.nanoTime() / 1_000_000
        var scrolled = false
        while (true) {
            if (exists(matcher)) return
            val elapsed = System.nanoTime() / 1_000_000 - start
            if (elapsed > timeoutMs) timeout(what, timeoutMs)
            if (!scrolled && elapsed > 2_000) {
                scrolled = true
                scrollLazyListsTo(matcher)
                continue
            }
            compose.waitForIdle()
            Thread.sleep(50)
        }
    }

    fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = UI_TIMEOUT_MS) =
        waitForExists(txt(text, substring), "text '$text'" + if (substring) " (substring)" else "", timeoutMs)

    fun waitUntilGone(matcher: SemanticsMatcher, what: String, timeoutMs: Long = UI_TIMEOUT_MS) =
        waitFor("$what to disappear", timeoutMs) { !exists(matcher) }

    fun waitForNoText(text: String, substring: Boolean = false, timeoutMs: Long = UI_TIMEOUT_MS) =
        waitUntilGone(txt(text, substring), "text '$text'", timeoutMs)

    /** Asserts something does NOT show up, after giving the UI a moment to (wrongly) produce it. */
    fun assertNeverShows(matcher: SemanticsMatcher, what: String, forMs: Long = 2_500) {
        compose.waitForIdle()
        Thread.sleep(forMs)
        compose.waitForIdle()
        if (exists(matcher)) throw AssertionError("$what should not be on screen, but it is.\nOn screen:\n${screenDump()}")
    }

    // ----- scrolling -----

    private val verticalLazyList: SemanticsMatcher =
        hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollLazyListsTo(matcher: SemanticsMatcher) {
        val lists = runCatching { compose.onAllNodes(verticalLazyList).fetchSemanticsNodes().size }.getOrDefault(0)
        for (i in 0 until lists) {
            runCatching { compose.onAllNodes(verticalLazyList)[i].performScrollToNode(matcher) }
            if (exists(matcher)) return
        }
    }

    /** Scrolls so that the matching node is on screen (lazy lists are searched; failures to scroll are ignored). */
    fun scrollIntoView(matcher: SemanticsMatcher, index: Int = 0) {
        if (!exists(matcher)) scrollLazyListsTo(matcher)
        // performScrollTo only scrolls the closest scrolling parent, so a chip in a sideways-scrolling
        // row can stay below the fold of the screen's own scroll. Bring the row itself into view first.
        runCatching {
            compose.onAllNodes(hasScrollAction() and hasAnyDescendant(matcher) and hasAnyAncestor(hasScrollAction()))
                .onFirst()
                .performScrollTo()
        }
        runCatching { compose.onAllNodes(matcher)[index].performScrollTo() }
    }

    // ----- tapping and typing -----

    /** Waits for [matcher], scrolls it into view and taps the [index]th match. */
    fun tap(matcher: SemanticsMatcher, what: String, index: Int = 0) {
        waitForExists(matcher, what)
        scrollIntoView(matcher, index)
        try {
            compose.onAllNodes(matcher)[index].performClick()
        } catch (e: AssertionError) {
            throw AssertionError("Could not tap $what (#$index): ${e.message}\nOn screen:\n${screenDump()}", e)
        }
        compose.waitForIdle()
    }

    fun tapText(text: String, index: Int = 0) = tap(clickableText(text), "'$text'", index)

    fun tapInDialog(text: String, index: Int = 0) = tap(dialogButton(text), "dialog button '$text'", index)

    fun tapBack() = tap(hasContentDescription("Back") and hasClickAction(), "the Back button")

    fun tapIcon(contentDescription: String, index: Int = 0) =
        tap(hasContentDescription(contentDescription) and hasClickAction(), "icon '$contentDescription'", index)

    fun tapField(label: String, index: Int = 0) = tap(field(label), "field '$label'", index)

    fun tapMenuItem(text: String) = tap(menuItem(text), "menu item '$text'")

    /** Replaces the content of the text field with this label. */
    fun type(label: String, value: String, index: Int = 0) {
        waitForExists(field(label), "text field '$label'")
        scrollIntoView(field(label), index)
        compose.onAllNodes(field(label))[index].performTextReplacement(value)
        compose.waitForIdle()
    }

    fun assertFieldValue(label: String, expected: String, index: Int = 0) {
        waitFor("field '$label' to read '$expected' (it reads '${fieldValue(label, index)}')") {
            fieldValue(label, index) == expected
        }
    }

    /** Opens the drop-down picker with this label and taps [option]; returns once the menu has closed. */
    fun choose(fieldLabel: String, option: String, index: Int = 0) {
        tapField(fieldLabel, index)
        tapMenuItem(option)
        waitUntilGone(isPopup(), "the drop-down menu")
        compose.waitForIdle()
    }

    /**
     * Opens a bottom-bar tab ("Home", "Activity", "Forecast", "Plan", "Insights") at its own screen. A
     * tab remembers the screen that was open on it (e.g. an account page opened from Home) and brings
     * it back; no tab is highlighted there, and tapping the tab again goes to the tab's own screen.
     */
    fun goToTab(name: String) {
        val tab = clickableText(name)
        val selectedTab = hasText(name) and isSelected()
        tap(tab, "the '$name' tab")
        if (!exists(selectedTab)) tap(tab, "the '$name' tab again")
        waitForExists(selectedTab, "the '$name' tab to be selected")
    }

    fun openSettings() {
        tapIcon("Settings")
        waitForText("Money display")
    }

    /** Index, among all switches on screen, of the one drawn on the same line as the text [label]. */
    private fun switchIndexNextTo(label: String): Int {
        val labelY = compose.onAllNodes(hasText(label)).fetchSemanticsNodes().first().boundsInRoot.center.y
        val switches = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
        return switches.indices.minByOrNull { abs(switches[it].boundsInRoot.center.y - labelY) }
            ?: throw AssertionError("No switch on screen next to '$label'.\nOn screen:\n${screenDump()}")
    }

    /** Taps the switch that sits on the same line as the text [label] (switch rows are not merged nodes). */
    fun tapSwitchNextTo(label: String) {
        waitForExists(hasText(label), "the text '$label'")
        scrollIntoView(hasText(label))
        compose.onAllNodes(isToggleable())[switchIndexNextTo(label)].performClick()
        compose.waitForIdle()
    }

    /** True if the switch next to [label] is currently on. */
    fun isSwitchOnNextTo(label: String): Boolean {
        val node = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()[switchIndexNextTo(label)]
        return node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    fun waitForSwitch(label: String, on: Boolean) =
        waitFor("the switch next to '$label' to be ${if (on) "on" else "off"}") { isSwitchOnNextTo(label) == on }

    /**
     * Taps the [target] node that is drawn closest (vertically) to the [anchor] node, for list rows
     * whose pieces are separate nodes, e.g. a preset row's title and its Delete icon. Rows are far
     * enough apart that the nearest match is the one in the anchor's own row.
     */
    fun tapNearestTo(anchor: SemanticsMatcher, target: SemanticsMatcher, what: String) {
        waitForExists(anchor, "the row anchor for $what")
        waitForExists(target, what)
        scrollIntoView(anchor)
        val anchorY = compose.onAllNodes(anchor).fetchSemanticsNodes().first().boundsInRoot.center.y
        val candidates = compose.onAllNodes(target).fetchSemanticsNodes()
        val index = candidates.indices.minByOrNull { abs(candidates[it].boundsInRoot.center.y - anchorY) }
            ?: throw AssertionError("No $what near the anchor.\nOn screen:\n${screenDump()}")
        compose.onAllNodes(target)[index].performClick()
        compose.waitForIdle()
    }

    fun assertEnabled(matcher: SemanticsMatcher, what: String) {
        waitForExists(matcher, what)
        compose.onAllNodes(matcher)[0].assertIsEnabled()
    }

    fun assertDisabled(matcher: SemanticsMatcher, what: String) {
        waitForExists(matcher, what)
        compose.onAllNodes(matcher)[0].assertIsNotEnabled()
    }

    /** Sends the system Back key (used to leave QuickAdd etc.). */
    fun pressSystemBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
    }

    // ----- date picker -----

    /** Anything inside the open date-picker dialog (recognised by its OK button, so a dialog behind it is ignored). */
    val inDatePicker: SemanticsMatcher = hasAnyAncestor(hasAnyDescendant(hasText("OK") and hasClickAction()))

    fun datePickerButton(text: String): SemanticsMatcher = hasText(text) and hasClickAction() and inDatePicker

    /**
     * The cell for [date] in the month the picker shows. Material 3 gives each day cell the full date
     * as its text for accessibility ("Tuesday, September 15, 2026" in the US, "Tuesday 15 September
     * 2026" in the UK), not the bare number drawn in it, so the cell is matched on the day number,
     * full month name and year. (Disabled days have no click action, so it is not required here.)
     */
    fun dayInDatePicker(date: LocalDate): SemanticsMatcher {
        val month = date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        val year = date.year.toString()
        val day = Regex("(?<![0-9])${date.dayOfMonth}(?![0-9])")
        return SemanticsMatcher("the date-picker cell for $date") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any {
                val text = it.text
                text.contains(month, ignoreCase = true) && text.contains(year) && day.containsMatchIn(text.replace(year, ""))
            } == true
        } and inDatePicker
    }

    /**
     * Drives an open Material 3 date-picker dialog: [shown] is the date it opens on (its month is
     * what is displayed), [target] the day to choose. Navigates months, taps the day, presses OK and
     * waits for the dialog to close.
     */
    fun chooseInDatePicker(shown: LocalDate, target: LocalDate) {
        waitForExists(datePickerButton("OK"), "the date picker's OK button")
        val months = ChronoUnit.MONTHS.between(YearMonth.from(shown), YearMonth.from(target)).toInt()
        val direction = if (months > 0) "next month" else "previous month"
        repeat(abs(months)) {
            tap(
                hasContentDescription(direction, substring = true, ignoreCase = true) and hasClickAction(),
                "the date picker's '$direction' button",
            )
        }
        tap(dayInDatePicker(target), "day ${target.dayOfMonth} in the date picker")
        tap(datePickerButton("OK"), "the date picker's OK button")
        waitUntilGone(datePickerButton("OK"), "the date picker")
    }

    /**
     * Sets the date field labelled [label] (which currently shows [shown]) to [target] through the
     * real picker, then checks the field reads the new date.
     */
    fun pickDate(label: String, shown: LocalDate, target: LocalDate, index: Int = 0) {
        tapField(label, index)
        chooseInDatePicker(shown, target)
        assertFieldValue(label, target.asFieldText(), index)
    }

    // ----- keypad (QuickAdd) -----

    /** Presses the QuickAdd keypad keys for [amount], e.g. "12.50". */
    fun typeOnKeypad(amount: String) {
        amount.forEach { ch ->
            tap(hasText(ch.toString()) and hasClickAction(), "keypad key '$ch'")
        }
    }

    // ----- Home helpers -----

    /** Waits until Home's "Your money" figure reads [expected]. */
    fun expectYourMoney(expected: String, timeoutMs: Long = HEAVY_TIMEOUT_MS) {
        waitFor("'Your money' to read $expected (it reads '${firstText(hasTestTag(YOUR_MONEY_TAG))}')", timeoutMs) {
            firstText(hasTestTag(YOUR_MONEY_TAG)) == expected
        }
    }

    /** Waits for an account row on Home showing both [name] and [balance]. */
    fun expectAccountRow(name: String, balance: String) {
        waitForExists(
            hasText(name) and hasText(balance) and hasClickAction(),
            "the account row '$name' showing $balance",
            HEAVY_TIMEOUT_MS,
        )
    }

    /** The whole-pound safe-to-spend per week shown in Home's hero card, or null while it is not shown. */
    fun safeToSpendPerWeek(): Long? {
        val text = firstText(hasTextMatching(Regex("£[0-9,]+ / week"))) ?: return null
        return text.removePrefix("£").substringBefore(" / week").replace(",", "").toLongOrNull()
    }

    // ----- screenshots -----

    fun shot(name: String) {
        compose.waitForIdle()
        Screenshots.take(name)
    }

    fun isSelectedChip(text: String): Boolean = exists(hasText(text) and isSelected())

    companion object {
        /** Test tag on Home's "Your money" figure (HomeScreen.kt). */
        const val YOUR_MONEY_TAG = "your-money"

        /** Test tag on the balance headline of the account detail screen (AccountDetailScreen.kt). */
        const val ACCOUNT_BALANCE_TAG = "account-balance"
    }
}

/** True once [scenario]'s activity has finished. */
fun ActivityScenario<*>.isFinished(): Boolean = runCatching { state.name == "DESTROYED" }.getOrDefault(false)
