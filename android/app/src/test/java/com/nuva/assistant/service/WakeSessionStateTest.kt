package com.nuva.assistant.service

import com.nuva.assistant.service.WakeSessionState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15: wake-word state transitions, verified without a device. */
class WakeSessionStateTest {

    @Test
    fun `session starts idle and only a verified wake activates it`() {
        val s = WakeSessionState()
        assertEquals(Phase.IDLE, s.phase)
        s.onCommandFinished(success = true) // no-op outside an active session
        assertEquals(Phase.IDLE, s.phase)
        s.onVerifiedWake()
        assertEquals(Phase.ACTIVE, s.phase)
        assertEquals(WakeSessionState.DEFAULT_MAX_FOLLOW_UPS, s.followUpsLeft)
    }

    @Test
    fun `success keeps the session open until follow ups run out`() {
        val s = WakeSessionState()
        s.onVerifiedWake()
        assertTrue(s.onCommandFinished(success = true)) // follow-up 1 allowed
        assertTrue(s.onCommandFinished(success = true)) // follow-up 2 allowed
        assertFalse(s.onCommandFinished(success = true)) // used up → re-arm
        assertEquals(Phase.REARM, s.phase)
    }

    @Test
    fun `any failure immediately returns to wake-only listening`() {
        val s = WakeSessionState()
        s.onVerifiedWake()
        assertFalse(s.onCommandFinished(success = false))
        assertEquals(Phase.REARM, s.phase)
        assertEquals(0, s.followUpsLeft)
    }

    @Test
    fun `dismiss always re-arms`() {
        val s = WakeSessionState()
        s.onVerifiedWake()
        s.onDismissed()
        assertEquals(Phase.REARM, s.phase)
        s.onReArmed()
        assertEquals(Phase.IDLE, s.phase)
    }
}
