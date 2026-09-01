package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFreshnessTest {
    private val now = 100_000L
    private val catalog = SprutCatalog(
        controls = listOf(
            SprutControl(
                id = "1:2:3",
                accessoryId = "1",
                serviceId = "2",
                characteristicId = "3",
                title = "Свет",
            ),
        ),
        refreshedAtEpochMs = now - 5_000L,
    )

    @Test
    fun connectedSocketMakesAuthoritativeCatalogLive() {
        val freshness = CatalogFreshnessPolicy.evaluate(
            catalog = catalog.copy(refreshedAtEpochMs = now - 3_600_000L),
            connection = ConnectionStatus(phase = ConnectionPhase.CONNECTED_CLOUD),
            nowEpochMs = now,
        )

        assertEquals(CatalogFreshnessPhase.LIVE, freshness.phase)
        assertTrue(freshness.canDisplayAuthoritativeState)
    }

    @Test
    fun failedTransportNeverMakesYoungCacheLookLive() {
        val freshness = CatalogFreshnessPolicy.evaluate(
            catalog = catalog,
            connection = ConnectionStatus(phase = ConnectionPhase.ERROR),
            nowEpochMs = now,
        )

        assertEquals(CatalogFreshnessPhase.OFFLINE, freshness.phase)
        assertFalse(freshness.canDisplayAuthoritativeState)
    }

    @Test
    fun idleCacheExpiresAfterDisplayWindow() {
        val recent = CatalogFreshnessPolicy.evaluate(
            catalog = catalog,
            connection = ConnectionStatus(),
            nowEpochMs = now,
        )
        val stale = CatalogFreshnessPolicy.evaluate(
            catalog = catalog.copy(refreshedAtEpochMs = now - 31_000L),
            connection = ConnectionStatus(),
            nowEpochMs = now,
        )

        assertEquals(CatalogFreshnessPhase.RECENT, recent.phase)
        assertEquals(CatalogFreshnessPhase.STALE, stale.phase)
    }

    @Test
    fun pendingControlIsReportedSeparatelyFromFreshness() {
        val freshness = CatalogFreshnessPolicy.evaluate(
            catalog = catalog,
            connection = ConnectionStatus(phase = ConnectionPhase.CONNECTED_LOCAL),
            pendingControlIds = setOf("1:2:3"),
            nowEpochMs = now,
        )

        assertEquals(CatalogFreshnessPhase.LIVE, freshness.phase)
        assertTrue(freshness.isPending("1:2:3"))
    }

    @Test
    fun staleOrPendingToggleNeverLooksActive() {
        val enabled = catalog.controls.single().copy(value = SprutValue(boolValue = true))
        val offline = CatalogFreshnessPolicy.evaluate(
            catalog = catalog.copy(controls = listOf(enabled)),
            connection = ConnectionStatus(phase = ConnectionPhase.ERROR),
            nowEpochMs = now,
        ).presentationFor(enabled)
        val pending = CatalogFreshnessPolicy.evaluate(
            catalog = catalog.copy(controls = listOf(enabled)),
            connection = ConnectionStatus(phase = ConnectionPhase.CONNECTED_LOCAL),
            pendingControlIds = setOf(enabled.id),
            nowEpochMs = now,
        ).presentationFor(enabled)

        assertFalse(offline.active)
        assertFalse(offline.stateIsAuthoritative)
        assertEquals("Нет связи", offline.statusLabel)
        assertFalse(pending.active)
        assertEquals("Подтверждаем…", pending.statusLabel)
    }

    @Test
    fun recentCacheIsLabelledEvenThoughItsValueCanBeShown() {
        val presentation = CatalogFreshnessPolicy.evaluate(
            catalog = catalog,
            connection = ConnectionStatus(),
            nowEpochMs = now,
        ).presentationFor(catalog.controls.single())

        assertTrue(presentation.stateIsAuthoritative)
        assertEquals("Недавно обновлено", presentation.statusLabel)
    }
}
