package com.urlxl.mail.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialCipherTest {
    @Test
    fun wrap_thenUnwrap_roundTripsWithCorrectKey() {
        val salt = CredentialCipher.randomSalt()
        val key = CredentialCipher.deriveKey("123456", salt)
        val wrapped = CredentialCipher.wrap("top-secret-device-secret", key)

        assertEquals("top-secret-device-secret", CredentialCipher.unwrap(wrapped, key))
    }

    @Test
    fun unwrap_returnsNull_withWrongPinDerivedKey() {
        val salt = CredentialCipher.randomSalt()
        val correctKey = CredentialCipher.deriveKey("123456", salt)
        val wrongKey = CredentialCipher.deriveKey("000000", salt)
        val wrapped = CredentialCipher.wrap("top-secret-device-secret", correctKey)

        assertNull(CredentialCipher.unwrap(wrapped, wrongKey))
    }

    @Test
    fun unwrap_returnsNull_forTamperedCiphertext() {
        val salt = CredentialCipher.randomSalt()
        val key = CredentialCipher.deriveKey("123456", salt)
        val wrapped = CredentialCipher.wrap("top-secret-device-secret", key)
        val tampered = wrapped.copy(ciphertext = wrapped.ciphertext.also { it[0] = it[0].inc() })

        assertNull(CredentialCipher.unwrap(tampered, key))
    }
}
