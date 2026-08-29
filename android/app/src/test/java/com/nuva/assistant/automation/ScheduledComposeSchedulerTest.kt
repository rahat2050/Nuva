package com.nuva.assistant.automation

import com.nuva.assistant.command.ComposeRecurrence
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledComposeSchedulerTest {

    private fun now(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply {
        set(2026, Calendar.AUGUST, 26, 20, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `tomorrow is always the next calendar day`() {
        val trigger = ScheduledComposeScheduler.nextTrigger("kal shokal 9 tay", 9, 0, now())
        val result = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply { timeInMillis = trigger }
        assertEquals(27, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `past time rolls forward and future time stays today`() {
        val past = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply {
            timeInMillis = ScheduledComposeScheduler.nextTrigger("8 tay", 8, 0, now())
        }
        assertEquals(27, past.get(Calendar.DAY_OF_MONTH))

        val future = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply {
            timeInMillis = ScheduledComposeScheduler.nextTrigger("raat 10 tay", 22, 0, now())
        }
        assertEquals(26, future.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, future.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `recurring trigger advances past missed periods`() {
        val day = 86_400_000L
        assertEquals(4 * day, ScheduledComposeScheduler.nextRecurringTrigger(day, ComposeRecurrence.DAILY, 3 * day))
        assertEquals(8 * day, ScheduledComposeScheduler.nextRecurringTrigger(day, ComposeRecurrence.WEEKLY, 3 * day))
    }

    @Test
    fun `weekday scheduling chooses the next requested weekday`() {
        val trigger = ScheduledComposeScheduler.nextTrigger("shukrobar 9 tay", 9, 0, now())
        val result = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply { timeInMillis = trigger }
        assertEquals(Calendar.FRIDAY, result.get(Calendar.DAY_OF_WEEK))
        assertTrue(trigger > now().timeInMillis)
    }
}
