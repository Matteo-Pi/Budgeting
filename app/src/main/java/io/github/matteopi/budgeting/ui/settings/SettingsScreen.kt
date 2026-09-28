package io.github.matteopi.budgeting.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matteopi.budgeting.core.model.Money
import io.github.matteopi.budgeting.core.model.ZeroPoint
import io.github.matteopi.budgeting.data.settings.AppSettings
import io.github.matteopi.budgeting.ui.components.AccountPicker
import io.github.matteopi.budgeting.ui.components.SectionCard
import io.github.matteopi.budgeting.ui.lock.canUseAppLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** Money display, forecast, categories, presets, privacy, reminders, data and about (WP-U5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenStatementImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = viewModel.buildCsvExport()
                context.contentResolver.openOutputStream(uri)?.use { out -> out.write(csv.toByteArray(Charsets.UTF_8)) }
                viewModel.onCsvExported()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { innerPadding ->
        if (!uiState.loaded) {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(title = "Money display") {
                    ZeroPointSection(uiState.budgetSettings.zeroPoint, viewModel::setZeroPoint)
                    Spacer(Modifier.height(8.dp))
                    BufferField(uiState.budgetSettings.buffer, viewModel::setBuffer)
                }
                SectionCard(title = "Default account") {
                    AccountPicker(
                        accounts = uiState.accounts,
                        selectedAccountId = uiState.budgetSettings.defaultAccountId,
                        onSelect = { id -> viewModel.setDefaultAccountId(id) },
                    )
                }
                SectionCard(title = "Forecast") {
                    HistoryStartRow(uiState.budgetSettings.historyStartDate, viewModel::setHistoryStartDate)
                    Spacer(Modifier.height(16.dp))
                    SimulationsRow(uiState.budgetSettings.simulations, viewModel::setSimulations)
                    Spacer(Modifier.height(16.dp))
                    HorizonRow(uiState.budgetSettings.forecastHorizonDays, viewModel::setForecastHorizonDays)
                }
                SectionCard(title = "Categories & presets") {
                    NavRow(label = "Categories", onClick = onOpenCategories)
                    NavRow(label = "Presets", onClick = onOpenPresets)
                }
                SectionCard(title = "Privacy") {
                    PrivacySection(
                        appSettings = uiState.appSettings,
                        onHideWidgetAmounts = viewModel::setHideWidgetAmounts,
                        onAppLockEnabled = viewModel::setAppLockEnabled,
                        onSecureScreen = viewModel::setSecureScreen,
                    )
                }
                SectionCard(title = "Reminders") {
                    RemindersSection(
                        appSettings = uiState.appSettings,
                        onEnabledChange = viewModel::setReconcileReminderEnabled,
                        onDayChange = viewModel::setReconcileReminderDay,
                    )
                }
                SectionCard(title = "Data") {
                    DataSection(
                        onOpenStatementImport = onOpenStatementImport,
                        onOpenBackup = onOpenBackup,
                        onExportCsv = { csvLauncher.launch("budgeting-transactions-${viewModel.today()}.csv") },
                    )
                }
                SectionCard(title = "About") {
                    AboutSection()
                }
            }
        }
    }
}

@Composable
private fun ZeroPointSection(zeroPoint: ZeroPoint, onChange: (ZeroPoint) -> Unit) {
    Column {
        ZeroPointOption(
            label = "£0 = empty account",
            description = "The balance shown matches your bank app exactly.",
            selected = zeroPoint == ZeroPoint.ACCOUNT_BALANCE,
            onSelect = { onChange(ZeroPoint.ACCOUNT_BALANCE) },
        )
        ZeroPointOption(
            label = "£0 = overdraft used up",
            description = "The balance shown adds your overdraft limit on top, so £0 means you've used it all.",
            selected = zeroPoint == ZeroPoint.OVERDRAFT_LIMIT,
            onSelect = { onChange(ZeroPoint.OVERDRAFT_LIMIT) },
        )
    }
}

@Composable
private fun ZeroPointOption(label: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BufferField(buffer: Money, onChange: (Money) -> Unit) {
    var text by remember(buffer) { mutableStateOf(buffer.format(showPence = false).removePrefix("£")) }
    val error = text.isNotBlank() && (Money.parse(text) == null || Money.parse(text)?.isNegative == true)
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val money = Money.parse(newText)
            if (money != null && !money.isNegative) onChange(money)
        },
        label = { Text("Safety buffer (£)") },
        singleLine = true,
        isError = error,
        supportingText = {
            Text(if (error) "Enter a valid amount" else "Kept above zero when working out safe-to-spend")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryStartRow(date: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        Text("History start date", style = MaterialTheme.typography.labelMedium)
        Text(
            "The forecast learns from transactions on or after this date. Leave unset to use your very first transaction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = date?.toString() ?: "Not set",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.weight(1f).clickable { showPicker = true },
            )
            if (date != null) {
                TextButton(onClick = { onChange(null) }) { Text("Clear") }
            }
        }
    }
    if (showPicker) {
        val initialMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val maxMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val selectableDates = remember(maxMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= maxMillis
            }
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis, selectableDates = selectableDates)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimulationsRow(value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("Simulations", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500, 1000, 2000).forEach { option ->
                FilterChip(selected = value == option, onClick = { onChange(option) }, label = { Text(option.toString()) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HorizonRow(days: Int, onChange: (Int) -> Unit) {
    Column {
        Text("Forecast horizon", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(13 to 91, 26 to 182, 52 to 364).forEach { (weeks, dayCount) ->
                FilterChip(selected = days == dayCount, onClick = { onChange(dayCount) }, label = { Text("$weeks weeks") })
            }
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacySection(
    appSettings: AppSettings,
    onHideWidgetAmounts: (Boolean) -> Unit,
    onAppLockEnabled: (Boolean) -> Unit,
    onSecureScreen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var appLockError by remember { mutableStateOf<String?>(null) }

    SwitchRow(
        label = "Hide widget amounts",
        description = "Shows •••• instead of numbers on the home-screen widget.",
        checked = appSettings.hideWidgetAmounts,
        onCheckedChange = onHideWidgetAmounts,
    )
    SwitchRow(
        label = "App lock",
        description = "Ask for your fingerprint, face or device PIN when opening the app.",
        checked = appSettings.appLockEnabled,
        onCheckedChange = { enabled ->
            when {
                !enabled -> {
                    appLockError = null
                    onAppLockEnabled(false)
                }
                canUseAppLock(context) -> {
                    appLockError = null
                    onAppLockEnabled(true)
                }
                else -> appLockError = "Set up a fingerprint, face unlock or screen lock on this device first."
            }
        },
    )
    appLockError?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
    }
    SwitchRow(
        label = "Hide in recent apps",
        description = "Blanks the app's preview in the recent-apps switcher and blocks screenshots.",
        checked = appSettings.secureScreen,
        onCheckedChange = onSecureScreen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindersSection(
    appSettings: AppSettings,
    onEnabledChange: (Boolean) -> Unit,
    onDayChange: (DayOfWeek) -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SwitchRow(
        label = "Weekly balance check",
        description = "A notification if you haven't updated your balances in a while.",
        checked = appSettings.reconcileReminderEnabled,
        onCheckedChange = { enabled ->
            onEnabledChange(enabled)
            if (enabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
    if (appSettings.reconcileReminderEnabled) {
        Spacer(Modifier.height(8.dp))
        Text("Remind me on", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayOfWeek.values().forEach { day ->
                FilterChip(
                    selected = appSettings.reconcileReminderDay == day,
                    onClick = { onDayChange(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.UK)) },
                )
            }
        }
    }
}

@Composable
private fun DataSection(onOpenStatementImport: () -> Unit, onOpenBackup: () -> Unit, onExportCsv: () -> Unit) {
    NavRow(label = "Import statement", onClick = onOpenStatementImport)
    NavRow(label = "Backup & restore", onClick = onOpenBackup)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Export CSV", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onExportCsv) { Text("Export") }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
    Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
}
