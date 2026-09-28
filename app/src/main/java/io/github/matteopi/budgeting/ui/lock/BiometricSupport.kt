package io.github.matteopi.budgeting.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** The authenticators app lock uses everywhere: biometric or the device's own PIN/pattern/password. */
val APP_LOCK_AUTHENTICATORS: Int = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * True only if the device can actually authenticate this way right now (docs/PLAN.md §6.9):
 * app lock may only be turned on when this is true.
 */
fun canUseAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(APP_LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Shows the system biometric/device-credential prompt. No negative button is set: when
 * [DEVICE_CREDENTIAL] is one of the allowed authenticators, `BiometricPrompt` doesn't allow one
 * (the system supplies its own "use PIN" affordance instead).
 */
fun showUnlockPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (CharSequence) -> Unit = {},
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString)
            }
        },
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Budgeting")
        .setAllowedAuthenticators(APP_LOCK_AUTHENTICATORS)
        .build()
    prompt.authenticate(promptInfo)
}
