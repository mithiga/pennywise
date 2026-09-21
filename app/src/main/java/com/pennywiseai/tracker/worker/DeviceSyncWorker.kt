package com.pennywiseai.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.data.sync.SyncCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DeviceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCoordinator: SyncCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val result = syncCoordinator.sync()
        return if (result.isSuccess) Result.success() else Result.retry()
    }
}
