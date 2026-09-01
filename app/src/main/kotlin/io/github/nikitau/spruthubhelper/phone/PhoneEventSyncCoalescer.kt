package io.github.nikitau.spruthubhelper.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces bursts without cancelling a sync that has already started.
 *
 * The wake-up channel is conflated, so event storms cannot grow memory use.
 * Distinct pending reasons are kept separately. A reason received during an
 * active sync remains pending for the next iteration instead of cancelling the
 * in-flight network request.
 */
internal class PhoneEventSyncCoalescer(
    scope: CoroutineScope,
    private val debounceMs: Long,
    private val retryDelayMs: Long,
    private val sync: suspend (Set<PhoneSyncTrigger>) -> Result<Unit>,
    private val onAttemptFinished: (Set<PhoneSyncTrigger>, Int, Result<Unit>) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val pending = linkedSetOf<PhoneSyncTrigger>()
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private val job: Job = scope.launch {
        for (ignored in wakeUps) {
            delay(debounceMs)
            val triggers = drainPending()
            if (triggers.isEmpty()) continue

            val first = attempt(triggers)
            onAttemptFinished(triggers, 1, first)
            if (first.isFailure) {
                delay(retryDelayMs)
                val retry = attempt(triggers)
                onAttemptFinished(triggers, 2, retry)
            }
        }
    }

    fun submit(trigger: PhoneSyncTrigger) {
        synchronized(lock) { pending += trigger }
        wakeUps.trySend(Unit)
    }

    fun cancel() {
        job.cancel()
        wakeUps.close()
    }

    private fun drainPending(): Set<PhoneSyncTrigger> = synchronized(lock) {
        pending.toSet().also { pending.clear() }
    }

    private suspend fun attempt(triggers: Set<PhoneSyncTrigger>): Result<Unit> = try {
        sync(triggers)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
