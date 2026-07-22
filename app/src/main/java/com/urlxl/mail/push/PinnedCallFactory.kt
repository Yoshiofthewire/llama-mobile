package com.urlxl.mail.push

import android.content.Context
import com.urlxl.mail.pairingHttpClient
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Builds (and caches) a TLS-pinned [Call.Factory] from the current pairing state, rebuilding
 * only when the pin or host actually change — e.g. on re-pairing after a legitimate cert
 * rotation (see [com.urlxl.mail.mail.MailOutcome.CertificateMismatch]'s recovery path) — rather
 * than reconnecting from scratch on every call. Returns null (meaning: the caller should fall
 * back to an unpinned client) until a pin has actually been captured, i.e. before the very first
 * successful pairing completes — see [PushSyncCoordinator.attemptPairing].
 *
 * Originally lived only inside `MailGraph` (mail traffic); pulled out here so every other
 * credential-bearing client (pull polling, MFA response, contacts/groups sync, PGP QR, device
 * deregistration, post-pairing resyncs) shares the exact same caching/TOFU behavior instead of
 * re-deriving it — see the 2026-07-22 security-hardening spec's final-review fix round, finding
 * C2.
 */
class PinnedCallFactoryProvider(
    private val tlsPinProvider: () -> String?,
    private val pairingProvider: () -> PairingData?,
) : () -> Call.Factory? {
    @Volatile private var cachedKey: Pair<String, String>? = null
    @Volatile private var cachedClient: Call.Factory? = null

    override fun invoke(): Call.Factory? {
        val pin = tlsPinProvider() ?: return null
        val host = pairingProvider()?.serverUrl?.toHttpUrlOrNull()?.host ?: return null
        val key = pin to host
        cachedClient?.takeIf { cachedKey == key }?.let { return it }
        return pairingHttpClient(pinnedSpkiSha256 = pin, host = host).also {
            cachedClient = it
            cachedKey = key
        }
    }
}

/**
 * Adapts a [PinnedCallFactoryProvider] (or any `() -> Call.Factory?` shaped the same way) into a
 * plain [Call.Factory], for constructors that only accept a fixed `Call.Factory` rather than a
 * provider function to re-check on every call (unlike `RelayMailSource`, which has its own
 * `pinnedCallFactory() ?: callFactory` fallback built in). Falls back to [fallback] (plain,
 * unpinned — matches every existing call site's previous behavior) until a pin exists, then
 * starts pinning automatically the moment one is captured, re-checked on every request rather
 * than snapshotted once at construction time — important since these clients are typically
 * built once and live for the process's lifetime (see e.g. `PushGraph`), well before the first
 * pairing (and thus the first TLS pin) may exist.
 */
class PinnedOrFallbackCallFactory(
    private val pinnedProvider: () -> Call.Factory?,
    private val fallback: Call.Factory = pairingHttpClient(),
) : Call.Factory {
    override fun newCall(request: Request): Call = (pinnedProvider() ?: fallback).newCall(request)
}

/**
 * Convenience for wiring a [PinnedOrFallbackCallFactory] to [PushRuntime]'s shared repository —
 * for every client/graph that lives *outside* [PushGraph] itself (e.g. `ContactsGraph`,
 * `PgpQrClient`'s construction sites). [PushGraph]'s own internal clients cannot use this (it
 * would recursively call [PushRuntime.graph] while [PushGraph] is still being constructed) and
 * instead wire a [PinnedCallFactoryProvider] directly to their own local `PushRepository`
 * instance — see `PushGraph.pinnedOrFallbackCallFactory`.
 */
fun pinnedPairingCallFactory(context: Context): Call.Factory {
    val appContext = context.applicationContext
    return PinnedOrFallbackCallFactory(
        PinnedCallFactoryProvider(
            tlsPinProvider = { PushRuntime.graph(appContext).repository.currentTlsPin() },
            pairingProvider = { PushRuntime.graph(appContext).repository.pairingForAuthenticatedCall() },
        ),
    )
}
