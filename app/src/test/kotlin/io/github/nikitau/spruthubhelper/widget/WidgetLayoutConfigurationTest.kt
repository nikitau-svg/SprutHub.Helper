package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `size buckets cover one ui pixel portrait and pixel landscape`() {
        assertEquals(WidgetSizeClass.ICON, WidgetHostSize(57f, 102f).sizeClass())
        assertEquals(WidgetSizeClass.STRIP, WidgetHostSize(127f, 51f).sizeClass())
        assertEquals(WidgetSizeClass.COMPACT, WidgetHostSize(130f, 102f).sizeClass())
        assertEquals(WidgetSizeClass.STRIP, WidgetHostSize(269f, 51f).sizeClass())
        assertEquals(WidgetSizeClass.COMPACT, WidgetHostSize(276f, 102f).sizeClass())
        assertEquals(WidgetSizeClass.STRIP, WidgetHostSize(554f, 51f).sizeClass())
        assertEquals(WidgetSizeClass.WIDE, WidgetHostSize(349f, 102f).sizeClass())
        assertEquals(WidgetSizeClass.TALL, WidgetHostSize(349f, 220f).sizeClass())

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
        assertEquals(
            WidgetContentBlock.PRIMARY_VALUE,
            visibleWidgetLines(content, configuration, WidgetSizeClass.STRIP).single().block,
        )
        assertEquals(
            listOf(WidgetContentBlock.PRIMARY_VALUE),
            visibleWidgetLines(
                content = content,
                configuration = configuration,
                sizeClass = WidgetSizeClass.WIDE,
                fontScale = 2f,
            ).map(WidgetContentLine::block),
        )
    }

    @Test
    fun `launcher sizes are validated deduplicated and capped`() {
        val valid = (1..20).map { WidgetHostSize(widthDp = 100f + it, heightDp = 102f) }
        val bounded = boundedWidgetHostSizes(
            listOf(
                WidgetHostSize(Float.NaN, 100f),
                WidgetHostSize(100f, Float.POSITIVE_INFINITY),
                WidgetHostSize(0f, 100f),
                WidgetHostSize(5_000f, 100f),
            ) + valid + valid.first(),
        )

        assertEquals(MAX_RESPONSIVE_WIDGET_SIZES, bounded.size)
        assertEquals(valid.take(MAX_RESPONSIVE_WIDGET_SIZES), bounded)
        assertEquals(
            WidgetHostSize(226f, 102f),
            safeWidgetHostSize(Float.NaN, -1f),
        )
        assertEquals(
            WidgetHostSize(226f, 102f),
            safeWidgetHostSize(5_000f, Float.POSITIVE_INFINITY),
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
            WidgetGridLayout(columns = 2, rows = 2, visibleItemCount = 4, hiddenItemCount = 4),
            widgetGridLayout(
                hostSize = WidgetHostSize(496f, 234f),
                itemCount = 8,
                density = WidgetInformationDensity.BALANCED,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 4, rows = 2, visibleItemCount = 8, hiddenItemCount = 0),
            widgetGridLayout(
                hostSize = WidgetHostSize(496f, 234f),
                itemCount = 8,
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
        assertEquals("Вкл. +3", compactWidgetValue("Включено", 3, narrow = true))
        assertEquals("23.4 °C +3", compactWidgetValue("Последнее: 23.4 °C", 3, narrow = true))
    }

    @Test
    fun `large font reduces card count before text starts clipping`() {
        assertEquals(
            WidgetGridLayout(columns = 4, rows = 2, visibleItemCount = 8, hiddenItemCount = 0),
            widgetGridLayout(
                hostSize = WidgetHostSize(349f, 220f),
                itemCount = 8,
                density = WidgetInformationDensity.DETAILED,
                fontScale = 1f,
            ),
        )
        assertEquals(
            WidgetGridLayout(columns = 3, rows = 2, visibleItemCount = 6, hiddenItemCount = 2),
            widgetGridLayout(
                hostSize = WidgetHostSize(349f, 220f),
                itemCount = 8,
                density = WidgetInformationDensity.DETAILED,
                fontScale = 1.6f,
            ),
        )
        assertFalse(shouldShowWidgetRefresh(WidgetHostSize(130f, 102f), requested = true))
        assertFalse(shouldShowWidgetRefresh(WidgetHostSize(269f, 51f), requested = true))
        assertTrue(shouldShowWidgetRefresh(WidgetHostSize(226f, 102f), requested = true))
    }

    @Test
    fun `quick templates adapt to device role and preserve selected items`() {
        val climate = card()
        val initial = WidgetLayoutConfiguration(
            items = listOf(WidgetItemConfiguration("power", "temperature", listOf("humidity"))),
            showRefresh = false,
        )

        val recommended = applyWidgetQuickTemplate(
            initial,
            WidgetQuickTemplate.RECOMMENDED,
            listOf(climate),
        )
        assertEquals("Климат", recommendedWidgetTemplateLabel(listOf(climate)))
        assertEquals(WidgetInformationDensity.DETAILED, recommended.density)
        assertEquals(initial.items, recommended.items)
        assertEquals(false, recommended.showRefresh)
        assertTrue(recommended.matchesQuickTemplate(WidgetQuickTemplate.RECOMMENDED, listOf(climate)))

        val compact = applyWidgetQuickTemplate(
            initial,
            WidgetQuickTemplate.COMPACT,
            listOf(climate),
        )
        assertEquals(WidgetInformationDensity.COMPACT, compact.density)
        assertEquals(
            listOf(WidgetContentBlock.TITLE, WidgetContentBlock.PRIMARY_VALUE),
            compact.orderedBlocks,
        )
    }

    @Test
    fun `recommended template names cover scenes and mixed compositions`() {
        val climate = card()
        val scene = climate.copy(kind = DeviceKind.SCENE)

        assertEquals("Сценарий", recommendedWidgetTemplateLabel(listOf(scene)))
        assertEquals(
            "Несколько устройств",
            recommendedWidgetTemplateLabel(listOf(climate, scene)),
        )
        assertEquals(
            WidgetInformationDensity.COMPACT,
            applyWidgetQuickTemplate(
                WidgetLayoutConfiguration(items = listOf(WidgetItemConfiguration("power"))),
                WidgetQuickTemplate.RECOMMENDED,
                listOf(scene),
            ).density,
        )
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
