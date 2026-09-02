package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.CatalogFreshnessPhase
import io.github.nikitau.spruthubhelper.data.CharacteristicDisplayValue
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
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
        assertNotEquals(
            first,
            fingerprint(control.copy(sourceType = "S.BatteryService"), live),
        )
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

    @Test
    fun `selected linked headline participates in widget fingerprint`() {
        val action = sensor("ignored").copy(
            id = "1:1:main",
            behavior = ControlBehavior.TOGGLE,
            value = SprutValue(boolValue = true),
            characteristicType = "Active",
            writable = true,
        )
        val firstSensor = sensor("Отличное").copy(id = "1:1:quality", characteristicId = "2")
        val firstCard = buildServiceControlCards(listOf(action, firstSensor)).single()
        val preference = ServicePresentationPreference(firstCard.id, headlineValueKey = firstSensor.id)
        val first = widgetRenderFingerprint(
            assignment = action.id,
            control = action,
            catalogIsEmpty = false,
            freshness = CatalogFreshness(CatalogFreshnessPhase.LIVE),
            customIconRevision = null,
            card = firstCard,
            preference = preference,
        )
        val changedCard = buildServiceControlCards(
            listOf(action, firstSensor.copy(value = SprutValue(stringValue = "Плохое"))),
        ).single()
        val changed = widgetRenderFingerprint(
            assignment = action.id,
            control = action,
            catalogIsEmpty = false,
            freshness = CatalogFreshness(CatalogFreshnessPhase.LIVE),
            customIconRevision = null,
            card = changedCard,
            preference = preference,
        )

        assertNotEquals(first, changed)
        assertEquals("Отличное", first.semanticValue)
        assertEquals("Плохое", changed.semanticValue)
    }

    @Test
    fun `widget subtitle omits a service name already represented by a metric`() {
        val parts = widgetSubtitleParts(
            statusPrefix = "",
            headline = metric("pm25", "PM2.5", "9"),
            secondary = listOf(
                metric("quality", "Качество воздуха", "Отличное"),
                metric("pm10", "PM10", "9"),
            ),
            serviceName = "Качество воздуха",
            room = "Спальня",
        )

        assertEquals(
            listOf("PM2.5", "Качество воздуха Отличное · PM10 9", "Спальня"),
            parts,
        )
    }

    @Test
    fun `widget subtitle retains a distinct service name`() {
        val parts = widgetSubtitleParts(
            statusPrefix = "",
            headline = metric("power", "Питание", "Включено"),
            secondary = listOf(metric("mode", "Заданный режим", "Охлаждение")),
            serviceName = "Кондиционер",
            room = "Зал",
        )

        assertEquals(
            listOf("Питание", "Заданный режим Охлаждение", "Кондиционер", "Зал"),
            parts,
        )
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

    private fun metric(key: String, label: String, value: String) =
        CharacteristicDisplayValue(key = key, label = label, value = value)
}
