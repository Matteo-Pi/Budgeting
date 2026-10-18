package io.github.matteopi.budgeting.ui.food.timers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import io.github.matteopi.budgeting.core.recipes.StepTimers

/**
 * Starting a timer on the phone's clock app too (docs/PLAN.md §11.11), so it rings even when this app is closed:
 * `AlarmClock.ACTION_SET_TIMER` with the length, a message and no clock screen. Needs the normal permission
 * `com.android.alarm.permission.SET_ALARM`, granted on install.
 */
object PhoneTimer {

    /** "Simmer · Chilli": what the clock app shows. */
    fun message(label: String, recipeTitle: String): String = "$label · $recipeTitle".take(MAX_MESSAGE)

    private const val MAX_MESSAGE = 80

    /** Asks the clock app for a timer of [seconds]; false when no app takes it (the app's own timer still runs). */
    fun start(context: Context, seconds: Int, message: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, StepTimers.MAX_SECONDS))
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
