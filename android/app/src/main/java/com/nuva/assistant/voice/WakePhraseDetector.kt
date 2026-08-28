package com.nuva.assistant.voice

/**
 * Small, local wake-phrase detector for the Android fallback wake loop.
 *
 * It only looks at text already produced by Android's on-device/system speech
 * recognizer. Nothing from the idle wake loop is sent to NUVA's backend or Groq;
 * cloud interpretation starts only after this detector has accepted "Hey Nuva".
 */
object WakePhraseDetector {

    data class Match(
        val phrase: String,
        val commandAfterWake: String?,
    )

    /*
     * Speech engines commonly render the made-up name "Nuva" as Nova/Niva.
     * Variants stay anchored at the beginning and still require either the
     * greeting or assistant name, keeping ordinary conversation from waking it.
     */
    private val latinWakePatterns = listOf(
        Regex(
            "^\\s*(hey|hi|hai|hay)\\s+(nuva|nova|nuba|neva|niva|noova|newva)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "^\\s*(nuva|nova|nuba|neva|niva|noova|newva)\\b",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val banglaWakePatterns = listOf(
        Regex("^\\s*(?:(হে|হেই|এই)\\s*)?(নুভা|নোভা|নুবা|নিভা)"),
        Regex("^\\s*(?:(হে|হেই|এই)\\s*)?নুভো"),
    )

    fun detect(raw: String): Match? {
        val text = raw.trim()
        if (text.isBlank()) return null

        val latin = latinWakePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.let { it.value.trim() to it.range }
        }
        if (latin != null) return latin.toMatch(text)

        val bangla = banglaWakePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.let { it.value.trim() to it.range }
        }
        return bangla?.toMatch(text)
    }

    fun containsWakePhrase(raw: String): Boolean = detect(raw) != null

    private fun Pair<String, IntRange>.toMatch(source: String): Match {
        val after = source
            .substring(second.last + 1)
            .trim()
            .trimStart(',', '.', '।', ':', ';', '-', '—')
            .trim()
        return Match(first, after.takeIf { it.isNotBlank() })
    }
}
