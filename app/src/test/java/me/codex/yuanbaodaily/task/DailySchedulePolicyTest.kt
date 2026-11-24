package me.codex.yuanbaodaily.task

import org.junit.Assert.assertEquals
import org.junit.Test

class DailySchedulePolicyTest {
    private val policy = DailySchedulePolicy(maxAttemptsPerDay = 3, retryDelayMs = 30 * 60_000L)

    @Test
    fun startingOrFailingRunDoesNotMarkDaySuccessful() {
        val initial = DailyScheduleState()
        val started = policy.recordStarted(initial, day = 20260926, nowMillis = 1_000L)
        val failed = policy.recordFinished(started, day = 20260926, success = false)

        assertEquals(0, started.successfulDay)
        assertEquals(0, failed.successfulDay)
        assertEquals(1, failed.attemptCount)
    }

    @Test
    fun failedRunRetriesAfterBackoffAndStopsAfterBoundedAttempts() {
        var state = DailyScheduleState()
        repeat(3) { attempt ->
            assertEquals(
                DailyScheduleDecision.RUN,
                policy.decide(true, 400, 180, 20260926, attempt * 2_000_000L, state),
            )
            state = policy.recordStarted(state, 20260926, attempt * 2_000_000L)
            state = policy.recordFinished(state, 20260926, success = false)
            if (attempt == 0) {
                assertEquals(
                    DailyScheduleDecision.WAIT_RETRY,
                    policy.decide(true, 400, 180, 20260926, 60_000L, state),
                )
            }
        }

        assertEquals(
            DailyScheduleDecision.ATTEMPTS_EXHAUSTED,
            policy.decide(true, 400, 180, 20260926, 9_000_000L, state),
        )
    }

    @Test
    fun successMarksDayDoneAndDisabledStateDoesNotConsumeAttempt() {
        val disabled = policy.decide(
            enabled = false,
            minuteOfDay = 400,
            runAtMinutes = 180,
            day = 20260926,
            nowMillis = 1_000L,
            state = DailyScheduleState(),
        )
        val enabled = policy.decide(
            enabled = true,
            minuteOfDay = 400,
            runAtMinutes = 180,
            day = 20260926,
            nowMillis = 1_000L,
            state = DailyScheduleState(),
        )
        val success = policy.recordFinished(
            policy.recordStarted(DailyScheduleState(), 20260926, 1_000L),
            day = 20260926,
            success = true,
        )

        assertEquals(DailyScheduleDecision.DISABLED, disabled)
        assertEquals(DailyScheduleDecision.RUN, enabled)
        assertEquals(DailyScheduleDecision.ALREADY_SUCCESSFUL, policy.decide(true, 400, 180, 20260926, 2_000L, success))
    }
}
