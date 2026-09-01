package io.github.nikitau.spruthubhelper.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogNetworkRecoveryCoordinatorTest {
    @Test
    fun `initial available callback does not create background catalog traffic`() = runTest {
        var refreshes = 0
        val coordinator = coordinator { refreshes += 1; Result.success(Unit) }

        coordinator.onNetworkAvailable(connectionIsOffline = false)
        testScheduler.advanceUntilIdle()

        assertEquals(0, refreshes)
    }

    @Test
    fun `network return performs one delayed recovery and collapses duplicate callbacks`() = runTest {
        var refreshes = 0
        val coordinator = coordinator { refreshes += 1; Result.success(Unit) }

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceTimeBy(2_499)
        assertEquals(0, refreshes)

        testScheduler.advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun `failed first recovery gets one controlled retry`() = runTest {
        var refreshes = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val coordinator = CatalogNetworkRecoveryCoordinator(
            scope = this,
            initialDelayMs = 2_500,
            retryDelayMs = 10_000,
            refresh = {
                refreshes += 1
                if (refreshes == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(Unit)
            },
            onAttemptFinished = { attempt, result -> attempts += attempt to result.isSuccess },
        )

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, refreshes)
        assertEquals(listOf(1 to false, 2 to true), attempts)
    }

    @Test
    fun `duplicate available callback cannot extend a failed recovery cycle`() = runTest {
        var refreshes = 0
        val coordinator = coordinator {
            refreshes += 1
            Result.failure(IllegalStateException("offline"))
        }

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, refreshes)
    }

    @Test
    fun `failed generation stays exhausted until a new loss`() = runTest {
        var refreshes = 0
        val coordinator = coordinator {
            refreshes += 1
            Result.failure(IllegalStateException("offline"))
        }

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, refreshes)

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(4, refreshes)
    }

    @Test
    fun `new outage during recovery schedules one new recovery cycle`() = runTest {
        var refreshes = 0
        val coordinator = coordinator {
            refreshes += 1
            Result.success(Unit)
        }

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceTimeBy(1_000)
        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, refreshes)
    }

    @Test
    fun `offline repository can recover even when callback missed the original loss`() = runTest {
        var refreshes = 0
        val coordinator = coordinator { refreshes += 1; Result.success(Unit) }

        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()

        assertEquals(1, refreshes)
    }

    @Test
    fun `successful recovery consumes outage until another real loss`() = runTest {
        var refreshes = 0
        val coordinator = coordinator { refreshes += 1; Result.success(Unit) }

        coordinator.onNetworkLost()
        coordinator.onNetworkAvailable(connectionIsOffline = true)
        testScheduler.advanceUntilIdle()
        coordinator.onNetworkAvailable(connectionIsOffline = false)
        testScheduler.advanceUntilIdle()

        assertEquals(1, refreshes)
    }

    @Test
    fun `separate interface refresh suppresses queued duplicate catalog read`() {
        val catalog = SprutCatalog(
            controls = listOf(
                SprutControl(
                    id = "1:2:3",
                    accessoryId = "1",
                    serviceId = "2",
                    characteristicId = "3",
                    title = "Fixture",
                ),
            ),
        )

        assertTrue(
            catalogRecoveredAfter(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.CONNECTED_LOCAL,
                    lastSuccessEpochMs = 5_000,
                ),
                catalog = catalog,
                recoveryBoundaryEpochMs = 4_000,
            ),
        )
        assertFalse(
            catalogRecoveredAfter(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.ERROR,
                    lastSuccessEpochMs = 5_000,
                ),
                catalog = catalog,
                recoveryBoundaryEpochMs = 4_000,
            ),
        )
        assertFalse(
            catalogRecoveredAfter(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.CONNECTED_LOCAL,
                    lastSuccessEpochMs = 3_000,
                ),
                catalog = catalog,
                recoveryBoundaryEpochMs = 4_000,
            ),
        )
        assertFalse(
            catalogRecoveredAfter(
                connection = ConnectionStatus(
                    phase = ConnectionPhase.CONNECTED_CLOUD,
                    lastSuccessEpochMs = 5_000,
                ),
                catalog = SprutCatalog(),
                recoveryBoundaryEpochMs = 4_000,
            ),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        refresh: suspend (Long) -> Result<Unit>,
    ) = CatalogNetworkRecoveryCoordinator(
        scope = this,
        initialDelayMs = 2_500,
        retryDelayMs = 10_000,
        nowEpochMs = { testScheduler.currentTime },
        refresh = refresh,
    )
}
