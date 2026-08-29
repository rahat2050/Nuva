package com.nuva.assistant.command

/**
 * History display statuses (v1.6, Phase 20): the canonical set the product
 * spec requires — SUCCESS / FAILED / CANCELLED / UNSUPPORTED / BLOCKED /
 * CONFIRMATION_REQUIRED — mapped from the internal lifecycle statuses stored
 * in Room (which also mirror the backend command-status constraint and stay
 * unchanged). Pure Kotlin → unit-tested.
 */
object HistoryStatus {

    val DISPLAY_STATUSES = listOf(
        "SUCCESS",
        "FAILED",
        "CANCELLED",
        "UNSUPPORTED",
        "BLOCKED",
        "CONFIRMATION_REQUIRED",
    )

    fun display(internal: String): String = when (internal) {
        "completed" -> "SUCCESS"
        "failed" -> "FAILED"
        "rejected", "cancelled", "expired" -> "CANCELLED"
        "unsupported" -> "UNSUPPORTED"
        "blocked" -> "BLOCKED"
        "pending_confirmation" -> "CONFIRMATION_REQUIRED"
        "pending_choice" -> "CONFIRMATION_REQUIRED"
        "confirmed" -> "CONFIRMATION_REQUIRED"
        "ready", "executing", "pending" -> "CONFIRMATION_REQUIRED"
        else -> internal.uppercase()
    }

    /** Which display rows each history filter should show. */
    fun matchesFilter(internal: String, filter: String): Boolean = when (filter) {
        "all" -> true
        "completed" -> display(internal) == "SUCCESS"
        "failed" -> display(internal) in listOf("FAILED", "BLOCKED", "UNSUPPORTED")
        "pending" -> display(internal) == "CONFIRMATION_REQUIRED"
        else -> true
    }
}
