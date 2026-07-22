package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.push.SecurePairingStore

/**
 * Re-wraps the current pairing's `deviceSecret` behind the credential key if it was left stored
 * unwrapped despite the "require unlock to receive push/MFA" gate being on.
 *
 * This closes a gap in [com.urlxl.mail.push.PushRepository.savePairing]: it falls back to a
 * plaintext (unwrapped) save whenever the gate is on but [AppLockManager.cachedCredentialKey] is
 * null — which happens whenever a background FCM token rotation runs in a process that was never
 * unlocked this session (Android routinely restarts the app to deliver FCM callbacks, and that
 * fresh process has no PIN-derived key cached yet). The rotated secret would otherwise sit
 * unwrapped indefinitely, silently defeating the gate.
 *
 * Call this after every successful PIN unlock (see [UnlockActivity.attemptUnlock]) — at that
 * point a fresh credential key is cached and any such gap can be closed immediately.
 */
suspend fun rewrapPairingIfNeeded(context: Context, appLockManager: AppLockManager) {
    val appLockStore = AppLockStore(context)
    if (!appLockStore.isCredentialPinGateEnabled()) return

    val securePairingStore = SecurePairingStore(context)
    if (securePairingStore.isDeviceSecretWrapped()) return

    val credentialKey = appLockManager.cachedCredentialKey() ?: return
    val credentialSalt = appLockStore.credentialSalt() ?: return

    val currentPairing = securePairingStore.pairing.value ?: return
    securePairingStore.savePairing(currentPairing, credentialKey, credentialSalt)
}
