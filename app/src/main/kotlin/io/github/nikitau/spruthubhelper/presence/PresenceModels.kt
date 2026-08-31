package io.github.nikitau.spruthubhelper.presence

import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
data class PresenceZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 150.0,
    val roomId: String,
    val publishDistance: Boolean = false,
    val enabled: Boolean = true,
    val binding: HealthDeviceBinding? = null,
    val isInside: Boolean? = null,
    val lastDistanceMeters: Double? = null,
    val lastUpdatedEpochMs: Long? = null,
) {
    init {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        require(radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS)
    }

    companion object {
        const val MIN_RADIUS_METERS = 100.0
        const val MAX_RADIUS_METERS = 10_000.0

        fun create(
            name: String,
            latitude: Double,
            longitude: Double,
            radiusMeters: Double,
            roomId: String,
            publishDistance: Boolean,
        ) = PresenceZone(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS),
            roomId = roomId,
            publishDistance = publishDistance,
        )
    }
}

data class PresencePermissionState(
    val foregroundGranted: Boolean = false,
    val preciseGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
)

data class PresenceUiState(
    val zones: List<PresenceZone> = emptyList(),
    val permissions: PresencePermissionState = PresencePermissionState(),
    val busy: Boolean = false,
    val geofencesRegistered: Boolean = false,
    val duplicateZoneNames: Set<String> = emptySet(),
    val message: String = "Добавьте первую зону",
)

internal const val PRESENCE_KEY = "PRESENCE"
internal const val DISTANCE_KEY = "DISTANCE"

internal fun presenceZoneNameKey(name: String): String =
    name.trim().lowercase().filterNot(Char::isWhitespace)

internal fun samePresenceZoneName(first: String, second: String): Boolean =
    presenceZoneNameKey(first) == presenceZoneNameKey(second)

internal fun duplicatePresenceZoneNames(zones: List<PresenceZone>): Set<String> = zones
    .groupBy { zone -> presenceZoneNameKey(zone.name) }
    .values
    .filter { sameName -> sameName.size > 1 }
    .mapTo(linkedSetOf()) { sameName -> sameName.first().name }

internal fun samePresenceZoneDefinition(
    zone: PresenceZone,
    name: String,
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    roomId: String,
    publishDistance: Boolean,
): Boolean = samePresenceZoneName(zone.name, name) &&
    abs(zone.latitude - latitude) < 0.000001 &&
    abs(zone.longitude - longitude) < 0.000001 &&
    abs(zone.radiusMeters - radiusMeters) < 0.5 &&
    zone.roomId == roomId &&
    zone.publishDistance == publishDistance
