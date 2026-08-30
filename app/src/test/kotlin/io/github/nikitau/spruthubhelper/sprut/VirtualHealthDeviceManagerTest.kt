package io.github.nikitau.spruthubhelper.sprut

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
}
