package io.github.nikitau.spruthubhelper.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.launch

class PresenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        AppGraph.initialize(context.applicationContext)
        val pending = goAsync()
        AppGraph.applicationScope.launch {
            try {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.BACKGROUND,
                    event = "Восстановление фоновой работы после загрузки или обновления",
                    outcome = DiagnosticOutcome.STARTED,
                )
                AppGraph.presence.refreshRegistrations()
                // Restore the user-enabled live phone monitor after reboot or
                // an in-place APK update. BOOT_COMPLETED and
                // MY_PACKAGE_REPLACED are permitted background-start cases;
                // the service itself still validates notification settings.
                AppGraph.phone.ensureLiveMonitorNow()
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.BACKGROUND,
                    event = "Восстановление фоновой работы после загрузки или обновления",
                    outcome = DiagnosticOutcome.SUCCESS,
                )
            } catch (error: Exception) {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.BACKGROUND,
                    event = "Восстановление фоновой работы после загрузки или обновления",
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message ?: "неизвестная ошибка",
                )
            } finally {
                pending.finish()
            }
        }
    }
}
