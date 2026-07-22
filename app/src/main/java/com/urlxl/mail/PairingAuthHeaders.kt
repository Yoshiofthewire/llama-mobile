package com.urlxl.mail

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request

const val HEADER_DEVICE_ID = "X-Kypost-Device-Id"
const val HEADER_DEVICE_SECRET = "X-Kypost-Device-Secret"

/**
 * Attaches this device's own pairing-auth credentials as headers. Replaces the old
 * account-wide shared subscriberId/subscriberHash headers (removed entirely — the server no
 * longer accepts them). deviceSecret is minted once per successful registration call and must
 * be persisted unconditionally by the caller (see SecurePairingStore), since each registration
 * mints a brand-new secret that invalidates the previous one.
 */
fun Request.Builder.pairingAuthHeaders(deviceId: String, deviceSecret: String): Request.Builder =
    header(HEADER_DEVICE_ID, deviceId).header(HEADER_DEVICE_SECRET, deviceSecret)

/**
 * Shared client for every request that carries [pairingAuthHeaders]. Redirect-following is
 * disabled: OkHttp only strips the standard Authorization header on a cross-host redirect, not
 * our custom device-id/secret headers, so a malicious or compromised paired server could
 * otherwise 3xx-redirect a request to an arbitrary host and receive the device's bearer
 * credential.
 */
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
