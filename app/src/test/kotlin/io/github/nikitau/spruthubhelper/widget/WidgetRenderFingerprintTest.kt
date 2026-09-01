package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.CatalogFreshnessPhase
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetRenderFingerprintTest {
    @Test
    fun `identical websocket events coalesce but visible changes do not`() {
        val control = sensor("Отличное")
        val live = CatalogFreshness(CatalogFreshnessPhase.LIVE)
        val first = fingerprint(control, live)

        assertEquals(first, fingerprint(control.copy(), live.copy()))
        assertNotEquals(first, fingerprint(sensor("Хорошее"), live))
        assertNotEquals(
            first,
            fingerprint(control, live.copy(pendingControlIds = setOf(control.id))),
        )
        assertNotEquals(first, fingerprint(control, CatalogFreshness(CatalogFreshnessPhase.OFFLINE)))
        assertNotEquals(first, fingerprint(control, live, iconRevision = "200:2"))
    }

    @Test
    fun `missing and unconfigured widgets have distinct stable states`() {
        val freshness = CatalogFreshness(CatalogFreshnessPhase.EMPTY)
        val unconfigured = widgetRenderFingerprint(null, null, true, freshness, null)
        val missing = widgetRenderFingerprint("gone", null, true, freshness, null)

        assertEquals(WidgetRenderMode.UNCONFIGURED, unconfigured.mode)
        assertEquals(WidgetRenderMode.MISSING, missing.mode)
        assertNotEquals(unconfigured, missing)
    }

    private fun fingerprint(
        control: SprutControl,
        freshness: CatalogFreshness,
        iconRevision: String? = "200:1",
    ) = widgetRenderFingerprint(
        assignment = control.id,
        control = control,
        catalogIsEmpty = false,
        freshness = freshness,
        customIconRevision = iconRevision,
    )

    private fun sensor(value: String) = SprutControl(
        id = "1:1:1",
        accessoryId = "1",
        serviceId = "1",
        characteristicId = "1",
        title = "Качество воздуха",
        subtitle = "Air Quality",
        room = "Спальня",
        kind = DeviceKind.SENSOR,
        behavior = ControlBehavior.SENSOR,
        value = SprutValue(stringValue = value),
        characteristicType = "AirQuality",
    )
}
