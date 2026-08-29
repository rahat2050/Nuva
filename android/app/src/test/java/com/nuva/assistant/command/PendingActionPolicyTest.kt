package com.nuva.assistant.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionPolicyTest {

    @Test
    fun `confirmation is valid only inside bounded lifetime`() {
        val now = 1_000_000L
        assertTrue(PendingActionPolicy.isFresh(now, now))
        assertTrue(PendingActionPolicy.isFresh(now - PendingActionPolicy.MAX_AGE_MS, now))
        assertFalse(PendingActionPolicy.isFresh(now - PendingActionPolicy.MAX_AGE_MS - 1L, now))
    }

    @Test
    fun `invalid or future timestamps fail closed`() {
        assertFalse(PendingActionPolicy.isFresh(0L, 1_000L))
        assertFalse(PendingActionPolicy.isFresh(-1L, 1_000L))
        assertFalse(PendingActionPolicy.isFresh(1_001L, 1_000L))
        assertFalse(PendingActionPolicy.isFresh(1_000L, 1_000L, maxAgeMs = 0L))
    }
}
