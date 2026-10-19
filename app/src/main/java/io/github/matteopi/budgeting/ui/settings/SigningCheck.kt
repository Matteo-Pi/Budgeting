package io.github.matteopi.budgeting.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Which key the installed copy of the app is signed with. Every build from GitHub is signed with the
 * project's key (`app/debug.keystore`); Android only installs an update over the top when the keys match.
 * A copy that some installer app re-signed with its own key can never be updated, so Settings says so.
 */
object SigningCheck {

    /** SHA-256 of the project key's certificate, as CI pins it (`.github/workflows/build.yml`). */
    const val PROJECT_CERT_SHA256 = "56c27a4047eb8a6bb148bc73840a51d1c3a42223e6434e383484d6d0b6c7b784"

    /** Lower-case hex SHA-256 of a certificate's bytes. */
    fun sha256Hex(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

    /** "56:C2:7A:40:…:B7:84": the start and end of a fingerprint, as the release page and phones show them. */
    fun shortFingerprint(hex: String): String {
        val pairs = hex.uppercase().chunked(2)
        if (pairs.size <= 6) return pairs.joinToString(":")
        return (pairs.take(4) + "…" + pairs.takeLast(2)).joinToString(":")
    }

    /** True when every signer of the installed copy is the project key (there is exactly one). */
    fun isProjectKey(signers: List<String>): Boolean = signers.isNotEmpty() && signers.all { it == PROJECT_CERT_SHA256 }

    /** What Settings says about [signers] (the fingerprints of the installed copy's certificates). */
    fun summary(signers: List<String>): String = when {
        signers.isEmpty() -> "Couldn't read which key this copy is signed with."
        isProjectKey(signers) -> "Signed with the project key (${shortFingerprint(PROJECT_CERT_SHA256)}): new builds from GitHub install over it."
        else -> "This copy is signed with a different key (${signers.joinToString { shortFingerprint(it) }}), probably by the " +
            "app that installed it. New builds from GitHub can't install over it: export a backup, uninstall, then " +
            "install budgeting.apk with Android's own installer and restore the backup."
    }

    /** The SHA-256 fingerprints of the installed copy's signing certificates; empty if they can't be read. */
    fun installedSigners(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching emptyList()
            val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            // The newest certificate is the current signer when the key has been rotated.
            (if (info.hasMultipleSigners()) signatures.toList() else listOfNotNull(signatures.lastOrNull()))
                .map { sha256Hex(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
        }
    }.getOrDefault(emptyList())
}
