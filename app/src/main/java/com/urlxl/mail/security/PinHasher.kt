package com.urlxl.mail.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PBKDF2_ITERATIONS = 150_000
private const val KEY_LENGTH_BITS = 256
private const val SALT_LENGTH_BYTES = 16

/** [hash] is never the raw PIN — only this derived, salted value is ever persisted. */
data class PinHash(val salt: ByteArray, val hash: ByteArray)

/**
 * PBKDF2-based PIN hashing for the app-lock PIN (see "Require Unlock to Open" in the
 * 2026-07-22 security-hardening spec). [matches] uses [MessageDigest.isEqual], which is
 * documented as timing-attack-resistant, rather than `ByteArray.contentEquals` — a PIN
 * comparison is exactly the kind of check where short-circuiting on the first differing byte
 * would leak information to a timing attacker.
 */
object PinHasher {
    fun hash(pin: String, salt: ByteArray = randomSalt()): PinHash {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return PinHash(salt, derived)
    }

    fun matches(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt).hash, expectedHash)

    fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
}
