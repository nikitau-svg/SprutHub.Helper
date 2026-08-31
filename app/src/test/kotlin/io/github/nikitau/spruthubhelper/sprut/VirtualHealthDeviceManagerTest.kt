package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HealthValueKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualHealthDeviceManagerTest {
    @Test
    fun matchesLabelsAfterSprutHubRemovesFormattingSeparators() {
        assertTrue(
            sameSprutLabel(
                "Здоровье · Samsung SM-F971B",
                "Здоровье Samsung SM-F971B",
            ),
        )
        assertTrue(sameSprutLabel("Телефон · Заряд", "Телефон Заряд"))
        assertFalse(sameSprutLabel("Пульс", "Пульс в покое"))
    }

    @Test
    fun recognizesStringCharacteristicWithoutCurrentValue() {
        assertTrue(healthTypeDescriptorMatches("C_GenericString", HealthValueKind.STRING))
        assertTrue(healthTypeDescriptorMatches("format=String", HealthValueKind.STRING))
        assertFalse(healthTypeDescriptorMatches("C_GenericDouble", HealthValueKind.STRING))
    }

    @Test
    fun recognizesNestedNameCharacteristicType() {
        assertTrue(isSprutNameTypeIdentifier("C_Name"))
        assertTrue(isSprutNameTypeIdentifier("characteristic.name"))
        assertFalse(isSprutNameTypeIdentifier("C_GenericString"))
    }

    @Test
    fun detectsChangedVirtualDeviceSchemaByFieldKeys() {
        val binding = HealthDeviceBinding(
            accessoryId = "10",
            name = "Телефон",
            roomId = "1",
            targets = listOf(
                HealthTarget("BATTERY", "1", "1", "doubleValue"),
                HealthTarget("MODEL", "2", "2", "stringValue"),
            ),
        )

        assertTrue(
            bindingMatchesFields(
                binding,
                listOf(
                    VirtualFieldSpec("MODEL", "Модель", HealthValueKind.STRING),
                    VirtualFieldSpec("BATTERY", "Заряд", HealthValueKind.DOUBLE),
                ),
            ),
        )
        assertFalse(
            bindingMatchesFields(
                binding,
                listOf(VirtualFieldSpec("BATTERY", "Заряд", HealthValueKind.DOUBLE)),
            ),
        )
    }

    @Test
    fun reportsDuplicatesWithoutSelectingObjectsForDeletion() {
        val inspection = VirtualDeviceInspection(listOf("11", "14", "19"))

        assertTrue(inspection.exists)
        assertTrue(inspection.duplicateCount == 2)
        assertTrue(inspection.matchingAccessoryIds == listOf("11", "14", "19"))
    }
}
