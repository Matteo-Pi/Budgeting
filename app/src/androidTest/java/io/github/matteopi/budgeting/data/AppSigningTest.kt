package io.github.matteopi.budgeting.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.matteopi.budgeting.ui.settings.SigningCheck
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The app reads its own signing key as Android sees it, and a CI build is signed with the project key. */
@RunWith(AndroidJUnit4::class)
class AppSigningTest {

    @Test
    fun theInstalledAppIsSignedWithTheProjectKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(listOf(SigningCheck.PROJECT_CERT_SHA256), SigningCheck.installedSigners(context))
    }
}
