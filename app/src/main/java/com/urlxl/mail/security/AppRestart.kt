package com.urlxl.mail.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.urlxl.mail.MainActivity

private const val RESTART_DELAY_MILLIS = 300L

/**
 * Kills and relaunches the app process. Needed whenever a setting requires a fresh
 * [com.urlxl.mail.data.DataGraph] — Room's Java API has no supported way to hot-swap a live
 * database instance out from under Activities/ViewModels that already hold references to the
 * old one, so a clean process restart is the correct fix, not a workaround. Schedules
 * `MainActivity` to launch a few hundred ms in the future via `AlarmManager` (survives the
 * process actually dying, unlike a plain `Handler.postDelayed`), then kills this process.
 */
object AppRestart {
    fun relaunch(context: Context) {
        val appContext = context.applicationContext
        val restartIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
            pendingIntent,
        )
        Process.killProcess(Process.myPid())
    }
}
