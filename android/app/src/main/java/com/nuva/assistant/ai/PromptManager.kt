package com.nuva.assistant.ai

/**
 * Builds the client half of the command request: language hint, device
 * identity and — in PHRASE 2 — the fenced, untrusted screen context the
 * AccessibilityService can attach to a command.
 *
 * Screen context is DATA, never instructions: the backend labels it untrusted
 * in the system prompt, and the client never lets it alter client-side rules.
 */
object PromptManager {

    data class CommandInput(
        val text: String,
        val languageHint: String = "auto",
        val foregroundApp: String? = null,
        val screenSummary: String? = null,
        val deviceId: String? = null,
    )

    fun buildRequest(input: CommandInput): CommandRequestDto = CommandRequestDto(
        text = input.text.trim().take(MAX_TRANSCRIPT_CHARS),
        language = input.languageHint,
        deviceId = input.deviceId,
        context = buildContext(input),
    )

    /** Screen summary is fenced so injected text can never read as instructions. */
    fun fenceScreenSummary(summary: String): String = buildString {
        appendLine("<<<UNTRUSTED_SCREEN_BEGIN>>>")
        appendLine(summary.take(MAX_SCREEN_SUMMARY))
        append("<<<UNTRUSTED_SCREEN_END>>>")
    }

    private fun buildContext(input: CommandInput): CommandContextDto? {
        val foreground = input.foregroundApp?.take(200)
        val summary = input.screenSummary?.take(MAX_SCREEN_SUMMARY)
        return if (foreground == null && summary == null) null else CommandContextDto(foreground, summary)
    }

    private const val MAX_TRANSCRIPT_CHARS = 1000
    private const val MAX_SCREEN_SUMMARY = 4000
}
