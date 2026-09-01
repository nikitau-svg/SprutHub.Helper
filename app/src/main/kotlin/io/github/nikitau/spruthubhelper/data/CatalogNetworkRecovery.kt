package io.github.nikitau.spruthubhelper.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun catalogRecoveredAfter(
    connection: ConnectionStatus,
    catalog: SprutCatalog,
    networkAvailableAtEpochMs: Long,
): Boolean = connection.phase in setOf(
    ConnectionPhase.CONNECTED_LOCAL,
    ConnectionPhase.CONNECTED_CLOUD,
) &&
    (connection.lastSuccessEpochMs ?: 0L) >= networkAvailableAtEpochMs &&
    catalog.controls.isNotEmpty()

/**
 * Collapses Android network callbacks into one controlled catalog recovery.
 *
 * The phone-data publisher has its own SprutRpcClient, so its successful retry
 * cannot make widgets, tiles or Device Controls live again. This coordinator
 * deliberately refreshes the shared [SprutRepository] after a real default
 * network outage, without turning noisy capability callbacks into polling.
 */
internal class CatalogNetworkRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val refresh: suspend (networkAvailableAtEpochMs: Long) -> Result<Unit>,
    private val onAttemptFinished: (attempt: Int, result: Result<Unit>) -> Unit = { _, _ -> },
) {
    private val stateLock = Any()
    private var outageObserved = false
    private var outageGeneration = 0L
    private var pendingAvailability = false
    private var activeGeneration: Long? = null
    private var recoveryJob: Job? = null

    fun onNetworkLost() {
        synchronized(stateLock) {
            outageObserved = true
            outageGeneration += 1
        }
    }

    fun onNetworkAvailable(connectionIsOffline: Boolean) {
        val task = synchronized(stateLock) {
            if (recoveryJob?.isActive == true) {
                // Several callbacks for the same default network are normal.
                // Only a genuinely newer loss/return cycle may queue another
                // recovery behind the active one.
                if (activeGeneration != outageGeneration) {
                    pendingAvailability = true
                }
                return
            }
            if (!outageObserved && !connectionIsOffline) return

            val generation = outageGeneration
            val availableAt = nowEpochMs()
            scope.launch(start = CoroutineStart.LAZY) {
                runRecovery(generation, availableAt)
            }.also {
                activeGeneration = generation
                recoveryJob = it
            }
        }
        task.start()
    }

    private suspend fun runRecovery(generation: Long, availableAt: Long) {
        var succeeded = false
        try {
            delay(initialDelayMs)
            var result = attempt(1, availableAt)
            if (result.isFailure) {
                delay(retryDelayMs)
                result = attempt(2, availableAt)
            }
            succeeded = result.isSuccess
        } finally {
            val restart = synchronized(stateLock) {
                recoveryJob = null
                activeGeneration = null
                if (succeeded && outageGeneration == generation) outageObserved = false
                val shouldRestart = pendingAvailability && outageObserved
                pendingAvailability = false
                shouldRestart
            }
            if (restart) onNetworkAvailable(connectionIsOffline = true)
        }
    }

    private suspend fun attempt(number: Int, availableAt: Long): Result<Unit> {
        val result = try {
            refresh(availableAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        onAttemptFinished(number, result)
        return result
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 2_500L
        const val DEFAULT_RETRY_DELAY_MS = 10_000L
    }
}

internal class CatalogNetworkRecoveryMonitor(
    context: Context,
    private val repository: SprutRepository,
    scope: CoroutineScope,
    onAttemptFinished: (attempt: Int, result: Result<Unit>) -> Unit = { _, _ -> },
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private val coordinator = CatalogNetworkRecoveryCoordinator(
        scope = scope,
        refresh = { availableAt ->
            repository.refreshAfterNetworkRecovery(availableAt).map { Unit }
        },
        onAttemptFinished = onAttemptFinished,
    )
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestRecoveryIfRelevant()
        }

        override fun onLost(network: Network) {
            coordinator.onNetworkLost()
            // During Wi-Fi/mobile handover Android may expose the replacement
            // default network before reporting the old one as lost.
            if (connectivity.activeNetwork != null) {
                requestRecoveryIfRelevant()
            }
        }
    }

    private fun requestRecoveryIfRelevant() {
        val connection = repository.connectionStatus.value
        if (connection.phase == ConnectionPhase.IDLE && repository.catalog.value.controls.isEmpty()) return
        coordinator.onNetworkAvailable(connectionIsOffline = connection.phase == ConnectionPhase.ERROR)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.onFailure { error ->
            started.set(false)
            Log.w(LOG_TAG, "Catalog network recovery callback is unavailable", error)
        }
    }

    companion object {
        private const val LOG_TAG = "SprutHubHelper"
    }
}
