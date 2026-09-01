package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlUpdateMergeTest {
    @Test
    fun toggleEventDoesNotReplaceIndependentTemperatureRange() {
        val control = SprutControl(
            id = "11:13:main",
            accessoryId = "11",
            serviceId = "13",
            characteristicId = "18",
            rangeCharacteristicId = "19",
            title = "Кондиционер",
            behavior = ControlBehavior.TOGGLE_RANGE,
            value = SprutValue(boolValue = true, numberValue = 22.0),
        )

        val updated = mergeControlUpdate(control, "18", SprutValue(numberValue = 1.0))

        assertEquals(true, updated?.value?.boolValue)
        assertEquals(22.0, updated?.value?.numberValue!!, 0.0)
    }

    @Test
    fun rangeEventUpdatesTemperatureWithoutChangingPower() {
        val control = SprutControl(
            id = "11:13:main",
            accessoryId = "11",
            serviceId = "13",
            characteristicId = "18",
            rangeCharacteristicId = "19",
            title = "Кондиционер",
            behavior = ControlBehavior.TOGGLE_RANGE,
            value = SprutValue(boolValue = true, numberValue = 22.0),
        )

        val updated = mergeControlUpdate(control, "19", SprutValue(numberValue = 23.0))

        assertEquals(true, updated?.value?.boolValue)
        assertEquals(23.0, updated?.value?.numberValue!!, 0.0)
    }

    @Test
    fun eventArrivingDuringFullRefreshWinsOverOlderSnapshot() {
        val parsed = SprutControl(
            id = "11:13:main",
            accessoryId = "11",
            serviceId = "13",
            characteristicId = "18",
            title = "Свет",
            value = SprutValue(boolValue = false),
        )
        val live = parsed.copy(value = SprutValue(boolValue = true))

        val merged = preserveConcurrentControlValues(
            parsedControls = listOf(parsed),
            currentControls = listOf(live),
            authoritativeAtStart = mapOf(parsed.id to 7L),
            currentVersions = mapOf(parsed.id to 8L),
        )

        assertEquals(true, merged.single().value.boolValue)
    }

    @Test
    fun unchangedControlUsesFreshFullSnapshot() {
        val parsed = SprutControl(
            id = "11:13:main",
            accessoryId = "11",
            serviceId = "13",
            characteristicId = "18",
            title = "Свет",
            value = SprutValue(boolValue = false),
        )
        val oldCache = parsed.copy(value = SprutValue(boolValue = true))

        val merged = preserveConcurrentControlValues(
            parsedControls = listOf(parsed),
            currentControls = listOf(oldCache),
            authoritativeAtStart = mapOf(parsed.id to 7L),
            currentVersions = mapOf(parsed.id to 7L),
        )

        assertEquals(false, merged.single().value.boolValue)
    }
}
