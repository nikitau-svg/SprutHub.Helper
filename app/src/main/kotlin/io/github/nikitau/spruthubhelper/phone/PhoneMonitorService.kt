package io.github.nikitau.spruthubhelper.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PhoneMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null
    private var pollingJob: Job? = null
    private var receiverRegistered = false
    private var networkCallbackRegistered = false

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            triggerSync("broadcast:${intent?.action.orEmpty().substringAfterLast('.')}")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = triggerSync("network-available")
        override fun onLost(network: Network) = triggerSync("network-lost")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            triggerSync("network-capabilities")

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            triggerSync("network-address")
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(applicationContext)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        registerEvents()
        observePollingInterval()
        running.value = true
        triggerSync("monitor-started")
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
                triggerSync("settings-changed")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.value = false
        eventJob?.cancel()
        pollingJob?.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(eventReceiver) }
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
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
        }
        ContextCompat.registerReceiver(
            this,
            eventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { error -> Log.w(LOG_TAG, "Network callback is unavailable", error) }
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
                        AppGraph.phone.syncNow(fromBackground = true)
                    }
                }
        }
    }

    private fun triggerSync(reason: String) {
        eventJob?.cancel()
        eventJob = scope.launch {
            delay(EVENT_DEBOUNCE_MS)
            Log.d(LOG_TAG, "Phone event sync: $reason")
            val first = AppGraph.phone.syncNow(fromBackground = true)
            if (first.isFailure) {
                delay(EVENT_RETRY_MS)
                Log.d(LOG_TAG, "Phone event retry: $reason")
                AppGraph.phone.syncNow(fromBackground = true)
            }
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneMonitorService::class.java))
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
