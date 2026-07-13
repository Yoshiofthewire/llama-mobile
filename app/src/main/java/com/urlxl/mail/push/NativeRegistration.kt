package com.urlxl.mail.push

import android.os.Build
import com.urlxl.mail.APP_VERSION
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
data class NativeRegistrationRequest(
    @SerialName("subscriberId") val subscriberId: String,
    @SerialName("subscriberHash") val subscriberHash: String?,
    @SerialName("pairingToken") val pairingToken: String,
    @SerialName("deviceToken") val deviceToken: String,
    @SerialName("deviceId") val deviceId: String?,
    @SerialName("platform") val platform: String,
    @SerialName("transport") val transport: String? = null,
    @SerialName("deviceName") val deviceName: String?,
    @SerialName("appVersion") val appVersion: String?,
    // WebPush encryption key material (RFC 8291), present only for transport="unifiedpush".
    // The server needs these to encrypt payloads so the UnifiedPush connector can decrypt them;
    // without them, messages arrive as undecryptable ciphertext.
    @SerialName("p256dh") val p256dh: String? = null,
    @SerialName("auth") val auth: String? = null,
)

@Serializable
data class NativeRegistrationResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("synced") val synced: Boolean = false,
    @SerialName("deviceId") val deviceId: String? = null,
    // Current delivery mode for this user ("push" | "pull") and the endpoint to poll
    // when in pull mode. Both may be absent on older servers.
    @SerialName("deliveryMode") val deliveryMode: String? = null,
    @SerialName("pullEndpoint") val pullEndpoint: String? = null,
    // The transport the server actually stored ("fcm" | "apns" | "unifiedpush"), echoed back
    // so the client displays an authoritative value rather than just assuming its request won.
    // Absent on older servers.
    @SerialName("transport") val transport: String? = null,
)

/**
 * Resolves the pull endpoint: the server-provided value wins, otherwise it is derived
 * from the paired server base URL. Mirrors [NativeRegistrationEndpointResolver] for the
 * register endpoint.
 */
fun resolvePullEndpoint(serverUrl: String, provided: String?): String {
    provided?.takeIf { it.isNotBlank() }?.let { return it }
    return "${serverUrl.trimEnd('/')}/api/notifications/native/pull"
}

object NativeRegistrationRequestMapper {
    fun map(
        pairing: PairingData,
        token: String,
        transport: String? = null,
        p256dh: String? = null,
        auth: String? = null,
    ): NativeRegistrationRequest {
        return NativeRegistrationRequest(
            subscriberId = pairing.subscriberId,
            subscriberHash = pairing.subscriberHash.takeIf { it.isNotBlank() },
            pairingToken = pairing.pairingToken,
            deviceToken = token,
            deviceId = pairing.deviceId,
            platform = "android",
            transport = transport,
            deviceName = Build.MODEL,
            appVersion = "llama Mail for Android v$APP_VERSION",
            p256dh = p256dh,
            auth = auth,
        )
    }
}

sealed class NativeRegistrationResult {
    data class Success(
        val syncedAtEpochMs: Long,
        val deviceId: String?,
        val deliveryMode: DeliveryMode = DeliveryMode.PUSH,
        val pullEndpoint: String? = null,
        val transport: String? = null,
    ) : NativeRegistrationResult()
    data class Error(val message: String, val expiredPairingToken: Boolean = false) : NativeRegistrationResult()
}

class NativeRegistrationClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun register(
        pairing: PairingData,
        token: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        transport: String? = null,
        p256dh: String? = null,
        auth: String? = null,
    ): NativeRegistrationResult {
        if (token.isBlank()) return NativeRegistrationResult.Error("FCM token is empty")

        val request = NativeRegistrationRequestMapper.map(
            pairing = pairing,
            token = token,
            transport = transport,
            p256dh = p256dh,
            auth = auth,
        )
        val httpRequest = Request.Builder()
            .url(pairing.registrationUrl)
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val result = withContext(Dispatchers.IO) {
            okHttpClient.executeSync(httpRequest) { response -> response.code to response.body?.string().orEmpty() }
        }
        val (code, rawBody) = result.getOrNull()
            ?: return NativeRegistrationResult.Error(result.exceptionOrNull()?.message ?: "Failed to register device")

        return when (code) {
            200 -> {
                val body = runCatching { json.decodeFromString<NativeRegistrationResponse>(rawBody) }.getOrNull()
                if (body?.ok == true && body.synced) {
                    NativeRegistrationResult.Success(
                        syncedAtEpochMs = nowEpochMs,
                        deviceId = body.deviceId,
                        deliveryMode = DeliveryMode.fromWire(body.deliveryMode),
                        pullEndpoint = body.pullEndpoint,
                        transport = body.transport,
                    )
                } else {
                    NativeRegistrationResult.Error("Registration did not confirm sync")
                }
            }
            400 -> NativeRegistrationResult.Error("Malformed request or missing fields")
            401 -> NativeRegistrationResult.Error(
                message = "Pairing token expired or invalid",
                expiredPairingToken = true,
            )
            503 -> NativeRegistrationResult.Error("Pairing not configured on backend")
            else -> NativeRegistrationResult.Error("Failed to register device ($code)")
        }
    }
}
