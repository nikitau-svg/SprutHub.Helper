package io.github.nikitau.spruthubhelper.health

import android.content.Context
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
        return Result.success()
    }
}
