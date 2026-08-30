package io.github.nikitau.spruthubhelper

import android.app.Application
import android.content.Context
import io.github.nikitau.spruthubhelper.data.CatalogCache
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.data.SprutRepository
import io.github.nikitau.spruthubhelper.health.HealthReader
import io.github.nikitau.spruthubhelper.health.HealthSyncManager
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import io.github.nikitau.spruthubhelper.sprut.SprutRpcClient
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.tiles.TileComponents
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
    lateinit var applicationScope: CoroutineScope
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(appContext)
        val client = SprutRpcClient()
        repository = SprutRepository(
            settings = settings,
            client = client,
            parser = SprutCatalogParser(),
            cache = CatalogCache(appContext),
            scope = applicationScope,
        )
        health = HealthSyncManager(
            context = appContext,
            settings = settings,
            reader = HealthReader(appContext),
            virtualDevice = VirtualHealthDeviceManager(settings, client),
            scope = applicationScope,
        )
        applicationScope.launch {
            repository.tileAssignments.collect { assignments ->
                TileComponents.syncEnabled(appContext, assignments)
            }
        }
        initialized = true
    }
}
