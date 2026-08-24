package com.nuva.assistant.command

/**
 * The 15 registered NUVA actions — an exact mirror of the frozen backend
 * registry (backend/types/action.ts). THE WHITELIST: anything not in this enum
 * is never executed, no matter what the model said.
 */
enum class NuvaIntent(val wireName: String) {
    OPEN_APP("OPEN_APP"),
    CLOSE_APP("CLOSE_APP"),
    GO_HOME("GO_HOME"),
    GO_BACK("GO_BACK"),
    TAP("TAP"),
    TYPE_TEXT("TYPE_TEXT"),
    SWIPE("SWIPE"),
    SCROLL("SCROLL"),
    CALL_CONTACT("CALL_CONTACT"),
    SEND_MESSAGE("SEND_MESSAGE"),
    SET_ALARM("SET_ALARM"),
    SET_TIMER("SET_TIMER"),
    OPEN_URL("OPEN_URL"),
    PLAY_MEDIA("PLAY_MEDIA"),
    READ_SCREEN("READ_SCREEN"),
    ;

    companion object {
        /** UNSUPPORTED is deliberately NOT an intent here — it can never execute. */
        fun fromWire(value: String?): NuvaIntent? = entries.firstOrNull { it.wireName == value }
    }
}

/** Risk tiers — must match the server's RISK_LEVELS. */
enum class NuvaRisk { LOW, MEDIUM, HIGH;

    companion object {
        fun fromWire(value: String?): NuvaRisk = when (value?.lowercase()) {
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> LOW
        }
    }
}

/** Registry baseline risk — the client recomputes this itself (defence in depth). */
fun baselineRisk(intent: NuvaIntent): NuvaRisk = when (intent) {
    NuvaIntent.CALL_CONTACT,
    NuvaIntent.SEND_MESSAGE,
    -> NuvaRisk.MEDIUM

    else -> NuvaRisk.LOW
}
