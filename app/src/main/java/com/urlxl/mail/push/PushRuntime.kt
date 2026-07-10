package com.urlxl.mail.push

import android.content.Context
import com.urlxl.mail.SingletonGraph

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

object PushRuntime {
    private val holder = SingletonGraph(::PushGraph)

    fun graph(context: Context): PushGraph = holder.get(context)
}

