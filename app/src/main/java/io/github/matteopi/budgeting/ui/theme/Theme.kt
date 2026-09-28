package io.github.matteopi.budgeting.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode

private val BaseTypography = Typography()

// Default Material 3 typography (no custom fonts) with slightly tighter large text for a calmer look.
private val BudgetingTypography = BaseTypography.copy(
    headlineLarge = BaseTypography.headlineLarge.copy(letterSpacing = (-0.2).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(letterSpacing = (-0.2).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(letterSpacing = (-0.2).sp),
    titleLarge = BaseTypography.titleLarge.copy(letterSpacing = (-0.1).sp),
)

private val BudgetingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** True if [darkMode] resolves to the dark palette, given whether the system itself is in dark mode. */
fun DarkMode.resolveDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    DarkMode.SYSTEM -> systemInDarkTheme
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/**
 * The colour scheme for [theme] in the light or dark mode. [AppTheme.DYNAMIC] uses the wallpaper
 * colours on Android 12+ and Paper below that. Also used by the Appearance previews in Settings.
 */
@Composable
fun rememberColorScheme(theme: AppTheme, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (theme == AppTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(theme, dark) { colorSchemeFor(theme, dark) }
    }
}

/**
 * Material 3 theme with the user's chosen [theme] and [darkMode] (docs/PLAN.md §6.12). Also provides
 * [LocalMoneyColors] and [LocalIsDarkTheme], and keeps the system bar icons readable when the app's
 * mode differs from the phone's.
 */
@Composable
fun BudgetingTheme(
    theme: AppTheme = AppTheme.PAPER,
    darkMode: DarkMode = DarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = darkMode.resolveDark(isSystemInDarkTheme())
    val colorScheme = rememberColorScheme(theme, darkTheme)
    val moneyColors = remember(theme, darkTheme) { moneyColorsFor(theme, darkTheme) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalMoneyColors provides moneyColors,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BudgetingTypography,
            shapes = BudgetingShapes,
            content = content,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
