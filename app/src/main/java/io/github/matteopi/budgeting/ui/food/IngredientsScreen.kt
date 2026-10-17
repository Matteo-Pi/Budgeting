package io.github.matteopi.budgeting.ui.food

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.matteopi.budgeting.data.settings.AppSection
import io.github.matteopi.budgeting.ui.components.EmptyState
import io.github.matteopi.budgeting.ui.navigation.SectionSwitchButton

/**
 * The Ingredients tab (docs/PLAN.md §11.8). A placeholder until the ingredient list is built: the whole
 * file is replaced by that package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Ingredients") },
                navigationIcon = { SectionSwitchButton(AppSection.RECIPES) },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState("The ingredient list is coming in the next build.")
        }
    }
}
