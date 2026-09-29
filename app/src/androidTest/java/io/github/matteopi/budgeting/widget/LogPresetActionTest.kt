package io.github.matteopi.budgeting.widget

import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.model.AccountType
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.core.model.TxKind
import io.github.matteopi.budgeting.testing.DataTestSupport
import io.github.matteopi.budgeting.testing.DataTestSupport.createAccount
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget's one-tap preset button ([LogPresetAction]) against the app's real container: tapping a
 * preset must log it as an ordinary transaction, through the same repositories the app uses.
 *
 * The action also refreshes the widget (`SafeToSpendWidget().updateAll(context)`) and posts a Toast;
 * with no widget placed on a home screen the refresh has nothing to update, so it is called for real.
 */
@RunWith(AndroidJUnit4::class)
class LogPresetActionTest {
    private val container get() = DataTestSupport.container
    private val today: LocalDate = DataTestSupport.today()

    /** The action ignores its [GlanceId]; a real one only exists for a widget placed on a home screen. */
    private object FakeGlanceId : GlanceId

    /** Presses the preset's button on the widget. */
    private suspend fun tap(presetId: Long) {
        LogPresetAction().onAction(DataTestSupport.app, FakeGlanceId, actionParametersOf(PresetIdKey to presetId))
    }

    private suspend fun savedPreset(
        label: String,
        pence: Long,
        flow: CategoryFlow,
        categoryName: String,
        accountId: Long? = null,
    ): Preset {
        val category = DataTestSupport.category(categoryName)
        val id = container.presetRepository.add(
            Preset(label = label, amount = Money(pence), flow = flow, categoryId = category.id, accountId = accountId),
        )
        return container.presetRepository.observePresets().first().single { it.id == id }
    }

    // ---- logging ------------------------------------------------------------------------------

    @Test
    fun tappingAPresetLogsOneNormalTransactionOnTheDefaultAccount() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(50_000))
        assertEquals(current.id, container.accountRepository.defaultAccountId(), "the first account is the default")
        val preset = savedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")

        tap(preset.id)

        val normal = DataTestSupport.transactionsOfKind(TxKind.NORMAL)
        assertEquals(1, normal.size, "exactly one transaction should be logged, but found $normal")
        val logged = normal.single()
        assertEquals("Meal deal", logged.label)
        assertEquals(Money(-385), logged.amount, "spending is stored negative")
        assertEquals(preset.categoryId, logged.categoryId)
        assertEquals(today, logged.date)
        assertEquals(current.id, logged.accountId, "a preset with no account of its own goes to the default account")
        assertEquals(TxKind.NORMAL, logged.kind)
        assertEquals(Money(99_615), DataTestSupport.balance(current.id))
        assertEquals(Money(50_000), DataTestSupport.balance(savings.id))
    }

    @Test
    fun aPresetWithItsOwnAccountIsLoggedOnThatAccountInsteadOfTheDefault() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val savings = createAccount("Savings", AccountType.SAVINGS, opening = Money(50_000))
        assertEquals(current.id, container.accountRepository.defaultAccountId(), "the first account is the default")
        val preset = savedPreset("Bus", 220, CategoryFlow.OUT, "Transport", accountId = savings.id)

        tap(preset.id)

        val normal = DataTestSupport.transactionsOfKind(TxKind.NORMAL)
        assertEquals(1, normal.size, "exactly one transaction should be logged, but found $normal")
        val logged = normal.single()
        assertEquals(savings.id, logged.accountId)
        assertEquals("Bus", logged.label)
        assertEquals(Money(-220), logged.amount)
        assertEquals(preset.categoryId, logged.categoryId)
        assertEquals(today, logged.date)
        assertEquals(Money(49_780), DataTestSupport.balance(savings.id))
        assertEquals(Money(100_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun anIncomePresetIsLoggedAsPositiveMoney() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(10_000))
        val preset = savedPreset("Cafe shift", 12_000, CategoryFlow.IN, "Wages")

        tap(preset.id)

        val logged = DataTestSupport.transactionsOfKind(TxKind.NORMAL).single()
        assertEquals("Cafe shift", logged.label)
        assertEquals(Money(12_000), logged.amount)
        assertEquals(preset.categoryId, logged.categoryId)
        assertEquals(current.id, logged.accountId)
        assertEquals(Money(22_000), DataTestSupport.balance(current.id))
    }

    // ---- things that must not log anything ----------------------------------------------------

    @Test
    fun anUnknownPresetIdLogsNothing() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val preset = savedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")
        val transactionsBefore = DataTestSupport.transactions().size

        tap(preset.id + 1_000)

        assertTrue(DataTestSupport.transactionsOfKind(TxKind.NORMAL).isEmpty(), "nothing should be logged")
        assertEquals(transactionsBefore, DataTestSupport.transactions().size)
        assertEquals(Money(100_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun aPresetDeletedAfterTheWidgetWasDrawnLogsNothing() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        val preset = savedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")
        container.presetRepository.delete(preset.id)

        tap(preset.id)

        assertTrue(DataTestSupport.transactionsOfKind(TxKind.NORMAL).isEmpty(), "nothing should be logged")
        assertEquals(Money(100_000), DataTestSupport.balance(current.id))
    }

    @Test
    fun aTapWithoutAPresetIdLogsNothing() = runBlocking<Unit> {
        val current = createAccount("Current", opening = Money(100_000))
        savedPreset("Meal deal", 385, CategoryFlow.OUT, "Eating out")

        LogPresetAction().onAction(DataTestSupport.app, FakeGlanceId, actionParametersOf())

        assertTrue(DataTestSupport.transactionsOfKind(TxKind.NORMAL).isEmpty(), "nothing should be logged")
        assertEquals(Money(100_000), DataTestSupport.balance(current.id))
    }
}
