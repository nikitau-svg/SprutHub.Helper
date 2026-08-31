package io.github.nikitau.spruthubhelper.presence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.flow.first

class PresenceSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val event = "Фоновая синхронизация геозон"
        if (AppGraph.settings.presenceZones.first().none(PresenceZone::enabled)) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.WORK_MANAGER,
                event = event,
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "Нет включённых геозон",
            )
            return Result.success()
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.WORK_MANAGER,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("период" to "15 минут", "ограничение" to "требуется сеть"),
        )
        return AppGraph.presence.syncNow(fromBackground = true).fold(
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
                Result.retry()
            },
        )
    }
}
