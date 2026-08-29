package com.nuva.assistant.command

/** Pure, testable lifetime rules for one-shot user confirmations. */
object PendingActionPolicy {

    const val MAX_AGE_MS: Long = 5 * 60_000L

    /**
     * Wall-clock timestamps are persisted by Room. A future timestamp is also
     * rejected: clock rollback must fail closed rather than extending consent.
     */
    fun isFresh(
        createdAt: Long,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_AGE_MS,
    ): Boolean {
        if (maxAgeMs <= 0L || createdAt <= 0L || createdAt > now) return false
        return now - createdAt <= maxAgeMs
    }
}
