package com.nuva.assistant.command

/**
 * Typed, validated NUVA actions — the Kotlin mirror of the backend action
 * schemas. Constructed ONLY via [com.nuva.assistant.ai.ActionParser], which
 * re-validates every field locally before an action can exist.
 */

/** Semantic UI selector; priority: resource_id → content_description → text → class_name. */
data class UiSelector(
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val className: String? = null,
    val index: Int? = null,
)

/** Screen point as a 0..1 fraction of screen size (resolution independent). */
data class ScreenPoint(val x: Float, val y: Float)

sealed interface NuvaAction {
    val intent: NuvaIntent

    data class OpenApp(val app: String, val pkg: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_APP
    }

    data class CloseApp(val app: String, val pkg: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CLOSE_APP
    }

    data object GoHome : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.GO_HOME
    }

    data object GoBack : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.GO_BACK
    }

    data class Tap(val target: UiSelector?, val point: ScreenPoint?, val longClick: Boolean) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.TAP
    }

    data class TypeText(val text: String, val target: UiSelector?, val submit: Boolean) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.TYPE_TEXT
    }

    data class Swipe(
        val direction: SwipeDirection?,
        val distance: SwipeDistance?,
        val from: ScreenPoint?,
        val to: ScreenPoint?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SWIPE
    }

    data class Scroll(val direction: SwipeDirection, val amount: Int, val target: UiSelector?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SCROLL
    }

    data class CallContact(val contact: String, val phoneNumber: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CALL_CONTACT
    }

    data class SendMessage(
        val app: MessagingApp,
        val contact: String,
        val message: String,
        val phoneNumber: String?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SEND_MESSAGE
    }

    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String?,
        val relativeDay: RelativeDay?,
        val days: List<Weekday>?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SET_ALARM
    }

    data class SetTimer(val durationSeconds: Long, val label: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SET_TIMER
    }

    data class OpenUrl(val url: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_URL
    }

    data class PlayMedia(val query: String, val app: MediaApp?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.PLAY_MEDIA
    }

    data class ReadScreen(val scope: ReadScreenScope?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.READ_SCREEN
    }
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT;

    companion object {
        fun fromWire(value: String?): SwipeDirection? = when (value?.lowercase()) {
            "up" -> UP
            "down" -> DOWN
            "left" -> LEFT
            "right" -> RIGHT
            else -> null
        }
    }
}

enum class SwipeDistance { SHORT, MEDIUM, LONG;

    companion object {
        fun fromWire(value: String?): SwipeDistance? = when (value?.lowercase()) {
            "short" -> SHORT
            "medium" -> MEDIUM
            "long" -> LONG
            else -> null
        }
    }
}

enum class MessagingApp(val wireName: String) {
    WHATSAPP("whatsapp"),
    SMS("sms"),
    TELEGRAM("telegram"),
    MESSENGER("messenger"),
    SIGNAL("signal"),
    VIBER("viber"),
    IMO("imo"),
    ;

    companion object {
        fun fromWire(value: String?): MessagingApp? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class MediaApp(val wireName: String) {
    YOUTUBE("youtube"),
    SPOTIFY("spotify"),
    LOCAL("local"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): MediaApp? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class ReadScreenScope(val wireName: String) {
    VISIBLE("visible"),
    FOCUSED("focused"),
    ALL("all"),
    ;

    companion object {
        fun fromWire(value: String?): ReadScreenScope? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class Weekday(val wireName: String) {
    MON("mon"), TUE("tue"), WED("wed"), THU("thu"), FRI("fri"), SAT("sat"), SUN("sun");

    companion object {
        fun fromWire(value: String): Weekday? = entries.firstOrNull { it.wireName == value.lowercase() }
    }
}

enum class RelativeDay(val wireName: String) {
    TODAY("today"), TOMORROW("tomorrow");

    companion object {
        fun fromWire(value: String?): RelativeDay? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/** A decision as delivered by the backend: typed action + risk + confirmation flag + speech. */
data class CommandDecision(
    val intent: NuvaIntent?,
    val action: NuvaAction?,
    val unsupported: Boolean,
    val risk: NuvaRisk,
    val requiresConfirmation: Boolean,
    val speech: String,
    val reasons: List<String>,
    val commandId: String?,
    val source: String,
)
