package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.PhoneSyncSettings
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresencePermissionState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupOverviewTest {
    private val binding = HealthDeviceBinding(
        accessoryId = "fixture-accessory",
        name = "Fixture",
        roomId = "fixture-room",
        targets = emptyList(),
    )

    private val connectedUi = MainUiState(
        connection = ConnectionStatus(
            phase = ConnectionPhase.CONNECTED_LOCAL,
            message = "Подключено",
        ),
        catalog = SprutCatalog(
            controls = listOf(
                SprutControl(
                    id = "1:2:3",
                    accessoryId = "1",
                    serviceId = "2",
                    characteristicId = "3",
                    title = "Fixture",
                ),
            ),
        ),
    )

    @Test
    fun `connected hub is ready and unused optional sources do not look broken`() {
        val overview = buildSetupOverview(
            ui = MainUiState(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.CONNECTED_CLOUD,
                    message = "Подключено",
                ),
            ),
            health = HealthUiState(available = true),
            phone = PhoneUiState(),
            presence = PresenceUiState(),
        ).associateBy(SetupOverviewItem::section)

        assertEquals(SetupTone.READY, overview.getValue(SettingsSection.CONNECTION).tone)
        assertEquals(SetupTone.OPTIONAL, overview.getValue(SettingsSection.HEALTH).tone)
        assertEquals(SetupTone.OPTIONAL, overview.getValue(SettingsSection.PHONE).tone)
        assertEquals(SetupTone.OPTIONAL, overview.getValue(SettingsSection.PRESENCE).tone)
    }

    @Test
    fun `connection error is the only mandatory error item`() {
        val overview = buildSetupOverview(
            ui = MainUiState(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.ERROR,
                    message = "Не удалось войти",
                ),
            ),
            health = HealthUiState(),
            phone = PhoneUiState(),
            presence = PresenceUiState(),
        )

        assertEquals(SetupTone.ERROR, overview.first().tone)
        assertEquals("Нужно исправить", overview.first().status)
        assertEquals(1, overview.count { it.tone == SetupTone.ERROR })
    }

    @Test
    fun `registered zone with full permissions is ready`() {
        val zone = PresenceZone.create(
            name = "Дом",
            latitude = 55.75,
            longitude = 37.61,
            radiusMeters = 150.0,
            roomId = "room-1",
            publishDistance = true,
        )
        val overview = buildSetupOverview(
            ui = MainUiState(),
            health = HealthUiState(),
            phone = PhoneUiState(),
            presence = PresenceUiState(
                zones = listOf(zone),
                permissions = PresencePermissionState(
                    foregroundGranted = true,
                    preciseGranted = true,
                    backgroundGranted = true,
                ),
                geofencesRegistered = true,
            ),
        ).associateBy(SetupOverviewItem::section)

        assertEquals(SetupTone.READY, overview.getValue(SettingsSection.PRESENCE).tone)
        assertEquals("Активных зон: 1", overview.getValue(SettingsSection.PRESENCE).detail)
    }

    @Test
    fun `home asks only for connection before optional sources`() {
        val readiness = buildHomeReadiness(
            ui = MainUiState(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.ERROR,
                    message = "Не удалось войти",
                ),
            ),
            health = HealthUiState(binding = binding),
            phone = PhoneUiState(binding = binding),
            presence = PresenceUiState(),
        )

        assertEquals(SettingsSection.CONNECTION, readiness.targetSection)
        assertEquals(SetupTone.ERROR, readiness.tone)
    }

    @Test
    fun `unused optional sources leave home ready`() {
        val readiness = buildHomeReadiness(
            ui = connectedUi,
            health = HealthUiState(available = true),
            phone = PhoneUiState(),
            presence = PresenceUiState(),
        )

        assertEquals("Всё работает", readiness.status)
        assertEquals(null, readiness.targetSection)
    }

    @Test
    fun `configured phone problem becomes the single next step`() {
        val readiness = buildHomeReadiness(
            ui = connectedUi,
            health = HealthUiState(available = true),
            phone = PhoneUiState(
                binding = binding,
                syncSettings = PhoneSyncSettings(enabled = true, mode = PhoneSyncMode.LIVE),
                notificationPermissionGranted = false,
                batteryOptimizationIgnored = true,
            ),
            presence = PresenceUiState(),
        )

        assertEquals(SettingsSection.PHONE, readiness.targetSection)
        assertEquals("Разрешите уведомления", readiness.title)
    }

    @Test
    fun `paused phone does not create a mandatory home action`() {
        val readiness = buildHomeReadiness(
            ui = connectedUi,
            health = HealthUiState(available = true),
            phone = PhoneUiState(
                binding = binding,
                syncSettings = PhoneSyncSettings(enabled = false),
            ),
            presence = PresenceUiState(),
        )

        assertEquals("Всё работает", readiness.status)
        assertEquals(null, readiness.targetSection)
    }

    @Test
    fun `configured health composition problem is not hidden`() {
        val readiness = buildHomeReadiness(
            ui = connectedUi,
            health = HealthUiState(
                available = true,
                allSelectedPermissionsGranted = true,
                binding = binding,
                configurationMatches = false,
            ),
            phone = PhoneUiState(),
            presence = PresenceUiState(),
        )

        assertEquals(SettingsSection.HEALTH, readiness.targetSection)
        assertEquals("Состав показателей изменён", readiness.title)
    }

    @Test
    fun `connected hub without a confirmed catalog points back to connection`() {
        val readiness = buildHomeReadiness(
            ui = connectedUi.copy(catalog = SprutCatalog()),
            health = HealthUiState(),
            phone = PhoneUiState(),
            presence = PresenceUiState(),
        )

        assertEquals(SettingsSection.CONNECTION, readiness.targetSection)
        assertEquals("Перечитайте устройства", readiness.title)
    }
}
