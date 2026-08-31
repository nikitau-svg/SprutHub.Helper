package io.github.nikitau.spruthubhelper.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PhoneMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private lateinit var eventSync: PhoneEventSyncCoalescer
    private var receiverRegistered = false
    private var networkCallbackRegistered = false
    private var displayObserverRegistered = false

    private val displaySettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            triggerSync(PhoneSyncTrigger.DISPLAY_SETTINGS_CHANGED)
        }
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val trigger = PhoneEventSyncPolicy.fromBroadcastAction(intent?.action)
            if (trigger == null) {
                Log.d(LOG_TAG, "Phone event skipped: unsupported broadcast")
            } else {
                triggerSync(trigger)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = triggerSync(PhoneSyncTrigger.NETWORK_AVAILABLE)
        override fun onLost(network: Network) = triggerSync(PhoneSyncTrigger.NETWORK_LOST)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            triggerSync(PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            triggerSync(PhoneSyncTrigger.NETWORK_ADDRESS_CHANGED)
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(applicationContext)
        eventSync = PhoneEventSyncCoalescer(
            scope = scope,
            debounceMs = EVENT_DEBOUNCE_MS,
            retryDelayMs = EVENT_RETRY_MS,
            sync = ::syncEventBatch,
            onAttemptFinished = { triggers, attempt, result ->
                val reasons = triggers.joinToString(",", transform = PhoneSyncTrigger::reason)
                if (result.isSuccess) {
                    Log.d(LOG_TAG, "Phone event batch completed: reasons=$reasons attempt=$attempt")
                } else {
                    Log.w(LOG_TAG, "Phone event batch failed: reasons=$reasons attempt=$attempt")
                }
            },
        )
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        registerEvents()
        observePollingInterval()
        running.value = true
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.FOREGROUND_SERVICE,
            event = "Постоянная синхронизация телефона",
            outcome = DiagnosticOutcome.STARTED,
        )
        triggerSync(PhoneSyncTrigger.MONITOR_STARTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    AppGraph.settings.setPhoneEnabled(false)
                    stopSelf()
                }
            }
            ACTION_REFRESH -> {
                observePollingInterval()
                triggerSync(PhoneSyncTrigger.SETTINGS_CHANGED)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.value = false
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.FOREGROUND_SERVICE,
            event = "Постоянная синхронизация телефона остановлена",
            outcome = DiagnosticOutcome.STATE,
        )
        if (::eventSync.isInitialized) eventSync.cancel()
        pollingJob?.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(eventReceiver) }
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        if (displayObserverRegistered) {
            runCatching { contentResolver.unregisterContentObserver(displaySettingsObserver) }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun registerEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            eventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        listOf(
            Settings.System.SCREEN_BRIGHTNESS,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_OFF_TIMEOUT,
        ).forEach { setting ->
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(setting),
                false,
                displaySettingsObserver,
            )
        }
        displayObserverRegistered = true
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { error ->
            Log.w(LOG_TAG, "Network callback is unavailable", error)
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.NETWORK,
                event = "Подписка на изменения сети",
                outcome = DiagnosticOutcome.FAILED,
                reason = error.message ?: "Android не предоставил callback сети",
            )
        }
    }

    private fun observePollingInterval() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            AppGraph.settings.phoneSyncSettings
                .map { it.pollInterval }
                .distinctUntilChanged()
                .collectLatest { interval ->
                    updateNotification()
                    while (isActive) {
                        delay(interval.minutes * 60_000L)
                        triggerSync(PhoneSyncTrigger.FOREGROUND_POLL)
                    }
                }
        }
    }

    private fun triggerSync(trigger: PhoneSyncTrigger) {
        if (trigger in NETWORK_TRIGGERS) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.NETWORK,
                event = "Изменение сети телефона",
                outcome = DiagnosticOutcome.STATE,
                details = mapOf("событие" to trigger.reason),
            )
        }
        if (::eventSync.isInitialized) eventSync.submit(trigger)
    }

    private suspend fun syncEventBatch(triggers: Set<PhoneSyncTrigger>): Result<Unit> {
        val selected = AppGraph.settings.selectedPhoneSensors.first()
        val decision = PhoneEventSyncPolicy.decide(triggers, selected)
        val reasons = triggers.joinToString(",", transform = PhoneSyncTrigger::reason)
        if (!decision.shouldSync) {
            Log.d(LOG_TAG, "Phone event batch skipped: reasons=$reasons cause=${decision.skipReason}")
            val diagnosticReason = when (decision.skipReason) {
                "no-selected-phone-sensors" -> "не выбраны показатели телефона"
                "selected-phone-sensors-unaffected" -> "событие не влияет на выбранные показатели"
                else -> "событие не требует синхронизации"
            }
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.SYNC,
                event = "Событийная синхронизация телефона",
                outcome = DiagnosticOutcome.SKIPPED,
                reason = diagnosticReason,
                details = mapOf("события" to reasons),
            )
            return Result.success(Unit)
        }
        Log.d(LOG_TAG, "Phone event batch started: reasons=$reasons")
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.SYNC,
            event = "Событийная синхронизация телефона",
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("события" to reasons),
        )
        return AppGraph.phone.syncNow(fromBackground = true).also { result ->
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.SYNC,
                event = "Событийная синхронизация телефона",
                outcome = if (result.isSuccess) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILED,
                reason = result.exceptionOrNull()?.message,
                details = mapOf("события" to reasons),
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Синхронизация телефона",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Мгновенная отправка событий телефона в SprutHub"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PhoneMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val interval = runCatching { AppGraph.phone.state.value.syncSettings.pollInterval }
            .getOrDefault(PhonePollInterval.FIVE_MINUTES)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("SprutHub Helper работает постоянно")
            .setContentText("События сразу · контрольный опрос ${interval.title}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        val running = MutableStateFlow(false)

        private const val CHANNEL_ID = "spruthub_phone_monitor"
        private const val NOTIFICATION_ID = 2042
        private const val EVENT_DEBOUNCE_MS = 1_500L
        private const val EVENT_RETRY_MS = 10_000L
        private const val ACTION_STOP = "io.github.nikitau.spruthubhelper.phone.STOP"
        private const val ACTION_REFRESH = "io.github.nikitau.spruthubhelper.phone.REFRESH"
        private const val LOG_TAG = "SprutHubPhone"
        private val NETWORK_TRIGGERS = setOf(
            PhoneSyncTrigger.NETWORK_AVAILABLE,
            PhoneSyncTrigger.NETWORK_LOST,
            PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED,
            PhoneSyncTrigger.NETWORK_ADDRESS_CHANGED,
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, PhoneMonitorService::class.java)
            context.stopService(serviceIntent)
        }

        fun refresh(context: Context) {
            if (running.value) {
                context.startService(
                    Intent(context, PhoneMonitorService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }
    }
}
