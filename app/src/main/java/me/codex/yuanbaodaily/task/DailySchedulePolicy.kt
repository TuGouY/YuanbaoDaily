package me.codex.yuanbaodaily.task

import java.util.Calendar
import java.util.TimeZone

internal data class DailyScheduleState(
    val successfulDay: Int = 0,
    val attemptDay: Int = 0,
    val attemptCount: Int = 0,
    val nextRetryAtMillis: Long = 0L,
)

internal enum class DailyScheduleDecision {
    DISABLED,
    BEFORE_SCHEDULE,
    RUN,
    WAIT_RETRY,
    ATTEMPTS_EXHAUSTED,
    ALREADY_SUCCESSFUL,
}

internal class DailySchedulePolicy(
    private val maxAttemptsPerDay: Int,
    private val retryDelayMs: Long,
) {
    fun decide(
        enabled: Boolean,
        minuteOfDay: Int,
        runAtMinutes: Int,
        day: Int,
        nowMillis: Long,
        state: DailyScheduleState,
    ): DailyScheduleDecision {
        if (!enabled) return DailyScheduleDecision.DISABLED
        if (minuteOfDay < runAtMinutes) return DailyScheduleDecision.BEFORE_SCHEDULE
        if (state.successfulDay == day) return DailyScheduleDecision.ALREADY_SUCCESSFUL

        val attempts = if (state.attemptDay == day) state.attemptCount else 0
        val retryAt = if (state.attemptDay == day) state.nextRetryAtMillis else 0L
        if (attempts >= maxAttemptsPerDay) return DailyScheduleDecision.ATTEMPTS_EXHAUSTED
        if (nowMillis < retryAt) return DailyScheduleDecision.WAIT_RETRY
        return DailyScheduleDecision.RUN
    }

    fun recordStarted(state: DailyScheduleState, day: Int, nowMillis: Long): DailyScheduleState {
        val previousAttempts = if (state.attemptDay == day) state.attemptCount else 0
        return state.copy(
            attemptDay = day,
            attemptCount = previousAttempts + 1,
            nextRetryAtMillis = nowMillis + retryDelayMs,
        )
    }

    fun recordFinished(state: DailyScheduleState, day: Int, success: Boolean): DailyScheduleState =
        if (success) state.copy(successfulDay = day, nextRetryAtMillis = 0L) else state
}

internal object NextRunCalculator {
    fun nextTriggerAt(nowMillis: Long, runAtMinutes: Int, timeZone: TimeZone): Long {
        val safeMinutes = runAtMinutes.coerceIn(0, 23 * 60 + 59)
        val target = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, safeMinutes / 60)
            set(Calendar.MINUTE, safeMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }
}
