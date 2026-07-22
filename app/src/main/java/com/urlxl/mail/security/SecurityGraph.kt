package com.urlxl.mail.security

import android.content.Context
import com.urlxl.mail.SingletonGraph
import kotlinx.coroutines.runBlocking

class SecurityGraph(context: Context) {
    private val appContext = context.applicationContext
    val appLockManager: AppLockManager = AppLockManager(AppLockStore(appContext)) {
        runBlocking { SecurityWipe.wipeAndResetApp(appContext) }
    }

    companion object {
        private val holder = SingletonGraph(::SecurityGraph)

        fun of(context: Context): SecurityGraph = holder.get(context)
    }
}
