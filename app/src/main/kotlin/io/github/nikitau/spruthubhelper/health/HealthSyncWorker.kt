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
        // Being away from the home LAN is expected, not a terminal failure. Finishing this
        // occurrence successfully keeps the periodic work scheduled for the next interval.
        AppGraph.health.syncNow(fromBackground = true)
            .onFailure { error -> Log.w("SprutHubHealth", "Background health sync skipped or failed", error) }
        return Result.success()
    }
}
