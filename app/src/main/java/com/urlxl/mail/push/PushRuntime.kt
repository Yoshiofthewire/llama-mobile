package com.urlxl.mail.push

import android.content.Context
import com.urlxl.mail.SingletonGraph
import okhttp3.Call

class PushGraph(context: Context) {
    private val appContext = context.applicationContext
    val repository = PushRepository(appContext)

    // Every credential-bearing client below shares this one pinned-or-fallback factory rather
    // than defaulting to the plain unpinned `pairingHttpClient()` — see the 2026-07-22
    // security-hardening spec's final-review fix round, finding C2. Wired directly to this
    // graph's own [repository] (not via [PushRuntime.graph], which would recursively construct
    // this same [PushGraph] instance mid-construction) — falls back to unpinned automatically
    // until a TLS pin exists (i.e. before the first successful pairing), then pins from the next
    // request onward.
    private val pinnedOrFallbackCallFactory: Call.Factory = PinnedOrFallbackCallFactory(
        PinnedCallFactoryProvider(
            tlsPinProvider = { repository.currentTlsPin() },
            pairingProvider = { repository.pairingForAuthenticatedCall() },
        ),
    )

    val pullCoordinator = PullSyncCoordinator(
        appContext = appContext,
        repository = repository,
        pullClient = PullNotificationClient(callFactory = pinnedOrFallbackCallFactory),
    )
    val syncCoordinator = PushSyncCoordinator(
        repository = repository,
        // First pairing itself stays correctly TOFU-unpinned (no pin exists yet, so this falls
        // back to plain `pairingHttpClient()`); every resync afterward automatically pins once
        // the pairing call above has captured one.
        registrationClient = NativeRegistrationClient(callFactory = pinnedOrFallbackCallFactory),
    )
    val mfaResponseClient = MfaResponseClient(callFactory = pinnedOrFallbackCallFactory)
    val deregisterClient = DeregisterClient(callFactory = pinnedOrFallbackCallFactory)
}

object PushRuntime {
    private val holder = SingletonGraph(::PushGraph)

    fun graph(context: Context): PushGraph = holder.get(context)
}

