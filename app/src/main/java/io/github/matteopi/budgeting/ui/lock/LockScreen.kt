package io.github.matteopi.budgeting.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/**
 * Covers the whole UI while the app is locked (docs/PLAN.md §6.9). Triggers the system
 * biometric/device-credential prompt as soon as it appears, with a button to retry it (e.g. if
 * the user dismissed it) as a fallback.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as? FragmentActivity

    fun promptNow() {
        if (activity != null) {
            showUnlockPrompt(activity, onSuccess = onUnlock)
        }
    }

    LaunchedEffect(Unit) { promptNow() }

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp))
                Text("Budgeting is locked", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Unlock with your fingerprint, face or device PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { promptNow() }) { Text("Unlock") }
            }
        }
    }
}
