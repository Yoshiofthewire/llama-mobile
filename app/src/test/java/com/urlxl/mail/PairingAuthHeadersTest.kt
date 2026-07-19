package com.urlxl.mail

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingAuthHeadersTest {

    @Test
    fun pairingAuthHeaders_setsBothHeadersOnTheRequest() {
        val request = Request.Builder()
            .url("https://relay.example.com/api/inbox".toHttpUrl())
            .get()
            .pairingAuthHeaders("sub-1", "hash-1")
            .build()

        assertEquals("sub-1", request.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", request.header(HEADER_SUBSCRIBER_HASH))
    }

    @Test
    fun pairingAuthHeaders_doesNotAddQueryParams() {
        val request = Request.Builder()
            .url("https://relay.example.com/api/inbox".toHttpUrl())
            .get()
            .pairingAuthHeaders("sub-1", "hash-1")
            .build()

        assertNull(request.url.queryParameter("sub"))
        assertNull(request.url.queryParameter("hash"))
    }
}
