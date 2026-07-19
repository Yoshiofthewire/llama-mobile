package com.urlxl.mail

import okhttp3.Request

const val HEADER_SUBSCRIBER_ID = "X-Kypost-Subscriber-Id"
const val HEADER_SUBSCRIBER_HASH = "X-Kypost-Subscriber-Hash"

/**
 * Attaches pairing-auth credentials as headers — the server (already migrated) reads these in
 * preference to the legacy `?sub=&hash=` query params. This app always sends headers now; there
 * is no query-param fallback on the client side.
 */
fun Request.Builder.pairingAuthHeaders(subscriberId: String, subscriberHash: String): Request.Builder =
    header(HEADER_SUBSCRIBER_ID, subscriberId).header(HEADER_SUBSCRIBER_HASH, subscriberHash)
