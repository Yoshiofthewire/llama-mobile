package com.urlxl.mail.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val ENCRYPTED_PREFS_FILE_NAME = "app_lock_secure"

private const val KEY_LOCK_ENABLED = "lock_enabled"
private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
private const val KEY_CREDENTIAL_PIN_GATE_ENABLED = "credential_pin_gate_enabled"
private const val KEY_PIN_SALT = "pin_salt"
private const val KEY_PIN_HASH = "pin_hash"
private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
private const val KEY_LOCKOUT_UNTIL = "lockout_until_epoch_ms"

/** Everything [AppLockManager] needs from persisted app-lock state, kept as an interface so
 *  [AppLockManager] can be unit-tested against a fake instead of a real Context/Keystore. */
interface AppLockState {
    fun isLockEnabled(): Boolean
    fun setLockEnabled(enabled: Boolean)
    fun isBiometricEnabled(): Boolean
    fun setBiometricEnabled(enabled: Boolean)
    fun isCredentialPinGateEnabled(): Boolean
    fun setCredentialPinGateEnabled(enabled: Boolean)
    fun setPin(pin: String)
    fun verifyPin(pin: String): Boolean
    fun hasPin(): Boolean
    fun incrementFailedAttempts(): Int
    fun resetFailedAttempts()
    fun lockoutUntilEpochMs(): Long
    fun setLockoutUntilEpochMs(epochMs: Long)
    /** Clears PIN, lock/biometric/credential-gate flags, and attempt counters — the app-lock
     *  half of [SecurityWipe]'s full wipe, also used by "turn off Require Unlock to Open". */
    fun reset()
}

/**
 * Keystore-backed storage for the app-lock PIN and its associated state — same
 * `EncryptedSharedPreferences` pattern as [com.urlxl.mail.push.SecurePairingStore]. The PIN
 * itself is never stored, only [PinHasher]'s salted hash.
 */
class AppLockStore(context: Context) : AppLockState {
    private val prefs: SharedPreferences by lazy { buildEncryptedPrefs(context.applicationContext) }

    override fun isLockEnabled(): Boolean = prefs.getBoolean(KEY_LOCK_ENABLED, false)
    override fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ENABLED, enabled).commit()
    }

    override fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    override fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).commit()
    }

    override fun isCredentialPinGateEnabled(): Boolean = prefs.getBoolean(KEY_CREDENTIAL_PIN_GATE_ENABLED, false)
    override fun setCredentialPinGateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CREDENTIAL_PIN_GATE_ENABLED, enabled).commit()
    }

    override fun setPin(pin: String) {
        val hash = PinHasher.hash(pin)
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(hash.salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash.hash, Base64.NO_WRAP))
            .commit()
    }

    override fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    override fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val hash = prefs.getString(KEY_PIN_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return PinHasher.matches(pin, salt, hash)
    }

    override fun incrementFailedAttempts(): Int {
        val next = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, next).commit()
        return next
    }

    override fun resetFailedAttempts() {
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).commit()
    }

    override fun lockoutUntilEpochMs(): Long = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
    override fun setLockoutUntilEpochMs(epochMs: Long) {
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL, epochMs).commit()
    }

    override fun reset() {
        prefs.edit().clear().commit()
    }

    private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
