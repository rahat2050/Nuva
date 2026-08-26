package com.nuva.assistant.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 20: history statuses map to the required display set. */
class HistoryStatusTest {

    @Test
    fun `internal statuses map to the canonical display set`() {
        assertEquals("SUCCESS", HistoryStatus.display("completed"))
        assertEquals("FAILED", HistoryStatus.display("failed"))
        assertEquals("CANCELLED", HistoryStatus.display("rejected"))
        assertEquals("UNSUPPORTED", HistoryStatus.display("unsupported"))
        assertEquals("BLOCKED", HistoryStatus.display("blocked"))
        assertEquals("CONFIRMATION_REQUIRED", HistoryStatus.display("pending_confirmation"))
        assertEquals("CONFIRMATION_REQUIRED", HistoryStatus.display("pending_choice"))
    }

    @Test
    fun `unknown statuses surface honestly instead of vanishing`() {
        assertEquals("SOMETHINGELSE", HistoryStatus.display("somethingelse"))
    }

    @Test
    fun `filters use the display statuses`() {
        assertTrue(HistoryStatus.matchesFilter("completed", "failed").not())
        assertTrue(HistoryStatus.matchesFilter("blocked", "failed"))
        assertTrue(HistoryStatus.matchesFilter("pending_confirmation", "pending"))
        assertTrue(HistoryStatus.matchesFilter("completed", "all"))
        assertFalse(HistoryStatus.matchesFilter("completed", "pending"))
    }
}
