# Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Require Unlock to Open" (PIN + optional biometric), "Hostile Location Protection" (no on-device cache), an opt-in "require unlock to receive push/MFA" credential gate, plus FLAG_SECURE, backup disabling, and TOFU certificate pinning, to the kypost-android app.

**Architecture:** A new `com.urlxl.mail.security` package holds pure-logic primitives (PIN hashing, lockout policy, credential wrapping) as unit-testable plain classes, Context-dependent storage as `EncryptedSharedPreferences`/`SharedPreferences` wrappers (androidTest-covered, mirroring `SecurePairingStore`'s existing pattern), and UI glue (Activities) that wires them together untested, matching this codebase's existing split (see `PgpKeyActivity`/`PgpKeyActivityTest` — logic extracted and tested, Activity itself untested). `DataGraph` gains a disk-vs-in-memory Room seam; `EmailDetailActivity` gains an ephemeral-view path for attachments; `SecurePairingStore` gains an optional PIN-wrap layer for `deviceSecret`.

**Tech Stack:** Kotlin, Room 2.8.4, `androidx.security.crypto` 1.1.0 (already present), `androidx.biometric` (new), OkHttp 5.2.1, JUnit4 (unit tests), AndroidJUnit4/Espresso (androidTest).

## Global Constraints

- Full design/rationale lives in `docs/superpowers/specs/2026-07-22-security-hardening-design.md` — this plan implements it; do not re-derive decisions already made there.
- No on-device PGP private key exists anywhere in this app (decryption happens server-side) — nothing in this plan needs to protect key material, only cached mail/contacts/attachments and the pairing credential.
- Toggle 2 (Hostile Location Protection) and toggle 3 (credential PIN-gate) are both **disabled and greyed out in the UI unless toggle 1 (Require Unlock to Open) is on** — enforce this in `SecuritySettingsActivity`, not just by convention.
- 6-digit numeric PIN. Lockout: attempts 1–2 plain error; attempt 3+ escalating delay 30s/1min/5min/15min/30min (cap); attempt 10 in a row without an intervening success triggers a full wipe.
- Hostile Location Protection wipes existing cache immediately on enable and requires an automatic app relaunch to take effect (Room instance swap).
- Toggle 3 is off by default and requires an explicit warning dialog before enabling.
- Follow existing package/file conventions: settings screens are `ScrollView`/`LinearLayout` (`KeywordSettingsActivity`) or XML-layout (`ThemesActivity`/`PgpKeyActivity`) Activities using `applyThemeToActivity`/`applyTopInsetWithHeader`; encrypted storage mirrors `SecurePairingStore`; per-feature singletons mirror `SingletonGraph`.
- Minimum SDK is 31 — no need to support anything older.

---

## Task 1: Add the biometric dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `androidx.biometric:biometric` on the classpath (`BiometricPrompt`, `BiometricManager`), used starting Task 13.

- [ ] **Step 1: Add the version + library entry**

In `gradle/libs.versions.toml`, add to `[versions]` (near `securityCrypto`):

```toml
biometric = "1.1.0"
```

Add to `[libraries]` (near `androidx-security-crypto`):

```toml
androidx-biometric = { group = "androidx.biometric", name = "biometric", version.ref = "biometric" }
```

- [ ] **Step 2: Add the dependency**

In `app/build.gradle.kts`, in the `dependencies` block, add next to `implementation(libs.androidx.security.crypto)`:

```kotlin
implementation(libs.androidx.biometric)
```

- [ ] **Step 3: Sync and verify the build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (this only adds a dependency; nothing references it yet).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "android: add androidx.biometric dependency"
```

---

## Task 2: PinHasher (PBKDF2 hashing + constant-time verification)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/PinHasher.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/PinHasherTest.kt`

**Interfaces:**
- Produces: `data class PinHash(val salt: ByteArray, val hash: ByteArray)`, `object PinHasher { fun hash(pin: String, salt: ByteArray = randomSalt()): PinHash; fun matches(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean; fun randomSalt(): ByteArray }`. Consumed by `AppLockStore` (Task 4) and `AppLockManager` (Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun matches_returnsTrue_forCorrectPin() {
        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash("123456", salt)
        assertTrue(PinHasher.matches("123456", salt, hash.hash))
    }

    @Test
    fun matches_returnsFalse_forWrongPin() {
        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash("123456", salt)
        assertFalse(PinHasher.matches("654321", salt, hash.hash))
    }

    @Test
    fun hash_isDeterministic_forSameSalt() {
        val salt = PinHasher.randomSalt()
        val first = PinHasher.hash("123456", salt)
        val second = PinHasher.hash("123456", salt)
        assertTrue(first.hash.contentEquals(second.hash))
    }

    @Test
    fun randomSalt_producesDifferentValues() {
        val a = PinHasher.randomSalt()
        val b = PinHasher.randomSalt()
        assertFalse(a.contentEquals(b))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.PinHasherTest"`
Expected: FAIL (compile error — `PinHasher` doesn't exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.PinHasherTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/PinHasher.kt app/src/test/java/com/urlxl/mail/security/PinHasherTest.kt
git commit -m "android: add PBKDF2 PIN hashing for app lock"
```

---

## Task 3: LockoutPolicy (escalating delay + wipe threshold)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/LockoutPolicy.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/LockoutPolicyTest.kt`

**Interfaces:**
- Produces: `object LockoutPolicy { const val WIPE_THRESHOLD: Int; fun delayMillisFor(attemptCount: Int): Long; fun shouldWipe(attemptCount: Int): Boolean }`. Consumed by `AppLockManager` (Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockoutPolicyTest {
    @Test
    fun delayMillisFor_isZero_forFirstTwoAttempts() {
        assertEquals(0L, LockoutPolicy.delayMillisFor(1))
        assertEquals(0L, LockoutPolicy.delayMillisFor(2))
    }

    @Test
    fun delayMillisFor_escalates_fromThirdAttempt() {
        assertEquals(30_000L, LockoutPolicy.delayMillisFor(3))
        assertEquals(60_000L, LockoutPolicy.delayMillisFor(4))
        assertEquals(300_000L, LockoutPolicy.delayMillisFor(5))
        assertEquals(900_000L, LockoutPolicy.delayMillisFor(6))
        assertEquals(1_800_000L, LockoutPolicy.delayMillisFor(7))
    }

    @Test
    fun delayMillisFor_caps_atThirtyMinutes() {
        assertEquals(1_800_000L, LockoutPolicy.delayMillisFor(8))
        assertEquals(1_800_000L, LockoutPolicy.delayMillisFor(9))
    }

    @Test
    fun shouldWipe_isFalse_belowThreshold() {
        assertFalse(LockoutPolicy.shouldWipe(9))
    }

    @Test
    fun shouldWipe_isTrue_atThreshold() {
        assertTrue(LockoutPolicy.shouldWipe(10))
        assertTrue(LockoutPolicy.shouldWipe(11))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.LockoutPolicyTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

/**
 * Escalating-delay + eventual-wipe lockout curve for wrong app-lock PIN attempts (see
 * "Require Unlock to Open" in the 2026-07-22 security-hardening spec). Attempts 1-2 are free
 * (typos happen); attempt 3 onward adds a growing delay before the next try is allowed;
 * [WIPE_THRESHOLD] consecutive wrong attempts (no intervening correct PIN/biometric) wipes
 * local data via [SecurityWipe].
 */
object LockoutPolicy {
    private val DELAYS_MS = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 1_800_000L)
    private const val FIRST_DELAYED_ATTEMPT = 3
    const val WIPE_THRESHOLD = 10

    fun delayMillisFor(attemptCount: Int): Long {
        if (attemptCount < FIRST_DELAYED_ATTEMPT) return 0L
        val index = (attemptCount - FIRST_DELAYED_ATTEMPT).coerceAtMost(DELAYS_MS.size - 1)
        return DELAYS_MS[index]
    }

    fun shouldWipe(attemptCount: Int): Boolean = attemptCount >= WIPE_THRESHOLD
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.LockoutPolicyTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/LockoutPolicy.kt app/src/test/java/com/urlxl/mail/security/LockoutPolicyTest.kt
git commit -m "android: add app-lock lockout delay/wipe policy"
```

---

## Task 4: AppLockState interface + AppLockStore (encrypted PIN/lock storage)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/AppLockStore.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/security/AppLockStoreTest.kt`

**Interfaces:**
- Produces: `interface AppLockState` (see below) and `class AppLockStore(context: Context) : AppLockState`. Consumed by `AppLockManager` (Task 6), `UnlockActivity` (Task 13), `SecuritySettingsActivity` (Task 16).
- Consumes: `PinHasher`/`PinHash` (Task 2).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetState() {
        AppLockStore(context).reset()
    }

    @Test
    fun setPin_thenVerifyPin_succeedsWithCorrectPin() {
        val store = AppLockStore(context)
        store.setPin("123456")
        assertTrue(store.verifyPin("123456"))
    }

    @Test
    fun verifyPin_fails_withWrongPin() {
        val store = AppLockStore(context)
        store.setPin("123456")
        assertFalse(store.verifyPin("000000"))
    }

    @Test
    fun lockEnabled_defaultsFalse_andPersistsWhenSet() {
        val store = AppLockStore(context)
        assertFalse(store.isLockEnabled())
        store.setLockEnabled(true)
        assertTrue(AppLockStore(context).isLockEnabled())
    }

    @Test
    fun failedAttempts_incrementAndReset() {
        val store = AppLockStore(context)
        assertEquals(1, store.incrementFailedAttempts())
        assertEquals(2, store.incrementFailedAttempts())
        store.resetFailedAttempts()
        assertEquals(1, store.incrementFailedAttempts())
    }

    @Test
    fun reset_clearsPinAndLockState() {
        val store = AppLockStore(context)
        store.setPin("123456")
        store.setLockEnabled(true)
        store.incrementFailedAttempts()

        store.reset()

        val fresh = AppLockStore(context)
        assertFalse(fresh.isLockEnabled())
        assertFalse(fresh.verifyPin("123456"))
        assertEquals(1, fresh.incrementFailedAttempts())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.AppLockStoreTest"`
Expected: FAIL (compile error — `AppLockStore` doesn't exist)

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.AppLockStoreTest"`
Expected: PASS (5 tests) on a connected device/emulator.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/AppLockStore.kt app/src/androidTest/java/com/urlxl/mail/security/AppLockStoreTest.kt
git commit -m "android: add encrypted AppLockStore for the app-lock PIN"
```

---

## Task 5: HostileLocationSettings (non-secret cache-mode flag)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/HostileLocationSettings.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/security/HostileLocationSettingsTest.kt`

**Interfaces:**
- Produces: `class HostileLocationSettings(context: Context) { fun isEnabled(): Boolean; fun setEnabled(enabled: Boolean) }`. Consumed by `DataGraph` (Task 9), `EmailDetailActivity` (Task 12), `SecuritySettingsActivity` (Task 17).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostileLocationSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetState() {
        HostileLocationSettings(context).setEnabled(false)
    }

    @Test
    fun isEnabled_defaultsFalse() {
        assertFalse(HostileLocationSettings(context).isEnabled())
    }

    @Test
    fun setEnabled_persistsAcrossInstances() {
        HostileLocationSettings(context).setEnabled(true)
        assertTrue(HostileLocationSettings(context).isEnabled())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.HostileLocationSettingsTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "com.urlxl.mail.hostile_location_settings"
private const val KEY_ENABLED = "enabled"

/**
 * Whether Hostile Location Protection is on (see the 2026-07-22 security-hardening spec) — a
 * plain, unencrypted flag (not a secret) that [com.urlxl.mail.data.DataGraph] reads at Room
 * construction time to decide disk-backed vs in-memory-only storage.
 */
class HostileLocationSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.HostileLocationSettingsTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/HostileLocationSettings.kt app/src/androidTest/java/com/urlxl/mail/security/HostileLocationSettingsTest.kt
git commit -m "android: add HostileLocationSettings flag store"
```

---

## Task 6: AppLockManager (in-memory lock state + lockout orchestration)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/AppLockManager.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/AppLockManagerTest.kt`

**Interfaces:**
- Consumes: `AppLockState` (Task 4), `LockoutPolicy` (Task 3).
- Produces: `sealed class UnlockAttemptResult { object Success; data class Rejected(val delayMillis: Long); object Wiped }`, `class AppLockManager(private val state: AppLockState, private val onWipe: () -> Unit) { val locked: StateFlow<Boolean>; fun lockNow(); fun unlockWithBiometric(); fun attemptPin(pin: String): UnlockAttemptResult; fun remainingLockoutMillis(): Long }`. Consumed by `KyPostApp` (Task 15), `UnlockActivity` (Task 13/14).

- [ ] **Step 1: Write the failing test**

```kotlin
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AppLockManagerTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun lockNow() {
        if (state.isLockEnabled()) _locked.value = true
    }

    fun unlockWithBiometric() {
        _locked.value = false
        state.resetFailedAttempts()
    }

    /** Returns [UnlockAttemptResult.Rejected] with the delay the caller should hold the PIN
     *  field disabled for (0 for the first two wrong attempts), or [UnlockAttemptResult.Wiped]
     *  once [LockoutPolicy.WIPE_THRESHOLD] consecutive wrong attempts have accumulated — in
     *  which case [onWipe] has already run by the time this returns. */
    fun attemptPin(pin: String): UnlockAttemptResult {
        if (state.verifyPin(pin)) {
            _locked.value = false
            state.resetFailedAttempts()
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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AppLockManagerTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/AppLockManager.kt app/src/test/java/com/urlxl/mail/security/AppLockManagerTest.kt
git commit -m "android: add AppLockManager lock state and lockout orchestration"
```

---

## Task 7: SecurityWipe (shared destructive reset)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/SecurityWipe.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/security/SecurityWipeTest.kt`

**Interfaces:**
- Consumes: `AppLockStore.reset()` (Task 4), `com.urlxl.mail.push.SecurePairingStore.clearPairing()` (existing), `com.urlxl.mail.data.DataRuntime` (existing).
- Produces: `object SecurityWipe { suspend fun wipeAndResetApp(context: Context) }`. Consumed by `AppLockManager`'s `onWipe` wiring in Task 15, and toggle-1 disable flow in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urlxl.mail.push.PairingData
import com.urlxl.mail.push.SecurePairingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityWipeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun wipeAndResetApp_clearsPinPairingAndLockState() = runBlocking {
        val appLockStore = AppLockStore(context)
        appLockStore.setPin("123456")
        appLockStore.setLockEnabled(true)

        SecurePairingStore(context).savePairing(
            PairingData(
                subscriberId = "sub", serverUrl = "https://example.com",
                registrationUrl = "https://example.com/register", pairingToken = "token",
                deviceId = "device", deviceSecret = "secret", pairedAtEpochMs = 1L,
            ),
        )

        SecurityWipe.wipeAndResetApp(context)

        assertFalse(AppLockStore(context).isLockEnabled())
        assertFalse(AppLockStore(context).verifyPin("123456"))
        assertNull(SecurePairingStore(context).pairing.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.SecurityWipeTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.SecurePairingStore

/**
 * Full destructive reset: runs when [LockoutPolicy.WIPE_THRESHOLD] wrong PIN attempts
 * accumulate, and when the user explicitly turns "Require Unlock to Open" off (which also
 * clears the PIN, since a stale PIN with lock disabled would be confusing state). Closes and
 * deletes the Room database, clears pairing credentials (forcing re-pairing), and clears the
 * app-lock PIN/flags — the app ends up in exactly its first-run state.
 */
object SecurityWipe {
    suspend fun wipeAndResetApp(context: Context) {
        DataRuntime.graph(context).database.close()
        context.deleteDatabase("kypost_mail.db")
        SecurePairingStore(context).clearPairing()
        AppLockStore(context).reset()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.SecurityWipeTest"`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/SecurityWipe.kt app/src/androidTest/java/com/urlxl/mail/security/SecurityWipeTest.kt
git commit -m "android: add SecurityWipe full-reset helper"
```

---

## Task 8: AppRestart (relaunch the app process)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/AppRestart.kt`

**Interfaces:**
- Produces: `object AppRestart { fun relaunch(context: Context) }`. Consumed by the Hostile Location Protection toggle (Task 17).

No dedicated test: this kills the current process by design (`Process.killProcess`), which a JVM/instrumented test process cannot safely exercise on itself — the same reason `KyPostApp` itself (also process-lifecycle glue) has no test in this codebase.

- [ ] **Step 1: Write the implementation directly**

```kotlin
package com.urlxl.mail.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.urlxl.mail.MainActivity

private const val RESTART_DELAY_MILLIS = 300L

/**
 * Kills and relaunches the app process. Needed whenever a setting requires a fresh
 * [com.urlxl.mail.data.DataGraph] — Room's Java API has no supported way to hot-swap a live
 * database instance out from under Activities/ViewModels that already hold references to the
 * old one, so a clean process restart is the correct fix, not a workaround. Schedules
 * `MainActivity` to launch a few hundred ms in the future via `AlarmManager` (survives the
 * process actually dying, unlike a plain `Handler.postDelayed`), then kills this process.
 */
object AppRestart {
    fun relaunch(context: Context) {
        val appContext = context.applicationContext
        val restartIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Non-exact set() deliberately, not setExact(): API 33+ requires SCHEDULE_EXACT_ALARM/
        // USE_EXACT_ALARM for the exact variants (undeclared here, and unnecessary — this only
        // needs "a few hundred ms" tolerance, not millisecond precision).
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
            pendingIntent,
        )
        Process.killProcess(Process.myPid())
    }
}
```

- [ ] **Step 2: Verify the build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/AppRestart.kt
git commit -m "android: add AppRestart process-relaunch helper"
```

---

## Task 9: DataGraph in-memory Room swap (Hostile Location Protection data layer)

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/data/DataRuntime.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/data/DataGraphHostileLocationTest.kt`

**Interfaces:**
- Consumes: `HostileLocationSettings` (Task 5).
- Produces: unchanged `DataGraph.database: AppDatabase` — every existing repository/DAO is untouched.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.urlxl.mail.security.HostileLocationSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataGraphHostileLocationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() {
        HostileLocationSettings(context).setEnabled(false)
        context.deleteDatabase("kypost_mail.db")
    }

    @Test
    fun disabled_createsOnDiskDatabaseFile() {
        HostileLocationSettings(context).setEnabled(false)
        val graph = DataGraph(context)
        graph.database.openHelper.writableDatabase // force creation
        val dbFile = context.getDatabasePath("kypost_mail.db")
        assertTrue(dbFile.exists())
        graph.database.close()
    }

    @Test
    fun enabled_neverCreatesAnOnDiskDatabaseFile() {
        context.deleteDatabase("kypost_mail.db")
        HostileLocationSettings(context).setEnabled(true)
        val graph = DataGraph(context)
        graph.database.openHelper.writableDatabase // force creation — should stay in memory
        val dbFile = context.getDatabasePath("kypost_mail.db")
        assertFalse(dbFile.exists())
        graph.database.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.data.DataGraphHostileLocationTest"`
Expected: FAIL — `enabled_neverCreatesAnOnDiskDatabaseFile` fails because `DataGraph` always builds a disk-backed database today.

- [ ] **Step 3: Modify `DataGraph`**

Read the current file first (`app/src/main/java/com/urlxl/mail/data/DataRuntime.kt`), then replace its contents:

```kotlin
package com.urlxl.mail.data

import android.content.Context
import androidx.room.Room
import com.urlxl.mail.SingletonGraph
import com.urlxl.mail.security.HostileLocationSettings

class DataGraph(context: Context) {
    private val appContext = context.applicationContext

    /**
     * In-memory when Hostile Location Protection is on (see the 2026-07-22 security-hardening
     * spec) — every repository/DAO is unchanged either way, since both builders produce the
     * same [AppDatabase] type; only where its rows live differs. Toggling the setting requires
     * an app relaunch ([com.urlxl.mail.security.AppRestart]) since this decision is only made
     * once, at construction time.
     */
    val database: AppDatabase = if (HostileLocationSettings(appContext).isEnabled()) {
        Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java).build()
    } else {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "kypost_mail.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()
    }
}

/** Standalone singleton, kept independent of PushGraph/KyPostApp — mirrors how PushGraph itself
 *  stands alone rather than nesting inside another graph. */
object DataRuntime {
    private val holder = SingletonGraph(::DataGraph)

    fun graph(context: Context): DataGraph = holder.get(context)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.data.DataGraphHostileLocationTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/data/DataRuntime.kt app/src/androidTest/java/com/urlxl/mail/data/DataGraphHostileLocationTest.kt
git commit -m "android: swap Room to in-memory when Hostile Location Protection is on"
```

---

## Task 10: attachmentActionFor (pure decision: view-ephemeral vs save-to-Downloads)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/AttachmentAction.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/AttachmentActionTest.kt`

**Interfaces:**
- Produces: `enum class AttachmentAction { VIEW_EPHEMERAL, SAVE_TO_DOWNLOADS }`, `fun attachmentActionFor(hostileLocationProtectionEnabled: Boolean): AttachmentAction`. Consumed by `EmailDetailActivity` (Task 12).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentActionTest {
    @Test
    fun forSettings_viewsEphemerally_whenProtectionEnabled() {
        assertEquals(AttachmentAction.VIEW_EPHEMERAL, attachmentActionFor(hostileLocationProtectionEnabled = true))
    }

    @Test
    fun forSettings_savesToDownloads_whenProtectionDisabled() {
        assertEquals(AttachmentAction.SAVE_TO_DOWNLOADS, attachmentActionFor(hostileLocationProtectionEnabled = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AttachmentActionTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

/** Whether a tapped attachment should be viewed ephemerally (no disk write at all) or saved to
 *  the public Downloads collection — see "Attachments" under Hostile Location Protection in the
 *  2026-07-22 security-hardening spec. */
enum class AttachmentAction { VIEW_EPHEMERAL, SAVE_TO_DOWNLOADS }

fun attachmentActionFor(hostileLocationProtectionEnabled: Boolean): AttachmentAction =
    if (hostileLocationProtectionEnabled) AttachmentAction.VIEW_EPHEMERAL else AttachmentAction.SAVE_TO_DOWNLOADS
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AttachmentActionTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/AttachmentAction.kt app/src/test/java/com/urlxl/mail/security/AttachmentActionTest.kt
git commit -m "android: add attachmentActionFor decision helper"
```

---

## Task 11: EphemeralAttachmentProvider (disk-free attachment viewing)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/EphemeralAttachmentProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/urlxl/mail/security/EphemeralAttachmentProviderTest.kt`

**Interfaces:**
- Produces: `object EphemeralAttachmentBytes { fun register(bytes: ByteArray, mimeType: String): Uri }`, `class EphemeralAttachmentProvider : ContentProvider()`. Consumed by `EmailDetailActivity` (Task 12).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EphemeralAttachmentProviderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun register_thenRead_roundTripsBytesAndMimeType() {
        val bytes = "hello attachment".toByteArray()
        val uri = EphemeralAttachmentBytes.register(bytes, "text/plain")

        assertEquals("text/plain", context.contentResolver.getType(uri))

        val readBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        assertArrayEquals(bytes, readBytes)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.EphemeralAttachmentProviderTest"`
Expected: FAIL (compile error — class and manifest entry don't exist)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val AUTHORITY_SUFFIX = ".ephemeralattachments"

internal data class PendingAttachment(val bytes: ByteArray, val mimeType: String)

/**
 * In-memory holder for attachment bytes awaiting a single ephemeral read, keyed by a one-time
 * token. Nothing here is ever written to disk — see [EphemeralAttachmentProvider], the
 * `ContentProvider` that actually serves these bytes to a viewer app.
 */
object EphemeralAttachmentBytes {
    private val pending = ConcurrentHashMap<String, PendingAttachment>()
    private var authority: String = ""

    internal fun configure(authority: String) {
        this.authority = authority
    }

    fun register(bytes: ByteArray, mimeType: String): Uri {
        val token = UUID.randomUUID().toString()
        pending[token] = PendingAttachment(bytes, mimeType)
        return Uri.parse("content://$authority/$token")
    }

    internal fun take(token: String): PendingAttachment? = pending.remove(token)

    internal fun peekMimeType(token: String): String? = pending[token]?.mimeType
}

/**
 * Serves attachment bytes registered via [EphemeralAttachmentBytes.register] through a pipe,
 * never a file — see "Attachments" under Hostile Location Protection in the 2026-07-22
 * security-hardening spec. Each token is single-use: [EphemeralAttachmentBytes.take] removes it
 * from memory the moment this provider starts serving it.
 */
class EphemeralAttachmentProvider : ContentProvider() {
    override fun attachInfo(context: android.content.Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        EphemeralAttachmentBytes.configure(info.authority)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = EphemeralAttachmentBytes.peekMimeType(tokenFrom(uri))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val attachment = EphemeralAttachmentBytes.take(tokenFrom(uri))
            ?: throw IOException("Attachment already consumed or unknown: $uri")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(attachment.bytes) }
        }.start()
        return readSide
    }

    private fun tokenFrom(uri: Uri): String = uri.lastPathSegment.orEmpty()

    // Not a real data table — attachments are single-use byte streams, not queryable rows.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
```

- [ ] **Step 4: Register the provider in the manifest**

In `app/src/main/AndroidManifest.xml`, add inside `<application>`, alongside the other component declarations:

```xml
<provider
    android:name=".security.EphemeralAttachmentProvider"
    android:authorities="${applicationId}.ephemeralattachments"
    android:exported="false"
    android:grantUriPermissions="true" />
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.security.EphemeralAttachmentProviderTest"`
Expected: PASS (1 test)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/EphemeralAttachmentProvider.kt app/src/main/AndroidManifest.xml app/src/androidTest/java/com/urlxl/mail/security/EphemeralAttachmentProviderTest.kt
git commit -m "android: add EphemeralAttachmentProvider for disk-free attachment viewing"
```

---

## Task 12: Wire attachment viewing into EmailDetailActivity

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/EmailDetailActivity.kt:204-236`

**Interfaces:**
- Consumes: `attachmentActionFor` (Task 10), `EphemeralAttachmentBytes.register` (Task 11), `HostileLocationSettings` (Task 5).

No new automated test: this is Activity UI glue wiring already-tested pieces together (`attachmentActionFor` and `EphemeralAttachmentBytes` each have their own tests) — matches this codebase's existing convention of leaving Activity wiring itself untested (e.g. `PgpKeyActivity`).

- [ ] **Step 1: Read the current attachment-handling code**

Read `app/src/main/java/com/urlxl/mail/EmailDetailActivity.kt` around lines 195-236 (the `downloadAttachment`/`saveToDownloads` functions) to confirm line numbers before editing — other work may have shifted them since this plan was written.

- [ ] **Step 2: Replace `downloadAttachment` with a branching version**

```kotlin
private fun downloadAttachment(emailId: String, emailFolder: String, info: AttachmentInfo) {
    val hostileLocationProtectionEnabled = com.urlxl.mail.security.HostileLocationSettings(this).isEnabled()
    val action = com.urlxl.mail.security.attachmentActionFor(hostileLocationProtectionEnabled)
    val loadingMessage = if (action == com.urlxl.mail.security.AttachmentAction.VIEW_EPHEMERAL) {
        getString(R.string.attachment_opening, info.name)
    } else {
        getString(R.string.attachment_downloading, info.name)
    }
    Toast.makeText(this, loadingMessage, Toast.LENGTH_SHORT).show()
    ioExecutor.execute {
        val outcome = mailRepository.downloadAttachment(emailId, emailFolder, info.index)
        val downloaded = (outcome as? MailOutcome.Success)?.value
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (downloaded == null) {
                val message = outcome.userFacingMessage() ?: getString(R.string.attachment_save_failed, info.name)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            when (action) {
                com.urlxl.mail.security.AttachmentAction.VIEW_EPHEMERAL -> viewAttachmentEphemerally(downloaded)
                com.urlxl.mail.security.AttachmentAction.SAVE_TO_DOWNLOADS -> {
                    val saved = saveToDownloads(downloaded.name, downloaded.mimeType, downloaded.bytes)
                    val message = if (saved) getString(R.string.attachment_saved, info.name) else getString(R.string.attachment_save_failed, info.name)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Hostile Location Protection path: hands the bytes to [com.urlxl.mail.security.EphemeralAttachmentBytes]
 *  (never written to disk) and launches a viewer via ACTION_VIEW — nothing is saved anywhere. */
private fun viewAttachmentEphemerally(downloaded: DownloadedAttachment) {
    val uri = com.urlxl.mail.security.EphemeralAttachmentBytes.register(downloaded.bytes, downloaded.mimeType)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, downloaded.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(intent) }.onFailure {
        Toast.makeText(this, getString(R.string.attachment_save_failed, downloaded.name), Toast.LENGTH_LONG).show()
    }
}
```

Remove the old `downloadAttachment` body (the `saveToDownloads`-only version) — `saveToDownloads` itself is unchanged and still called from the `SAVE_TO_DOWNLOADS` branch above.

- [ ] **Step 3: Update the attachment chip label when protection is on**

In the same file, where the attachment `Chip` is built (around line 195-198), change the label so the changed behavior is visible, not silent:

```kotlin
val chip = Chip(this).apply {
    val protectionEnabled = com.urlxl.mail.security.HostileLocationSettings(this@EmailDetailActivity).isEnabled()
    text = if (protectionEnabled) "👁 ${info.name}" else "📎 ${info.name}"
    setOnClickListener { downloadAttachment(emailId, emailFolder, info) }
}
```

- [ ] **Step 4: Add the two new strings**

In `app/src/main/res/values/strings.xml`, alongside the existing `attachment_downloading`/`attachment_saved`/`attachment_save_failed` strings:

```xml
<string name="attachment_opening">Opening %1$s…</string>
```

- [ ] **Step 5: Build and manually verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

Manual check (this plan's one UI feature without an automated test — see `superpowers:verification-before-completion`): install the debug build, enable Hostile Location Protection once Task 17 is done, open an email with an attachment, confirm tapping it opens a viewer app instead of saving to Downloads, and confirm the Downloads app shows nothing new afterward.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/EmailDetailActivity.kt app/src/main/res/values/strings.xml
git commit -m "android: view attachments ephemerally under Hostile Location Protection"
```

---

## Task 13: SecurityGraph singleton + UnlockActivity (PIN entry UI)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/SecurityGraph.kt`
- Create: `app/src/main/java/com/urlxl/mail/security/UnlockActivity.kt`
- Create: `app/src/main/res/layout/activity_unlock.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AppLockManager`, `AppLockStore` (Task 4, 6), `SecurityWipe` (Task 7).
- Produces: `class SecurityGraph(context: Context) { val appLockManager: AppLockManager }` plus a separate `object SecurityRuntime { fun graph(context: Context): SecurityGraph }` (mirrors `PushGraph`/`PushRuntime` and `DataGraph`/`DataRuntime`'s exact split — a plain graph class plus a standalone `SingletonGraph`-backed runtime object, not an embedded companion). Consumed by `KyPostApp` (Task 15), `SecuritySettingsActivity` (Task 16), and `PushRepository` (Task 21) as `SecurityRuntime.graph(context)`.

No dedicated test — this is Activity UI plus a thin DI singleton; the logic it wires together (`AppLockManager.attemptPin`) already has its own unit tests from Task 6.

- [ ] **Step 1: Add the SecurityGraph singleton**

```kotlin
package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.SingletonGraph
import kotlinx.coroutines.runBlocking

class SecurityGraph(context: Context) {
    private val appContext = context.applicationContext
    val appLockManager: AppLockManager = AppLockManager(AppLockStore(appContext)) {
        runBlocking { SecurityWipe.wipeAndResetApp(appContext) }
    }
}

/** Standalone singleton, kept independent of other runtimes — mirrors how `PushRuntime`/
 *  `DataRuntime` each stand alone rather than nesting inside their graph classes. */
object SecurityRuntime {
    private val holder = SingletonGraph(::SecurityGraph)

    fun graph(context: Context): SecurityGraph = holder.get(context)
}
```

- [ ] **Step 2: Add the layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/unlockRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp">

    <TextView
        android:id="@+id/unlockTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/unlock_title"
        android:textSize="20sp"
        android:layout_marginBottom="24dp" />

    <EditText
        android:id="@+id/unlockPinField"
        android:layout_width="200dp"
        android:layout_height="wrap_content"
        android:inputType="numberPassword"
        android:maxLength="6"
        android:gravity="center"
        android:textSize="28sp"
        android:hint="@string/unlock_pin_hint" />

    <TextView
        android:id="@+id/unlockErrorText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textColor="#CC3333"
        android:visibility="gone" />

    <Button
        android:id="@+id/unlockSubmitButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/unlock_button" />

</LinearLayout>
```

- [ ] **Step 3: Add strings**

```xml
<string name="unlock_title">Enter your PIN to continue</string>
<string name="unlock_pin_hint">6-digit PIN</string>
<string name="unlock_button">Unlock</string>
<string name="unlock_wrong_pin">Wrong PIN</string>
<string name="unlock_locked_out">Too many attempts — try again in %1$s</string>
```

- [ ] **Step 4: Write the Activity**

```kotlin
package com.urlxl.mail.security

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.urlxl.mail.R
import com.urlxl.mail.applyThemeToActivity
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Full-screen PIN gate shown whenever [AppLockManager.locked] is true — see "Require Unlock to
 * Open" in the 2026-07-22 security-hardening spec. Biometric unlock (Task 14) layers on top of
 * this; the PIN field here is always present as the fallback.
 */
class UnlockActivity : AppCompatActivity() {
    private lateinit var pinField: EditText
    private lateinit var errorText: TextView
    private lateinit var submitButton: Button
    private lateinit var appLockManager: AppLockManager
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)
        applyThemeToActivity(this)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)

        appLockManager = SecurityRuntime.graph(this).appLockManager

        pinField = findViewById(R.id.unlockPinField)
        errorText = findViewById(R.id.unlockErrorText)
        submitButton = findViewById(R.id.unlockSubmitButton)
        submitButton.setOnClickListener { attemptUnlock() }
    }

    override fun onResume() {
        super.onResume()
        applyRemainingLockout()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdown?.cancel()
    }

    private fun attemptUnlock() {
        val pin = pinField.text.toString()
        when (val result = appLockManager.attemptPin(pin)) {
            is UnlockAttemptResult.Success -> finish()
            is UnlockAttemptResult.Wiped -> restartToFirstRun()
            is UnlockAttemptResult.Rejected -> {
                pinField.text.clear()
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.unlock_wrong_pin)
                if (result.delayMillis > 0) applyRemainingLockout()
            }
        }
    }

    private fun applyRemainingLockout() {
        val remaining = appLockManager.remainingLockoutMillis()
        countdown?.cancel()
        if (remaining <= 0) {
            submitButton.isEnabled = true
            pinField.isEnabled = true
            return
        }
        submitButton.isEnabled = false
        pinField.isEnabled = false
        countdown = object : CountDownTimer(remaining, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.unlock_locked_out, "${millisUntilFinished / 1_000}s")
            }

            override fun onFinish() {
                submitButton.isEnabled = true
                pinField.isEnabled = true
                errorText.visibility = View.GONE
            }
        }.start()
    }

    private fun restartToFirstRun() {
        lifecycleScope.launch {
            // SecurityWipe already ran (inside AppLockManager.attemptPin's onWipe callback) by
            // the time UnlockAttemptResult.Wiped is returned — this just relaunches so the app
            // picks up the now-cleared state.
            AppRestart.relaunch(this@UnlockActivity)
        }
    }
}
```

- [ ] **Step 5: Register the Activity in the manifest**

```xml
<activity
    android:name=".security.UnlockActivity"
    android:exported="false"
    android:launchMode="singleInstance"
    android:excludeFromRecents="true" />
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/SecurityGraph.kt app/src/main/java/com/urlxl/mail/security/UnlockActivity.kt app/src/main/res/layout/activity_unlock.xml app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "android: add SecurityGraph singleton and UnlockActivity PIN entry screen"
```

---

## Task 14: Biometric unlock in UnlockActivity

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/security/UnlockActivity.kt`

**Interfaces:**
- Consumes: `androidx.biometric.BiometricPrompt`/`BiometricManager` (Task 1), `AppLockStore.isBiometricEnabled()` (Task 4), `AppLockManager.unlockWithBiometric()` (Task 6).

No dedicated test — `BiometricPrompt` requires real hardware/enrolled biometrics and has no meaningful unit-test surface; this mirrors how this codebase leaves other hardware-integration glue (e.g. the QR scanner in `PgpKeyActivity.scanQr`) untested.

- [ ] **Step 1: Add biometric prompt wiring**

In `UnlockActivity.kt`, add to `onCreate` (after the existing view lookups) and a new method:

```kotlin
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
```

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ...existing body...
    if (AppLockStore(this).isBiometricEnabled()) {
        showBiometricPromptIfAvailable()
    }
}

private fun showBiometricPromptIfAvailable() {
    val biometricManager = BiometricManager.from(this)
    val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) return

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.unlock_title))
        .setNegativeButtonText(getString(R.string.unlock_use_pin_button))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()

    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                appLockManager.unlockWithBiometric()
                finish()
            }
            // onAuthenticationError (includes the user tapping "Use PIN") and
            // onAuthenticationFailed both just leave the always-visible PIN field as the
            // fallback — no separate handling needed.
        },
    )
    prompt.authenticate(promptInfo)
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Add the one new string**

```xml
<string name="unlock_use_pin_button">Use PIN</string>
```

- [ ] **Step 4: Manually verify**

Manual check (no automated test covers biometric hardware): on a device/emulator with a fingerprint or face enrolled, enable both "Require Unlock to Open" and "Use biometric unlock" (Task 16), background and re-foreground the app, confirm the biometric prompt appears automatically and that tapping "Use PIN" dismisses it leaving the PIN field usable.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/UnlockActivity.kt app/src/main/res/values/strings.xml
git commit -m "android: add biometric unlock to UnlockActivity"
```

---

## Task 15: Wire AppLockManager into KyPostApp's process lifecycle

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/KyPostApp.kt`

**Interfaces:**
- Consumes: `SecurityGraph` (created in Task 13), `UnlockActivity` (Task 13).

No dedicated test: this is process-lifecycle glue identical in kind to `KyPostApp`'s existing untested `onStart`/`onCreate` — the behavior it delegates to (`AppLockManager`) already has full unit-test coverage from Task 6.

- [ ] **Step 1: Hook into KyPostApp's ProcessLifecycleOwner observer**

Read `app/src/main/java/com/urlxl/mail/KyPostApp.kt` first, then modify:

```kotlin
package com.urlxl.mail

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.urlxl.mail.contacts.ContactsRuntime
import com.urlxl.mail.contacts.device.DeviceContactsRuntime
import com.urlxl.mail.push.PushNotificationDispatcher
import com.urlxl.mail.push.PushRuntime
import com.urlxl.mail.security.SecurityRuntime
import com.urlxl.mail.security.UnlockActivity

class KyPostApp : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        PushNotificationDispatcher.ensureChannel(this)
        try {
            DeviceContactsRuntime.graph(this).bootstrapIfEnabled()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to bootstrap device contacts", e)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App moved to the foreground.
        if (SecurityRuntime.graph(this).appLockManager.locked.value) {
            startActivity(
                Intent(this, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        try {
            PushRuntime.graph(this).pullCoordinator.pullNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to pull", e)
        }
        try {
            ContactsRuntime.graph(this).coordinator.syncNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to sync contacts (relay)", e)
        }
        try {
            DeviceContactsRuntime.graph(this).coordinator.syncNowAsync()
        } catch (e: Exception) {
            android.util.Log.e("KyPostApp", "Failed to sync contacts (device)", e)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        SecurityRuntime.graph(this).appLockManager.lockNow()
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manually verify the full lock cycle**

Manual check (process-lifecycle behavior isn't practically unit-testable): with "Require Unlock to Open" enabled (once Task 16 ships), force-stop and relaunch the app — `UnlockActivity` should appear before the inbox. Background the app (home button) and return — it should show `UnlockActivity` again immediately.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/KyPostApp.kt
git commit -m "android: lock the app on background, gate foreground behind UnlockActivity"
```

---

## Task 16: SecuritySettingsActivity — Require Unlock to Open (toggle 1)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AppLockStore` (Task 4), `SecurityWipe` (Task 7).
- Produces: the "Security" menu entry other tasks (17, 18) build on.

No dedicated test — Activity UI wiring over already-tested `AppLockStore`, matching `KeywordSettingsActivity`'s untested convention.

- [ ] **Step 1: Write the Activity (toggle 1 only for this task)**

```kotlin
package com.urlxl.mail.security

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.R
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyTopInsetWithHeader
import kotlinx.coroutines.launch

/**
 * "Security" settings screen: Require Unlock to Open (this task), Hostile Location Protection
 * (Task 17), and the credential PIN-gate (Task 18) — see the 2026-07-22 security-hardening
 * spec. Toggles 2 and 3 are disabled unless toggle 1 is on; enforced here, not just documented.
 */
class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var appLockStore: AppLockStore
    private lateinit var lockSwitch: Switch
    private lateinit var biometricSwitch: Switch
    // Suppresses lockSwitch's listener during a programmatic revert (setup cancelled/failed) —
    // without this, reverting isChecked re-fires the listener into the OPPOSITE flow (e.g.
    // reverting to "off" after a cancelled PIN-set pops up "enter PIN to disable", which always
    // fails since no PIN was set, bouncing the user into an unbreakable dialog ping-pong, and
    // worse, letting a wrong-PIN "disable" attempt bounce into promptSetPin(), which sets a new
    // PIN unconditionally — bypassing the "must know the current PIN to disable" guarantee).
    private var suppressLockToggleListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockStore = AppLockStore(this)
        setTitle(R.string.security_settings_title)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        applyTopInsetWithHeader(this, scrollView)

        lockSwitch = Switch(this).apply {
            text = getString(R.string.security_require_unlock_title)
            isChecked = appLockStore.isLockEnabled()
        }
        container.addView(lockSwitch)
        container.addView(
            TextView(this).apply {
                text = getString(R.string.security_require_unlock_intro)
                textSize = 13f
                setPadding(0, 4, 0, 16)
            },
        )

        biometricSwitch = Switch(this).apply {
            text = getString(R.string.security_use_biometric_title)
            isChecked = appLockStore.isBiometricEnabled()
            isEnabled = appLockStore.isLockEnabled()
        }
        container.addView(biometricSwitch)

        lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressLockToggleListener) return@setOnCheckedChangeListener
            onLockToggle(checked)
        }
        biometricSwitch.setOnCheckedChangeListener { _, checked -> appLockStore.setBiometricEnabled(checked) }

        scrollView.addView(container)
        setContentView(scrollView)
        applyThemeToActivity(this)
    }

    private fun onLockToggle(checked: Boolean) {
        if (checked) {
            promptSetPin()
        } else {
            promptDisableLock()
        }
    }

    /** Reverts [lockSwitch] without re-triggering [onLockToggle] — see [suppressLockToggleListener]. */
    private fun revertLockSwitch(checked: Boolean) {
        suppressLockToggleListener = true
        lockSwitch.isChecked = checked
        suppressLockToggleListener = false
    }

    private fun promptSetPin() {
        val pinField = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.unlock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.security_set_pin_title)
            .setView(pinField)
            .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
                val pin = pinField.text.toString()
                if (pin.length == 6) {
                    appLockStore.setPin(pin)
                    appLockStore.setLockEnabled(true)
                    biometricSwitch.isEnabled = true
                } else {
                    revertLockSwitch(false)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertLockSwitch(false) }
            .setCancelable(false)
            .show()
    }

    private fun promptDisableLock() {
        val pinField = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.unlock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.security_confirm_disable_title)
            .setView(pinField)
            .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
                if (appLockStore.verifyPin(pinField.text.toString())) {
                    lifecycleScope.launch {
                        SecurityWipe.wipeAndResetApp(this@SecuritySettingsActivity)
                        AppRestart.relaunch(this@SecuritySettingsActivity)
                    }
                } else {
                    revertLockSwitch(true)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertLockSwitch(true) }
            .setCancelable(false)
            .show()
    }
}
```

- [ ] **Step 2: Add strings**

```xml
<string name="security_settings_title">Security</string>
<string name="security_require_unlock_title">Require Unlock to Open</string>
<string name="security_require_unlock_intro">Require a PIN (or biometric) every time the app opens or returns from the background.</string>
<string name="security_use_biometric_title">Use biometric unlock</string>
<string name="security_set_pin_title">Set a 6-digit PIN</string>
<string name="security_set_pin_confirm">Confirm</string>
<string name="security_confirm_disable_title">Enter your PIN to turn this off</string>
<string name="menu_security">Security</string>
```

- [ ] **Step 3: Register the Activity**

```xml
<activity
    android:name=".security.SecuritySettingsActivity"
    android:exported="false" />
```

- [ ] **Step 4: Wire the "Security" menu entry into InboxActivity**

Read `app/src/main/java/com/urlxl/mail/InboxActivity.kt` around the `onCreateOptionsMenu`/`onOptionsItemSelected`/companion-object menu-id block (lines ~446-479, ~693-697) to confirm current line numbers, then:

```kotlin
override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    menu?.add(0, MENU_PGP_KEY, 0, R.string.menu_pgp_key)
    menu?.add(0, MENU_KEYWORDS, 1, R.string.menu_keywords)
    menu?.add(0, MENU_THEMES, 2, R.string.menu_themes)
    menu?.add(0, MENU_PUSH_PAIRING, 3, R.string.menu_pairing)
    menu?.add(0, MENU_SECURITY, 4, R.string.menu_security)
    menu?.add(0, MENU_ABOUT, 5, R.string.menu_about)
    return super.onCreateOptionsMenu(menu)
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        MENU_PGP_KEY -> { startActivity(Intent(this, PgpKeyActivity::class.java)); true }
        MENU_KEYWORDS -> { startActivity(Intent(this, KeywordSettingsActivity::class.java)); true }
        MENU_THEMES -> { startActivity(Intent(this, ThemesActivity::class.java)); true }
        MENU_PUSH_PAIRING -> { startActivity(Intent(this, com.urlxl.mail.push.PushPairingActivity::class.java)); true }
        MENU_SECURITY -> { startActivity(Intent(this, com.urlxl.mail.security.SecuritySettingsActivity::class.java)); true }
        MENU_ABOUT -> { showAboutDialog(this); true }
        else -> super.onOptionsItemSelected(item)
    }
}
```

And in the companion object:

```kotlin
private const val MENU_PGP_KEY = 0
private const val MENU_KEYWORDS = 1
private const val MENU_THEMES = 2
private const val MENU_PUSH_PAIRING = 3
private const val MENU_SECURITY = 4
private const val MENU_ABOUT = 5
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Manually verify**

Manual check: open Security settings from the Inbox menu, turn on Require Unlock to Open, set a PIN, confirm the app locks on next background/foreground cycle, confirm turning it back off requires the correct PIN.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt app/src/main/java/com/urlxl/mail/InboxActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "android: add SecuritySettingsActivity with Require Unlock to Open"
```

---

## Task 17: SecuritySettingsActivity — Hostile Location Protection (toggle 2)

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `HostileLocationSettings` (Task 5), `AppRestart` (Task 8).

- [ ] **Step 1: Add the toggle, gated on toggle 1**

In `SecuritySettingsActivity.onCreate`, after the biometric switch block:

```kotlin
val hostileLocationSettings = HostileLocationSettings(this)
val hostileLocationSwitch = Switch(this).apply {
    text = getString(R.string.security_hostile_location_title)
    isChecked = hostileLocationSettings.isEnabled()
    isEnabled = appLockStore.isLockEnabled()
}
container.addView(hostileLocationSwitch)
container.addView(
    TextView(this).apply {
        text = if (appLockStore.isLockEnabled()) {
            getString(R.string.security_hostile_location_intro)
        } else {
            getString(R.string.security_hostile_location_requires_lock)
        }
        textSize = 13f
        setPadding(0, 4, 0, 16)
    },
)
hostileLocationSwitch.setOnCheckedChangeListener { _, checked ->
    hostileLocationSettings.setEnabled(checked)
    AppRestart.relaunch(this)
}
```

Also update `onLockToggle`/`promptDisableLock`'s success paths to keep `hostileLocationSwitch` in sync with the dependency: when lock is turned off, Hostile Location Protection must be turned off too (it can't legally stay on with its prerequisite gone). Add to the top of `promptDisableLock`'s positive-button callback, before the wipe call:

```kotlin
.setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
    if (appLockStore.verifyPin(pinField.text.toString())) {
        HostileLocationSettings(this@SecuritySettingsActivity).setEnabled(false)
        lifecycleScope.launch {
            SecurityWipe.wipeAndResetApp(this@SecuritySettingsActivity)
            AppRestart.relaunch(this@SecuritySettingsActivity)
        }
    } else {
        revertLockSwitch(true)
    }
}
```

(`SecurityWipe.wipeAndResetApp` already deletes the Room DB regardless of which mode it was in, so no separate in-memory-vs-disk handling is needed here — this just ensures the flag itself is off before the app restarts as part of the wipe's normal first-run flow. The explicit `AppRestart.relaunch` call after the wipe is required per `SecurityWipe`'s own caller contract, established during Task 7's review.)

- [ ] **Step 2: Add strings**

```xml
<string name="security_hostile_location_title">Hostile Location Protection</string>
<string name="security_hostile_location_intro">No mail, contacts, or attachments are cached on this device — everything is fetched fresh from the server each time. Turning this on immediately wipes what\'s cached now and restarts the app.</string>
<string name="security_hostile_location_requires_lock">Requires Require Unlock to Open.</string>
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manually verify**

Manual check: with Require Unlock to Open off, confirm the Hostile Location Protection switch is disabled/greyed with the "Requires..." text showing. Turn on Require Unlock to Open, then Hostile Location Protection — confirm the app restarts and `context.getDatabasePath("kypost_mail.db")` no longer exists (can check via `adb shell run-as com.urlxl.mail ls databases/`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "android: add Hostile Location Protection toggle, gated on app lock"
```

---

## Task 18: CredentialCipher (PIN-derived AES-GCM wrap/unwrap)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/CredentialCipher.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/CredentialCipherTest.kt`

**Interfaces:**
- Produces: `data class WrappedSecret(val iv: ByteArray, val ciphertext: ByteArray)`, `object CredentialCipher { fun randomSalt(): ByteArray; fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec; fun wrap(plaintext: String, key: SecretKeySpec): WrappedSecret; fun unwrap(wrapped: WrappedSecret, key: SecretKeySpec): String? }`. Consumed by `SecurePairingStore` (Task 19). Note: the PBKDF2 salt itself is deliberately *not* part of `WrappedSecret` — the salt is an input to `deriveKey`, owned and persisted by the caller (`SecurePairingStore` stores it once per pairing, independent of each wrap call), not an output of wrapping a single value.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.CredentialCipherTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.CredentialCipherTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/CredentialCipher.kt app/src/test/java/com/urlxl/mail/security/CredentialCipherTest.kt
git commit -m "android: add CredentialCipher PIN-derived credential wrapping"
```

---

## Task 19: SecurePairingStore — optional PIN-wrap layer for deviceSecret

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/SecurePairingStore.kt`
- Test: `app/src/androidTest/java/com/urlxl/mail/push/SecurePairingStoreCredentialGateTest.kt`

**Interfaces:**
- Consumes: `CredentialCipher` (Task 18).
- Produces: new overload `suspend fun savePairing(pairing: PairingData, credentialKey: SecretKeySpec?)` (existing single-arg `savePairing(pairing)` call sites are unaffected via a default parameter) and `fun currentCredentialSalt(): ByteArray?`. Consumed by `AppLockManager`'s credential-key caching (Task 20).

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.push.SecurePairingStoreCredentialGateTest"`
Expected: FAIL (compile error — new overload/method don't exist)

- [ ] **Step 3: Modify SecurePairingStore**

Read the current file first, then apply these changes:

Add imports and new constants near the top:

```kotlin
import android.util.Base64
import com.urlxl.mail.security.CredentialCipher
import com.urlxl.mail.security.WrappedSecret
import javax.crypto.spec.SecretKeySpec
```

```kotlin
private const val KEY_DEVICE_SECRET_CIPHERTEXT = "pair_device_secret_ciphertext"
private const val KEY_DEVICE_SECRET_SALT = "pair_device_secret_salt"
private const val KEY_DEVICE_SECRET_IV = "pair_device_secret_iv"
```

Add the new `savePairing` overload (keep the existing `savePairing(pairing: PairingData)` — Kotlin default parameters mean callers passing only `pairing` are unaffected):

```kotlin
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
```

Modify the existing `readPairing()` to take the credential key and unwrap when needed (this replaces the old no-arg private method; update its one remaining internal caller — the `init` block — to pass `null`, meaning "no key available yet at process start," matching this feature's documented behavior of only working during an already-unlocked session):

```kotlin
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
```

In `init { }`, change `_pairing.value = readPairing()` to `_pairing.value = readPairing(credentialKey = null)`.

In `clearPairing()`, add the three new keys to the `.remove(...)` chain:

```kotlin
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
            .commit()
    }
    _pairing.value = null
}
```

- [ ] **Step 4: Run the new test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.push.SecurePairingStoreCredentialGateTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Re-run the pre-existing SecurePairingStoreTest to confirm no regression**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.push.SecurePairingStoreTest"`
Expected: PASS (4 tests, unchanged) — confirms the default-parameter overload didn't disturb the unwrapped (today's default) path.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/push/SecurePairingStore.kt app/src/androidTest/java/com/urlxl/mail/push/SecurePairingStoreCredentialGateTest.kt
git commit -m "android: add optional PIN-wrap layer for deviceSecret"
```

---

## Task 20: AppLockManager — cache the credential key across an unlocked session

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/security/AppLockManager.kt`
- Modify: `app/src/main/java/com/urlxl/mail/security/AppLockStore.kt` (expose credential salt)
- Test: `app/src/test/java/com/urlxl/mail/security/AppLockManagerTest.kt` (extend)

**Interfaces:**
- Consumes: `CredentialCipher` (Task 18).
- Produces: `AppLockManager.cachedCredentialKey(): SecretKeySpec?` (new), consumed by `PushRepository`/background sync wiring in Task 21.

- [ ] **Step 1: Extend the failing test first**

Add to `AppLockManagerTest.kt` (new `FakeAppLockState` needs a credential-salt hook — extend it, then add the test):

```kotlin
private class FakeAppLockState(
    private var lockEnabled: Boolean = true,
    private var pin: String? = "123456",
    private var credentialSalt: ByteArray? = null,
) : AppLockState {
    // ...existing overrides unchanged...
    override fun isCredentialPinGateEnabled() = credentialGateEnabled
    // ...
}
```

```kotlin
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
```

(Add `import com.urlxl.mail.security.CredentialCipher` — already in-package, no import needed — and give `FakeAppLockState` a `credentialSalt` field returned from a new `AppLockState.credentialSalt(): ByteArray?` member, added in Step 3 below.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AppLockManagerTest"`
Expected: FAIL (compile error — `cachedCredentialKey`/`credentialSalt` don't exist yet)

- [ ] **Step 3: Add `credentialSalt()` to AppLockState/AppLockStore**

In `AppLockStore.kt`'s `AppLockState` interface, add:

```kotlin
fun credentialSalt(): ByteArray?
fun setCredentialSalt(salt: ByteArray)
```

In `AppLockStore`:

```kotlin
override fun credentialSalt(): ByteArray? =
    prefs.getString(KEY_CREDENTIAL_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

override fun setCredentialSalt(salt: ByteArray) {
    prefs.edit().putString(KEY_CREDENTIAL_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).commit()
}
```

Add the constant near the other `KEY_*` constants: `private const val KEY_CREDENTIAL_SALT = "credential_salt"`.

Update `FakeAppLockState` in the test file to implement the two new members (backed by its existing `credentialSalt` constructor param and a settable var).

- [ ] **Step 4: Add credential-key caching to AppLockManager**

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AppLockManagerTest"`
Expected: PASS (9 tests total)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/AppLockManager.kt app/src/main/java/com/urlxl/mail/security/AppLockStore.kt app/src/test/java/com/urlxl/mail/security/AppLockManagerTest.kt
git commit -m "android: cache PIN-derived credential key for an unlocked session"
```

---

## Task 21: Wire the credential gate into PushRepository + toggle 3 UI

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/PushRepository.kt`
- Modify: `app/src/main/java/com/urlxl/mail/security/AppLockManager.kt`
- Modify: `app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `SecurePairingStore.pairingSnapshot`/`savePairing` (Task 19), `AppLockManager.cachedCredentialKey()` (Task 20), `SecurityRuntime` (Task 13).
- Produces: `AppLockManager.deriveAndCacheCredentialKey(pin: String): Boolean` (new — see Step 5 below).

**Important correctness note found during this plan's own review (Task 20):** the registration-time wrapping in Step 4 alone is not sufficient. A user who already has a working pairing and only *later* turns toggle 3 on would have their existing `deviceSecret` sitting unwrapped indefinitely — nothing re-saves it. Worse, turning toggle 3 back *off* would leave the stored secret wrapped with no code path that ever unwraps it, permanently breaking authentication once the app relocks (since `cachedCredentialKey()` goes back to null once the gate is reported disabled). Both directions must re-derive the key on the spot (via a fresh PIN entry, since the app being merely "unlocked" doesn't guarantee a PIN-derived key is cached — the session may have been unlocked via biometric) and immediately re-save the current pairing in the matching form. Steps 5-6 below implement this; this supersedes the simpler confirm-only flow this task originally sketched.

No new automated test: this wires already-tested pieces (`SecurePairingStoreCredentialGateTest`, `AppLockManagerTest`) together at the one call site that matters (`PushRepository`'s reactive `pairing` state) — an end-to-end test would need a real paired device and background service lifecycle, out of proportion for this task.

- [ ] **Step 1: Read PushRepository's current pairing wiring**

Read `app/src/main/java/com/urlxl/mail/push/PushRepository.kt` in full to find every place it reads `securePairingStore.pairing` (the `StateFlow<PairingData?>`) — this task only needs to change how the *snapshot used for authenticated calls* is obtained, not the reactive state used for UI display (e.g. "is paired" checks elsewhere can keep reading the plain `pairing` StateFlow, since a null-`deviceSecret` `PairingData` is still non-null and correctly reports "paired").

- [ ] **Step 2: Add a snapshot method that folds in the cached credential key**

In `PushRepository.kt`, add:

```kotlin
import com.urlxl.mail.security.SecurityRuntime

/** Pairing data for making an authenticated relay call right now — `deviceSecret` comes back
 *  null if "require unlock to receive push/MFA" is on and the app isn't currently unlocked via
 *  PIN, per the 2026-07-22 security-hardening spec; callers already treat a blank/missing
 *  deviceSecret as an auth failure (see [com.urlxl.mail.pairingAuthHeaders]'s `.orEmpty()`
 *  usage), so this fails the same way a real 401 would — no new error path needed. */
fun pairingForAuthenticatedCall(): PairingData? =
    securePairingStore.pairingSnapshot(SecurityRuntime.graph(context).appLockManager.cachedCredentialKey())
```

- [ ] **Step 3: Redirect authenticated-call sites to the new snapshot method**

Search for every place `PushRepository`'s callers currently read `.pairing.value` or the `pairing` `StateFlow` specifically to build request headers (as opposed to just checking pairing/UI state) — this includes `RelayMailSource`'s `pairingProvider: () -> PairingData?` lambda (wherever it's constructed, likely in `PushRuntime`/`MailRepository` wiring) and any place in `push/` building `X-Kypost-Device-*` headers directly. Change each such call site from `securePairingStore.pairing.value` (or equivalent) to `pushRepository.pairingForAuthenticatedCall()`. Because this fan-out depends on exact current call sites that may have shifted since this plan was written, use this grep to find them before editing:

```bash
grep -rn "pairing\.value\|pairingProvider" app/src/main/java/com/urlxl/mail/push app/src/main/java/com/urlxl/mail/mail app/src/main/java/com/urlxl/mail/pgp
```

For each match that's used to build an authenticated request (not a plain "is paired" UI check), replace the source with `pairingForAuthenticatedCall()`.

- [ ] **Step 4: Update savePairing call sites to pass the credential key when the gate is on**

Find where pairing is first saved after a successful registration (in `PushRepository` or wherever `savePairing` is currently called), and change it to:

```kotlin
val appLockManager = SecurityRuntime.graph(context).appLockManager
val credentialKey = appLockManager.cachedCredentialKey()
if (credentialKey != null) {
    securePairingStore.savePairing(newPairing, credentialKey, securePairingStore... /* the salt AppLockManager just used/created */)
} else {
    securePairingStore.savePairing(newPairing)
}
```

Since `AppLockManager.cachedCredentialKey()` and the salt it used are two different pieces of state, expose the salt via the same store: `AppLockStore(context).credentialSalt()` (Task 20, Step 3) reads back what `cacheCredentialKeyIfEnabled` just wrote, so:

```kotlin
val credentialKey = appLockManager.cachedCredentialKey()
val credentialSalt = if (credentialKey != null) AppLockStore(context).credentialSalt() else null
if (credentialKey != null && credentialSalt != null) {
    securePairingStore.savePairing(newPairing, credentialKey, credentialSalt)
} else {
    securePairingStore.savePairing(newPairing)
}
```

- [ ] **Step 5: Add on-demand key derivation to AppLockManager**

Read the current `app/src/main/java/com/urlxl/mail/security/AppLockManager.kt` in full (post-Task-20) first. It currently has a private `cacheCredentialKeyIfEnabled(pin: String)`, called only from `attemptPin`'s success branch and gated on `state.isCredentialPinGateEnabled()`. Toggling the gate itself has no "successful unlock" event to hang off of — the app is already unlocked when the user flips the switch, and if they unlocked via biometric this session, no PIN-derived key exists yet to reuse. Refactor to extract the shared derive-or-reuse-salt logic, and add a new public method that works regardless of the gate's current enabled state (since it's used to transition the gate itself):

```kotlin
fun deriveAndCacheCredentialKey(pin: String): Boolean {
    if (!state.verifyPin(pin)) return false
    credentialKey = deriveKeyUsingPersistedSalt(pin)
    return true
}

private fun cacheCredentialKeyIfEnabled(pin: String) {
    if (!state.isCredentialPinGateEnabled()) return
    credentialKey = deriveKeyUsingPersistedSalt(pin)
}

private fun deriveKeyUsingPersistedSalt(pin: String): SecretKeySpec {
    val salt = state.credentialSalt() ?: CredentialCipher.randomSalt().also { state.setCredentialSalt(it) }
    return CredentialCipher.deriveKey(pin, salt)
}
```

Replace the body of the existing `cacheCredentialKeyIfEnabled` with the two-liner above (delegating to the new shared helper) — do not duplicate the salt-derivation logic. Run the full `AppLockManagerTest` suite (`./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.AppLockManagerTest"`) to confirm all 10 existing tests still pass unchanged (this is a refactor of already-tested logic, not new behavior on the `attemptPin` path).

- [ ] **Step 6: Add toggle 3 to SecuritySettingsActivity, with PIN-gated enable/disable and pairing re-wrap**

In `SecuritySettingsActivity.onCreate`, after the Hostile Location Protection block, add the switch as a `lateinit var` field (mirroring `lockSwitch`/`hostileLocationSwitch`) so it can be reverted programmatically without re-triggering its own listener — same re-entrancy hazard as `lockSwitch` in Task 16, guarded the same way:

```kotlin
// class-level field, alongside lockSwitch/hostileLocationSwitch/suppressLockToggleListener:
private lateinit var credentialGateSwitch: Switch
private var suppressCredentialGateListener = false

private fun revertCredentialGateSwitch(checked: Boolean) {
    suppressCredentialGateListener = true
    credentialGateSwitch.isChecked = checked
    suppressCredentialGateListener = false
}
```

```kotlin
// in onCreate, after the Hostile Location Protection block:
credentialGateSwitch = Switch(this).apply {
    text = getString(R.string.security_credential_gate_title)
    isChecked = appLockStore.isCredentialPinGateEnabled()
    isEnabled = appLockStore.isLockEnabled()
}
container.addView(credentialGateSwitch)
container.addView(
    TextView(this).apply {
        text = getString(R.string.security_credential_gate_intro)
        textSize = 13f
        setPadding(0, 4, 0, 16)
    },
)
credentialGateSwitch.setOnCheckedChangeListener { _, checked ->
    if (suppressCredentialGateListener) return@setOnCheckedChangeListener
    if (checked) confirmEnableCredentialGate() else confirmDisableCredentialGate()
}
```

```kotlin
private fun confirmEnableCredentialGate() {
    AlertDialog.Builder(this)
        .setTitle(R.string.security_credential_gate_warning_title)
        .setMessage(R.string.security_credential_gate_warning_body)
        .setPositiveButton(R.string.security_credential_gate_warning_confirm) { _, _ -> promptCredentialGatePin(enabling = true) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> revertCredentialGateSwitch(false) }
        .setCancelable(false)
        .show()
}

private fun confirmDisableCredentialGate() {
    promptCredentialGatePin(enabling = false)
}

/** Both directions need the PIN re-entered here (not just "the app happens to be unlocked
 *  right now") to guarantee a fresh PIN-derived key is available to actually re-wrap or
 *  unwrap the current pairing's deviceSecret in the same step — see this task's correctness
 *  note about why a confirm-only flow isn't sufficient. */
private fun promptCredentialGatePin(enabling: Boolean) {
    val pinField = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = getString(R.string.unlock_pin_hint)
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.security_credential_gate_pin_title)
        .setView(pinField)
        .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
            val appLockManager = SecurityRuntime.graph(this).appLockManager
            if (appLockManager.deriveAndCacheCredentialKey(pinField.text.toString())) {
                appLockStore.setCredentialPinGateEnabled(enabling)
                if (enabling) rewrapCurrentPairing() else unwrapCurrentPairing()
            } else {
                revertCredentialGateSwitch(!enabling)
            }
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> revertCredentialGateSwitch(!enabling) }
        .setCancelable(false)
        .show()
}

/** Re-saves the currently-paired credentials wrapped behind the just-derived key — without
 *  this, turning the gate on would only take effect for pairing data saved AFTER this point
 *  (a future re-pair), leaving an existing pairing's deviceSecret unwrapped indefinitely. */
private fun rewrapCurrentPairing() {
    lifecycleScope.launch {
        val securePairingStore = com.urlxl.mail.push.SecurePairingStore(this@SecuritySettingsActivity)
        val currentPairing = securePairingStore.pairing.value ?: return@launch
        val appLockManager = SecurityRuntime.graph(this@SecuritySettingsActivity).appLockManager
        val credentialKey = appLockManager.cachedCredentialKey() ?: return@launch
        val credentialSalt = appLockStore.credentialSalt() ?: return@launch
        securePairingStore.savePairing(currentPairing, credentialKey, credentialSalt)
    }
}

/** The inverse of [rewrapCurrentPairing] — without this, turning the gate back off would leave
 *  deviceSecret stored wrapped with no code path that ever unwraps it, permanently breaking
 *  authentication the next time the app locks (cachedCredentialKey() goes back to null once the
 *  gate reports disabled, and resolveDeviceSecret has no other way to read the wrapped value). */
private fun unwrapCurrentPairing() {
    lifecycleScope.launch {
        val securePairingStore = com.urlxl.mail.push.SecurePairingStore(this@SecuritySettingsActivity)
        val appLockManager = SecurityRuntime.graph(this@SecuritySettingsActivity).appLockManager
        val credentialKey = appLockManager.cachedCredentialKey() ?: return@launch
        val currentPairing = securePairingStore.pairingSnapshot(credentialKey) ?: return@launch
        securePairingStore.savePairing(currentPairing)
    }
}
```

- [ ] **Step 7: Add strings**

```xml
<string name="security_credential_gate_title">Require unlock to receive push/MFA</string>
<string name="security_credential_gate_intro">Off by default. When on, new-mail push notifications and MFA approval requests only arrive after you\'ve opened the app and unlocked it.</string>
<string name="security_credential_gate_warning_title">This disables background notifications while locked</string>
<string name="security_credential_gate_warning_body">While this is on, you will not receive new-mail push notifications or MFA approval requests until you open the app and enter your PIN. Turn this on only if you understand that tradeoff.</string>
<string name="security_credential_gate_warning_confirm">Turn on anyway</string>
<string name="security_credential_gate_pin_title">Enter your PIN to continue</string>
```

- [ ] **Step 8: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Manually verify**

Manual check: with an existing pairing, enable toggle 3 (entering the correct PIN when prompted) — confirm the setting sticks after backgrounding/foregrounding the app (re-unlocking with the PIN). Background the app, send a test push/MFA approval from the server — confirm no notification arrives. Reopen and unlock the app via PIN — confirm normal sync resumes (via the existing `KyPostApp.onStart` pull/sync calls) without any additional user action. Then turn toggle 3 back off (entering the PIN again when prompted) — confirm push/MFA delivery resumes normally afterward, proving the pairing was genuinely unwrapped back to a usable state and not left stuck.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/push/PushRepository.kt app/src/main/java/com/urlxl/mail/security/AppLockManager.kt app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt app/src/main/res/values/strings.xml
git commit -m "android: wire credential PIN-gate into push auth and settings UI"
```

---

## Task 22: FLAG_SECURE on sensitive screens

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/EmailDetailActivity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/ComposeActivity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt`
- Modify: `app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt`
- (`UnlockActivity` already sets this flag — added in Task 13.)

**Interfaces:** none — a one-line addition per Activity.

No automated test: `FLAG_SECURE`'s effect (blocked screenshots) isn't observable from within the app's own process/test harness.

- [ ] **Step 1: Add the flag to each Activity's `onCreate`, immediately after `super.onCreate(...)`**

```kotlin
window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
```

Add this line to `InboxActivity.onCreate`, `EmailDetailActivity.onCreate`, `ComposeActivity.onCreate`, `PgpKeyActivity.onCreate`, and `SecuritySettingsActivity.onCreate`. Read each file's current `onCreate` first to place it correctly (right after the `super.onCreate(savedInstanceState)` call, before any view inflation).

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manually verify**

Manual check: attempt a screenshot (hardware buttons or Recents) on the Inbox, Email Detail, Compose, PGP Key, and Security screens — each should show a "Can't take screenshot" system toast/blank capture. Confirm `ThemesActivity`/`KeywordSettingsActivity` (not in this list) still allow screenshots normally, confirming the flag is scoped correctly.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/InboxActivity.kt app/src/main/java/com/urlxl/mail/EmailDetailActivity.kt app/src/main/java/com/urlxl/mail/ComposeActivity.kt app/src/main/java/com/urlxl/mail/pgp/PgpKeyActivity.kt app/src/main/java/com/urlxl/mail/security/SecuritySettingsActivity.kt
git commit -m "android: set FLAG_SECURE on screens showing mail/PGP/security content"
```

---

## Task 23: Disable backup and remove the now-unused extraction-rule files

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/res/xml/backup_rules.xml`
- Delete: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:** none.

- [ ] **Step 1: Flip `allowBackup` and remove the now-dead attributes**

In `app/src/main/AndroidManifest.xml`, on the `<application>` element, change:

```xml
android:allowBackup="true"
android:dataExtractionRules="@xml/data_extraction_rules"
```

to:

```xml
android:allowBackup="false"
```

(Remove the `android:dataExtractionRules` and `android:fullBackupContent` attributes entirely — with `allowBackup="false"`, Android performs no backup of any kind, cloud or device-transfer, so the extraction-rules files they pointed to become dead configuration. Leaving unused attributes pointing at files that no longer matter is exactly the kind of stale state worth cleaning up alongside the actual fix, not leaving as a trap for a future reader who assumes they're still in effect.)

- [ ] **Step 2: Delete the now-unreferenced XML files**

```bash
rm app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manually verify**

Run: `adb backup -f /tmp/kypost-backup-test.ab com.urlxl.mail` (requires a debuggable build and USB debugging authorized)
Expected: the backup completes but is empty/rejected — Android refuses adb backup entirely for apps with `allowBackup="false"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git rm app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml
git commit -m "android: disable backup entirely (allowBackup=false)"
```

---

## Task 24: SpkiPinner (compute a certificate's SPKI pin string)

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/security/SpkiPinner.kt`
- Test: `app/src/test/java/com/urlxl/mail/security/SpkiPinnerTest.kt`

**Interfaces:**
- Produces: `object SpkiPinner { fun pinFor(certificate: Certificate): String }` (thin wrapper documenting *why* this exists — OkHttp's `CertificatePinner.pin(Certificate)` already does the SHA-256 SPKI computation correctly, this just gives the operation a name specific to this feature). Consumed by Task 25.

- [ ] **Step 1: Write the failing test**

Generate a real self-signed certificate in the test itself (no fixture file needed — `java.security` can mint one in-process):

```kotlin
package com.urlxl.mail.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo

private fun selfSignedTestCertificate(): X509Certificate {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val info = X509CertInfo().apply {
        set(X509CertInfo.VALIDITY, CertificateValidity(Date(), Date(System.currentTimeMillis() + 86_400_000L)))
        set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(BigInteger.ONE))
        set(X509CertInfo.SUBJECT, X500Name("CN=test"))
        set(X509CertInfo.ISSUER, X500Name("CN=test"))
        set(X509CertInfo.KEY, CertificateX509Key(keyPair.public))
        set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
        set(X509CertInfo.ALGORITHM_ID, CertificateAlgorithmId(AlgorithmId.get("SHA256withRSA")))
    }
    val cert = X509CertImpl(info)
    cert.sign(keyPair.private, "SHA256withRSA")
    return cert
}

class SpkiPinnerTest {
    @Test
    fun pinFor_returnsSha256PinStringFormat() {
        val pin = SpkiPinner.pinFor(selfSignedTestCertificate())
        assertTrue(pin.startsWith("sha256/"))
    }

    @Test
    fun pinFor_isStable_forTheSameCertificate() {
        val cert = selfSignedTestCertificate()
        assertTrue(SpkiPinner.pinFor(cert) == SpkiPinner.pinFor(cert))
    }
}
```

(If `sun.security.x509` isn't accessible in the unit-test JVM used by this project's Gradle/JDK setup, use `java.security.cert.CertificateFactory` to parse a hardcoded PEM test certificate string instead — either approach only needs *some* valid `X509Certificate` instance, the exact generation method is an implementation detail of the test.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.SpkiPinnerTest"`
Expected: FAIL (compile error)

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.security

import okhttp3.CertificatePinner
import java.security.cert.Certificate

/**
 * TOFU (trust-on-first-use) certificate pinning support — see "Certificate pinning" in the
 * 2026-07-22 security-hardening spec. kypost is self-hosted with a per-user server URL, so there
 * is no fixed certificate to hardcode; instead the server's certificate pin is captured once at
 * pairing time and enforced on every later connection. This wraps OkHttp's own
 * [CertificatePinner.pin] (which already computes the correct `sha256/BASE64` SPKI hash) purely
 * to give the operation a name specific to this feature, not because the computation itself
 * needs reimplementing.
 */
object SpkiPinner {
    fun pinFor(certificate: Certificate): String = CertificatePinner.pin(certificate)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.security.SpkiPinnerTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/security/SpkiPinner.kt app/src/test/java/com/urlxl/mail/security/SpkiPinnerTest.kt
git commit -m "android: add SpkiPinner for TOFU certificate pinning"
```

---

## Task 25: Capture and enforce the TOFU certificate pin

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/SecurePairingStore.kt`
- Modify: `app/src/main/java/com/urlxl/mail/PairingAuthHeaders.kt`
- Modify: `app/src/main/java/com/urlxl/mail/mail/MailSource.kt`
- Modify: `app/src/main/java/com/urlxl/mail/mail/RelayMailSource.kt`

**Interfaces:**
- Consumes: `SpkiPinner` (Task 24).
- Produces: `MailOutcome.CertificateMismatch` (new), `pairingHttpClient(pinnedSpkiSha256: String?, host: String?)` (modified signature — see Step 3 for handling existing zero-arg callers).

No new automated test: exercising a real TLS handshake mismatch needs a live second certificate and server, out of proportion for this task; the pin-computation logic itself is already tested in Task 24, and this task is wiring, mirroring Task 21's "wiring, not new logic" scope.

- [ ] **Step 1: Add pin storage to SecurePairingStore**

Add a new key and accessor pair, following the same pattern as `KEY_SERVER_URL`:

```kotlin
private const val KEY_TLS_PIN = "pair_tls_spki_pin"
```

```kotlin
suspend fun saveTlsPin(pin: String) {
    withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TLS_PIN, pin).commit()
    }
}

fun currentTlsPin(): String? = prefs.getString(KEY_TLS_PIN, null)
```

Add `.remove(KEY_TLS_PIN)` to `clearPairing()`'s edit chain.

- [ ] **Step 2: Capture the pin right after a successful pairing request**

Find the call site where the app makes its first authenticated request immediately after pairing succeeds (in `PushRepository`, likely the registration-confirmation call). Using OkHttp, the response object already carries `response.handshake?.peerCertificates` — after that first successful call, extract and store the leaf certificate's pin:

```kotlin
val handshake = response.handshake
val leafCertificate = handshake?.peerCertificates?.firstOrNull()
if (leafCertificate != null) {
    securePairingStore.saveTlsPin(SpkiPinner.pinFor(leafCertificate))
}
```

(Exact placement depends on which `PushRepository`/registration-flow method currently holds the raw OkHttp `Response` after pairing succeeds — read that method fully before inserting this, since `execute()`/`executeSync()` wrappers elsewhere in this codebase already discard the `Response` object after mapping it to a `MailOutcome`, and this needs the `Response` itself, not just its body string.)

- [ ] **Step 3: Make `pairingHttpClient()` pin-aware**

In `PairingAuthHeaders.kt`, change:

```kotlin
fun pairingHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
```

to:

```kotlin
import okhttp3.CertificatePinner

/** [pinnedSpkiSha256] + [host] both null (the default) matches every existing call site
 *  unchanged — no pin enforced, exactly today's behavior. Both non-null enables TOFU pinning
 *  for that host; see [com.urlxl.mail.security.SpkiPinner]. */
fun pairingHttpClient(pinnedSpkiSha256: String? = null, host: String? = null): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
    if (pinnedSpkiSha256 != null && host != null) {
        builder.certificatePinner(
            CertificatePinner.Builder().add(host, pinnedSpkiSha256).build(),
        )
    }
    return builder.build()
}
```

This keeps every existing `pairingHttpClient()` call site (zero-arg) working unchanged. `RelayMailSource`'s constructor default (`callFactory: Call.Factory = pairingHttpClient()`) should be updated to pass the current pin once one is available — read `RelayMailSource.kt`'s constructor and the site that builds it (likely in `MailRuntime`/`PushRuntime` wiring) to thread the stored pin + the pairing's host (parsed from `serverUrl`) through at construction time, rebuilding the client whenever pairing changes (mirroring how `pairingProvider: () -> PairingData?` is already a re-read-each-time lambda, not a one-time snapshot).

- [ ] **Step 4: Add a distinct outcome for a pin mismatch**

In `MailSource.kt`, add to the `MailOutcome` sealed class:

```kotlin
/** TLS certificate didn't match the pin captured at pairing time — could be a legitimate
 *  cert rotation on the user's own server, or an active MITM; either way, do not silently
 *  fall back to trusting it. */
data class CertificateMismatch(val message: String) : MailOutcome<Nothing>()
```

Add the corresponding case to `userFacingMessage()`:

```kotlin
is MailOutcome.CertificateMismatch -> "This server's certificate has changed since pairing — clear pairing and re-pair in Settings if you expect this (e.g. you rotated your server's certificate)"
```

- [ ] **Step 5: Map the TLS exception in RelayMailSource**

In `RelayMailSource.kt`'s `execute` function, catch the specific exception before falling through to the generic "Network error" message:

```kotlin
private fun <T> execute(request: Request, onResponse: (code: Int, body: String) -> MailOutcome<T>): MailOutcome<T> {
    val result = callFactory.executeSync(request) { response -> response.code to response.body?.string().orEmpty() }
    val exception = result.exceptionOrNull()
    if (exception is javax.net.ssl.SSLPeerUnverifiedException) {
        return MailOutcome.CertificateMismatch(exception.message ?: "Certificate pin mismatch")
    }
    val (code, body) = result.getOrNull()
        ?: return MailOutcome.UpstreamFailure(exception?.message ?: "Network error")
    return onResponse(code, body)
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Re-run the full existing RelayMailSource/SecurePairingStore test suites to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "com.urlxl.mail.mail.RelayMailSourceTest"`
Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.urlxl.mail.push.SecurePairingStoreTest"`
Expected: both PASS, unchanged test counts from before this task.

- [ ] **Step 8: Manually verify**

Manual check (needs a real server): pair against a test server, confirm subsequent mail sync works normally (pin matches). Then swap the test server's TLS certificate for a different one and confirm the next sync surfaces `MailOutcome.CertificateMismatch`'s message rather than a generic network error, and that clearing pairing + re-pairing recovers normal operation.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/push/SecurePairingStore.kt app/src/main/java/com/urlxl/mail/PairingAuthHeaders.kt app/src/main/java/com/urlxl/mail/mail/MailSource.kt app/src/main/java/com/urlxl/mail/mail/RelayMailSource.kt
git commit -m "android: capture and enforce TOFU certificate pin after pairing"
```

---

## Out of scope (per the spec)

Duress/panic PIN, root/tamper detection, clipboard-sensitive flagging, secure/overwrite deletion of the old disk-backed DB file, changes to default (non-Hostile-Location-Protection) attachment behavior, and any kypost-server-side work (e.g. short-lived/scoped push tokens) are explicitly out of scope — see the spec's "Explicitly out of scope this round" section. The spec's "Porting notes" section for kypost-Linux/iOS is informational only; no task in this plan touches those repos.

