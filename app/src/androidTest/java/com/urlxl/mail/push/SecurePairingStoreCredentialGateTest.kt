package com.urlxl.mail.push

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urlxl.mail.security.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurePairingStoreCredentialGateTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val pairing = PairingData(
        subscriberId = "subscriber-id",
        serverUrl = "https://server.example.com",
        registrationUrl = "https://server.example.com/api/notifications/native/register",
        pairingToken = "top-secret-pairing-token",
        deviceId = "resolved-device-id",
        deviceSecret = "top-secret-device-secret",
        pairedAtEpochMs = 1_000L,
    )

    @Before
    fun clearAnyExistingState() {
        runBlocking { SecurePairingStore(context).clearPairing() }
    }

    @Test
    fun savePairing_withCredentialKey_readingWithoutKey_omitsDeviceSecret() = runBlocking {
        val salt = CredentialCipher.randomSalt()
        val key = CredentialCipher.deriveKey("123456", salt)
        val store = SecurePairingStore(context)
        store.savePairing(pairing, credentialKey = key, credentialSalt = salt)

        // A read with no key available (app locked) must come back with deviceSecret == null,
        // not throw and not leak the wrapped ciphertext as if it were the plaintext secret.
        val lockedRead = store.pairingSnapshot(credentialKey = null)
        assertNull(lockedRead?.deviceSecret)
        assertEquals(pairing.subscriberId, lockedRead?.subscriberId)
    }

    @Test
    fun savePairing_withCredentialKey_readingWithCorrectKey_restoresDeviceSecret() = runBlocking {
        val salt = CredentialCipher.randomSalt()
        val key = CredentialCipher.deriveKey("123456", salt)
        val store = SecurePairingStore(context)
        store.savePairing(pairing, credentialKey = key, credentialSalt = salt)

        val unlockedRead = store.pairingSnapshot(credentialKey = key)
        assertEquals(pairing.deviceSecret, unlockedRead?.deviceSecret)
    }

    @Test
    fun savePairing_withoutCredentialKey_behavesAsUnwrapped() = runBlocking {
        val store = SecurePairingStore(context)
        store.savePairing(pairing)

        assertEquals(pairing.deviceSecret, store.pairingSnapshot(credentialKey = null)?.deviceSecret)
    }
}
