package com.urlxl.mail.push

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.urlxl.mail.ScopedValue
import com.urlxl.mail.security.AppLockStore
import com.urlxl.mail.security.SecurityRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.pushDataStore by preferencesDataStore(name = "push_state")

private val KEY_LAST_SYNC_AT = longPreferencesKey("sync_last_at")
private val KEY_SYNC_ERROR = stringPreferencesKey("sync_error")
private val KEY_HISTORY_JSON = stringPreferencesKey("history_json")
private val KEY_DELIVERY_MODE = stringPreferencesKey("delivery_mode")
private val KEY_PULL_ENDPOINT = stringPreferencesKey("pull_endpoint")
private val KEY_PULL_CURSOR = longPreferencesKey("pull_cursor")
private val KEY_PULL_CURSOR_SUB = stringPreferencesKey("pull_cursor_sub")
private val KEY_TRANSPORT = stringPreferencesKey("transport")
private val KEY_UNIFIEDPUSH_ENDPOINT = stringPreferencesKey("unifiedpush_endpoint")
private val KEY_UNIFIEDPUSH_P256DH = stringPreferencesKey("unifiedpush_p256dh")
private val KEY_UNIFIEDPUSH_AUTH = stringPreferencesKey("unifiedpush_auth")

private const val HISTORY_LIMIT = 30

class PushRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val securePairingStore = SecurePairingStore(context)
    private val pullCursorValue = ScopedValue(
        dataStore = context.pushDataStore,
        scopeKey = KEY_PULL_CURSOR_SUB,
        valueKey = KEY_PULL_CURSOR,
    )

    val state: Flow<PushState> = combine(
        context.pushDataStore.data.catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        },
        securePairingStore.pairing,
    ) { prefs, pairing -> toState(prefs, pairing) }

    /** Pairing data for making an authenticated relay call right now — `deviceSecret` comes back
     *  null if "require unlock to receive push/MFA" is on and the app isn't currently unlocked via
     *  PIN, per the 2026-07-22 security-hardening spec; callers already treat a blank/missing
     *  deviceSecret as an auth failure (see [com.urlxl.mail.pairingAuthHeaders]'s `.orEmpty()`
     *  usage), so this fails the same way a real 401 would — no new error path needed. */
    fun pairingForAuthenticatedCall(): PairingData? =
        securePairingStore.pairingSnapshot(SecurityRuntime.graph(context).appLockManager.cachedCredentialKey())

    /** The TOFU TLS pin captured right after the first successful pairing, or null if none has
     *  been captured yet (never paired, or paired before this feature existed). Read fresh on
     *  every call — never cached by the caller — since it can change on re-pairing. */
    fun currentTlsPin(): String? = securePairingStore.currentTlsPin()

    /** Persist the TLS pin captured on a just-succeeded pairing/registration call. See
     *  [SecurePairingStore.saveTlsPin]; only [com.urlxl.mail.push.PushSyncCoordinator]
     *  .attemptPairing calls this, not every routine registration resync. */
    suspend fun saveTlsPin(pin: String) = securePairingStore.saveTlsPin(pin)

    /** Saves pairing data, wrapping `deviceSecret` behind the currently-cached credential key if
     *  "require unlock to receive push/MFA" is on and a PIN-derived key is available this session
     *  (see [com.urlxl.mail.security.AppLockManager.cachedCredentialKey]) — otherwise stores it
     *  unwrapped, exactly as before this gate existed. */
    suspend fun savePairing(pairing: PairingData) {
        val appLockManager = SecurityRuntime.graph(context).appLockManager
        val credentialKey = appLockManager.cachedCredentialKey()
        val credentialSalt = if (credentialKey != null) AppLockStore(context).credentialSalt() else null
        if (credentialKey != null && credentialSalt != null) {
            securePairingStore.savePairing(pairing, credentialKey, credentialSalt)
        } else {
            securePairingStore.savePairing(pairing)
        }
        context.pushDataStore.edit { prefs ->
            prefs.remove(KEY_SYNC_ERROR)
        }
    }

    suspend fun clearPairing() {
        securePairingStore.clearPairing()
        context.pushDataStore.edit { prefs ->
            prefs.remove(KEY_LAST_SYNC_AT)
            prefs.remove(KEY_SYNC_ERROR)
            prefs.remove(KEY_DELIVERY_MODE)
            prefs.remove(KEY_PULL_ENDPOINT)
            prefs.remove(KEY_PULL_CURSOR)
            prefs.remove(KEY_PULL_CURSOR_SUB)
            prefs.remove(KEY_TRANSPORT)
            prefs.remove(KEY_UNIFIEDPUSH_ENDPOINT)
            prefs.remove(KEY_UNIFIEDPUSH_P256DH)
            prefs.remove(KEY_UNIFIEDPUSH_AUTH)
        }
    }

    /**
     * Best-effort server deregistration, then unconditional local clear: even if the network
     * call fails (offline, server already removed the device, credentials already invalid), the
     * device must still be usable to re-pair afterward — local state can never be stuck "paired".
     * Also cancels the periodic pull worker, which [clearPairing] alone does not do.
     */
    suspend fun unpairDevice(deregisterClient: DeregisterClient): DeregisterResult {
        val pairing = pairingForAuthenticatedCall()
        val networkResult = if (pairing != null) {
            deregisterClient.deregister(pairing)
        } else {
            DeregisterResult.Error("Device is not paired")
        }
        clearPairing()
        PullScheduler.cancelPeriodic(context)
        return networkResult
    }

    /** Persist the authoritative delivery mode and (derived or server-provided) pull endpoint. */
    suspend fun updateDelivery(mode: DeliveryMode, pullEndpoint: String?) {
        context.pushDataStore.edit { prefs ->
            prefs[KEY_DELIVERY_MODE] = mode.wire
            if (pullEndpoint.isNullOrBlank()) prefs.remove(KEY_PULL_ENDPOINT) else prefs[KEY_PULL_ENDPOINT] = pullEndpoint
        }
    }

    /** Persist the transport the server confirmed for the last successful registration. */
    suspend fun updateTransport(transport: String?) {
        context.pushDataStore.edit { prefs ->
            if (transport.isNullOrBlank()) prefs.remove(KEY_TRANSPORT) else prefs[KEY_TRANSPORT] = transport
        }
    }

    /**
     * Persist the UnifiedPush endpoint + WebPush encryption keys from the last successful
     * unifiedpush registration, so a later resync (e.g. the user tapping "resync", or the
     * app re-syncing on open) can resend the same endpoint/keys instead of falling back to
     * an FCM token — there is no synchronous way to re-fetch these from the UnifiedPush
     * connector, they only ever arrive via the onNewEndpoint callback. Pass all-null to clear
     * (e.g. when the confirmed transport is no longer unifiedpush).
     */
    suspend fun updateUnifiedPushRegistration(endpoint: String?, p256dh: String?, auth: String?) {
        context.pushDataStore.edit { prefs ->
            if (endpoint.isNullOrBlank()) prefs.remove(KEY_UNIFIEDPUSH_ENDPOINT) else prefs[KEY_UNIFIEDPUSH_ENDPOINT] = endpoint
            if (p256dh.isNullOrBlank()) prefs.remove(KEY_UNIFIEDPUSH_P256DH) else prefs[KEY_UNIFIEDPUSH_P256DH] = p256dh
            if (auth.isNullOrBlank()) prefs.remove(KEY_UNIFIEDPUSH_AUTH) else prefs[KEY_UNIFIEDPUSH_AUTH] = auth
        }
    }

    /**
     * The durable pull cursor for [subscriberId], defaulting to 0. Scoped to the subscriber so
     * re-pairing as a different subscriber starts from a clean cursor rather than skipping their
     * backlog.
     */
    suspend fun pullCursor(subscriberId: String): Long = pullCursorValue.get(subscriberId) ?: 0L

    /** Advance the cursor to max(existing, [cursor]); resets when the subscriber changes. */
    suspend fun advancePullCursor(subscriberId: String, cursor: Long) {
        pullCursorValue.update(subscriberId) { current -> maxOf(current ?: 0L, cursor) }
    }

    suspend fun updateSyncState(lastSyncAtEpochMs: Long?, syncError: String?) {
        context.pushDataStore.edit { prefs ->
            if (lastSyncAtEpochMs == null) prefs.remove(KEY_LAST_SYNC_AT) else prefs[KEY_LAST_SYNC_AT] = lastSyncAtEpochMs
            if (syncError.isNullOrBlank()) prefs.remove(KEY_SYNC_ERROR) else prefs[KEY_SYNC_ERROR] = syncError
        }
    }

    suspend fun appendPayload(payload: PushPayload) {
        context.pushDataStore.edit { prefs ->
            val current = decodeHistory(prefs[KEY_HISTORY_JSON])
            val updated = (listOf(payload) + current)
                .distinctBy { it.messageId }
                .take(HISTORY_LIMIT)
            prefs[KEY_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    private fun toState(prefs: Preferences, pairing: PairingData?): PushState {
        val history = decodeHistory(prefs[KEY_HISTORY_JSON])
        val pullEndpoint = prefs[KEY_PULL_ENDPOINT]
            ?: pairing?.serverUrl?.let { resolvePullEndpoint(it, null) }
        return PushState(
            pairing = pairing,
            lastTokenSyncAtEpochMs = prefs[KEY_LAST_SYNC_AT],
            syncError = prefs[KEY_SYNC_ERROR],
            history = history,
            latestPayload = history.firstOrNull(),
            deliveryMode = DeliveryMode.fromWire(prefs[KEY_DELIVERY_MODE]),
            pullEndpoint = pullEndpoint,
            transport = prefs[KEY_TRANSPORT],
            unifiedPushEndpoint = prefs[KEY_UNIFIEDPUSH_ENDPOINT],
            unifiedPushP256dh = prefs[KEY_UNIFIEDPUSH_P256DH],
            unifiedPushAuth = prefs[KEY_UNIFIEDPUSH_AUTH],
        )
    }

    private fun decodeHistory(value: String?): List<PushPayload> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PushPayload>>(value) }.getOrDefault(emptyList())
    }
}

data class PushState(
    val pairing: PairingData?,
    val lastTokenSyncAtEpochMs: Long?,
    val syncError: String?,
    val latestPayload: PushPayload?,
    val history: List<PushPayload>,
    val deliveryMode: DeliveryMode = DeliveryMode.PUSH,
    val pullEndpoint: String? = null,
    val transport: String? = null,
    val unifiedPushEndpoint: String? = null,
    val unifiedPushP256dh: String? = null,
    val unifiedPushAuth: String? = null,
)
