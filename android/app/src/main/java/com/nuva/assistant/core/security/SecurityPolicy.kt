package com.nuva.assistant.core.security

/**
 * CLIENT SECURITY POLICY — the invariants the app itself enforces, regardless
 * of what any server says (blueprint §2.15: AI ≠ direct system control).
 *
 * 1. Only the 15 registered actions can ever execute (CommandValidator).
 * 2. Risk is recomputed locally; the model can raise it, never lower it.
 * 3. requires_confirmation ⇒ the executor BLOCKS until the user approves.
 * 4. UNSUPPORTED actions are spoken, never executed.
 * 5. No secret (Groq key, service role, Cloudinary secret) ever exists in the
 *    app — the APK only ever holds the backend URL and the public anon key.
 * 6. Screen context is data, fenced, never instructions (PromptManager).
 */
object SecurityPolicy {

    /** Keys the memory layer refuses to store — mirrors /api/memory rules. */
    private val FORBIDDEN_MEMORY_KEYS = Regex(
        "(password|passwd|secret|token|api[_-]?key|otp|pin|cvv|credit[_-]?card|private[_-]?key|seed[_-]?phrase)",
        RegexOption.IGNORE_CASE,
    )

    fun isMemoryKeyAllowed(key: String): Boolean =
        key.length in 1..120 && !FORBIDDEN_MEMORY_KEYS.containsMatchIn(key)

    /** URL guard — identical policy to the server: http/https only. */
    fun isUrlAllowed(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        val host = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
        return (scheme == "http" || scheme == "https") && host.isNotBlank()
    }

    /**
     * Settings-driven confirmation override check. There is deliberately NO
     * way to disable confirmations for medium/high risk — the mode is
     * `always` or `risky_only`, mirroring the DB constraint.
     */
    fun mustConfirm(riskLevel: com.nuva.assistant.command.NuvaRisk, confirmationModeAlways: Boolean): Boolean =
        when (confirmationModeAlways) {
            true -> true
            false -> riskLevel != com.nuva.assistant.command.NuvaRisk.LOW
        }
}
