package io.github.nikitau.spruthubhelper.presence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.sprut.VirtualPresenceDeviceManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresenceManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val virtualDevice: VirtualPresenceDeviceManager,
    private val scope: CoroutineScope,
) {
    private val geofencing = LocationServices.getGeofencingClient(context)
    private val locations = LocationServices.getFusedLocationProviderClient(context)
    private val runtime = MutableStateFlow(PresenceRuntime())
    private val mutationMutex = Mutex()

    val state: StateFlow<PresenceUiState> = combine(settings.presenceZones, runtime) { zones, live ->
        PresenceUiState(
            zones = zones,
            permissions = permissionState(),
            busy = live.busy,
            geofencesRegistered = live.registered,
            message = live.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PresenceUiState())

    init {
        scope.launchSafely {
            val zones = settings.presenceZones.first()
            updateSchedule(zones)
            if (permissionState().preciseGranted) registerGeofences(zones)
        }
    }

    suspend fun currentCoordinates(): Result<Pair<Double, Double>> = runCatching {
        check(permissionState().preciseGranted) { "Разрешите точную геопозицию" }
        val location = currentLocation() ?: error("Android пока не смог определить геопозицию")
        location.latitude to location.longitude
    }

    suspend fun addZone(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        roomId: String,
        publishDistance: Boolean,
    ): Result<PresenceZone> = mutationMutex.withLock {
        runCatching {
            check(name.isNotBlank()) { "Введите название зоны" }
            check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
            check(permissionState().preciseGranted) { "Разрешите точную геопозицию" }
            runtime.update { it.copy(busy = true, message = "Создаю зону…") }
            val location = currentLocation()
                ?: error("Android пока не смог определить геопозицию. Включите геолокацию и повторите.")
            var zone = PresenceZone.create(
                name = name,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                roomId = roomId,
                publishDistance = publishDistance,
            )
            settings.upsertPresenceZone(zone)
            registerGeofences(settings.presenceZones.first())

            val distance = distanceMeters(zone, location)
            val inside = distance <= zone.radiusMeters
            val binding = virtualDevice.createOrRecover(zone)
            binding.let {
                zone = zone.copy(binding = it)
                settings.upsertPresenceZone(zone)
            }
            val verified = virtualDevice.publish(zone, inside, distance)
            zone = zone.copy(
                binding = verified,
                isInside = inside,
                lastDistanceMeters = distance,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            )
            settings.upsertPresenceZone(zone)
            updateSchedule(settings.presenceZones.first())
            runtime.update {
                it.copy(busy = false, message = "Зона «${zone.name}» создана и синхронизирована")
            }
            zone
        }.onFailure { error ->
            runtime.update { it.copy(busy = false, message = error.message ?: "Не удалось создать зону") }
        }
    }

    suspend fun setEnabled(zoneId: String, enabled: Boolean): Result<Unit> = mutationMutex.withLock {
        runCatching {
            val zone = settings.presenceZones.first().firstOrNull { it.id == zoneId }
                ?: error("Зона больше не найдена")
            settings.upsertPresenceZone(zone.copy(enabled = enabled))
            val zones = settings.presenceZones.first()
            registerGeofences(zones)
            updateSchedule(zones)
            runtime.update {
                it.copy(message = if (enabled) "Зона «${zone.name}» включена" else "Зона «${zone.name}» выключена")
            }
        }.onFailure { error -> runtime.update { it.copy(message = error.message ?: "Не удалось изменить зону") } }
    }

    suspend fun removeZone(zoneId: String): Result<Unit> = mutationMutex.withLock {
        runCatching {
            val zone = settings.presenceZones.first().firstOrNull { it.id == zoneId }
                ?: return@runCatching
            runtime.update { it.copy(busy = true, message = "Удаляю зону «${zone.name}»…") }
            virtualDevice.delete(zone)
            settings.removePresenceZone(zoneId)
            val zones = settings.presenceZones.first()
            registerGeofences(zones)
            updateSchedule(zones)
            runtime.update { it.copy(busy = false, message = "Зона «${zone.name}» удалена") }
        }.onFailure { error ->
            runtime.update { it.copy(busy = false, message = error.message ?: "Не удалось удалить зону") }
        }
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> = mutationMutex.withLock {
        runCatching {
            val enabled = settings.presenceZones.first().filter(PresenceZone::enabled)
            if (enabled.isEmpty()) return@runCatching
            check(permissionState().preciseGranted) { "Нет разрешения на точную геопозицию" }
            val location = currentLocation(allowCached = true)
                ?: error("Android пока не вернул геопозицию")
            if (!fromBackground) runtime.update { it.copy(busy = true, message = "Обновляю зоны…") }
            enabled.forEach { zone -> publishAtLocation(zone, location, insideOverride = null) }
            runtime.update { it.copy(busy = false, message = "Зоны синхронизированы") }
        }.onFailure { error ->
            runtime.update { it.copy(busy = false, message = error.message ?: "Ошибка синхронизации зон") }
        }
    }

    suspend fun handleTransition(
        zoneIds: Set<String>,
        entered: Boolean,
        triggeringLocation: Location?,
    ) = mutationMutex.withLock {
        val zones = settings.presenceZones.first().filter { it.id in zoneIds && it.enabled }
        zones.forEach { zone ->
            val location = triggeringLocation ?: runCatching {
                currentLocation(allowCached = true)
            }.getOrNull()
            val distance = location?.let { distanceMeters(zone, it) }
            val eventState = zone.copy(
                isInside = entered,
                lastDistanceMeters = distance ?: zone.lastDistanceMeters,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            )
            // Persist the transition before any network operation. If Android
            // kills the process or SprutHub is temporarily unreachable, the
            // user's actual enter/exit event is not lost locally.
            settings.upsertPresenceZone(eventState)
            runCatching {
                publishAtLocation(eventState, location, insideOverride = entered)
            }.onFailure { error ->
                runtime.update { it.copy(message = error.message ?: "Не удалось отправить событие зоны") }
                enqueueImmediateSync()
            }
        }
    }

    suspend fun refreshRegistrations(): Result<Unit> = runCatching {
        val zones = settings.presenceZones.first()
        updateSchedule(zones)
        registerGeofences(zones)
    }.onFailure { error ->
        runtime.update { it.copy(registered = false, message = error.message ?: "Не удалось включить геозоны") }
    }

    fun refreshPermissionState() {
        runtime.update { it.copy(permissionRevision = it.permissionRevision + 1) }
        scope.launchSafely { refreshRegistrations() }
    }

    fun reportError(message: String) {
        runtime.update { it.copy(message = message) }
    }

    private suspend fun publishAtLocation(
        initial: PresenceZone,
        location: Location?,
        insideOverride: Boolean?,
    ) {
        val distance = location?.let { distanceMeters(initial, it) }
        val inside = insideOverride ?: distance?.let { it <= initial.radiusMeters }
            ?: initial.isInside
            ?: false
        var zone = initial
        val binding = zone.binding ?: virtualDevice.createOrRecover(zone)
        if (binding != zone.binding) {
            zone = zone.copy(binding = binding)
            settings.upsertPresenceZone(zone)
        }
        val verified = virtualDevice.publish(zone, inside, distance)
        settings.upsertPresenceZone(
            zone.copy(
                binding = verified,
                isInside = inside,
                lastDistanceMeters = distance ?: zone.lastDistanceMeters,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun registerGeofences(zones: List<PresenceZone>) {
        if (!permissionState().preciseGranted) {
            runtime.update { it.copy(registered = false, message = "Разрешите точную геопозицию") }
            return
        }
        val pendingIntent = geofencePendingIntent()
        runCatching { geofencing.removeGeofences(pendingIntent).await() }
        val enabled = zones.filter(PresenceZone::enabled)
        if (enabled.isEmpty()) {
            runtime.update { it.copy(registered = false) }
            return
        }
        val geofences = enabled.map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters.toFloat())
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT,
            )
            .addGeofences(geofences)
            .build()
        geofencing.addGeofences(request, pendingIntent).await()
        runtime.update {
            it.copy(
                registered = true,
                message = if (permissionState().backgroundGranted) {
                    "Геозоны активны в фоне"
                } else {
                    "Геозоны добавлены; разрешите доступ к геопозиции «Всегда»"
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(allowCached: Boolean = false): Location? {
        check(permissionState().preciseGranted) { "Нет разрешения на точную геопозицию" }
        if (allowCached) {
            locations.lastLocation.await()?.takeIf { location ->
                System.currentTimeMillis() - location.time <= MAX_CACHED_LOCATION_AGE_MS
            }?.let { return it }
        }
        return locations.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token,
        ).await() ?: locations.lastLocation.await()
    }

    private fun permissionState(): PresencePermissionState {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return PresencePermissionState(
            foregroundGranted = fine || coarse,
            preciseGranted = fine,
            backgroundGranted = background,
        )
    }

    private fun distanceMeters(zone: PresenceZone, location: Location): Double {
        val result = FloatArray(1)
        Location.distanceBetween(
            zone.latitude,
            zone.longitude,
            location.latitude,
            location.longitude,
            result,
        )
        return result[0].toDouble().coerceAtLeast(0.0)
    }

    private fun geofencePendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            GEOFENCE_REQUEST_CODE,
            Intent(context, GeofenceBroadcastReceiver::class.java).setAction(ACTION_GEOFENCE),
            flags,
        )
    }

    private fun updateSchedule(zones: List<PresenceZone>) {
        val work = WorkManager.getInstance(context)
        if (zones.none(PresenceZone::enabled)) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<PresenceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<PresenceSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit) {
        launch {
            runCatching { block() }.onFailure { error ->
                runtime.update { it.copy(message = error.message ?: "Ошибка геозон") }
            }
        }
    }

    private data class PresenceRuntime(
        val busy: Boolean = false,
        val registered: Boolean = false,
        val permissionRevision: Long = 0,
        val message: String = "Добавьте первую зону",
    )

    private companion object {
        const val WORK_NAME = "spruthub_presence_sync"
        const val IMMEDIATE_WORK_NAME = "spruthub_presence_immediate_sync"
        const val ACTION_GEOFENCE = "io.github.nikitau.spruthubhelper.presence.GEOFENCE"
        const val GEOFENCE_REQUEST_CODE = 4301
        const val MAX_CACHED_LOCATION_AGE_MS = 30 * 60_000L
    }
}
