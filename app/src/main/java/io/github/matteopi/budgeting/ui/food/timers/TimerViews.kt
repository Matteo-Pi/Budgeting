@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.matteopi.budgeting.ui.food.timers

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.matteopi.budgeting.AppContainer
import io.github.matteopi.budgeting.BudgetApp
import io.github.matteopi.budgeting.core.recipes.RecipeStep
import io.github.matteopi.budgeting.core.recipes.StepTimers
import io.github.matteopi.budgeting.ui.components.AppIcons
import kotlinx.coroutines.delay

/** Test tags of the recipe timers. */
object TimerTags {
    const val BAR = "timers-bar"
    const val CUSTOM = "timer-custom"
    const val CUSTOM_MINUTES = "timer-custom-minutes"
    const val CUSTOM_START = "timer-custom-start"
    const val ALERT = "timer-alert"
    const val ALERT_DISMISS = "timer-alert-dismiss"
    const val ALERT_MORE = "timer-alert-more"

    /** The [timer]th timer chip of method step [step] (both from 0). */
    fun chip(step: Int, timer: Int) = "timer-chip-$step-$timer"

    /** The cancel button of the [index]th running timer in the bar. */
    fun cancel(index: Int) = "timer-cancel-$index"
}

/** Starts a recipe timer: [label] is what it is for ("Simmer"), [seconds] how long. */
typealias TimerStarter = (label: String, seconds: Int) -> Unit

private fun Context.container(): AppContainer = (applicationContext as BudgetApp).container

/**
 * What a timer chip calls (docs/PLAN.md §11.11): the app's own timer, and with *Start timers on the phone's clock*
 * on, one on the clock app too. [onMessage] says when the clock app couldn't take it.
 */
@Composable
fun rememberTimerStarter(recipeId: Long, recipeTitle: String, onMessage: (String) -> Unit): TimerStarter {
    val context = LocalContext.current
    val container = remember(context) { context.container() }
    val phoneTimers by container.settingsStore.phoneTimers.collectAsStateWithLifecycle(initialValue = false)
    // The setting, the title and the message can change while the screen is open; the starter reads them fresh.
    val on by rememberUpdatedState(phoneTimers)
    val title by rememberUpdatedState(recipeTitle)
    val message by rememberUpdatedState(onMessage)
    return remember(recipeId, context) {
        { label: String, seconds: Int -> startTimer(context, container, recipeId, title, label, seconds, on, message) }
    }
}

private fun startTimer(
    context: Context,
    container: AppContainer,
    recipeId: Long,
    recipeTitle: String,
    label: String,
    seconds: Int,
    phoneTimers: Boolean,
    onMessage: (String) -> Unit,
) {
    val onPhone = phoneTimers && PhoneTimer.start(context, seconds, PhoneTimer.message(label, recipeTitle))
    container.cookingTimers.start(recipeId, recipeTitle, label, seconds, onPhone)
    if (phoneTimers && !onPhone) onMessage("No clock app took the timer, so only this app is timing it.")
}

/** The timer chips of method step [index] (from 0): one per duration in its time and text ([StepTimers.of]). */
@Composable
fun StepTimerChips(index: Int, step: RecipeStep, onStart: TimerStarter, modifier: Modifier = Modifier) {
    val timers = remember(step) { StepTimers.of(step) }
    if (timers.isEmpty()) return
    val label = step.title?.takeIf { it.isNotBlank() } ?: "Step ${index + 1}"
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        timers.forEachIndexed { n, timer ->
            AssistChip(
                onClick = { onStart(label, timer.seconds) },
                label = { Text(timer.label) },
                leadingIcon = {
                    Icon(AppIcons.Timer, contentDescription = "Start a timer", modifier = Modifier.padding(0.dp))
                },
                colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(TimerTags.chip(index, n)),
            )
        }
    }
}

/** *Timer*: a timer of any number of minutes, for anything the steps don't time. */
@Composable
fun CustomTimerButton(onStart: TimerStarter, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    AssistChip(
        onClick = { asking = true },
        label = { Text("Timer") },
        leadingIcon = { Icon(AppIcons.Timer, contentDescription = null) },
        modifier = modifier.testTag(TimerTags.CUSTOM),
    )
    if (asking) {
        var minutes by rememberSaveable { mutableStateOf("") }
        val value = minutes.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it * 60 <= StepTimers.MAX_SECONDS }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Start a timer") },
            text = {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(TimerTags.CUSTOM_MINUTES),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        if (value != null) onStart("Timer", Math.round(value * 60).toInt())
                    },
                    enabled = value != null,
                    modifier = Modifier.testTag(TimerTags.CUSTOM_START),
                ) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The running timers, counting down, each with *Cancel* (docs/PLAN.md §11.11). Shown at the bottom of the recipe
 * screen and the cooking view; empty when nothing is running. [onMessage] explains that cancelling here leaves the
 * phone's clock timing.
 */
@Composable
fun TimersBar(onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timers = remember(context) { context.container().cookingTimers }
    val running by timers.running.collectAsStateWithLifecycle()
    if (running.isEmpty()) return
    var now by remember { mutableLongStateOf(timers.now()) }
    LaunchedEffect(running) {
        while (true) {
            now = timers.now()
            delay(250)
        }
    }
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().testTag(TimerTags.BAR)) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            running.forEachIndexed { index, timer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(timer.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            timer.recipeTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        StepTimers.clock(timer.secondsLeft(now)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(
                        onClick = {
                            timers.cancel(timer.id)
                            if (timer.onPhone) onMessage("Stopped here. The phone's clock is still timing it: stop it there too.")
                        },
                        modifier = Modifier.testTag(TimerTags.cancel(index)),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * "Time's up" on any screen of the app, with the phone's alarm sound unless the clock app is ringing for the same
 * timer (docs/PLAN.md §11.11). *1 more minute* starts another, on the phone's clock too when that is on.
 */
@Composable
fun TimerAlerts() {
    val context = LocalContext.current
    val container = remember(context) { context.container() }
    val timers = container.cookingTimers
    val finished by timers.finished.collectAsStateWithLifecycle()
    val phoneTimers by container.settingsStore.phoneTimers.collectAsStateWithLifecycle(initialValue = false)
    val timer = finished.firstOrNull() ?: return

    DisposableEffect(timer.id) {
        val ringtone = if (timer.onPhone) null else alarmSound(context)
        ringtone?.play()
        onDispose { ringtone?.stop() }
    }
    AlertDialog(
        onDismissRequest = { timers.dismiss(timer.id) },
        modifier = Modifier.testTag(TimerTags.ALERT),
        icon = { Icon(AppIcons.Timer, contentDescription = null) },
        title = { Text("Time's up: ${timer.label}") },
        text = { Text("${StepTimers.format(timer.seconds)} for ${timer.recipeTitle}.") },
        confirmButton = {
            TextButton(onClick = { timers.dismiss(timer.id) }, modifier = Modifier.testTag(TimerTags.ALERT_DISMISS)) {
                Text("Dismiss")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    timers.dismiss(timer.id)
                    startTimer(context, container, timer.recipeId, timer.recipeTitle, timer.label, 60, phoneTimers) {}
                },
                modifier = Modifier.testTag(TimerTags.ALERT_MORE),
            ) { Text("1 more minute") }
        },
    )
}

/** The phone's alarm sound (else its notification sound), played on the alarm stream; null if there is none. */
private fun alarmSound(context: Context): Ringtone? = runCatching {
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ?: return@runCatching null
    RingtoneManager.getRingtone(context, uri)?.apply {
        audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}.getOrNull()
