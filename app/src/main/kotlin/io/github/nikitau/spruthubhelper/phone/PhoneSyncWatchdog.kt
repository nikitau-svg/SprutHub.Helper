package io.github.nikitau.spruthubhelper.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.ui.MainActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PhoneWatchdogInput(
    val syncEnabled: Boolean,
    val watchdogEnabled: Boolean,
    val nowEpochMs: Long,
    val monitoringStartedEpochMs: Long?,
    val lastSuccessEpochMs: Long?,
    val notifiedReferenceEpochMs: Long?,
    val staleAfterMs: Long,
)

internal sealed interface PhoneWatchdogDecision {
    data class Notify(val referenceEpochMs: Long, val staleForMs: Long) : PhoneWatchdogDecision
    data class Skip(val reason: PhoneWatchdogSkipReason, val staleForMs: Long? = null) : PhoneWatchdogDecision
}

internal enum class PhoneWatchdogSkipReason(val diagnosticReason: String) {
    SYNC_DISABLED("фоновая синхронизация выключена"),
    WATCHDOG_DISABLED("локальный watchdog выключен"),
    BASELINE_MISSING("ещё нет точки отсчёта"),
    RECENT_SUCCESS("успешная синхронизация была недавно"),
    ALREADY_NOTIFIED("для этого периода предупреждение уже показано"),
}

internal fun decidePhoneWatchdog(input: PhoneWatchdogInput): PhoneWatchdogDecision {
    if (!input.syncEnabled) return PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.SYNC_DISABLED)
    if (!input.watchdogEnabled) return PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.WATCHDOG_DISABLED)
    val reference = listOfNotNull(input.monitoringStartedEpochMs, input.lastSuccessEpochMs).maxOrNull()
        ?: return PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.BASELINE_MISSING)
    val staleFor = if (input.nowEpochMs <= reference) 0L else input.nowEpochMs - reference
    if (staleFor < input.staleAfterMs) {
        return PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.RECENT_SUCCESS, staleFor)
    }
    if (input.notifiedReferenceEpochMs == reference) {
        return PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.ALREADY_NOTIFIED, staleFor)
    }
    return PhoneWatchdogDecision.Notify(reference, staleFor)
}

internal sealed interface PhoneWatchdogCheckResult {
    data class Skipped(val reason: String) : PhoneWatchdogCheckResult
    data class Notified(val staleForMs: Long) : PhoneWatchdogCheckResult
}

internal object PhoneSyncWatchdog {
    const val STALE_AFTER_MINUTES = 45L
    private val notificationMutex = Mutex()

    suspend fun check(
        context: Context,
        settings: SettingsRepository,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PhoneWatchdogCheckResult = notificationMutex.withLock {
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.WORK_MANAGER,
            event = "Проверка watchdog синхронизации телефона",
            outcome = DiagnosticOutcome.STARTED,
        )
        val syncSettings = settings.phoneSyncSettings.first()
        val decision = decidePhoneWatchdog(
            PhoneWatchdogInput(
                syncEnabled = syncSettings.enabled,
                watchdogEnabled = syncSettings.watchdogEnabled,
                nowEpochMs = nowEpochMs,
                monitoringStartedEpochMs = settings.phoneMonitoringStarted.first(),
                lastSuccessEpochMs = settings.lastPhoneSync.first(),
                notifiedReferenceEpochMs = settings.phoneWatchdogNotifiedReference.first(),
                staleAfterMs = TimeUnit.MINUTES.toMillis(STALE_AFTER_MINUTES),
            ),
        )
        if (decision is PhoneWatchdogDecision.Skip) {
            val reason = decision.reason.diagnosticReason
            Log.d(LOG_TAG, "Phone watchdog skipped: $reason")
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.BACKGROUND,
                event = "Watchdog синхронизации телефона",
                outcome = DiagnosticOutcome.SKIPPED,
                reason = reason,
            )
            return@withLock PhoneWatchdogCheckResult.Skipped(reason)
        }

        decision as PhoneWatchdogDecision.Notify
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureNotificationChannel(manager)
        val permissionSkip = notificationSkipReason(context, manager)
        if (permissionSkip != null) {
            Log.w(LOG_TAG, "Phone watchdog could not notify: $permissionSkip")
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.PERMISSION,
                event = "Уведомление watchdog",
                outcome = DiagnosticOutcome.SKIPPED,
                reason = permissionSkip,
            )
            return@withLock PhoneWatchdogCheckResult.Skipped(permissionSkip)
        }

        manager.notify(NOTIFICATION_ID, staleNotification(context))
        settings.markPhoneWatchdogNotified(decision.referenceEpochMs)
        Log.w(LOG_TAG, "Phone watchdog notified after ${decision.staleForMs} ms without success")
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.BACKGROUND,
            event = "Предупреждение о застывшей синхронизации показано",
            outcome = DiagnosticOutcome.SUCCESS,
            reason = "успешной синхронизации нет не менее $STALE_AFTER_MINUTES минут",
        )
        PhoneWatchdogCheckResult.Notified(decision.staleForMs)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PhoneSyncWatchdogWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    suspend fun onSyncSucceeded(context: Context) {
        notificationMutex.withLock {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureNotificationChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Проблемы синхронизации",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Локальное предупреждение, если телефон давно не отправлял данные в SprutHub"
                setShowBadge(true)
            },
        )
    }

    private fun notificationSkipReason(context: Context, manager: NotificationManager): String? {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Android не разрешил уведомления"
        }
        if (!manager.areNotificationsEnabled()) return "уведомления приложения выключены"
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return "канал предупреждений watchdog выключен"
        }
        return null
    }

    private fun staleNotification(context: Context): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Телефон давно не синхронизировался")
            .setContentText("Нет успешной отправки больше $STALE_AFTER_MINUTES минут. Проверьте сеть и ограничения фона.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private const val CHANNEL_ID = "spruthub_phone_watchdog"
    private const val NOTIFICATION_ID = 2043
    private const val WORK_NAME = "spruthub_phone_sync_watchdog"
    private const val LOG_TAG = "SprutHubPhone"
}
