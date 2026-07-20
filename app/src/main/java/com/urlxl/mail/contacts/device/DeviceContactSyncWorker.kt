package com.urlxl.mail.contacts.device

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DeviceContactSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = DeviceContactsRuntime.graph(applicationContext)
        return try {
            graph.repository.syncAll()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object DeviceContactSyncScheduler {
    private const val PERIODIC_WORK_NAME = "kypost_device_contact_sync_periodic"
    private const val PERIOD_MINUTES = 15L

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DeviceContactSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
