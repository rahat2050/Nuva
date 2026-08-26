package com.nuva.assistant.service

/**
 * Wake-popup session state machine (v1.4), extracted so the lifecycle is
 * unit-testable without Android:
 *
 *   IDLE ── verified "Hey Nuva" ──▶ ACTIVE(followUps = N)
 *   ACTIVE ── command success ────▶ ACTIVE(followUps - 1)   (conversational flow)
 *   ACTIVE ── command failed ─────▶ REARM                   (safe stop)
 *   ACTIVE ── follow-ups used ────▶ REARM                   (back to wake-only)
 *   ACTIVE ── user dismissed ─────▶ REARM
 *
 * §13: the popup NEVER transitions to ACTIVE without a verified wake event —
 * there is no other path in.
 */
class WakeSessionState(private val maxFollowUps: Int = DEFAULT_MAX_FOLLOW_UPS) {

    enum class Phase { IDLE, ACTIVE, REARM }

    var phase: Phase = Phase.IDLE
        private set
    var followUpsLeft: Int = 0
        private set

    /** Only a verified wake event activates the session. */
    fun onVerifiedWake() {
        phase = Phase.ACTIVE
        followUpsLeft = maxFollowUps
    }

    /** A command finished; true when the session may accept a follow-up. */
    fun onCommandFinished(success: Boolean): Boolean {
        if (phase != Phase.ACTIVE) return false
        if (!success) {
            phase = Phase.REARM
            followUpsLeft = 0
            return false
        }
        if (followUpsLeft <= 0) {
            phase = Phase.REARM
            return false
        }
        followUpsLeft--
        return true
    }

    fun onDismissed() {
        phase = Phase.REARM
        followUpsLeft = 0
    }

    /** Called once the wake listener is re-armed; back to the resting state. */
    fun onReArmed() {
        phase = Phase.IDLE
    }

    companion object {
        const val DEFAULT_MAX_FOLLOW_UPS = 2
    }
}
