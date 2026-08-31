package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresencePermissionState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupOverviewTest {
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
    fun `connection error is the only mandatory attention item`() {
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

        assertEquals(SetupTone.ATTENTION, overview.first().tone)
        assertEquals("Нужно исправить", overview.first().status)
        assertEquals(1, overview.count { it.tone == SetupTone.ATTENTION })
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
}
