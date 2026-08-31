package io.github.nikitau.spruthubhelper.phone

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.flow.first

class PhoneSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        if (!AppGraph.settings.phoneSyncSettings.first().enabled) {
            Log.i(LOG_TAG, "WorkManager phone sync skipped: background sync is disabled")
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.WORK_MANAGER,
                event = "15-минутная синхронизация телефона",
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "фоновая синхронизация выключена",
            )
            return Result.success()
        }
        Log.d(LOG_TAG, "WorkManager periodic phone sync started")
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.WORK_MANAGER,
            event = "15-минутная синхронизация телефона",
            outcome = DiagnosticOutcome.STARTED,
        )
        return AppGraph.phone.syncNow(fromBackground = true).fold(
            onSuccess = {
                Log.d(LOG_TAG, "WorkManager periodic phone sync completed")
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = "15-минутная синхронизация телефона",
                    outcome = DiagnosticOutcome.SUCCESS,
                )
                Result.success()
            },
            onFailure = { error ->
                Log.w(LOG_TAG, "Background phone sync failed; WorkManager will retry", error)
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = "15-минутная синхронизация телефона",
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message ?: "неизвестная ошибка",
                )
                Result.retry()
            },
        )
    }

    private companion object {
        const val LOG_TAG = "SprutHubPhone"
    }
}
