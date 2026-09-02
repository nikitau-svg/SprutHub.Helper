package io.github.nikitau.spruthubhelper.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
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
    recoveryBoundaryEpochMs: Long,
): Boolean = connection.phase in setOf(
    ConnectionPhase.CONNECTED_LOCAL,
    ConnectionPhase.CONNECTED_CLOUD,
) &&
    (connection.lastSuccessEpochMs ?: 0L) >= recoveryBoundaryEpochMs &&
    catalog.controls.isNotEmpty()

/**
 * Uses persisted connection intent rather than process-local catalog state.
 * Immediately after a Worker recreates the process, the cache-loading coroutine
 * may not have populated [SprutRepository.catalog] yet.
 */
internal fun hasCatalogRecoveryConfiguration(config: HubConfig): Boolean =
    config.serial.isNotBlank() && when (config.mode) {
        ConnectionMode.AUTO -> config.localUrl.isNotBlank() || config.cloudUrl.isNotBlank()
        ConnectionMode.LOCAL -> config.localUrl.isNotBlank()
        ConnectionMode.CLOUD -> config.cloudUrl.isNotBlank()
    }

/**
 * Prevents one physical route loss and the WebSocket failure it causes from
 * being counted as two independent outages. A transport failure without a
 * preceding Android network callback still claims a recovery cycle; this is
 * important during seamless Wi-Fi/mobile handovers where the replacement
 * network can become default before the old socket reports its late failure.
 */
internal class CatalogTransportRecoveryGate {
    private val outageAlreadySignalled = AtomicBoolean(false)

    fun onNetworkLost() {
        outageAlreadySignalled.set(true)
    }

    fun onCatalogConnected() {
        outageAlreadySignalled.set(false)
    }

    fun claimUnexpectedTransportLoss(): Boolean =
        outageAlreadySignalled.compareAndSet(false, true)
}

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
    private val refresh: suspend (recoveryBoundaryEpochMs: Long) -> Result<Unit>,
    private val onAttemptFinished: (attempt: Int, result: Result<Unit>) -> Unit = { _, _ -> },
) {
    private val stateLock = Any()
    private var outageObserved = false
    private var outageGeneration = 0L
    private var pendingAvailability = false
    private var activeGeneration: Long? = null
    private var completedGeneration: Long? = null
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
            if (completedGeneration == outageGeneration) return

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
                completedGeneration = generation
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

/**
 * A process-independent safety net for OEMs that defer app network callbacks
 * while the display sleeps. It is enqueued at the moment of a real loss and
 * Android starts it only after a usable network exists again.
 */
internal class CatalogRecoveryWorkScheduler(
    private val context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<CatalogRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(CatalogRecoveryWorker.KEY_OUTAGE_AT to nowEpochMs()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "spruthub_catalog_network_recovery"
    }
}

class CatalogRecoveryWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val repository = AppGraph.repository
        val config = AppGraph.settings.currentConfig()
        if (!hasCatalogRecoveryConfiguration(config)) {
            return Result.success()
        }

        // NetworkType.CONNECTED may be satisfied during route handover before
        // DNS and the local route have fully settled.
        delay(CatalogNetworkRecoveryCoordinator.DEFAULT_INITIAL_DELAY_MS)
        val recoveryBoundary = inputData.getLong(KEY_OUTAGE_AT, 0L)
        val result = repository.refreshAfterNetworkRecovery(recoveryBoundary)
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.NETWORK,
            event = "Страховочное восстановление каталога после возврата сети",
            outcome = if (result.isSuccess) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILED,
            reason = result.exceptionOrNull()?.let { "SprutHub пока недоступен" },
            details = mapOf("механизм" to "WorkManager"),
        )
        return if (result.isSuccess) {
            Log.i(LOG_TAG, "Catalog recovered after network return by WorkManager")
            Result.success()
        } else {
            // The live callback already owns its one controlled retry. This
            // independent path is deliberately one-shot to keep every outage
            // bounded even on noisy OEM network stacks.
            Log.w(LOG_TAG, "Catalog recovery safety net could not reach SprutHub")
            Result.failure()
        }
    }

    companion object {
        const val KEY_OUTAGE_AT = "outage_at_epoch_ms"
        private const val LOG_TAG = "SprutHubHelper"
    }
}

internal class CatalogNetworkRecoveryMonitor(
    context: Context,
    private val repository: SprutRepository,
    private val scope: CoroutineScope,
    onAttemptFinished: (attempt: Int, result: Result<Unit>) -> Unit = { _, _ -> },
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private val workScheduler = CatalogRecoveryWorkScheduler(context)
    private val transportRecoveryGate = CatalogTransportRecoveryGate()
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
            if (!recoveryIsRelevant()) return
            transportRecoveryGate.onNetworkLost()
            coordinator.onNetworkLost()
            scheduleSafetyNet()
            // During Wi-Fi/mobile handover Android may expose the replacement
            // default network before reporting the old one as lost.
            if (connectivity.activeNetwork != null) {
                requestRecoveryIfRelevant()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                // Some OEMs defer onAvailable while sleeping but still send
                // the final validated-capabilities callback after wake-up.
                requestRecoveryIfRelevant(allowMissedLoss = false)
            }
        }
    }

    private fun recoveryIsRelevant(): Boolean {
        val connection = repository.connectionStatus.value
        return connection.phase != ConnectionPhase.IDLE || repository.catalog.value.controls.isNotEmpty()
    }

    private fun requestRecoveryIfRelevant(allowMissedLoss: Boolean = true) {
        if (!recoveryIsRelevant()) return
        coordinator.onNetworkAvailable(
            connectionIsOffline = allowMissedLoss &&
                repository.connectionStatus.value.phase == ConnectionPhase.ERROR,
        )
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            observeLateTransportFailures()
        }.onFailure { error ->
            started.set(false)
            Log.w(LOG_TAG, "Catalog network recovery callback is unavailable", error)
        }
    }

    private fun observeLateTransportFailures() {
        scope.launch {
            var previousPhase = repository.connectionStatus.value.phase
            repository.connectionStatus.collect { connection ->
                val nextPhase = connection.phase
                if (nextPhase == ConnectionPhase.CONNECTED_LOCAL || nextPhase == ConnectionPhase.CONNECTED_CLOUD) {
                    transportRecoveryGate.onCatalogConnected()
                } else if (
                    nextPhase == ConnectionPhase.ERROR &&
                    previousPhase in setOf(ConnectionPhase.CONNECTED_LOCAL, ConnectionPhase.CONNECTED_CLOUD) &&
                    transportRecoveryGate.claimUnexpectedTransportLoss()
                ) {
                    // A route can already be usable by the time the old
                    // WebSocket reports its failure. Do not wait for a second
                    // Android network callback which may never arrive.
                    coordinator.onNetworkLost()
                    scheduleSafetyNet()
                    if (connectivity.activeNetwork != null) {
                        requestRecoveryIfRelevant()
                    }
                }
                previousPhase = nextPhase
            }
        }
    }

    private fun scheduleSafetyNet() {
        runCatching(workScheduler::schedule).onFailure { error ->
            Log.w(LOG_TAG, "Catalog recovery safety net could not be scheduled", error)
        }
    }

    companion object {
        private const val LOG_TAG = "SprutHubHelper"
    }
}
