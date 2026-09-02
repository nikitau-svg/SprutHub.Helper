package io.github.nikitau.spruthubhelper

import android.app.Application
import android.content.Context
import io.github.nikitau.spruthubhelper.data.CatalogCache
import io.github.nikitau.spruthubhelper.data.CatalogFreshnessPolicy
import io.github.nikitau.spruthubhelper.data.CatalogNetworkRecoveryMonitor
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.data.SprutRepository
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticChannel
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticEvent as StructuredDiagnosticEvent
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticJournal
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.health.HealthReader
import io.github.nikitau.spruthubhelper.health.HealthSyncManager
import io.github.nikitau.spruthubhelper.phone.PhoneReader
import io.github.nikitau.spruthubhelper.phone.PhoneSyncManager
import io.github.nikitau.spruthubhelper.presence.PresenceManager
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import io.github.nikitau.spruthubhelper.sprut.SprutHeartbeatScenarioManager
import io.github.nikitau.spruthubhelper.sprut.SprutRpcClient
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.sprut.VirtualDeviceProfile
import io.github.nikitau.spruthubhelper.sprut.VirtualPresenceDeviceManager
import io.github.nikitau.spruthubhelper.tiles.TileComponents
import io.github.nikitau.spruthubhelper.tiles.TileInstallStateStore
import io.github.nikitau.spruthubhelper.widget.SprutAppWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SprutHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
    }
}

object AppGraph {
    private var initialized = false

    lateinit var settings: SettingsRepository
        private set
    lateinit var repository: SprutRepository
        private set
    lateinit var health: HealthSyncManager
        private set
    lateinit var phone: PhoneSyncManager
        private set
    lateinit var presence: PresenceManager
        private set
    lateinit var applicationScope: CoroutineScope
        private set
    lateinit var diagnostics: DiagnosticJournal
        private set
    private lateinit var catalogNetworkRecovery: CatalogNetworkRecoveryMonitor

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        diagnostics = DiagnosticJournal.create(appContext, applicationScope)
        settings = SettingsRepository(appContext)
        val repositoryClient = SprutRpcClient(appContext)
        val healthClient = SprutRpcClient(appContext)
        val phoneClient = SprutRpcClient(appContext)
        val heartbeatClient = SprutRpcClient(appContext)
        val presenceClient = SprutRpcClient(appContext)
        repository = SprutRepository(
            settings = settings,
            client = repositoryClient,
            parser = SprutCatalogParser(),
            cache = CatalogCache(appContext),
            scope = applicationScope,
        )
        catalogNetworkRecovery = CatalogNetworkRecoveryMonitor(
            context = appContext,
            repository = repository,
            scope = applicationScope,
            onAttemptFinished = { attempt, result ->
                diagnostics.record(
                    category = DiagnosticCategory.NETWORK,
                    event = "Восстановление каталога после возврата сети",
                    outcome = if (result.isSuccess) {
                        DiagnosticOutcome.SUCCESS
                    } else {
                        DiagnosticOutcome.FAILED
                    },
                    reason = result.exceptionOrNull()?.let { "SprutHub пока недоступен" },
                    details = mapOf("попытка" to attempt.toString()),
                )
            },
        ).also(CatalogNetworkRecoveryMonitor::start)
        health = HealthSyncManager(
            context = appContext,
            settings = settings,
            reader = HealthReader(appContext),
            virtualDevice = VirtualHealthDeviceManager(settings, healthClient),
            scope = applicationScope,
        )
        phone = PhoneSyncManager(
            context = appContext,
            settings = settings,
            reader = PhoneReader(appContext),
            virtualDevice = VirtualHealthDeviceManager(
                settings = settings,
                client = phoneClient,
                profile = VirtualDeviceProfile.PHONE,
            ),
            heartbeatScenario = SprutHeartbeatScenarioManager(settings, heartbeatClient),
            scope = applicationScope,
        )
        presence = PresenceManager(
            context = appContext,
            settings = settings,
            virtualDevice = VirtualPresenceDeviceManager(settings, presenceClient),
            scope = applicationScope,
        )
        TileInstallStateStore.initialize(appContext)
        applicationScope.launch {
            repository.tileAssignments.collect { assignments ->
                TileComponents.syncEnabled(appContext, assignments)
            }
        }
        applicationScope.launch {
            combine(
                repository.catalog,
                repository.connectionStatus,
                repository.pendingControlIds,
                repository.servicePresentations,
            ) { catalog, connection, _, _ -> catalog to connection }
                .collectLatest { (catalog, connection) ->
                    delay(100)
                    SprutAppWidgetProvider.updateAll(appContext)

                    // A recently loaded disk cache becomes stale after 30
                    // seconds even if no other flow emits in the meantime.
                    if (
                        connection.phase == ConnectionPhase.IDLE &&
                        catalog.refreshedAtEpochMs > 0L
                    ) {
                        val untilStale = catalog.refreshedAtEpochMs +
                            CatalogFreshnessPolicy.DISPLAY_MAX_AGE_MS -
                            System.currentTimeMillis()
                        if (untilStale >= 0L) delay(untilStale + 100L)
                        if (
                            repository.connectionStatus.value.phase == ConnectionPhase.IDLE &&
                            repository.catalog.value.refreshedAtEpochMs == catalog.refreshedAtEpochMs
                        ) {
                            SprutAppWidgetProvider.updateAll(appContext)
                        }
                    }
                }
        }
        applicationScope.launch {
            var lastMirroredEvent: io.github.nikitau.spruthubhelper.data.DiagnosticEvent? = null
            repository.diagnostics.collect { legacyEvents ->
                val legacy = legacyEvents.firstOrNull() ?: return@collect
                if (legacy == lastMirroredEvent) return@collect
                lastMirroredEvent = legacy
                val isCommand = legacy.message.contains("плит", ignoreCase = true) ||
                    legacy.message.contains("панел", ignoreCase = true) ||
                    legacy.message.contains("команд", ignoreCase = true)
                val safeEventName = when {
                    legacy.message.contains("Каталог обновлён", ignoreCase = true) -> "Каталог SprutHub обновлён"
                    legacy.message.contains("кэш", ignoreCase = true) -> "Локальный кэш каталога загружен"
                    isCommand -> "Команда Android-интерфейса"
                    legacy.isError -> "Подключение к SprutHub не выполнено"
                    else -> "Состояние подключения обновлено"
                }
                val channel = when (repository.connectionStatus.value.phase) {
                    ConnectionPhase.CONNECTED_LOCAL -> DiagnosticChannel.LOCAL
                    ConnectionPhase.CONNECTED_CLOUD -> DiagnosticChannel.CLOUD
                    else -> DiagnosticChannel.NONE
                }
                diagnostics.record(
                    StructuredDiagnosticEvent(
                        epochMs = legacy.epochMs,
                        category = if (isCommand) DiagnosticCategory.COMMAND else DiagnosticCategory.CONNECTION,
                        event = safeEventName,
                        outcome = when {
                            legacy.isError -> DiagnosticOutcome.FAILED
                            isCommand || legacy.message.contains("обновлён", ignoreCase = true) -> DiagnosticOutcome.SUCCESS
                            else -> DiagnosticOutcome.STATE
                        },
                        channel = channel,
                        reason = legacy.message.takeIf { legacy.isError },
                    ),
                )
            }
        }
        diagnostics.record(
            category = DiagnosticCategory.APP,
            event = "Приложение запущено",
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("источник" to "Application.onCreate"),
        )
        initialized = true
    }
}
