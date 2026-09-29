package io.github.matteopi.budgeting.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.core.forecast.CommittedFlow
import io.github.matteopi.budgeting.core.forecast.FlowSource
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.forecast.SafeToSpend
import io.github.matteopi.budgeting.core.model.Flow as CategoryFlow
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.Preset
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget's layouts (docs/PLAN.md §6.7), checked with the Glance widget testing library: the
 * composables are rendered at the three documented sizes and the text nodes inspected. No widget is
 * placed on a home screen and no database is involved, so only [WidgetContent] and [MessageContent]
 * are covered here, not the state loading in `SafeToSpendWidget.provideGlance`.
 */
@RunWith(AndroidJUnit4::class)
class SafeToSpendWidgetContentTest {

    // ---- small: amount and "+" only -----------------------------------------------------------

    @Test
    fun smallWidgetShowsTheWeeklyAmountAndThePlusButton() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult()) }

            onNode(hasText("Safe to spend")).assertExists()
            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("+")).assertExists()
        }
    }

    @Test
    fun smallWidgetHasNoDaysLine() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult(daysUntilIncome = 50)) }

            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("50 days to Student loan")).assertDoesNotExist()
        }
    }

    @Test
    fun smallWidgetShowsNoPresetButtonsEvenWhenPresetsAreGiven() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult(), presets = presets(3)) }

            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("Preset 1")).assertDoesNotExist()
            onNode(hasText("Preset 2")).assertDoesNotExist()
            onNode(hasText("Preset 3")).assertDoesNotExist()
        }
    }

    // ---- wide: adds the days to the next major income -----------------------------------------

    @Test
    fun wideWidgetAddsTheDaysToTheNextMajorIncome() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { Widget(forecastResult(daysUntilIncome = 50, incomeLabel = "Student loan")) }

            onNode(hasText("Safe to spend")).assertExists()
            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("50 days to Student loan")).assertExists()
            onNode(hasText("+")).assertExists()
        }
    }

    @Test
    fun wideWidgetSaysDayNotDaysForOneDay() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { Widget(forecastResult(daysUntilIncome = 1, incomeLabel = "Student loan")) }

            onNode(hasText("1 day to Student loan")).assertExists()
            onNode(hasText("1 days to Student loan")).assertDoesNotExist()
        }
    }

    @Test
    fun wideWidgetCallsAnUnnamedIncomeNextIncome() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { Widget(forecastResult(daysUntilIncome = 3, incomeLabel = null)) }

            onNode(hasText("3 days to next income")).assertExists()
        }
    }

    @Test
    fun wideWidgetShowsNoPresetButtonsEvenWhenPresetsAreGiven() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { Widget(forecastResult(), presets = presets(3)) }

            onNode(hasText("50 days to Student loan")).assertExists()
            onNode(hasText("Preset 1")).assertDoesNotExist()
            onNode(hasText("Preset 2")).assertDoesNotExist()
            onNode(hasText("Preset 3")).assertDoesNotExist()
        }
    }

    // ---- tall: adds up to three preset buttons ------------------------------------------------

    @Test
    fun tallWidgetShowsTheAmountTheDaysLineAndAButtonForEachPreset() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(TALL)
            provideComposable { Widget(forecastResult(), presets = presets(3)) }

            onNode(hasText("Safe to spend")).assertExists()
            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("50 days to Student loan")).assertExists()
            onNode(hasText("Preset 1")).assertExists()
            onNode(hasText("Preset 2")).assertExists()
            onNode(hasText("Preset 3")).assertExists()
        }
    }

    @Test
    fun tallWidgetShowsAtMostThreePresetButtons() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(TALL)
            provideComposable { Widget(forecastResult(), presets = presets(5)) }

            onNode(hasText("Preset 1")).assertExists()
            onNode(hasText("Preset 2")).assertExists()
            onNode(hasText("Preset 3")).assertExists()
            onNode(hasText("Preset 4")).assertDoesNotExist()
            onNode(hasText("Preset 5")).assertDoesNotExist()
        }
    }

    @Test
    fun tallWidgetShowsOnlyTheButtonsItHasWhenThereAreFewerThanThreePresets() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(TALL)
            provideComposable { Widget(forecastResult(), presets = presets(1)) }

            onNode(hasText("Preset 1")).assertExists()
            onNode(hasText("Preset 2")).assertDoesNotExist()
        }
    }

    @Test
    fun tallWidgetWithNoPresetsStillShowsTheAmountAndTheDaysLine() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(TALL)
            provideComposable { Widget(forecastResult(), presets = emptyList()) }

            onNode(hasText("Safe to spend")).assertExists()
            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("50 days to Student loan")).assertExists()
        }
    }

    // ---- hide amounts -------------------------------------------------------------------------

    @Test
    fun hiddenAmountsShowBulletsInsteadOfTheNumber() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult(weekly = Money.pounds(386)), hideAmounts = true) }

            onNode(hasText("Safe to spend")).assertExists()
            onNode(hasText(HIDDEN_AMOUNT)).assertExists()
            onNode(hasText("£386/wk")).assertDoesNotExist()
            onNode(hasText("£386")).assertDoesNotExist()
        }
    }

    @Test
    fun hiddenAmountsStayHiddenOnTheTallLayoutToo() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(TALL)
            provideComposable { Widget(forecastResult(), presets = presets(3), hideAmounts = true) }

            onNode(hasText(HIDDEN_AMOUNT)).assertExists()
            onNode(hasText("£386/wk")).assertDoesNotExist()
            onNode(hasText("£386")).assertDoesNotExist()
            // Only the money is hidden: the days line and the preset buttons are still there.
            onNode(hasText("50 days to Student loan")).assertExists()
            onNode(hasText("Preset 1")).assertExists()
        }
    }

    // ---- how the amount is written ------------------------------------------------------------

    @Test
    fun theAmountDropsPenceInsteadOfRoundingUp() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult(weekly = Money(38_699))) }

            onNode(hasText("£386/wk")).assertExists()
            onNode(hasText("£387/wk")).assertDoesNotExist()
        }
    }

    @Test
    fun theAmountGroupsThousands() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { Widget(forecastResult(weekly = Money.pounds(1_234))) }

            onNode(hasText("£1,234/wk")).assertExists()
        }
    }

    // ---- messages -----------------------------------------------------------------------------

    @Test
    fun setUpMessageShowsItsTextAndNoAmount() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(SMALL)
            provideComposable { GlanceTheme { MessageContent(text = "Set up Budgeting") } }

            onNode(hasText("Set up Budgeting")).assertExists()
            onNode(hasText("Safe to spend")).assertDoesNotExist()
        }
    }

    @Test
    fun openAppMessageShowsItsText() {
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(WIDE)
            provideComposable { GlanceTheme { MessageContent(text = "Open app") } }

            onNode(hasText("Open app")).assertExists()
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private companion object {
        /** The three layouts' sizes, as in docs/PLAN.md §6.7. */
        val SMALL = DpSize(110.dp, 48.dp)
        val WIDE = DpSize(250.dp, 48.dp)
        val TALL = DpSize(250.dp, 110.dp)

        /** What the amount reads as when "Hide widget amounts" is on. */
        const val HIDDEN_AMOUNT = "••••/wk"

        val TODAY: LocalDate = LocalDate.of(2026, 9, 28)

        /** [count] presets labelled "Preset 1", "Preset 2", ..., in the order the widget should show them. */
        fun presets(count: Int): List<Preset> = (1..count).map { n ->
            Preset(
                id = n.toLong(),
                label = "Preset $n",
                amount = Money(100L * n),
                flow = CategoryFlow.OUT,
                categoryId = 1L,
                sortOrder = n,
            )
        }

        /**
         * A forecast result whose only interesting parts are the safe-to-spend figure and the next major income
         * ([daysUntilIncome] days away, called [incomeLabel]; no income at all when [incomeLabel] is null).
         */
        fun forecastResult(
            weekly: Money = Money.pounds(386),
            daysUntilIncome: Int? = 50,
            incomeLabel: String? = "Student loan",
        ): ForecastResult {
            val end = TODAY.plusDays(182)
            val nextIncome = if (daysUntilIncome == null || incomeLabel == null) {
                null
            } else {
                CommittedFlow(
                    date = TODAY.plusDays(daysUntilIncome.toLong()),
                    amount = Money.pounds(1_500),
                    label = incomeLabel,
                    source = FlowSource.SCHEDULED,
                    sourceId = null,
                    categoryId = null,
                    accountId = null,
                    isMajorIncome = true,
                )
            }
            return ForecastResult(
                today = TODAY,
                forecastEnd = end,
                poolBalanceNow = Money.ZERO,
                poolOverdraft = Money.ZERO,
                floor = Money.ZERO,
                days = emptyList(),
                categoryEstimates = emptyList(),
                needsPrediction = emptyList(),
                safeToSpend = SafeToSpend(
                    weekly = weekly,
                    perDay = Money(weekly.pence / 7),
                    horizonEnd = end,
                    nextMajorIncome = nextIncome,
                    daysUntilNextMajorIncome = daysUntilIncome,
                    bindingDate = null,
                    bindingReason = null,
                    shortfall = Money.ZERO,
                    predictedWeeklySpending = Money.ZERO,
                ),
                risk = null,
                budgetPeriods = emptyList(),
                fundReservations = emptyList(),
                committedFlows = emptyList(),
                tier1WeeklyByCategory = emptyMap(),
                historyStart = null,
                completeWeeksOfHistory = 0,
            )
        }
    }
}

/** [WidgetContent] inside the Glance theme, the way the real widget composes it. */
@Composable
private fun Widget(result: ForecastResult, presets: List<Preset> = emptyList(), hideAmounts: Boolean = false) {
    GlanceTheme {
        WidgetContent(result = result, presets = presets, hideAmounts = hideAmounts)
    }
}
