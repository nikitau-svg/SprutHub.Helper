package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.PhoneSyncSettings
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresencePermissionState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupGuidanceTest {
    private val binding = HealthDeviceBinding(
        accessoryId = "accessory",
        name = "Virtual device",
        roomId = "room",
        targets = emptyList(),
    )

    @Test
    fun `connection error points to save and test`() {
        val guidance = connectionGuidance(
            MainUiState(connection = ConnectionStatus(phase = ConnectionPhase.ERROR)),
        )

        assertEquals(GuidanceAction.SAVE_AND_TEST_CONNECTION, guidance.action)
        assertEquals(SetupTone.ATTENTION, guidance.tone)
    }

    @Test
    fun `edited connection is tested even while old connection remains active`() {
        val guidance = connectionGuidance(
            ui = MainUiState(connection = ConnectionStatus(phase = ConnectionPhase.CONNECTED_LOCAL)),
            hasUnsavedChanges = true,
        )

        assertEquals(GuidanceAction.SAVE_AND_TEST_CONNECTION, guidance.action)
        assertEquals(SetupTone.ATTENTION, guidance.tone)
    }

    @Test
    fun `health permissions are requested before virtual device creation`() {
        val missingPermission = healthGuidance(
            HealthUiState(
                available = true,
                allSelectedPermissionsGranted = false,
                binding = null,
            ),
        )
        val readyToCreate = healthGuidance(
            HealthUiState(
                available = true,
                allSelectedPermissionsGranted = true,
                backgroundReadAvailable = false,
                binding = null,
            ),
        )

        assertEquals(GuidanceAction.REQUEST_HEALTH_PERMISSIONS, missingPermission.action)
        assertEquals(GuidanceAction.CREATE_HEALTH_DEVICE, readyToCreate.action)
    }

    @Test
    fun `changed health composition takes priority over normal sync`() {
        val guidance = healthGuidance(
            health = HealthUiState(
                available = true,
                allSelectedPermissionsGranted = true,
                backgroundReadAvailable = false,
                binding = binding,
                enabled = true,
            ),
            configurationMatches = false,
        )

        assertEquals(GuidanceAction.RECREATE_HEALTH_DEVICE, guidance.action)
    }

    @Test
    fun `phone reliability requests notifications before reporting ready`() {
        val guidance = phoneGuidance(
            PhoneUiState(
                binding = binding,
                syncSettings = PhoneSyncSettings(
                    enabled = true,
                    mode = PhoneSyncMode.LIVE,
                    watchdogEnabled = true,
                ),
                notificationPermissionGranted = false,
                batteryOptimizationIgnored = true,
                monitorRunning = false,
            ),
        )

        assertEquals(GuidanceAction.REQUEST_PHONE_LIVE_MODE, guidance.action)
        assertEquals(SetupTone.ATTENTION, guidance.tone)
    }

    @Test
    fun `presence asks for background access before zone creation`() {
        val guidance = presenceGuidance(
            PresenceUiState(
                permissions = PresencePermissionState(
                    foregroundGranted = true,
                    preciseGranted = true,
                    backgroundGranted = false,
                ),
            ),
        )

        assertEquals(GuidanceAction.OPEN_BACKGROUND_LOCATION_SETTINGS, guidance.action)
    }

    @Test
    fun `registered presence zones offer a manual refresh`() {
        val zone = PresenceZone.create(
            name = "Дом",
            latitude = 55.75,
            longitude = 37.61,
            radiusMeters = 150.0,
            roomId = "room",
            publishDistance = true,
        )
        val guidance = presenceGuidance(
            PresenceUiState(
                zones = listOf(zone),
                permissions = PresencePermissionState(
                    foregroundGranted = true,
                    preciseGranted = true,
                    backgroundGranted = true,
                ),
                geofencesRegistered = true,
            ),
        )

        assertEquals(GuidanceAction.SYNC_PRESENCE, guidance.action)
        assertEquals(SetupTone.READY, guidance.tone)
    }

    @Test
    fun `disabled presence zones are paused instead of broken`() {
        val zone = PresenceZone.create(
            name = "Работа",
            latitude = 55.75,
            longitude = 37.61,
            radiusMeters = 150.0,
            roomId = "room",
            publishDistance = false,
        ).copy(enabled = false)
        val guidance = presenceGuidance(
            PresenceUiState(
                zones = listOf(zone),
                permissions = PresencePermissionState(
                    foregroundGranted = true,
                    preciseGranted = true,
                    backgroundGranted = true,
                ),
                geofencesRegistered = false,
            ),
        )

        assertEquals(null, guidance.action)
        assertEquals(SetupTone.OPTIONAL, guidance.tone)
    }
}
