package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutConfigurationTest {
    @Test
    fun `normalization keeps primary first and bounds nested selections`() {
        val configuration = WidgetLayoutConfiguration(
            orderedBlocks = listOf(WidgetContentBlock.TITLE, WidgetContentBlock.TITLE),
            items = (1..10).map { index ->
                WidgetItemConfiguration(
                    controlId = " control-$index ",
                    headlineValueKey = " headline ",
                    secondaryValueKeys = listOf("headline", "a", "a", "b", "c", "d"),
                )
            },
        ).normalized(fallbackPrimaryControlId = "control-4")

        assertEquals("control-4", configuration.items.first().controlId)
        assertEquals(MAX_WIDGET_ITEMS, configuration.items.size)
        assertTrue(WidgetContentBlock.PRIMARY_VALUE in configuration.orderedBlocks)
        assertEquals(listOf("a", "b", "c"), configuration.items.first().secondaryValueKeys)
        assertEquals(CURRENT_WIDGET_LAYOUT_SCHEMA, configuration.schemaVersion)
    }

    @Test
    fun `codec round trip preserves multi device layout`() {
        val expected = WidgetLayoutConfiguration(
            density = WidgetInformationDensity.DETAILED,
            orderedBlocks = listOf(
                WidgetContentBlock.PRIMARY_VALUE,
                WidgetContentBlock.TITLE,
                WidgetContentBlock.CONTEXT,
            ),
            items = listOf(
                WidgetItemConfiguration("light", "state", listOf("brightness")),
                WidgetItemConfiguration("climate", "temperature", emptyList()),
            ),
            showRefresh = false,
        ).normalized()

        assertEquals(expected, WidgetLayoutConfigurationCodec.decode(WidgetLayoutConfigurationCodec.encode(expected)))
        assertNull(WidgetLayoutConfigurationCodec.decode("not-json"))
    }

    @Test
    fun `size buckets match one ui and preserve primary line`() {
        assertEquals(WidgetSizeClass.ICON, WidgetHostSize(91f, 101f).sizeClass())
        assertEquals(WidgetSizeClass.COMPACT, WidgetHostSize(226f, 101f).sizeClass())
        assertEquals(WidgetSizeClass.WIDE, WidgetHostSize(496f, 101f).sizeClass())
        assertEquals(WidgetSizeClass.TALL, WidgetHostSize(181f, 187f).sizeClass())
        assertEquals(WidgetHostSize(92f, 102f), previewHostSize(WidgetSizeClass.ICON))

        val card = card()
        val configuration = WidgetLayoutConfiguration(
            orderedBlocks = listOf(
                WidgetContentBlock.TITLE,
                WidgetContentBlock.CONTEXT,
                WidgetContentBlock.PRIMARY_VALUE,
                WidgetContentBlock.SECONDARY_VALUES,
            ),
            items = listOf(
                WidgetItemConfiguration(
                    controlId = "power",
                    headlineValueKey = "temperature",
                    secondaryValueKeys = listOf("humidity"),
                ),
            ),
        )
        val content = resolveWidgetContent(card, configuration, configuration.items.single())

        assertEquals("temperature", content.headline.key)
        assertEquals(listOf("humidity"), content.secondary.map { it.key })
        assertEquals(
            listOf(WidgetContentBlock.TITLE, WidgetContentBlock.PRIMARY_VALUE),
            visibleWidgetLines(content, configuration, WidgetSizeClass.COMPACT).map { it.block },
        )
        assertEquals(
            WidgetContentBlock.PRIMARY_VALUE,
            visibleWidgetLines(content, configuration, WidgetSizeClass.ICON).single().block,
        )
    }

    @Test
    fun `multi item pending intent can only address assigned controls`() {
        val layout = WidgetLayoutConfiguration(
            items = listOf(
                WidgetItemConfiguration("primary"),
                WidgetItemConfiguration("secondary"),
            ),
        )

        assertEquals("secondary", resolveWidgetActionControlId("primary", layout, "secondary"))
        assertEquals("primary", resolveWidgetActionControlId("primary", layout, "foreign"))
        assertEquals("primary", resolveWidgetActionControlId("primary", layout, null))
        assertNull(resolveWidgetActionControlId(null, layout, "secondary"))
    }

    @Test
    fun `adaptive grid uses available cells and reports hidden items`() {
        assertEquals(
            WidgetGridLayout(columns = 2, rows = 1, visibleItemCount = 2, hiddenItemCount = 3),
            widgetGridLayout(
                hostSize = WidgetHostSize(226f, 102f),
                itemCount = 5,
                density = WidgetInformationDensity.DETAILED,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 3, rows = 1, visibleItemCount = 3, hiddenItemCount = 0),
            widgetGridLayout(
                hostSize = WidgetHostSize(496f, 102f),
                itemCount = 3,
                density = WidgetInformationDensity.DETAILED,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 2, rows = 2, visibleItemCount = 3, hiddenItemCount = 0),
            widgetGridLayout(
                hostSize = WidgetHostSize(226f, 220f),
                itemCount = 3,
                density = WidgetInformationDensity.DETAILED,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 3, rows = 2, visibleItemCount = 5, hiddenItemCount = 0),
            widgetGridLayout(
                hostSize = WidgetHostSize(496f, 220f),
                itemCount = 5,
                density = WidgetInformationDensity.DETAILED,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 2, rows = 1, visibleItemCount = 2, hiddenItemCount = 6),
            widgetGridLayout(
                hostSize = WidgetHostSize(496f, 220f),
                itemCount = 8,
                density = WidgetInformationDensity.COMPACT,
            ),
        )
        assertEquals("+3 ещё", widgetOverflowLabel(3))
        assertEquals("Включено · +3", compactWidgetValue("Включено", 3))
    }

    private fun card() = buildServiceControlCards(
        listOf(
            SprutControl(
                id = "power",
                accessoryId = "climate",
                serviceId = "main",
                characteristicId = "power",
                title = "Климат",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                behavior = ControlBehavior.TOGGLE,
                value = SprutValue(boolValue = true),
                characteristicType = "Active",
                characteristicName = "Питание",
                writable = true,
            ),
            SprutControl(
                id = "temperature",
                accessoryId = "climate",
                serviceId = "main",
                characteristicId = "temperature",
                title = "Климат",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                behavior = ControlBehavior.SENSOR,
                value = SprutValue(numberValue = 23.4),
                characteristicType = "CurrentTemperature",
                characteristicName = "Сейчас",
                unit = "celsius",
            ),
            SprutControl(
                id = "humidity",
                accessoryId = "climate",
                serviceId = "main",
                characteristicId = "humidity",
                title = "Климат",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                behavior = ControlBehavior.SENSOR,
                value = SprutValue(numberValue = 46.0),
                characteristicType = "CurrentRelativeHumidity",
                characteristicName = "Влажность",
                unit = "percentage",
            ),
        ),
    ).single()
}
