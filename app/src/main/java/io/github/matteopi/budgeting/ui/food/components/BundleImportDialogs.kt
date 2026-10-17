package io.github.matteopi.budgeting.ui.food.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matteopi.budgeting.core.recipes.IngredientMerge
import io.github.matteopi.budgeting.core.recipes.MergeChoice

/** Test tags of the import dialogs. */
object BundleImportTags {
    const val MERGE_DIALOG = "bundle-merge-dialog"
}

/**
 * The dialogs of a [BundleImporter]: the merge question (*Use existing* or *Import as new* for each ingredient the
 * user already has, docs/PLAN.md §11.7) and the problems found in a file that could not be read. Put it
 * anywhere in a screen that has an importer.
 */
@Composable
fun BundleImportDialogs(state: BundleImportState, importer: BundleImporter) {
    val prompt = state.prompt
    if (prompt != null) {
        MergeDialog(prompt = prompt, importer = importer)
    }
    if (state.problems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = importer::dismissProblems,
            title = { Text("Can't add these recipes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nothing was added.", style = MaterialTheme.typography.bodyMedium)
                    state.problems.take(MAX_PROBLEMS_SHOWN).forEach { problem ->
                        Text("• $problem", style = MaterialTheme.typography.bodyMedium)
                    }
                    val more = state.problems.size - MAX_PROBLEMS_SHOWN
                    if (more > 0) {
                        Text("…and $more more.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = importer::dismissProblems) { Text("OK") } },
        )
    }
}

private const val MAX_PROBLEMS_SHOWN = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeDialog(prompt: MergePrompt, importer: BundleImporter) {
    AlertDialog(
        onDismissRequest = importer::cancel,
        modifier = Modifier.testTag(BundleImportTags.MERGE_DIALOG),
        title = { Text("Ingredients you already have") },
        text = {
            Column {
                Text(
                    "${FoodFormat.count(prompt.matches.size, "ingredient")} in these recipes " +
                        "${if (prompt.matches.size == 1) "matches one" else "match ones"} already in your cupboard. " +
                        "Use yours, or add the recipes' own as new ingredients.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { importer.chooseAll(MergeChoice.USE_EXISTING) }, label = { Text("Use existing for all") })
                    AssistChip(onClick = { importer.chooseAll(MergeChoice.IMPORT_AS_NEW) }, label = { Text("Import all as new") })
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(prompt.matches, key = { it.key }) { match ->
                        MergeRow(
                            match = match,
                            choice = prompt.choices[match.key] ?: MergeChoice.USE_EXISTING,
                            onChoose = { importer.choose(match.key, it) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = importer::confirm) { Text("Add recipes") } },
        dismissButton = { TextButton(onClick = importer::cancel) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeRow(match: IngredientMerge, choice: MergeChoice, onChoose: (MergeChoice) -> Unit) {
    Column {
        Text(match.bundle.name, style = MaterialTheme.typography.bodyMedium)
        if (!match.existing.name.equals(match.bundle.name, ignoreCase = true)) {
            Text(
                "You have it as ${match.existing.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = choice == MergeChoice.USE_EXISTING,
                onClick = { onChoose(MergeChoice.USE_EXISTING) },
                label = { Text("Use existing") },
            )
            FilterChip(
                selected = choice == MergeChoice.IMPORT_AS_NEW,
                onClick = { onChoose(MergeChoice.IMPORT_AS_NEW) },
                label = { Text("Import as new") },
            )
        }
    }
}
