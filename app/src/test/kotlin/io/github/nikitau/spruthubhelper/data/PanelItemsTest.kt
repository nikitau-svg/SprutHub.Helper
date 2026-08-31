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
}
