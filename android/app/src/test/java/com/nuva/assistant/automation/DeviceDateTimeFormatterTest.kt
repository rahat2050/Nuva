package com.nuva.assistant.automation

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDateTimeFormatterTest {

    private fun fixedCalendar(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply {
        set(2026, Calendar.AUGUST, 26, 20, 7, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `banglish time and date use the supplied device clock snapshot`() {
        val calendar = fixedCalendar()
        assertEquals("Ekhon raat 8 ta 7 minute.", DeviceDateTimeFormatter.time(calendar, "banglish"))
        assertEquals("Aj Budhbar, 26 August 2026.", DeviceDateTimeFormatter.date(calendar, "banglish"))
    }

    @Test
    fun `bangla response uses bangla words and digits`() {
        val result = DeviceDateTimeFormatter.dateTime(fixedCalendar(), "bn")
        assertEquals("এখন রাত ৮টা ৭ মিনিট। আজ বুধবার, ২৬ আগস্ট ২০২৬।", result)
        assertTrue(result.none { it in '0'..'9' })
    }

    @Test
    fun `english response has unambiguous am pm and full date`() {
        val calendar = fixedCalendar()
        assertEquals("It is 8:07 PM.", DeviceDateTimeFormatter.time(calendar, "en"))
        assertEquals("Today is Wednesday, 26 August 2026.", DeviceDateTimeFormatter.date(calendar, "en"))
    }

    @Test
    fun `uptime duration formatter is deterministic and bounded`() {
        assertEquals("0 minute", DeviceStatusProvider.formatDuration(0))
        assertEquals("1 hour 30 minute", DeviceStatusProvider.formatDuration(5_400_000))
        assertEquals("2 day 3 hour 4 minute", DeviceStatusProvider.formatDuration(183_840_000))
        assertEquals("0 minute", DeviceStatusProvider.formatDuration(-1))
    }

    @Test
    fun `zero minutes are spoken naturally`() {
        val calendar = fixedCalendar().apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }
        assertEquals("Ekhon shokal 9 ta.", DeviceDateTimeFormatter.time(calendar, "banglish"))
        assertEquals("এখন সকাল ৯টা।", DeviceDateTimeFormatter.time(calendar, "bn"))
    }
}
