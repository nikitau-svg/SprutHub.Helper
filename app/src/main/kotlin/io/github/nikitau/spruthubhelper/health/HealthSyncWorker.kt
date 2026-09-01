package io.github.nikitau.spruthubhelper.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.flow.first

class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val event = "Фоновая синхронизация здоровья"
        if (!AppGraph.settings.healthEnabled.first()) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.WORK_MANAGER,
                event = event,
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "Фоновое чтение здоровья выключено или разрешение отозвано",
            )
            return Result.success()
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.WORK_MANAGER,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("период" to "15 минут", "ограничение" to "требуется сеть"),
        )
        return AppGraph.health.syncNow(fromBackground = true).fold(
            onSuccess = {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = event,
                    outcome = DiagnosticOutcome.SUCCESS,
                )
                Result.success()
            },
            onFailure = { error ->
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.WORK_MANAGER,
                    event = event,
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message,
                )
                Log.w("SprutHubHealth", "Background health sync failed; WorkManager will retry", error)
                Result.retry()
            },
        )
    }
}
