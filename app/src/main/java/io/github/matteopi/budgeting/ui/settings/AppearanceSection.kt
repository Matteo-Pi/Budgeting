package io.github.matteopi.budgeting.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.data.settings.AppTheme
import io.github.matteopi.budgeting.data.settings.DarkMode
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.plan.ChoiceRow
import io.github.matteopi.budgeting.ui.theme.LocalIsDarkTheme
import io.github.matteopi.budgeting.ui.theme.rememberColorScheme

/**
 * Settings > Appearance (docs/PLAN.md §6.12): a scrolling row of theme preview cards, each drawn in
 * its own colours (in the light or dark mode currently showing), and a System / Light / Dark choice.
 * Changes apply immediately because the theme is read from settings at the top of each activity.
 */
@Composable
fun AppearanceSection(
    theme: AppTheme,
    darkMode: DarkMode,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, title = "Appearance") {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTheme.entries.forEach { option ->
                ThemePreviewCard(
                    theme = option,
                    selected = option == theme,
                    onClick = { onThemeChange(option) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Dynamic uses your wallpaper colours on Android 12 and newer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Mode", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        ChoiceRow(
            options = DarkMode.entries.toList(),
            selected = darkMode,
            label = { it.label },
            onSelect = onDarkModeChange,
        )
    }
}

/** A miniature of one theme: its background, a surface strip with an accent dot, and its name. */
@Composable
private fun ThemePreviewCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val previewScheme = rememberColorScheme(theme, LocalIsDarkTheme.current)
    val activeScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(shape)
            .background(previewScheme.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) activeScheme.primary else activeScheme.outline,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(previewScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .align(Alignment.CenterStart)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(previewScheme.primary),
            )
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(previewScheme.onSurfaceVariant),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = theme.label,
                style = MaterialTheme.typography.labelLarge,
                color = previewScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                // The selected state is announced by `selectable`, so the tick itself has no description.
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = previewScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
