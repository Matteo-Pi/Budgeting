package io.github.matteopi.budgeting.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Settings says about the key the installed copy is signed with. */
class SigningCheckTest {

    @Test
    fun fingerprintsAreSha256InHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SigningCheck.sha256Hex("abc".toByteArray()))
        assertEquals("56:C2:7A:40:…:B7:84", SigningCheck.shortFingerprint(SigningCheck.PROJECT_CERT_SHA256))
    }

    @Test
    fun onlyTheProjectKeyCountsAsOurs() {
        assertTrue(SigningCheck.isProjectKey(listOf(SigningCheck.PROJECT_CERT_SHA256)))
        assertFalse(SigningCheck.isProjectKey(emptyList()))
        assertFalse(SigningCheck.isProjectKey(listOf("00".repeat(32))))
        assertFalse(SigningCheck.isProjectKey(listOf(SigningCheck.PROJECT_CERT_SHA256, "00".repeat(32))))
        assertTrue("new builds from GitHub install over it" in SigningCheck.summary(listOf(SigningCheck.PROJECT_CERT_SHA256)))
        val other = SigningCheck.summary(listOf("ab".repeat(32)))
        assertTrue("different key (AB:AB:AB:AB:…:AB:AB)" in other, other)
        assertTrue("export a backup, uninstall" in other)
    }
}
