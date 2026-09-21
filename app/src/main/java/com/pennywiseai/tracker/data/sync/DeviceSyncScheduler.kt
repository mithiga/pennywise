package com.pennywiseai.tracker.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pennywiseai.tracker.worker.DeviceSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<DeviceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<DeviceSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val PERIODIC_WORK = "device_sync_periodic"
        const val ONE_SHOT_WORK = "device_sync_now"
    }
}
