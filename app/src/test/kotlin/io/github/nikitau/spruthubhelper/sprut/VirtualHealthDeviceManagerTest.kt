package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HealthValueKind
import io.github.nikitau.spruthubhelper.data.HelperDeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun detectsChangedVirtualDeviceValueType() {
        val binding = HealthDeviceBinding(
            accessoryId = "10",
            name = "Телефон",
            roomId = "1",
            targets = listOf(HealthTarget("BATTERY", "1", "1", "stringValue")),
        )

        assertFalse(
            bindingMatchesFields(
                binding,
                listOf(VirtualFieldSpec("BATTERY", "Заряд", HealthValueKind.DOUBLE)),
            ),
        )
    }

    @Test
    fun selectsOneExactSchemaInsteadOfAnOldSuperset() {
        val selected = selectVirtualAccessoryId(
            candidates = listOf(
                VirtualAccessoryCandidate("old", setOf("battery", "model", "legacy")),
                VirtualAccessoryCandidate("current", setOf("battery", "model")),
            ),
            expectedFieldTitles = setOf("battery", "model"),
        )

        assertEquals("current", selected)
    }

    @Test
    fun refusesToGuessBetweenDuplicateExactSchemas() {
        val error = assertThrows(VirtualDeviceConflictException::class.java) {
            selectVirtualAccessoryId(
                candidates = listOf(
                    VirtualAccessoryCandidate("11", setOf("battery", "model")),
                    VirtualAccessoryCandidate("19", setOf("battery", "model")),
                ),
                expectedFieldTitles = setOf("battery", "model"),
            )
        }

        assertTrue(error.message.orEmpty().contains("11"))
        assertTrue(error.message.orEmpty().contains("19"))
    }

    @Test
    fun newInstallDoesNotRecoverAnotherPhonesLegacyName() {
        val names = virtualDeviceNames(
            prefix = "Телефон",
            manufacturer = "samsung",
            model = "SM-F971B",
            identity = HelperDeviceIdentity(shortId = "A1B2C3", legacyRecoveryAllowed = false),
        )

        assertEquals("Телефон · Samsung SM-F971B · A1B2C3", names.preferred)
        assertEquals(listOf(names.preferred), names.recoveryNames)
    }

    @Test
    fun upgradedInstallCanRecoverOneLegacyNameWithoutRenamingIt() {
        val names = virtualDeviceNames(
            prefix = "Здоровье",
            manufacturer = "samsung",
            model = "SM-F971B",
            identity = HelperDeviceIdentity(shortId = "D4E5F6", legacyRecoveryAllowed = true),
        )

        assertEquals(
            listOf("Здоровье · Samsung SM-F971B · D4E5F6", "Здоровье · Samsung SM-F971B"),
            names.recoveryNames,
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
