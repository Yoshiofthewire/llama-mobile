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

object SecurityRuntime {
    private val holder = SingletonGraph(::SecurityGraph)

    fun graph(context: Context): SecurityGraph = holder.get(context)
}
