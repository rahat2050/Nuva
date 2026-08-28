package com.nuva.assistant.automation

import com.nuva.assistant.core.security.SensitiveAppPolicy

/**
 * Privacy boundary for text explicitly shared/selected into NUVA from another app.
 * Accepted text becomes a local editable draft only; this class never submits it.
 */
object ExternalTextHandoffPolicy {
    sealed interface Result {
        data class Accepted(val draft: String, val truncated: Boolean) : Result
        data class Blocked(val reason: String) : Result
        data object Empty : Result
    }

    fun prepare(raw: CharSequence?): Result {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isBlank()) return Result.Empty
        if (SensitiveAppPolicy.mentionsCredentials(text)) {
            return Result.Blocked("Credential-like selected text was not imported.")
        }
        if (SensitiveAppPolicy.isTransactionRequest(text)) {
            return Result.Blocked("Financial transaction text was not imported.")
        }
        val bounded = text.take(MAX_DRAFT_CHARS)
        return Result.Accepted(bounded, truncated = bounded.length < text.length)
    }

    const val MAX_DRAFT_CHARS = 1_000
}
