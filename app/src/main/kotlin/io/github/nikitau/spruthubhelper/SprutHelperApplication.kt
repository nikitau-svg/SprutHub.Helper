package io.github.nikitau.spruthubhelper

import android.app.Application
import android.content.Context
import io.github.nikitau.spruthubhelper.data.CatalogCache
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.data.SprutRepository
import io.github.nikitau.spruthubhelper.health.HealthReader
import io.github.nikitau.spruthubhelper.health.HealthSyncManager
import io.github.nikitau.spruthubhelper.phone.PhoneReader
import io.github.nikitau.spruthubhelper.phone.PhoneSyncManager
import io.github.nikitau.spruthubhelper.presence.PresenceManager
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import io.github.nikitau.spruthubhelper.sprut.SprutRpcClient
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.sprut.VirtualDeviceProfile
import io.github.nikitau.spruthubhelper.sprut.VirtualPresenceDeviceManager
import io.github.nikitau.spruthubhelper.tiles.TileComponents
import io.github.nikitau.spruthubhelper.tiles.TileInstallStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
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

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(appContext)
        val repositoryClient = SprutRpcClient(appContext)
        val healthClient = SprutRpcClient(appContext)
        val phoneClient = SprutRpcClient(appContext)
        val presenceClient = SprutRpcClient(appContext)
        repository = SprutRepository(
            settings = settings,
            client = repositoryClient,
            parser = SprutCatalogParser(),
            cache = CatalogCache(appContext),
            scope = applicationScope,
        )
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
        initialized = true
    }
}
