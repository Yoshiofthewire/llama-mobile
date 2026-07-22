package com.urlxl.mail.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PBKDF2_ITERATIONS = 150_000
private const val KEY_LENGTH_BITS = 256
private const val SALT_LENGTH_BYTES = 16
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

/** The PBKDF2 salt is deliberately not part of this type — it's an input to [CredentialCipher.deriveKey],
 *  owned and persisted once per pairing by the caller ([com.urlxl.mail.push.SecurePairingStore]),
 *  not an output of wrapping a single value. */
data class WrappedSecret(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * PIN-derived AES-GCM wrapping for the pairing `deviceSecret` — see "Require unlock to receive
 * push/MFA" in the 2026-07-22 security-hardening spec. Deliberately independent of Android
 * Keystore: unlike [AppLockStore]'s Keystore-backed prefs, this key must be re-derivable from
 * just the PIN + a stored salt on demand (whenever [AppLockManager] caches it after a successful
 * unlock), not tied to hardware key material.
 */
object CredentialCipher {
    fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun wrap(plaintext: String, key: SecretKeySpec): WrappedSecret {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return WrappedSecret(iv = iv, ciphertext = ciphertext)
    }

    /** Null on a wrong key or corrupted/tampered ciphertext (GCM's auth tag fails to verify) —
     *  callers (see [com.urlxl.mail.push.SecurePairingStore]) treat this as "credential
     *  unavailable right now," never as a crash. */
    fun unwrap(wrapped: WrappedSecret, key: SecretKeySpec): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapped.iv))
        String(cipher.doFinal(wrapped.ciphertext), Charsets.UTF_8)
    }.getOrNull()
}
