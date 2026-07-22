package com.urlxl.mail.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.spec.SecretKeySpec

sealed class UnlockAttemptResult {
    object Success : UnlockAttemptResult()
    data class Rejected(val delayMillis: Long) : UnlockAttemptResult()
    object Wiped : UnlockAttemptResult()
}

/**
 * In-memory app-lock state for the current process (see "Require Unlock to Open" in the
 * 2026-07-22 security-hardening spec) — "locked" means "since this process started, has the
 * correct PIN/biometric been presented," it is never persisted. [onWipe] runs
 * [SecurityWipe]'s work; kept as an injected callback rather than a direct dependency so this
 * class stays unit-testable without a Context.
 */
class AppLockManager(private val state: AppLockState, private val onWipe: () -> Unit) {
    private val _locked = MutableStateFlow(state.isLockEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile
    private var credentialKey: SecretKeySpec? = null

    fun lockNow() {
        if (state.isLockEnabled()) _locked.value = true
        credentialKey = null
    }

    fun unlockWithBiometric() {
        _locked.value = false
        state.resetFailedAttempts()
        // Biometric unlock can't derive a PIN-based key — the credential gate (Task 19-21)
        // simply stays unavailable for the rest of this session if the user unlocks via
        // biometric only, exactly as documented: it requires the PIN specifically.
    }

    /** Returns [UnlockAttemptResult.Rejected] with the delay the caller should hold the PIN
     *  field disabled for (0 for the first two wrong attempts), or [UnlockAttemptResult.Wiped]
     *  once [LockoutPolicy.WIPE_THRESHOLD] consecutive wrong attempts have accumulated — in
     *  which case [onWipe] has already run by the time this returns. */
    fun attemptPin(pin: String): UnlockAttemptResult {
        if (state.verifyPin(pin)) {
            _locked.value = false
            state.resetFailedAttempts()
            cacheCredentialKeyIfEnabled(pin)
            return UnlockAttemptResult.Success
        }
        val attempts = state.incrementFailedAttempts()
        if (LockoutPolicy.shouldWipe(attempts)) {
            onWipe()
            return UnlockAttemptResult.Wiped
        }
        val delay = LockoutPolicy.delayMillisFor(attempts)
        if (delay > 0) state.setLockoutUntilEpochMs(System.currentTimeMillis() + delay)
        return UnlockAttemptResult.Rejected(delay)
    }

    /** How long the PIN field should stay disabled for, or 0 if there's no active lockout. */
    fun remainingLockoutMillis(): Long =
        (state.lockoutUntilEpochMs() - System.currentTimeMillis()).coerceAtLeast(0L)

    /** The PIN-derived AES key for unwrapping `deviceSecret`, if "require unlock to receive
     *  push/MFA" is on and the app is currently unlocked via PIN — null otherwise, including
     *  the instant [lockNow] runs. See [com.urlxl.mail.push.SecurePairingStore]. */
    fun cachedCredentialKey(): SecretKeySpec? = credentialKey

    private fun cacheCredentialKeyIfEnabled(pin: String) {
        if (!state.isCredentialPinGateEnabled()) return
        val salt = state.credentialSalt() ?: CredentialCipher.randomSalt().also { state.setCredentialSalt(it) }
        credentialKey = CredentialCipher.deriveKey(pin, salt)
    }
}
