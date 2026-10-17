package io.github.matteopi.budgeting.ui.food.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The activity this context belongs to (a Compose `LocalContext` is usually a wrapper around it), or null. */
fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
