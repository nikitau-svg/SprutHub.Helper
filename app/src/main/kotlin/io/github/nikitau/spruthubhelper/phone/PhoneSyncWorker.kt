package io.github.nikitau.spruthubhelper.phone

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph

class PhoneSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = AppGraph.phone.syncNow(fromBackground = true).fold(
        onSuccess = { Result.success() },
        onFailure = { error ->
            Log.w(LOG_TAG, "Background phone sync failed; WorkManager will retry", error)
            Result.retry()
        },
    )

    private companion object {
        const val LOG_TAG = "SprutHubPhone"
    }
}
