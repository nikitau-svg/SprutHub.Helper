package io.github.nikitau.spruthubhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlSurfaceGuideTest {
    @Test
    fun `verified fold reports all three checked surfaces`() {
        val result = buildControlSurfaceCompatibility(
            apiLevel = 36,
            manufacturer = "Samsung",
            model = "SM-F971B",
            hasSystemControls = true,
            hasEmbeddedPanel = true,
        )

        assertEquals(3, result.size)
        assertEquals(
            setOf(SurfaceCompatibilityTone.VERIFIED),
            result.map(ControlSurfaceCompatibility::tone).toSet(),
        )
    }

    @Test
    fun `android eleven describes compatible fallbacks without overpromising`() {
        val result = buildControlSurfaceCompatibility(
            apiLevel = 30,
            manufacturer = "Example",
            model = "Phone",
            hasSystemControls = true,
            hasEmbeddedPanel = false,
        ).associateBy(ControlSurfaceCompatibility::kind)

        assertEquals(SurfaceCompatibilityTone.LIMITED, result.getValue(ControlSurfaceKind.HOME_WIDGET).tone)
        assertEquals(SurfaceCompatibilityTone.LIMITED, result.getValue(ControlSurfaceKind.QUICK_TILE).tone)
        assertEquals(SurfaceCompatibilityTone.LIMITED, result.getValue(ControlSurfaceKind.DEVICE_PANEL).tone)
    }

    @Test
    fun `missing device controls feature is shown as unavailable`() {
        val result = buildControlSurfaceCompatibility(
            apiLevel = 35,
            manufacturer = "Example",
            model = "Phone",
            hasSystemControls = false,
            hasEmbeddedPanel = false,
        ).associateBy(ControlSurfaceCompatibility::kind)

        assertEquals(
            SurfaceCompatibilityTone.UNAVAILABLE,
            result.getValue(ControlSurfaceKind.DEVICE_PANEL).tone,
        )
        assertEquals(
            SurfaceCompatibilityTone.SHELL_DEPENDENT,
            result.getValue(ControlSurfaceKind.HOME_WIDGET).tone,
        )
    }

    @Test
    fun `android fourteen embedded panel remains shell dependent when unverified`() {
        val panel = buildControlSurfaceCompatibility(
            apiLevel = 34,
            manufacturer = "Example",
            model = "Phone",
            hasSystemControls = true,
            hasEmbeddedPanel = true,
        ).single { it.kind == ControlSurfaceKind.DEVICE_PANEL }

        assertEquals(SurfaceCompatibilityTone.SHELL_DEPENDENT, panel.tone)
    }
}
