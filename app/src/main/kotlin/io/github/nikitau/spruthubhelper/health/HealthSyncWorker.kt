package io.github.nikitau.spruthubhelper.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph

class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return AppGraph.health.syncNow(fromBackground = true).fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                Log.w("SprutHubHealth", "Background health sync failed; WorkManager will retry", error)
                Result.retry()
            },
        )
    }
}
