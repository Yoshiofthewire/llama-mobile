package com.urlxl.mail.push

import android.os.Build
import com.urlxl.mail.APP_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRegistrationRequestMapperTest {

    @Test
    fun map_usesSubscriberIdPairingTokenDeviceIdAndPlatform() {
        val pairing = PairingData(
            subscriberId = "subscriber-id",
            subscriberHash = "subscriber-hash",
            serverUrl = "https://server.example.com",
            registrationUrl = "https://server.example.com/api/notifications/native/register",
            pairingToken = "pairing-token",
            deviceId = "last-known-device-id",
            pairedAtEpochMs = 100L,
        )

        val request = NativeRegistrationRequestMapper.map(pairing = pairing, token = "fcm-token")

        assertEquals("subscriber-id", request.subscriberId)
        assertEquals("subscriber-hash", request.subscriberHash)
        assertEquals("pairing-token", request.pairingToken)
        assertEquals("fcm-token", request.deviceToken)
        assertEquals("last-known-device-id", request.deviceId)
        assertEquals("android", request.platform)
        assertEquals(Build.MODEL, request.deviceName)
        assertEquals("llama Mail for Android v$APP_VERSION", request.appVersion)
    }

    @Test
    fun map_blankSubscriberHash_becomesNull() {
        val pairing = PairingData(
            subscriberId = "subscriber-id",
            subscriberHash = "",
            serverUrl = "https://server.example.com",
            registrationUrl = "https://server.example.com/api/notifications/native/register",
            pairingToken = "pairing-token",
            deviceId = null,
            pairedAtEpochMs = 100L,
        )

        val request = NativeRegistrationRequestMapper.map(pairing = pairing, token = "fcm-token")

        assertNull(request.subscriberHash)
        assertNull(request.deviceId)
    }
}
