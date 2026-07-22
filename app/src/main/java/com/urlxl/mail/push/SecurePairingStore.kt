package com.urlxl.mail.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.urlxl.mail.security.CredentialCipher
import com.urlxl.mail.security.WrappedSecret
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val ENCRYPTED_PREFS_FILE_NAME = "push_pairing_secure"

private const val KEY_SUBSCRIBER_ID = "pair_sub"
private const val KEY_DEVICE_SECRET = "pair_device_secret"
private const val KEY_SERVER_URL = "pair_srv"
private const val KEY_REGISTRATION_URL = "pair_reg"
private const val KEY_PAIRING_TOKEN = "pair_pt"
private const val KEY_DEVICE_ID = "pair_device_id"
private const val KEY_PAIRED_AT = "pair_paired_at"
private const val KEY_DEVICE_SECRET_CIPHERTEXT = "pair_device_secret_ciphertext"
private const val KEY_DEVICE_SECRET_SALT = "pair_device_secret_salt"
private const val KEY_DEVICE_SECRET_IV = "pair_device_secret_iv"
private const val KEY_TLS_PIN = "pair_tls_spki_pin"

/**
 * Holds pairing proof material (device secret, pairing token) in a Keystore-backed
 * EncryptedSharedPreferences file rather than the plaintext DataStore used for the rest
 * of the push state (history, sync status, server URL setting).
 */
class SecurePairingStore(context: Context) {
    private val prefs: SharedPreferences by lazy { buildEncryptedPrefs(context.applicationContext) }

    private val _pairing = MutableStateFlow<PairingData?>(null)
    val pairing: StateFlow<PairingData?> = _pairing.asStateFlow()

    init {
        _pairing.value = readPairing(credentialKey = null)
    }

    suspend fun savePairing(pairing: PairingData, credentialKey: SecretKeySpec? = null, credentialSalt: ByteArray? = null) {
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
                .putString(KEY_SUBSCRIBER_ID, pairing.subscriberId)
                .putString(KEY_SERVER_URL, pairing.serverUrl)
                .putString(KEY_REGISTRATION_URL, pairing.registrationUrl)
                .putString(KEY_PAIRING_TOKEN, pairing.pairingToken)
                .putLong(KEY_PAIRED_AT, pairing.pairedAtEpochMs)
            if (pairing.deviceId.isNullOrBlank()) editor.remove(KEY_DEVICE_ID) else editor.putString(KEY_DEVICE_ID, pairing.deviceId)

            val deviceSecret = pairing.deviceSecret
            when {
                deviceSecret.isNullOrBlank() -> editor.remove(KEY_DEVICE_SECRET)
                    .remove(KEY_DEVICE_SECRET_CIPHERTEXT).remove(KEY_DEVICE_SECRET_SALT).remove(KEY_DEVICE_SECRET_IV)
                credentialKey != null && credentialSalt != null -> {
                    val wrapped = CredentialCipher.wrap(deviceSecret, credentialKey)
                    editor.remove(KEY_DEVICE_SECRET)
                        .putString(KEY_DEVICE_SECRET_CIPHERTEXT, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                        .putString(KEY_DEVICE_SECRET_SALT, Base64.encodeToString(credentialSalt, Base64.NO_WRAP))
                        .putString(KEY_DEVICE_SECRET_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                }
                else -> editor.putString(KEY_DEVICE_SECRET, deviceSecret)
                    .remove(KEY_DEVICE_SECRET_CIPHERTEXT).remove(KEY_DEVICE_SECRET_SALT).remove(KEY_DEVICE_SECRET_IV)
            }
            editor.commit()
        }
        _pairing.value = readPairing(credentialKey = null)
    }

    /** Reads pairing state, unwrapping `deviceSecret` with [credentialKey] if it was stored wrapped
     *  (see [savePairing]'s `credentialKey` param). Returns the same shape either way; `deviceSecret`
     *  comes back `null` if it's wrapped and [credentialKey] is null or wrong — never throws. */
    fun pairingSnapshot(credentialKey: SecretKeySpec?): PairingData? = readPairing(credentialKey)

    /** The salt needed to re-derive the credential key from the PIN — non-secret, read by
     *  [com.urlxl.mail.security.AppLockManager] after a successful unlock. Null if `deviceSecret`
     *  isn't currently stored wrapped. */
    fun currentCredentialSalt(): ByteArray? =
        prefs.getString(KEY_DEVICE_SECRET_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** True if `deviceSecret` is currently stored wrapped behind a credential key (see
     *  [savePairing]'s `credentialKey` param). Used by
     *  [com.urlxl.mail.security.rewrapPairingIfNeeded] to detect a pairing that was saved
     *  unwrapped — e.g. by a background FCM token rotation that ran before any PIN unlock this
     *  session — and still needs re-wrapping. */
    fun isDeviceSecretWrapped(): Boolean = prefs.contains(KEY_DEVICE_SECRET_CIPHERTEXT)

    /** Persists the TOFU (trust-on-first-use) TLS certificate pin captured right after the
     *  first successful pairing/registration call (see [com.urlxl.mail.push.PushSyncCoordinator]
     *  .attemptPairing and [com.urlxl.mail.security.SpkiPinner]) — never overwritten on later
     *  requests, only on a fresh pairing (initial or after [clearPairing] + re-pair). */
    suspend fun saveTlsPin(pin: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_TLS_PIN, pin).commit()
        }
    }

    /** The currently enforced TLS pin, or null if this device has never captured one (not yet
     *  paired, or paired before this feature existed). */
    fun currentTlsPin(): String? = prefs.getString(KEY_TLS_PIN, null)

    suspend fun clearPairing() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_SUBSCRIBER_ID)
                .remove(KEY_DEVICE_SECRET)
                .remove(KEY_DEVICE_SECRET_CIPHERTEXT)
                .remove(KEY_DEVICE_SECRET_SALT)
                .remove(KEY_DEVICE_SECRET_IV)
                .remove(KEY_SERVER_URL)
                .remove(KEY_REGISTRATION_URL)
                .remove(KEY_PAIRING_TOKEN)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_PAIRED_AT)
                .remove(KEY_TLS_PIN)
                .commit()
        }
        _pairing.value = null
    }

    private fun readPairing(credentialKey: SecretKeySpec?): PairingData? {
        val subId = prefs.getString(KEY_SUBSCRIBER_ID, null).orEmpty()
        val serverUrl = prefs.getString(KEY_SERVER_URL, null).orEmpty()
        val registrationUrl = prefs.getString(KEY_REGISTRATION_URL, null).orEmpty()
        val pairingToken = prefs.getString(KEY_PAIRING_TOKEN, null).orEmpty()
        val pairedAt = if (prefs.contains(KEY_PAIRED_AT)) prefs.getLong(KEY_PAIRED_AT, 0L) else null

        if (subId.isBlank() || serverUrl.isBlank() ||
            registrationUrl.isBlank() || pairingToken.isBlank() || pairedAt == null
        ) {
            return null
        }

        val deviceSecret = resolveDeviceSecret(credentialKey)

        return PairingData(
            subscriberId = subId,
            serverUrl = serverUrl,
            registrationUrl = registrationUrl,
            pairingToken = pairingToken,
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            deviceSecret = deviceSecret,
            pairedAtEpochMs = pairedAt,
        )
    }

    private fun resolveDeviceSecret(credentialKey: SecretKeySpec?): String? {
        val wrappedCiphertext = prefs.getString(KEY_DEVICE_SECRET_CIPHERTEXT, null)
        if (wrappedCiphertext == null) return prefs.getString(KEY_DEVICE_SECRET, null)
        val key = credentialKey ?: return null
        val iv = prefs.getString(KEY_DEVICE_SECRET_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val ciphertext = Base64.decode(wrappedCiphertext, Base64.NO_WRAP)
        // The salt (KEY_DEVICE_SECRET_SALT) isn't read here — credentialKey has already been derived
        // from it by the caller (see AppLockManager.cacheCredentialKeyIfEnabled, Task 20); it's
        // exposed separately via currentCredentialSalt() for that derivation to happen at all.
        return CredentialCipher.unwrap(WrappedSecret(iv, ciphertext), key)
    }

    private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
        return try {
            createEncryptedPrefs(appContext)
        } catch (e: Exception) {
            // The Keystore-backed key can become unable to decrypt the stored keyset (e.g. OS-level
            // key invalidation) — that's unrecoverable, and it happens in the init path, so an
            // uncaught failure here crashes the app on every launch. Reset to a fresh, empty
            // encrypted file instead; readPairing() then reports null and the user just re-pairs.
            android.util.Log.e("SecurePairingStore", "Encrypted pairing store unreadable, resetting", e)
            appContext.deleteSharedPreferences(ENCRYPTED_PREFS_FILE_NAME)
            createEncryptedPrefs(appContext)
        }
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
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
