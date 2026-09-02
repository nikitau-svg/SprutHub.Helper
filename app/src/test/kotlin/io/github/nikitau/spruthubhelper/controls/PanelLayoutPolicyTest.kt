package io.github.nikitau.spruthubhelper.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelLayoutPolicyTest {
    @Test
    fun keepsCompactGridAtNormalFontScale() {
        assertEquals(2, PanelLayoutPolicy.columnCount(maxWidthDp = 360f, fontScale = 1f))
        assertEquals(3, PanelLayoutPolicy.columnCount(maxWidthDp = 540f, fontScale = 1f))
        assertEquals(4, PanelLayoutPolicy.columnCount(maxWidthDp = 760f, fontScale = 1f))
    }

    @Test
    fun givesLargeFontsMoreHorizontalSpace() {
        assertEquals(1, PanelLayoutPolicy.columnCount(maxWidthDp = 360f, fontScale = 1.3f))
        assertEquals(2, PanelLayoutPolicy.columnCount(maxWidthDp = 760f, fontScale = 1.5f))
    }

    @Test
    fun alwaysReturnsAtLeastOneAndAtMostFourColumns() {
        assertEquals(1, PanelLayoutPolicy.columnCount(maxWidthDp = 0f, fontScale = 2f))
        assertEquals(4, PanelLayoutPolicy.columnCount(maxWidthDp = 2_000f, fontScale = 0.8f))
    }

    @Test
    fun avoidsRepeatingRoomAlreadyVisibleInDeviceTitle() {
        assertEquals(
            "Кондиционер",
            panelCardMetadata(
                title = "Климат гостиной",
                room = "Гостиная",
                serviceName = "Кондиционер",
            ),
        )
        assertEquals(
            "Гостиная",
            panelCardMetadata(
                title = "Торшер",
                room = "Гостиная",
                serviceName = "Свет",
            ),
        )
    }
}
