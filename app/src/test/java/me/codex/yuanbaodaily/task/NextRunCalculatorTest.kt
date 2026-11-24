package me.codex.yuanbaodaily.task

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class NextRunCalculatorTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun schedulesTodayBeforeConfiguredTimeAndTomorrowAfterIt() {
        val before = instant(2026, Calendar.SEPTEMBER, 26, 2, 30)
        val after = instant(2026, Calendar.SEPTEMBER, 26, 3, 1)

        assertEquals(
            instant(2026, Calendar.SEPTEMBER, 26, 3, 0),
            NextRunCalculator.nextTriggerAt(before, 3 * 60, zone),
        )
        assertEquals(
            instant(2026, Calendar.SEPTEMBER, 27, 3, 0),
            NextRunCalculator.nextTriggerAt(after, 3 * 60, zone),
        )
    }

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
