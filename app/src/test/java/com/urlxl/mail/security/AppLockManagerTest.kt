package com.urlxl.mail.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory [AppLockState] test double — lets [AppLockManager] be unit-tested without a
 *  real Context/Keystore, matching how [AppLockStore] backs the real interface. */
private class FakeAppLockState(
    private var lockEnabled: Boolean = true,
    private var pin: String? = "123456",
    private var credentialSalt: ByteArray? = null,
) : AppLockState {
    private var biometricEnabled = false
    private var credentialGateEnabled = false
    private var failedAttempts = 0
    private var lockoutUntil = 0L

    override fun isLockEnabled() = lockEnabled
    override fun setLockEnabled(enabled: Boolean) { lockEnabled = enabled }
    override fun isBiometricEnabled() = biometricEnabled
    override fun setBiometricEnabled(enabled: Boolean) { biometricEnabled = enabled }
    override fun isCredentialPinGateEnabled() = credentialGateEnabled
    override fun setCredentialPinGateEnabled(enabled: Boolean) { credentialGateEnabled = enabled }
    override fun setPin(pin: String) { this.pin = pin }
    override fun verifyPin(pin: String) = this.pin == pin
    override fun hasPin() = pin != null
    override fun incrementFailedAttempts(): Int { failedAttempts++; return failedAttempts }
    override fun resetFailedAttempts() { failedAttempts = 0; lockoutUntil = 0L }
    override fun lockoutUntilEpochMs() = lockoutUntil
    override fun setLockoutUntilEpochMs(epochMs: Long) { lockoutUntil = epochMs }
    override fun credentialSalt() = credentialSalt
    override fun setCredentialSalt(salt: ByteArray) { credentialSalt = salt }
    override fun reset() { lockEnabled = false; pin = null; biometricEnabled = false; credentialGateEnabled = false; failedAttempts = 0; lockoutUntil = 0L }
}

class AppLockManagerTest {
    private lateinit var state: FakeAppLockState
    private var wipeCount = 0
    private lateinit var manager: AppLockManager

    @Before
    fun setUp() {
        state = FakeAppLockState()
        wipeCount = 0
        manager = AppLockManager(state) { wipeCount++ }
    }

    @Test
    fun locked_startsTrue_whenLockEnabled() {
        assertTrue(manager.locked.value)
    }

    @Test
    fun locked_startsFalse_whenLockDisabled() {
        val disabledState = FakeAppLockState(lockEnabled = false)
        val disabledManager = AppLockManager(disabledState) {}
        assertFalse(disabledManager.locked.value)
    }

    @Test
    fun attemptPin_withCorrectPin_unlocksAndResetsAttempts() {
        val result = manager.attemptPin("123456")
        assertEquals(UnlockAttemptResult.Success, result)
        assertFalse(manager.locked.value)
    }

    @Test
    fun attemptPin_withWrongPin_staysLockedAndNoDelayFirstTwoTimes() {
        val first = manager.attemptPin("000000")
        assertTrue(first is UnlockAttemptResult.Rejected)
        assertEquals(0L, (first as UnlockAttemptResult.Rejected).delayMillis)
        assertTrue(manager.locked.value)
    }

    @Test
    fun attemptPin_escalatesDelay_fromThirdWrongAttempt() {
        repeat(2) { manager.attemptPin("000000") }
        val third = manager.attemptPin("000000") as UnlockAttemptResult.Rejected
        assertEquals(30_000L, third.delayMillis)
    }

    @Test
    fun attemptPin_wipes_afterTenWrongAttempts() {
        repeat(9) { manager.attemptPin("000000") }
        val tenth = manager.attemptPin("000000")
        assertEquals(UnlockAttemptResult.Wiped, tenth)
        assertEquals(1, wipeCount)
    }

    @Test
    fun lockNow_relocks_afterASuccessfulUnlock() {
        manager.attemptPin("123456")
        assertFalse(manager.locked.value)
        manager.lockNow()
        assertTrue(manager.locked.value)
    }

    @Test
    fun attemptPin_withCredentialGateEnabled_cachesDerivedKey_untilLocked() {
        val salt = CredentialCipher.randomSalt()
        val state = FakeAppLockState(credentialSalt = salt).apply { setCredentialPinGateEnabled(true) }
        val manager = AppLockManager(state) {}

        manager.attemptPin("123456")
        assertTrue(manager.cachedCredentialKey() != null)

        manager.lockNow()
        assertTrue(manager.cachedCredentialKey() == null)
    }

    @Test
    fun attemptPin_withCredentialGateDisabled_neverCachesAKey() {
        val manager = AppLockManager(FakeAppLockState(), {})
        manager.attemptPin("123456")
        assertTrue(manager.cachedCredentialKey() == null)
    }
}
