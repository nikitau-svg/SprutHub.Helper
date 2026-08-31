package io.github.nikitau.spruthubhelper.health

import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthStatusMessageTest {
    private val binding = HealthDeviceBinding(
        accessoryId = "accessory",
        name = "Здоровье",
        roomId = "room",
        targets = emptyList(),
    )

    @Test
    fun `persisted binding replaces stale default message after process restart`() {
        assertEquals(
            "Устройство здоровья настроено",
            resolveHealthMessage(binding, "Не настроено"),
        )
    }

    @Test
    fun `meaningful runtime message is preserved`() {
        assertEquals(
            "Здоровье синхронизировано локально",
            resolveHealthMessage(binding, "Здоровье синхронизировано локально"),
        )
    }

    @Test
    fun `unconfigured health keeps default message`() {
        assertEquals("Не настроено", resolveHealthMessage(null, "Не настроено"))
    }
}
