package io.github.nikitau.spruthubhelper.presence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph

class PresenceSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = AppGraph.presence.syncNow(fromBackground = true).fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
