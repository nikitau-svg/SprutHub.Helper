package io.github.nikitau.spruthubhelper.phone

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSyncWatchdogTest {
    private val threshold = TimeUnit.MINUTES.toMillis(45)

    @Test
    fun `disabled sync is skipped`() {
        val decision = decidePhoneWatchdog(input(syncEnabled = false))

        assertEquals(
            PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.SYNC_DISABLED),
            decision,
        )
    }

    @Test
    fun `disabled watchdog is skipped`() {
        val decision = decidePhoneWatchdog(input(watchdogEnabled = false))

        assertEquals(
            PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.WATCHDOG_DISABLED),
            decision,
        )
    }

    @Test
    fun `missing baseline is skipped instead of notifying immediately`() {
        val decision = decidePhoneWatchdog(
            input(monitoringStartedEpochMs = null, lastSuccessEpochMs = null),
        )

        assertEquals(
            PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.BASELINE_MISSING),
            decision,
        )
    }

    @Test
    fun `monitoring start gives a full grace period`() {
        val decision = decidePhoneWatchdog(
            input(nowEpochMs = threshold - 1),
        )

        assertEquals(
            PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.RECENT_SUCCESS, threshold - 1),
            decision,
        )
    }

    @Test
    fun `stale sync notifies at threshold`() {
        val decision = decidePhoneWatchdog(input(nowEpochMs = threshold))

        assertEquals(PhoneWatchdogDecision.Notify(0, threshold), decision)
    }

    @Test
    fun `same stale episode is notified only once`() {
        val decision = decidePhoneWatchdog(
            input(nowEpochMs = threshold * 2, notifiedReferenceEpochMs = 0),
        )

        assertEquals(
            PhoneWatchdogDecision.Skip(PhoneWatchdogSkipReason.ALREADY_NOTIFIED, threshold * 2),
            decision,
        )
    }

    @Test
    fun `new success becomes a new reference and clears stale state`() {
        val decision = decidePhoneWatchdog(
            input(
                nowEpochMs = threshold * 2,
                lastSuccessEpochMs = threshold + 1,
                notifiedReferenceEpochMs = 0,
            ),
        )

        assertTrue(decision is PhoneWatchdogDecision.Skip)
        assertEquals(PhoneWatchdogSkipReason.RECENT_SUCCESS, (decision as PhoneWatchdogDecision.Skip).reason)
    }

    @Test
    fun `old notification marker does not suppress a later stale episode`() {
        val newSuccess = threshold
        val decision = decidePhoneWatchdog(
            input(
                nowEpochMs = threshold * 2,
                lastSuccessEpochMs = newSuccess,
                notifiedReferenceEpochMs = 0,
            ),
        )

        assertEquals(PhoneWatchdogDecision.Notify(newSuccess, threshold), decision)
    }

    private fun input(
        syncEnabled: Boolean = true,
        watchdogEnabled: Boolean = true,
        nowEpochMs: Long = threshold,
        monitoringStartedEpochMs: Long? = 0,
        lastSuccessEpochMs: Long? = null,
        notifiedReferenceEpochMs: Long? = null,
    ) = PhoneWatchdogInput(
        syncEnabled = syncEnabled,
        watchdogEnabled = watchdogEnabled,
        nowEpochMs = nowEpochMs,
        monitoringStartedEpochMs = monitoringStartedEpochMs,
        lastSuccessEpochMs = lastSuccessEpochMs,
        notifiedReferenceEpochMs = notifiedReferenceEpochMs,
        staleAfterMs = threshold,
    )
}
