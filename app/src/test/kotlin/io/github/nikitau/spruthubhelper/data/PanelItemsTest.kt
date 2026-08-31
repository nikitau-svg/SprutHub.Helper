package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelItemsTest {
    @Test
    fun `reconcile preserves order and size while migrating stale ids`() {
        val current = listOf(
            PanelItem("old-light", PanelItemSize.LARGE),
            PanelItem("sensor", PanelItemSize.COMPACT),
        )

        val result = reconcilePanelSelection(
            current = current,
            validControlIds = setOf("new-light", "sensor"),
            replacements = mapOf("old-light" to "new-light"),
        )

        assertEquals(
            listOf(
                PanelItem("new-light", PanelItemSize.LARGE),
                PanelItem("sensor", PanelItemSize.COMPACT),
            ),
            result,
        )
    }

    @Test
    fun `reconcile removes missing items and duplicate replacement`() {
        val result = reconcilePanelSelection(
            current = listOf(
                PanelItem("old", PanelItemSize.LARGE),
                PanelItem("current", PanelItemSize.COMPACT),
                PanelItem("gone", PanelItemSize.COMPACT),
            ),
            validControlIds = setOf("current"),
            replacements = mapOf("old" to "current"),
        )

        assertEquals(listOf(PanelItem("current", PanelItemSize.LARGE)), result)
    }

    @Test
    fun `reconcile migrates raw characteristic selection to service card and keeps attributes`() {
        val current = listOf(
            PanelItem(
                controlId = "11:13:main",
                size = PanelItemSize.LARGE,
                attributeControlIds = listOf("11:13:20"),
            ),
        )

        val result = reconcilePanelSelection(
            current = current,
            validControlIds = setOf("service:11:13"),
            replacements = mapOf("11:13:main" to "service:11:13"),
        )

        assertEquals(
            PanelItem(
                controlId = "service:11:13",
                size = PanelItemSize.LARGE,
                attributeControlIds = listOf("11:13:20"),
            ),
            result.single(),
        )
    }
}
