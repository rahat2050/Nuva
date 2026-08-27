package com.nuva.assistant.command

/**
 * The 15 registered AI actions — an exact mirror of the frozen backend
 * registry (backend/types/action.ts). THE WHITELIST: anything not in this enum
 * is never executed, no matter what the model said.
 *
 * v1.1 adds LOCAL-ONLY intents (localOnly = true): practical phone utilities
 * that are produced exclusively by the on-device parser and typed input.
 * [fromWire] deliberately refuses to resolve them, so no server response —
 * however malformed or malicious — can ever trigger a local-only action.
 */
enum class NuvaIntent(val wireName: String, val localOnly: Boolean = false) {
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

    // --- LOCAL-ONLY (v1.1): never resolvable from server/AI wire payloads ---
    SHOW_RECENTS("SHOW_RECENTS", localOnly = true),
    SEARCH_WEB("SEARCH_WEB", localOnly = true),
    DEVICE_STATUS("DEVICE_STATUS", localOnly = true),
    OPEN_SETTING("OPEN_SETTING", localOnly = true),
    READ_NOTIFICATIONS("READ_NOTIFICATIONS", localOnly = true),
    SET_REMINDER("SET_REMINDER", localOnly = true),
    CREATE_NOTE("CREATE_NOTE", localOnly = true),
    CREATE_TODO("CREATE_TODO", localOnly = true),
    MEDIA_CONTROL("MEDIA_CONTROL", localOnly = true),
    VOLUME_CONTROL("VOLUME_CONTROL", localOnly = true),
    CAMERA("CAMERA", localOnly = true),
    OPEN_CHAT("OPEN_CHAT", localOnly = true),
    PRESS("PRESS", localOnly = true),
    CLEAR_TEXT("CLEAR_TEXT", localOnly = true),
    OPEN_NOTIFICATIONS("OPEN_NOTIFICATIONS", localOnly = true),
    OPEN_NOTIFICATION_APP("OPEN_NOTIFICATION_APP", localOnly = true),
    DESCRIBE_SCREEN("DESCRIBE_SCREEN", localOnly = true),
    /** Deterministic calculator/converter/daily-utility result; never accepted from the server. */
    LOCAL_ANSWER("LOCAL_ANSWER", localOnly = true),
    READ_SAVED_ITEMS("READ_SAVED_ITEMS", localOnly = true),
    /** User-present Android picker workflow; never accepted from server/AI. */
    USER_FILE("USER_FILE", localOnly = true),
    COMPOSE_EMAIL("COMPOSE_EMAIL", localOnly = true),
    REPLY_NOTIFICATION("REPLY_NOTIFICATION", localOnly = true),
    PREPARE_FORM("PREPARE_FORM", localOnly = true),
    SCHEDULE_COMPOSE("SCHEDULE_COMPOSE", localOnly = true),
    LIST_SCHEDULED_DRAFTS("LIST_SCHEDULED_DRAFTS", localOnly = true),
    CANCEL_SCHEDULED_DRAFT("CANCEL_SCHEDULED_DRAFT", localOnly = true),
    SHARE_TEXT("SHARE_TEXT", localOnly = true),
    CREATE_CONTACT_DRAFT("CREATE_CONTACT_DRAFT", localOnly = true),
    MANAGE_NOTIFICATION("MANAGE_NOTIFICATION", localOnly = true),
    CONTACT_HANDOFF("CONTACT_HANDOFF", localOnly = true),
    UNINSTALL_APP("UNINSTALL_APP", localOnly = true),
    OPEN_APP_MANAGEMENT("OPEN_APP_MANAGEMENT", localOnly = true),
    ;

    companion object {
        /**
         * UNSUPPORTED is deliberately NOT an intent here — it can never execute.
         * Local-only intents are also not resolvable from the wire: the AI
         * registry stays frozen at the 15 actions above.
         */
        fun fromWire(value: String?): NuvaIntent? =
            entries.firstOrNull { !it.localOnly && it.wireName == value }
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
    NuvaIntent.COMPOSE_EMAIL,
    NuvaIntent.REPLY_NOTIFICATION,
    NuvaIntent.PREPARE_FORM,
    NuvaIntent.SCHEDULE_COMPOSE,
    NuvaIntent.CANCEL_SCHEDULED_DRAFT,
    NuvaIntent.SHARE_TEXT,
    NuvaIntent.CREATE_CONTACT_DRAFT,
    NuvaIntent.MANAGE_NOTIFICATION,
    NuvaIntent.CONTACT_HANDOFF,
    NuvaIntent.UNINSTALL_APP,
    // Policy §37: calendar edits always confirm (we prefill, the user saves).
    NuvaIntent.SET_REMINDER,
    -> NuvaRisk.MEDIUM

    else -> NuvaRisk.LOW
}
