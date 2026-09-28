package io.github.matteopi.budgeting.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.MainActivity
import io.github.matteopi.budgeting.core.forecast.ForecastResult
import io.github.matteopi.budgeting.core.model.Preset
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.ui.quickadd.QuickAddActivity
import io.github.matteopi.budgeting.ui.theme.colorSchemeFor
import kotlinx.coroutines.flow.first

private const val MAX_WIDGET_PRESETS = 3
private val SMALL_SIZE = DpSize(110.dp, 48.dp)
private val WIDE_SIZE = DpSize(250.dp, 48.dp)
private val TALL_SIZE = DpSize(250.dp, 110.dp)

// Above these, the layout shows the extra row (wide) / preset buttons (tall) for that size.
private val TALL_HEIGHT_THRESHOLD: Dp = 90.dp
private val WIDE_WIDTH_THRESHOLD: Dp = 180.dp

/**
 * The "safe to spend" home-screen widget: small (amount + "+"), wide (+ days to next income),
 * tall (+ up to 3 preset buttons). See docs/PLAN.md §6.7.
 */
class SafeToSpendWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, WIDE_SIZE, TALL_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BudgetApp).container

        val appSettings = container.settingsStore.appSettings.first()
        val accounts = container.accountRepository.observeAccounts().first()
        val needsSetup = !appSettings.onboardingDone || accounts.isEmpty()

        val presets = if (needsSetup) {
            emptyList()
        } else {
            container.presetRepository.observePresets().first()
                .filter { it.showOnWidget }
                .sortedBy { it.sortOrder }
                .take(MAX_WIDGET_PRESETS)
        }

        val result = if (needsSetup) null else runCatching { container.forecastService.quick() }.getOrNull()

        provideContent {
            WidgetTheme(theme = appSettings.theme, darkMode = appSettings.darkMode) {
                Box(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background)) {
                    when {
                        needsSetup -> MessageContent(text = "Set up Budgeting")
                        result == null -> MessageContent(text = "Open app")
                        else -> WidgetContent(result = result, presets = presets, hideAmounts = appSettings.hideWidgetAmounts)
                    }
                }
            }
        }
    }
}

/**
 * Applies the user's chosen theme to the widget. [AppTheme.DYNAMIC] keeps Glance's default
 * (wallpaper-derived) colours on Android 12+ and looks like Paper below that. Other themes use
 * their own light/dark schemes; a forced light or dark app mode is honoured by giving both slots
 * the same scheme, otherwise the widget follows the system's day/night setting.
 */
@Composable
private fun WidgetTheme(theme: AppTheme, darkMode: DarkMode, content: @Composable () -> Unit) {
    if (theme == AppTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceTheme(content = content)
    } else {
        val light = colorSchemeFor(theme, dark = false)
        val dark = colorSchemeFor(theme, dark = true)
        val colors = when (darkMode) {
            DarkMode.SYSTEM -> ColorProviders(light = light, dark = dark)
            DarkMode.LIGHT -> ColorProviders(light = light, dark = light)
            DarkMode.DARK -> ColorProviders(light = dark, dark = dark)
        }
        GlanceTheme(colors = colors, content = content)
    }
}

@Composable
private fun MessageContent(text: String) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = TextStyle(color = GlanceTheme.colors.onBackground, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun WidgetContent(result: ForecastResult, presets: List<Preset>, hideAmounts: Boolean) {
    val size = LocalSize.current
    val isTall = size.height >= TALL_HEIGHT_THRESHOLD
    val isWide = size.width >= WIDE_WIDTH_THRESHOLD

    val amountText = if (hideAmounts) "••••" else result.safeToSpend.weekly.format(showPence = false)
    val daysUntil = result.safeToSpend.daysUntilNextMajorIncome
    val incomeLabel = result.safeToSpend.nextMajorIncome?.label
    val daysText = if (daysUntil != null) {
        val label = incomeLabel ?: "next income"
        val plural = if (daysUntil == 1) "day" else "days"
        "$daysUntil $plural to $label"
    } else {
        null
    }

    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Text(
                    text = "Safe to spend",
                    style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 12.sp),
                )
                Text(
                    text = "$amountText/wk",
                    style = TextStyle(
                        color = GlanceTheme.colors.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (isWide && daysText != null) {
                    Text(
                        text = daysText,
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 12.sp),
                    )
                }
            }
            Box(
                modifier = GlanceModifier
                    .size(36.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(actionStartActivity<QuickAddActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        if (isTall && presets.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                presets.forEach { preset ->
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(horizontal = 4.dp)
                            .background(GlanceTheme.colors.secondaryContainer)
                            .clickable(
                                actionRunCallback<LogPresetAction>(actionParametersOf(PresetIdKey to preset.id)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = preset.label,
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}
