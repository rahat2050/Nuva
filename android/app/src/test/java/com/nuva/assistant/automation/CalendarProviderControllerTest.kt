package com.nuva.assistant.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarProviderControllerTest {
    private val events = listOf(
        CalendarProviderController.Event(1, "Project Meeting", 1000, 2000, "Office", "Work", false),
        CalendarProviderController.Event(2, "Project Meeting Followup", 3000, 4000, null, "Work", false),
        CalendarProviderController.Event(3, "Dentist", 5000, 6000, "Clinic", "Personal", false),
        CalendarProviderController.Event(4, "OTP 123456", 7000, 8000, null, "Private", false),
    )

    @Test
    fun `event matching prefers exact then starts with`() {
        assertEquals(listOf(1L), CalendarProviderController.matchEvents(events, "project meeting").map { it.id })
        assertEquals(listOf(3L), CalendarProviderController.matchEvents(events, "dentist").map { it.id })
        assertEquals(listOf(1L, 2L), CalendarProviderController.matchEvents(events, "project").map { it.id })
    }

    @Test
    fun `credential titled events are excluded from matching`() {
        assertTrue(CalendarProviderController.matchEvents(events, "otp").isEmpty())
    }
}
