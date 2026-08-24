package com.nuva.assistant.command

/**
 * OFFLINE FALLBACK PARSER — the client-side mirror of the backend's
 * deterministic parser (backend/lib/fallbackParser.ts).
 *
 * Used ONLY when the network is unavailable, for a small set of simple
 * low-risk commands, so basic device control keeps working offline
 * (blueprint §2.21). Everything else must wait for the AI.
 */
object CommandParser {

    private val WAKE_WORD = Regex("^\\s*(nuva|নুভা)\\s*[,.!]?\\s*", RegexOption.IGNORE_CASE)

    private val APP_ALIASES: Map<String, String> = mapOf(
        "youtube" to "youtube",
        "ইউটিউব" to "youtube",
        "whatsapp" to "whatsapp",
        "হোয়াটসঅ্যাপ" to "whatsapp",
        "facebook" to "facebook",
        "ফেসবুক" to "facebook",
        "chrome" to "chrome",
        "browser" to "browser",
        "ব্রাউজার" to "browser",
        "camera" to "camera",
        "ক্যামেরা" to "camera",
        "calculator" to "calculator",
        "google maps" to "google maps",
        "ম্যাপ" to "google maps",
        "settings" to "settings",
    )

    data class OfflineResult(
        val decision: CommandDecision,
    )

    /** Returns null when the utterance is not one of the offline-supported patterns. */
    // (OfflineResult is kept as the documented shape future callers consume.)
    fun parse(rawText: String): CommandDecision? {
        val text = WAKE_WORD.replace(rawText, "").trim().lowercase()
        if (text.isEmpty()) return null

        // GO_HOME
        if (listOf("home e jao", "go home", "home e cholo", "হোমে যাও", "হোম স্ক্রিনে যাও")
                .any { text.contains(it) }
        ) {
            return decision(NuvaAction.GoHome, "Home e jacchi.")
        }

        // GO_BACK
        if (listOf("back jao", "go back", "back koro", "পিছনে যাও", "পিছনে চলো")
                .any { text.contains(it) }
        ) {
            return decision(NuvaAction.GoBack, "Pichone jacchi.")
        }

        // SET_TIMER — "10 minute er timer" / "set a timer for 10 minutes" / "১০ মিনিট টাইমার"
        val timer = Regex("(\\d+)\\s*(minute|min|মিনিট)").find(text)
        if (timer != null && (text.contains("timer") || text.contains("টাইমার"))) {
            val minutes = timer.groupValues[1].toIntOrNull() ?: return null
            if (minutes in 1..1440) {
                return decision(
                    NuvaAction.SetTimer(minutes * 60L, null),
                    "$minutes minute er timer set korchi.",
                )
            }
        }

        // OPEN_APP — "youtube open koro" / "youtube khulo" / "ইউটিউব খোলো"
        val openPhrases = listOf("open", "khulo", "khule dao", "খোলো", "খুলে দাও", "চালাও")
        if (openPhrases.any { text.contains(it) }) {
            for ((phrase, app) in APP_ALIASES) {
                if (text.contains(phrase)) {
                    return decision(NuvaAction.OpenApp(app, null), "${app.replaceFirstChar { it.uppercase() }} khulchi.")
                }
            }
        }

        return null
    }

    private fun decision(action: NuvaAction, speech: String): CommandDecision = CommandDecision(
        intent = action.intent,
        action = action,
        unsupported = false,
        risk = NuvaRisk.LOW,
        requiresConfirmation = false,
        speech = speech,
        reasons = listOf("parsed offline"),
        commandId = null,
        source = "offline",
    )
}
