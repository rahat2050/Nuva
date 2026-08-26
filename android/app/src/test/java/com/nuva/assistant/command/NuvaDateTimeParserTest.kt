package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** Bangla/Banglish/English date-time parsing (v1.1). */
class NuvaDateTimeParserTest {

    // --- Clock times ---------------------------------------------------------------

    @Test
    fun `clock times in three scripts`() {
        assertEquals(ParsedTime(7, 0), NuvaDateTimeParser.parseTime("shokal 7 tay"))
        assertEquals(ParsedTime(7, 0), NuvaDateTimeParser.parseTime("সকাল ৭টায়"))
        assertEquals(ParsedTime(19, 30), NuvaDateTimeParser.parseTime("7:30 pm"))
        assertEquals(ParsedTime(7, 30), NuvaDateTimeParser.parseTime("7:30 am"))
        assertEquals(ParsedTime(20, 0), NuvaDateTimeParser.parseTime("raat 8 tay"))
        assertEquals(ParsedTime(14, 0), NuvaDateTimeParser.parseTime("dupur 2 tay"))
        assertEquals(ParsedTime(16, 30), NuvaDateTimeParser.parseTime("bikal 4:30"))
    }

    @Test
    fun `hour plus minute bangla form`() {
        assertEquals(ParsedTime(14, 30), NuvaDateTimeParser.parseTime("দুপুর ২টা ৩০ মিনিটে"))
        assertEquals(ParsedTime(6, 15), NuvaDateTimeParser.parseTime("shokal 6 tay 15 minit"))
    }

    @Test
    fun `noon and midnight edges`() {
        assertEquals(ParsedTime(12, 0), NuvaDateTimeParser.parseTime("12 pm"))
        assertEquals(ParsedTime(0, 0), NuvaDateTimeParser.parseTime("12 am"))
        assertEquals(ParsedTime(0, 0), NuvaDateTimeParser.parseTime("shokal 12 tay"))
        assertEquals(ParsedTime(12, 0), NuvaDateTimeParser.parseTime("dupur 12 tay"))
    }

    @Test
    fun `twenty four hour times stay as they are`() {
        assertEquals(ParsedTime(18, 45), NuvaDateTimeParser.parseTime("18:45"))
        assertEquals(ParsedTime(22, 0), NuvaDateTimeParser.parseTime("raat 10 tay"))
    }

    @Test
    fun `no time returns null`() {
        assertNull(NuvaDateTimeParser.parseTime("ajke bhalo din"))
        assertNull(NuvaDateTimeParser.parseTime(""))
    }

    // --- Relative days ---------------------------------------------------------------

    @Test
    fun `relative days in three scripts`() {
        assertEquals(RelativeDay.TODAY, NuvaDateTimeParser.relativeDay("aj kaj ache"))
        assertEquals(RelativeDay.TODAY, NuvaDateTimeParser.relativeDay("আজ কাজ আছে"))
        assertEquals(RelativeDay.TOMORROW, NuvaDateTimeParser.relativeDay("kal shokal"))
        assertEquals(RelativeDay.TOMORROW, NuvaDateTimeParser.relativeDay("কাল সকালে"))
    }

    // --- Weekdays ---------------------------------------------------------------------

    @Test
    fun `weekdays in three scripts`() {
        assertEquals(Weekday.SAT, NuvaDateTimeParser.weekday("shonibar alarm"))
        assertEquals(Weekday.SAT, NuvaDateTimeParser.weekday("শনিবার"))
        assertEquals(Weekday.FRI, NuvaDateTimeParser.weekday("friday"))
        assertEquals(Weekday.SUN, NuvaDateTimeParser.weekday("robibar"))
    }

    // --- Durations ---------------------------------------------------------------------

    @Test
    fun `durations in three scripts`() {
        assertEquals(600L, NuvaDateTimeParser.parseDuration("10 minute"))
        assertEquals(7200L, NuvaDateTimeParser.parseDuration("2 ghonta"))
        assertEquals(5400L, NuvaDateTimeParser.parseDuration("1 ghonta 30 minute"))
        assertEquals(1800L, NuvaDateTimeParser.parseDuration("আধা ঘণ্টা"))
        assertEquals(2700L, NuvaDateTimeParser.parseDuration("45 মিনিট"))
        assertEquals(90L, NuvaDateTimeParser.parseDuration("90 second"))
        assertNull(NuvaDateTimeParser.parseDuration("bhalo din"))
    }

    @Test
    fun `mixed banglish duration words`() {
        assertEquals(1800L, NuvaDateTimeParser.parseDuration("adha ghonta"))
        assertEquals(600L, NuvaDateTimeParser.parseDuration("10 minit"))
        assertEquals(3600L, NuvaDateTimeParser.parseDuration("ek ghonta".replace("ek", "1")))
    }

    // --- Next occurrence ------------------------------------------------------------------

    @Test
    fun `next occurrence rolls to tomorrow when already passed`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }
        val next = NuvaDateTimeParser.nextOccurrence(ParsedTime(7, 0), now)
        assertEquals(7, next.get(Calendar.HOUR_OF_DAY))
        // 23:30 → 07:00 must be in the future.
        assert(next.timeInMillis > now.timeInMillis)
    }
}
