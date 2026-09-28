package io.github.matteopi.budgeting.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.ui.components.ConfirmDialog
import io.github.matteopi.budgeting.ui.components.SectionCard
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/** Export/restore a JSON backup and see when it was last exported (WP-U5, docs/PLAN.md §6.10). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = viewModel.buildExportJson()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("no output stream")
                    viewModel.onExported()
                    message = "Backup exported."
                } catch (e: Exception) {
                    message = "Couldn't write the backup file."
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
                if (text == null) {
                    viewModel.onFileReadFailed()
                } else {
                    viewModel.onFileChosen(text)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Export a backup") {
                val lastExport = appSettings?.lastExportDate
                val daysSince = lastExport?.let { ChronoUnit.DAYS.between(it, viewModel.today()) }
                Text(
                    text = if (lastExport == null) {
                        "You've never exported a backup."
                    } else {
                        "Last exported ${daysAgoText(daysSince ?: 0)}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (lastExport == null || (daysSince != null && daysSince > 30)) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "It's a good idea to keep a backup somewhere safe, like a cloud drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { exportLauncher.launch(defaultBackupFileName(viewModel.today())) }) {
                    Text("Export backup")
                }
            }

            SectionCard(title = "Restore a backup") {
                Text(
                    "Replaces everything currently in the app with the contents of a backup file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text("Choose backup file")
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    when (val state = restoreState) {
        is RestoreState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearRestoreState() },
                title = { Text("Couldn't read this backup") },
                text = { Text(state.errors.joinToString("\n")) },
                confirmButton = { TextButton(onClick = { viewModel.clearRestoreState() }) { Text("OK") } },
            )
        }
        is RestoreState.Ready -> {
            val data = state.success.data
            val dateRange = describeDateRange(data.transactions.map { it.date })
            ConfirmDialog(
                title = "Replace all data?",
                text = "This backup has ${data.transactions.size} transaction(s)" +
                    (dateRange?.let { " from $it" } ?: "") +
                    ", exported ${state.success.exportedAt}. Replacing all data on this device can't be undone.",
                confirmLabel = "Replace all data",
                onConfirm = { scope.launch { viewModel.confirmRestore(state.success) } },
                onDismiss = { viewModel.clearRestoreState() },
            )
        }
        null -> {}
    }
}

private fun defaultBackupFileName(today: LocalDate): String = "budgeting-backup-$today.json"

private fun daysAgoText(days: Long): String = when {
    days <= 0 -> "today"
    days == 1L -> "1 day ago"
    else -> "$days days ago"
}

private fun describeDateRange(dates: List<LocalDate>): String? {
    if (dates.isEmpty()) return null
    val min = dates.min()
    val max = dates.max()
    return if (min == max) min.toString() else "$min to $max"
}
