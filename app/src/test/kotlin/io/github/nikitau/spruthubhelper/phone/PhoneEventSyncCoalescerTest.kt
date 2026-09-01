package io.github.nikitau.spruthubhelper.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneEventSyncCoalescerTest {
    @Test
    fun `coalesces a burst and preserves all distinct reasons`() = runTest {
        val calls = mutableListOf<Set<PhoneSyncTrigger>>()
        val coalescer = PhoneEventSyncCoalescer(
            scope = backgroundScope,
            debounceMs = 1_500,
            retryDelayMs = 10_000,
            sync = { triggers -> calls += triggers; Result.success(Unit) },
        )

        coalescer.submit(PhoneSyncTrigger.BATTERY_CHANGED)
        coalescer.submit(PhoneSyncTrigger.POWER_CONNECTED)
        coalescer.submit(PhoneSyncTrigger.BATTERY_CHANGED)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(
            listOf(setOf(PhoneSyncTrigger.BATTERY_CHANGED, PhoneSyncTrigger.POWER_CONNECTED)),
            calls,
        )
        coalescer.cancel()
    }

    @Test
    fun `event during active sync is queued instead of cancelling it`() = runTest {
        val firstCanFinish = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val calls = mutableListOf<Set<PhoneSyncTrigger>>()
        val coalescer = PhoneEventSyncCoalescer(
            scope = backgroundScope,
            debounceMs = 100,
            retryDelayMs = 1_000,
            sync = { triggers ->
                calls += triggers
                if (calls.size == 1) {
                    firstStarted.complete(Unit)
                    firstCanFinish.await()
                }
                Result.success(Unit)
            },
        )

        coalescer.submit(PhoneSyncTrigger.SCREEN_ON)
        advanceTimeBy(100)
        runCurrent()
        firstStarted.await()
        coalescer.submit(PhoneSyncTrigger.NETWORK_AVAILABLE)
        runCurrent()

        assertFalse(firstCanFinish.isCompleted)
        assertEquals(1, calls.size)

        firstCanFinish.complete(Unit)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            listOf(
                setOf(PhoneSyncTrigger.SCREEN_ON),
                setOf(PhoneSyncTrigger.NETWORK_AVAILABLE),
            ),
            calls,
        )
        coalescer.cancel()
    }

    @Test
    fun `retries one failed batch without dropping its reasons`() = runTest {
        val calls = mutableListOf<Set<PhoneSyncTrigger>>()
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val coalescer = PhoneEventSyncCoalescer(
            scope = backgroundScope,
            debounceMs = 100,
            retryDelayMs = 500,
            sync = { triggers ->
                calls += triggers
                if (calls.size == 1) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
            },
            onAttemptFinished = { _, attempt, result -> attempts += attempt to result.isSuccess },
        )

        coalescer.submit(PhoneSyncTrigger.POWER_SAVE_MODE_CHANGED)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(2, calls.size)
        assertEquals(calls[0], calls[1])
        assertEquals(listOf(1 to false, 2 to true), attempts)
        coalescer.cancel()
    }

    @Test
    fun `a thrown sync error is retried without killing the event loop`() = runTest {
        var callCount = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val coalescer = PhoneEventSyncCoalescer(
            scope = backgroundScope,
            debounceMs = 100,
            retryDelayMs = 500,
            sync = {
                callCount += 1
                if (callCount == 1) error("temporary failure")
                Result.success(Unit)
            },
            onAttemptFinished = { _, attempt, result -> attempts += attempt to result.isSuccess },
        )

        coalescer.submit(PhoneSyncTrigger.NETWORK_AVAILABLE)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(2, callCount)
        assertEquals(listOf(1 to false, 2 to true), attempts)
        coalescer.cancel()
    }
}
