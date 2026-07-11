# Push 2FA Milestone 3: Android Approve/Deny UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a paired Android device approve or deny a push-based 2FA login challenge from the sibling `llama labels` web app, via notification action buttons and an in-app fallback screen.

**Architecture:** The backend already sends a distinct FCM data payload (`type: "mfa_challenge"`, `challengeId: <id>`) to approver-eligible devices and exposes `POST /api/mfa/push/respond` (body `{challengeId, subscriberId, subscriberHash, deviceId, approve}`, auth via the existing pairing subscriber-hash credential) — this milestone is purely additive on the mobile side, no backend changes. `LlamaFirebaseMessagingService.onMessageReceived` gains a type-discriminating branch; a new high-importance notification channel shows Approve/Deny actions wired to a new `BroadcastReceiver` (`PendingIntent.getBroadcast`, a pattern new to this codebase); a new `MfaResponseClient` POSTs the response using the same OkHttp-per-concern style as `NativeRegistrationClient`; a minimal `AppCompatActivity` fallback screen handles OEM background-restriction cases where the notification action's broadcast gets killed.

**Tech Stack:** Kotlin, OkHttp (no Retrofit), kotlinx.serialization, plain XML views + `AppCompatActivity` (no Compose for this feature), JUnit4 (no Mockito/Robolectric) for JVM unit tests.

## Global Constraints

- Backend contract is fixed and already shipped — do not modify anything under `/home/yoshi/git/llama labels`. FCM data payload keys: `type` (`"mfa_challenge"`), `challengeId`. Respond endpoint: `POST {serverUrl}/api/mfa/push/respond`, JSON body `{challengeId, subscriberId, subscriberHash, deviceId, approve}`, responses: `200 {ok:true, status:"approved"|"denied"}`, `401` (invalid pairing), `403` (device not permitted / push 2FA not enabled), `409 {status:...}` (already resolved by another device).
- No new dependencies (per `AGENTS.md` root: "Do not add new dependencies unless they reduce overall code size/complexity"). Everything here is buildable with what's already in `app/build.gradle.kts`.
- Avoid network or Android framework dependencies in JVM unit tests (`app/src/test/AGENTS.md`) — only pure-function logic (payload parsing, endpoint resolution) gets a JVM unit test here; anything needing a real `Context`/Keystore/notification manager is out of scope for automated tests in this plan (matches how `SecurePairingStore` itself is untested at the JVM level).
- Keep network off the main thread (`app/src/main/AGENTS.md`).
- Mirror existing patterns exactly: OkHttp client construction like `NativeRegistrationClient`, endpoint resolution like `resolvePullEndpoint`, test style like `PushPayloadParserTest` (JUnit4, `org.junit.Assert.*`, underscore test names).
- Update `app/src/main/AGENTS.md` "Local Contracts" section as the final task, per the repo's DOX framework ("Update After Editing" in root `AGENTS.md`).
- Run `./gradlew testDebugUnitTest` from `/home/yoshi/git/llama-mobile` after every task that touches `app/src/test/`.

---

## File Structure

New files:
- `app/src/main/java/com/urlxl/mail/push/MfaChallengePayload.kt` — data class + parser for the `mfa_challenge` FCM data payload.
- `app/src/test/java/com/urlxl/mail/push/MfaChallengePayloadParserTest.kt` — parser unit tests.
- `app/src/main/java/com/urlxl/mail/push/MfaResponseClient.kt` — request/response models, endpoint resolver, OkHttp client for `POST /api/mfa/push/respond`.
- `app/src/test/java/com/urlxl/mail/push/MfaResponseEndpointResolverTest.kt` — endpoint-resolver unit tests.
- `app/src/main/java/com/urlxl/mail/push/MfaResponseReceiver.kt` — `BroadcastReceiver` invoked by notification Approve/Deny actions; also exposes the shared respond-and-notify logic used by the fallback activity.
- `app/src/main/java/com/urlxl/mail/push/MfaApprovalActivity.kt` — in-app fallback screen (Approve/Deny buttons), reached by tapping the notification body.
- `app/src/main/res/layout/activity_mfa_approval.xml` — layout for the fallback screen.

Modified files:
- `app/src/main/java/com/urlxl/mail/push/PushNotificationDispatcher.kt` — add MFA notification channel + `showMfaChallenge`/`cancelMfaChallenge`, plus the action/extra constants shared with the receiver.
- `app/src/main/java/com/urlxl/mail/push/LlamaFirebaseMessagingService.kt` — branch `onMessageReceived` on the new payload type before falling through to the existing mail-payload path.
- `app/src/main/java/com/urlxl/mail/push/PushRuntime.kt` — wire `MfaResponseClient()` into `PushGraph`.
- `app/src/main/AndroidManifest.xml` — register the receiver and the fallback activity.
- `app/src/main/res/values/strings.xml` — new strings for the fallback screen.
- `app/src/main/AGENTS.md` — document the new MFA push contract.

---

### Task 1: MFA challenge payload parser

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/push/MfaChallengePayload.kt`
- Test: `app/src/test/java/com/urlxl/mail/push/MfaChallengePayloadParserTest.kt`

**Interfaces:**
- Produces: `data class MfaChallengePayload(val challengeId: String)` and `object MfaChallengePayloadParser { fun parse(data: Map<String, String>): MfaChallengePayload? }` — consumed by Task 2 (`LlamaFirebaseMessagingService`) and Task 4 (`PushNotificationDispatcher.showMfaChallenge`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.urlxl.mail.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MfaChallengePayloadParserTest {

    @Test
    fun parse_readsContractKeysExactly() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf(
                "type" to "mfa_challenge",
                "challengeId" to "ch-123",
            ),
        )
        requireNotNull(payload)
        assertEquals("ch-123", payload.challengeId)
    }

    @Test
    fun parse_wrongType_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf(
                "type" to "something_else",
                "challengeId" to "ch-123",
            ),
        )
        assertNull(payload)
    }

    @Test
    fun parse_missingType_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(mapOf("challengeId" to "ch-123"))
        assertNull(payload)
    }

    @Test
    fun parse_blankChallengeId_returnsNull() {
        val payload = MfaChallengePayloadParser.parse(
            mapOf("type" to "mfa_challenge", "challengeId" to "   "),
        )
        assertNull(payload)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.MfaChallengePayloadParserTest"`
Expected: FAIL (compile error — `MfaChallengePayload`/`MfaChallengePayloadParser` don't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.push

data class MfaChallengePayload(
    val challengeId: String,
)

object MfaChallengePayloadParser {
    private const val TYPE_MFA_CHALLENGE = "mfa_challenge"

    fun parse(data: Map<String, String>): MfaChallengePayload? {
        if (data["type"] != TYPE_MFA_CHALLENGE) return null
        val challengeId = data["challengeId"].orEmpty().trim()
        if (challengeId.isBlank()) return null
        return MfaChallengePayload(challengeId = challengeId)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.MfaChallengePayloadParserTest"`
Expected: PASS (4 tests green).

- [ ] **Step 5: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/MfaChallengePayload.kt app/src/test/java/com/urlxl/mail/push/MfaChallengePayloadParserTest.kt
git commit -m "feat(push): add MFA challenge payload parser"
```

---

### Task 2: `MfaResponseClient` — network call to `/api/mfa/push/respond`

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/push/MfaResponseClient.kt`
- Test: `app/src/test/java/com/urlxl/mail/push/MfaResponseEndpointResolverTest.kt`

**Interfaces:**
- Consumes: `PairingData` (`app/src/main/java/com/urlxl/mail/push/PairingModels.kt:9`, fields `subscriberId`, `subscriberHash`, `serverUrl`, `deviceId: String?`), `Call.Factory.executeSync` (`app/src/main/java/com/urlxl/mail/HttpExecute.kt:12`).
- Produces: `fun resolveMfaRespondEndpoint(serverUrl: String): String`, `sealed class MfaRespondResult { data class Success(val status: String); data class Error(val message: String) }`, `class MfaResponseClient { suspend fun respond(pairing: PairingData, challengeId: String, approve: Boolean): MfaRespondResult }` — consumed by Task 3 (`PushGraph`) and Task 5 (`MfaResponseReceiver`).

- [ ] **Step 1: Write the failing test** (pure-function resolver only, per the JVM-unit-test constraint — the OkHttp client itself is exercised manually, matching `NativeRegistrationClient`, which also has no JVM test of its network path)

```kotlin
package com.urlxl.mail.push

import org.junit.Assert.assertEquals
import org.junit.Test

class MfaResponseEndpointResolverTest {

    @Test
    fun resolve_trimsTrailingSlash() {
        assertEquals(
            "https://mail.example.com/api/mfa/push/respond",
            resolveMfaRespondEndpoint("https://mail.example.com/"),
        )
    }

    @Test
    fun resolve_noTrailingSlash_isUnchanged() {
        assertEquals(
            "https://mail.example.com/api/mfa/push/respond",
            resolveMfaRespondEndpoint("https://mail.example.com"),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.MfaResponseEndpointResolverTest"`
Expected: FAIL (compile error — `resolveMfaRespondEndpoint` doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.urlxl.mail.push

import com.urlxl.mail.executeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

@Serializable
data class MfaRespondRequest(
    @SerialName("challengeId") val challengeId: String,
    @SerialName("subscriberId") val subscriberId: String,
    @SerialName("subscriberHash") val subscriberHash: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("approve") val approve: Boolean,
)

@Serializable
data class MfaRespondResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("status") val status: String? = null,
)

/** Mirrors [resolvePullEndpoint] in NativeRegistration.kt — the respond endpoint has no server-provided override, it's always derived from the paired server URL. */
fun resolveMfaRespondEndpoint(serverUrl: String): String =
    "${serverUrl.trimEnd('/')}/api/mfa/push/respond"

sealed class MfaRespondResult {
    data class Success(val status: String) : MfaRespondResult()
    data class Error(val message: String) : MfaRespondResult()
}

class MfaResponseClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun respond(pairing: PairingData, challengeId: String, approve: Boolean): MfaRespondResult {
        val deviceId = pairing.deviceId
        if (deviceId.isNullOrBlank()) return MfaRespondResult.Error("Device is not registered yet")

        val request = MfaRespondRequest(
            challengeId = challengeId,
            subscriberId = pairing.subscriberId,
            subscriberHash = pairing.subscriberHash,
            deviceId = deviceId,
            approve = approve,
        )
        val httpRequest = Request.Builder()
            .url(resolveMfaRespondEndpoint(pairing.serverUrl))
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val result = withContext(Dispatchers.IO) {
            okHttpClient.executeSync(httpRequest) { response -> response.code to response.body?.string().orEmpty() }
        }
        val (code, rawBody) = result.getOrNull()
            ?: return MfaRespondResult.Error(result.exceptionOrNull()?.message ?: "Failed to reach server")

        return when (code) {
            200 -> {
                val body = runCatching { json.decodeFromString<MfaRespondResponse>(rawBody) }.getOrNull()
                if (body?.ok == true) {
                    MfaRespondResult.Success(body.status ?: "resolved")
                } else {
                    MfaRespondResult.Error("Server did not confirm response")
                }
            }
            401 -> MfaRespondResult.Error("Pairing is no longer valid")
            403 -> MfaRespondResult.Error("This device cannot approve sign-in")
            409 -> {
                val body = runCatching { json.decodeFromString<MfaRespondResponse>(rawBody) }.getOrNull()
                MfaRespondResult.Error("Already ${body?.status ?: "resolved"} on another device")
            }
            else -> MfaRespondResult.Error("Failed to respond ($code)")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.MfaResponseEndpointResolverTest"`
Expected: PASS (2 tests green).

- [ ] **Step 5: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/MfaResponseClient.kt app/src/test/java/com/urlxl/mail/push/MfaResponseEndpointResolverTest.kt
git commit -m "feat(push): add MfaResponseClient for /api/mfa/push/respond"
```

---

### Task 3: Wire `MfaResponseClient` into `PushGraph`

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/PushRuntime.kt`

**Interfaces:**
- Consumes: `MfaResponseClient` from Task 2.
- Produces: `PushGraph.mfaResponseClient: MfaResponseClient` — consumed by Task 5 (`MfaResponseReceiver`) and Task 6 (`MfaApprovalActivity`).

- [ ] **Step 1: Modify `PushGraph`**

Current (`app/src/main/java/com/urlxl/mail/push/PushRuntime.kt:6-17`):

```kotlin
class PushGraph(context: Context) {
    private val appContext = context.applicationContext
    val repository = PushRepository(appContext)
    val pullCoordinator = PullSyncCoordinator(
        appContext = appContext,
        repository = repository,
    )
    val syncCoordinator = PushSyncCoordinator(
        repository = repository,
        registrationClient = NativeRegistrationClient(),
    )
}
```

Replace with:

```kotlin
class PushGraph(context: Context) {
    private val appContext = context.applicationContext
    val repository = PushRepository(appContext)
    val pullCoordinator = PullSyncCoordinator(
        appContext = appContext,
        repository = repository,
    )
    val syncCoordinator = PushSyncCoordinator(
        repository = repository,
        registrationClient = NativeRegistrationClient(),
    )
    val mfaResponseClient = MfaResponseClient()
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/PushRuntime.kt
git commit -m "feat(push): wire MfaResponseClient into PushGraph"
```

---

### Task 4: MFA notification channel + Approve/Deny notification

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/PushNotificationDispatcher.kt`

**Interfaces:**
- Consumes: `MfaChallengePayload` from Task 1.
- Produces: `PushNotificationDispatcher.showMfaChallenge(context, payload)`, `PushNotificationDispatcher.cancelMfaChallenge(context, challengeId)`, and internal constants `ACTION_MFA_APPROVE`, `ACTION_MFA_DENY`, `EXTRA_MFA_CHALLENGE_ID` (made `internal const val` so Task 5's `MfaResponseReceiver` and Task 6's `MfaApprovalActivity` — same package — can read them) — consumed by Task 2 (`LlamaFirebaseMessagingService`), Task 5, Task 6.
- References `MfaApprovalActivity` (Task 6) and `MfaResponseReceiver` (Task 5) by class reference — this task must be implemented *after* Tasks 5 and 6, or those two class files must exist (even as empty stubs) before this file compiles. Recommended order: do Task 5 and Task 6 first, then Task 4. (The task numbering here reflects logical grouping — dispatcher, receiver, activity — not required execution order; the plan's actual execution order is Task 1 → 2 → 3 → **6 → 5 → 4** → 7 → 8, per the note in Task 6.)

- [ ] **Step 1: Add the MFA channel, actions, and `showMfaChallenge`/`cancelMfaChallenge`**

Current full file (`app/src/main/java/com/urlxl/mail/push/PushNotificationDispatcher.kt`):

```kotlin
package com.urlxl.mail.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.urlxl.mail.MainActivity
import com.urlxl.mail.R

object PushNotificationDispatcher {
    private const val CHANNEL_ID = "llama_labels_push"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Llama Labels",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Push notifications for labeled email events"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, payload: PushPayload) {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            payload.messageId.hashCode(),
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(PushPayloadParser.title(payload))
            .setContentText(PushPayloadParser.body(payload))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PushPayloadParser.body(payload)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(payload.messageId.hashCode(), notification)
    }
}
```

Replace entirely with:

```kotlin
package com.urlxl.mail.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.urlxl.mail.MainActivity
import com.urlxl.mail.R

object PushNotificationDispatcher {
    private const val CHANNEL_ID = "llama_labels_push"
    private const val MFA_CHANNEL_ID = "llama_labels_mfa"

    internal const val ACTION_MFA_APPROVE = "com.urlxl.mail.push.ACTION_MFA_APPROVE"
    internal const val ACTION_MFA_DENY = "com.urlxl.mail.push.ACTION_MFA_DENY"
    internal const val EXTRA_MFA_CHALLENGE_ID = "challengeId"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Llama Labels",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Push notifications for labeled email events"
        }
        manager.createNotificationChannel(channel)
    }

    fun ensureMfaChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(MFA_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            MFA_CHANNEL_ID,
            "Sign-in approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Approve or deny sign-in attempts to your account"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, payload: PushPayload) {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            payload.messageId.hashCode(),
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(PushPayloadParser.title(payload))
            .setContentText(PushPayloadParser.body(payload))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PushPayloadParser.body(payload)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(payload.messageId.hashCode(), notification)
    }

    fun showMfaChallenge(context: Context, payload: MfaChallengePayload) {
        ensureMfaChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notificationId = mfaNotificationId(payload.challengeId)

        val tapIntent = Intent(context, MfaApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val tapPendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val approveIntent = Intent(context, MfaResponseReceiver::class.java)
            .setAction(ACTION_MFA_APPROVE)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val approvePendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId * 2,
            approveIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val denyIntent = Intent(context, MfaResponseReceiver::class.java)
            .setAction(ACTION_MFA_DENY)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val denyPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            denyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, MFA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Approve sign-in")
            .setContentText("Tap to approve or deny a sign-in to your account.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .addAction(0, "Approve", approvePendingIntent)
            .addAction(0, "Deny", denyPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancelMfaChallenge(context: Context, challengeId: String) {
        NotificationManagerCompat.from(context).cancel(mfaNotificationId(challengeId))
    }

    private fun mfaNotificationId(challengeId: String): Int = ("mfa-$challengeId").hashCode()
}
```

- [ ] **Step 2: Build to verify it compiles**

This will only compile once `MfaApprovalActivity` (Task 6) and `MfaResponseReceiver` (Task 5) exist — do this step after completing those two tasks.

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/PushNotificationDispatcher.kt
git commit -m "feat(push): add MFA approval channel and Approve/Deny notification"
```

---

### Task 5: `MfaResponseReceiver` — handles notification action taps

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/push/MfaResponseReceiver.kt`

**Interfaces:**
- Consumes: `PushRuntime.graph(context): PushGraph` (`app/src/main/java/com/urlxl/mail/push/PushRuntime.kt:22`), `PushGraph.repository: PushRepository` and `PushGraph.mfaResponseClient: MfaResponseClient` (Task 3), `PushRepository.state: Flow<PushState>` where `PushState.pairing: PairingData?` (`app/src/main/java/com/urlxl/mail/push/PushRepository.kt:40,124-125`), `MfaResponseClient.respond(...)` and `MfaRespondResult` (Task 2), `PushNotificationDispatcher.ACTION_MFA_APPROVE`/`ACTION_MFA_DENY`/`EXTRA_MFA_CHALLENGE_ID`/`cancelMfaChallenge` (Task 4 — same package, `internal` visibility is enough).
- Produces: `class MfaResponseReceiver : BroadcastReceiver()` registered as a manifest receiver (Task 7); `companion object { suspend fun respond(context: Context, challengeId: String, approve: Boolean) }` — this is the shared respond-and-notify logic, consumed directly by Task 6 (`MfaApprovalActivity`) so the in-app fallback screen and the notification-action path share one code path rather than duplicating it.

Note on execution order: build this task (and Task 6) before finishing Task 4's `showMfaChallenge`/`cancelMfaChallenge`, since that function references `MfaApprovalActivity` and `MfaResponseReceiver` by class. Write Task 4's channel/constants edit first (compiles fine on its own — the `showMfaChallenge`/`cancelMfaChallenge` additions are what need the other two classes), then Task 5, then Task 6, then come back and add `showMfaChallenge`/`cancelMfaChallenge`, then Task 4's Step 2 build check will pass.

- [ ] **Step 1: Write the implementation**

There is no existing `BroadcastReceiver` in this codebase to test against without a real `Context`/`NotificationManager` (Robolectric is intentionally not a dependency here — `app/AGENTS.md:77`), so per the JVM-test constraint this task has no automated test; correctness is verified manually in Task 8.

```kotlin
package com.urlxl.mail.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MfaResponseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approve = when (intent.action) {
            PushNotificationDispatcher.ACTION_MFA_APPROVE -> true
            PushNotificationDispatcher.ACTION_MFA_DENY -> false
            else -> return
        }
        val challengeId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID).orEmpty()
        if (challengeId.isBlank()) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                respond(appContext, challengeId, approve)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun respond(context: Context, challengeId: String, approve: Boolean) {
            PushNotificationDispatcher.cancelMfaChallenge(context, challengeId)

            val graph = PushRuntime.graph(context)
            val pairing = graph.repository.state.first().pairing
            if (pairing == null) {
                showResultToast(context, "Not paired with a server")
                return
            }

            when (val result = graph.mfaResponseClient.respond(pairing, challengeId, approve)) {
                is MfaRespondResult.Success -> showResultToast(
                    context,
                    if (approve) "Sign-in approved" else "Sign-in denied",
                )
                is MfaRespondResult.Error -> showResultToast(context, result.message)
            }
        }

        private suspend fun showResultToast(context: Context, message: String) = withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/MfaResponseReceiver.kt
git commit -m "feat(push): add MfaResponseReceiver for notification Approve/Deny actions"
```

(This will not compile standalone yet — `PushNotificationDispatcher.ACTION_MFA_APPROVE` etc. from Task 4 Step 1 must already be in place. Do Task 4 Step 1 before this task, or write both in the same working session before building. Building is verified once at the end of Task 6.)

---

### Task 6: `MfaApprovalActivity` — in-app fallback screen

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/push/MfaApprovalActivity.kt`
- Create: `app/src/main/res/layout/activity_mfa_approval.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MfaResponseReceiver.respond(context, challengeId, approve)` (Task 5), `PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID` (Task 4).
- Produces: `class MfaApprovalActivity : AppCompatActivity()`, registered in the manifest (Task 7), launched by `showMfaChallenge`'s tap `PendingIntent` (Task 4) with extra `EXTRA_MFA_CHALLENGE_ID`.

This screen exists for the case where the notification's action-button broadcast gets killed by OEM background restrictions before `MfaResponseReceiver` finishes its network call — tapping the notification body itself (not an action button) opens this screen, which offers the same two buttons and reuses `MfaResponseReceiver.respond` directly (no receiver/broadcast involved, since an active `Activity` can run the suspend call directly in `lifecycleScope`). No ViewModel is introduced — two buttons and a status line don't warrant the `PushHomeViewModel`-style `StateFlow` machinery used by `PushPairingActivity`; this keeps the file self-contained per YAGNI.

- [ ] **Step 1: Add strings**

Append to `app/src/main/res/values/strings.xml`, near the existing `push_pairing_*` strings (after line 97):

```xml
    <string name="mfa_approval_title">Approve sign-in</string>
    <string name="mfa_approval_body">Someone is trying to sign in to your account. If this wasn\'t you, deny it.</string>
    <string name="mfa_approval_approve">Approve</string>
    <string name="mfa_approval_deny">Deny</string>
```

- [ ] **Step 2: Write the layout**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/mfaApprovalRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center">

    <TextView
        android:id="@+id/mfaApprovalTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/mfa_approval_title"
        android:textSize="20sp"
        android:textStyle="bold"
        android:layout_marginBottom="12dp" />

    <TextView
        android:id="@+id/mfaApprovalBody"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/mfa_approval_body"
        android:textSize="14sp"
        android:gravity="center"
        android:layout_marginBottom="24dp" />

    <Button
        android:id="@+id/btnMfaApprove"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_marginBottom="12dp"
        android:text="@string/mfa_approval_approve"
        android:textSize="16sp" />

    <Button
        android:id="@+id/btnMfaDeny"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="@string/mfa_approval_deny"
        android:textSize="16sp" />

</LinearLayout>
```

- [ ] **Step 3: Write the activity**

```kotlin
package com.urlxl.mail.push

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.R
import kotlinx.coroutines.launch

class MfaApprovalActivity : AppCompatActivity() {
    private var challengeId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mfa_approval)

        challengeId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID).orEmpty()
        if (challengeId.isBlank()) {
            finish()
            return
        }

        findViewById<Button>(R.id.btnMfaApprove).setOnClickListener { resolve(approve = true) }
        findViewById<Button>(R.id.btnMfaDeny).setOnClickListener { resolve(approve = false) }
    }

    private fun resolve(approve: Boolean) {
        findViewById<Button>(R.id.btnMfaApprove).isEnabled = false
        findViewById<Button>(R.id.btnMfaDeny).isEnabled = false
        lifecycleScope.launch {
            MfaResponseReceiver.respond(applicationContext, challengeId, approve)
            finish()
        }
    }
}
```

- [ ] **Step 4: Build to verify it compiles**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (This is the first build check that exercises Tasks 4, 5, and 6 together — if `MfaResponseReceiver` or `MfaApprovalActivity` references from Task 4's `showMfaChallenge` don't resolve, fix them now before moving on.)

- [ ] **Step 5: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/MfaApprovalActivity.kt app/src/main/res/layout/activity_mfa_approval.xml app/src/main/res/values/strings.xml
git commit -m "feat(push): add in-app MFA approval fallback screen"
```

---

### Task 7: Route incoming `mfa_challenge` FCM messages + register manifest entries

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/LlamaFirebaseMessagingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `MfaChallengePayloadParser.parse` (Task 1), `PushNotificationDispatcher.showMfaChallenge` (Task 4).

- [ ] **Step 1: Branch `onMessageReceived` on payload type**

Current (`app/src/main/java/com/urlxl/mail/push/LlamaFirebaseMessagingService.kt`, full file):

```kotlin
package com.urlxl.mail.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LlamaFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PushNotificationDispatcher.ensureChannel(this)
    }

    override fun onNewToken(token: String) {
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.syncCoordinator.syncProvidedToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PushPayloadParser.parse(message.data) ?: return
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.repository.appendPayload(payload)
        }
        PushNotificationDispatcher.show(applicationContext, payload)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
```

Replace `onCreate` and `onMessageReceived` with:

```kotlin
    override fun onCreate() {
        super.onCreate()
        PushNotificationDispatcher.ensureChannel(this)
        PushNotificationDispatcher.ensureMfaChannel(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val mfaChallenge = MfaChallengePayloadParser.parse(message.data)
        if (mfaChallenge != null) {
            PushNotificationDispatcher.showMfaChallenge(applicationContext, mfaChallenge)
            return
        }

        val payload = PushPayloadParser.parse(message.data) ?: return
        val graph = PushRuntime.graph(applicationContext)
        serviceScope.launch {
            graph.repository.appendPayload(payload)
        }
        PushNotificationDispatcher.show(applicationContext, payload)
    }
```

(All other methods/imports unchanged.)

- [ ] **Step 2: Register the receiver and fallback activity in the manifest**

Current (`app/src/main/AndroidManifest.xml:78-84`):

```xml
        <service
            android:name=".push.LlamaFirebaseMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>

    </application>
```

Replace with:

```xml
        <service
            android:name=".push.LlamaFirebaseMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>

        <receiver
            android:name=".push.MfaResponseReceiver"
            android:exported="false" />

        <activity
            android:name=".push.MfaApprovalActivity"
            android:exported="false"
            android:launchMode="singleTop" />

    </application>
```

(`exported="false"` on both — they are only ever invoked via explicit `PendingIntent`s this app creates itself, never by another app or a deep link, matching the `LlamaFirebaseMessagingService` service's own `exported="false"`.)

- [ ] **Step 3: Build to verify it compiles**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit test suite**

Run: `cd /home/yoshi/git/llama-mobile && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green including the two new files from Tasks 1 and 2.

- [ ] **Step 5: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/java/com/urlxl/mail/push/LlamaFirebaseMessagingService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(push): route mfa_challenge FCM messages, register MFA receiver/activity"
```

---

### Task 8: Manual end-to-end verification + DOX update

**Files:**
- Modify: `app/src/main/AGENTS.md`

No new code in this task — it closes the loop with a real device/emulator check (this feature can't be meaningfully unit-tested; `SecurePairingStore`, `NotificationManager`, and FCM delivery all require a real Android environment) and updates the binding contract doc per the repo's DOX framework.

- [ ] **Step 1: Manual verification against the real backend**

Prerequisites: a device/emulator already paired with a running `llama labels` backend (existing `PushPairingActivity` QR flow), and a web-app user account with TOTP enabled and push 2FA turned on (`Security` page → toggle "Push 2FA" — requires TOTP already enabled per the Milestone 2 design constraint) with this device marked as an approver.

1. From a browser, log in as that user with the correct password. The web app should show the push-wait screen and the paired phone should receive a high-priority notification titled "Approve sign-in" with **Approve** / **Deny** action buttons.
2. Tap **Approve** directly from the notification shade (don't open the app). Confirm: the web login completes within ~1.5s (poll interval), the notification is dismissed, and a "Sign-in approved" toast appears on the phone.
3. Repeat, this time tapping **Deny**. Confirm: the web app's `handlePushFinish`/poll path reports the challenge as denied (per existing web-side push-mfa test coverage) and the phone shows "Sign-in denied".
4. Repeat, this time tapping the notification body (not an action button) to open `MfaApprovalActivity`, then tap **Approve** there. Confirm the same end-to-end result as step 2, and that the notification is cancelled.
5. Trigger a challenge, then let it expire (~5 minutes, per the backend's `mfa.Store` TTL) without responding. Confirm the web app's poll reports `"expired"` and the phone's notification can still be tapped without crashing (should show an error toast, e.g. "Failed to respond (401)" or similar, not a crash).
6. With two devices paired as approvers on the same account, trigger a challenge and approve from device A. On device B, tap the still-showing notification's Approve or Deny. Confirm device B gets the "Already approved on another device" toast (409 path) rather than silently succeeding — this is the mobile-side check on the backend's already-tested first-response-wins `ResolvePush` behavior.

- [ ] **Step 2: Update `app/src/main/AGENTS.md`**

Add a new bullet to the "Local Contracts" section (after the existing "Incoming FCM payload parser contract keys are exact" bullet, `app/src/main/AGENTS.md:17`):

```markdown
- MFA push 2FA: an incoming FCM data payload with `type: "mfa_challenge"` and `challengeId` is parsed by `MfaChallengePayloadParser` (distinct from the mail payload parser) and shown via a separate high-importance notification channel (`PushNotificationDispatcher.showMfaChallenge`) with Approve/Deny actions. Actions are handled by `MfaResponseReceiver` (`PendingIntent.getBroadcast`, the only broadcast-receiver-driven notification actions in this app); tapping the notification body instead opens `MfaApprovalActivity`, an in-app fallback for when OEM background restrictions kill the action broadcast. Both paths call `MfaResponseReceiver.respond`, which POSTs to `{serverUrl}/api/mfa/push/respond` via `MfaResponseClient` using the same `sub`/`hash` pairing credential as native register/pull — no new device-auth scheme. The backend enforces that only a push-2FA-enabled account's own paired device can approve its challenge; the mobile side surfaces `403`/`409` responses as toasts rather than treating them as generic errors.
```

- [ ] **Step 3: Commit**

```bash
cd /home/yoshi/git/llama-mobile
git add app/src/main/AGENTS.md
git commit -m "docs: document MFA push 2FA contract in app AGENTS.md"
```

---

## Self-Review Notes

- **Spec coverage**: notification action buttons (Task 4), `MfaResponseReceiver` (Task 5), in-app fallback screen (Task 6) — all three items named in the original Milestone 3 scope (`llama-mobile` push-2FA milestone) from the shipped-features memory are covered. No `MfaChallengePayloadParserTest.kt`-shaped gap: Task 1 delivers exactly that file, mirroring `PushPayloadParserTest.kt` as specified.
- **No backend changes**: verified — every file in this plan is under `/home/yoshi/git/llama-mobile`.
- **Cross-file type consistency checked**: `MfaChallengePayload.challengeId` (Task 1) → `PushNotificationDispatcher.showMfaChallenge(context, payload: MfaChallengePayload)` (Task 4) → `payload.challengeId` used consistently; `MfaResponseClient.respond(pairing: PairingData, challengeId: String, approve: Boolean): MfaRespondResult` (Task 2) signature matches every call site in Task 5 and Task 6.
- **Ordering caveat flagged explicitly** (Task 4/5/6 have a circular class reference — dispatcher references activity/receiver, receiver references dispatcher's constants) rather than glossed over: build verification is deliberately deferred to the end of Task 6, not attempted after Task 4 alone.
