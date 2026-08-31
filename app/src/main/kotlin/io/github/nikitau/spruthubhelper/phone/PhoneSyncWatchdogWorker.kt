package io.github.nikitau.spruthubhelper.phone

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome

class PhoneSyncWatchdogWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        return runCatching {
            PhoneSyncWatchdog.check(applicationContext, AppGraph.settings)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                Log.w(LOG_TAG, "Phone watchdog failed; WorkManager will retry", error)
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = "Проверка watchdog синхронизации телефона",
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
