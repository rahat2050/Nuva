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

    // --- LOCAL-ONLY actions (v1.1) — built by CommandParser, never by the AI --

    data object ShowRecents : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SHOW_RECENTS
    }

    /** Media playback control via the active MediaSession (v1.2). */
    data class MediaControl(val command: MediaCommand) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.MEDIA_CONTROL
    }

    /** Direct volume changes — permitted by Android, no settings detour (v1.2). */
    data class VolumeControl(val command: VolumeCommand) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.VOLUME_CONTROL
    }

    /** Open the camera app in a mode; CAPTURE opens the still-capture flow (v1.2). */
    data class CameraOpen(val mode: CaptureMode) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CAMERA
    }

    /**
     * Open a specific chat in a messaging app WITHOUT sending anything
     * (v1.4). WhatsApp uses the wa.me deep link when a number is known;
     * other apps open their chat/search screen honestly. LOW risk — nothing
     * leaves the device; sending is a separate confirmed SEND_MESSAGE.
     */
    data class OpenChat(
        val app: MessagingApp,
        val contact: String,
        val phoneNumber: String?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_CHAT
    }

    /**
     * App-agnostic press (v1.5, Phase 5): "এটা press করো" / "Send button
     * chapo". [label] null ⇒ resolve the ONLY clickable on screen; several
     * matches ⇒ ASK, never guess. Executed against the CURRENT screen with
     * package + target verification.
     */
    data class Press(val label: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.PRESS
    }

    /** Clears the focused/last input field (universal accessibility action). */
    data object ClearText : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CLEAR_TEXT
    }

    /** Opens the notification shade (global accessibility action). */
    data object OpenNotificationShade : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_NOTIFICATIONS
    }

    /** Opens the app that posted the Nth (1-based, newest first) notification. */
    data class OpenNotificationApp(val ordinal: Int = 1) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_NOTIFICATION_APP
    }

    /** Speaks a UI summary built from the safe ScreenState model. */
    data object DescribeScreen : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.DESCRIBE_SCREEN
    }

    data class SearchWeb(val query: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SEARCH_WEB
    }

    data class DeviceStatusQuery(val query: DeviceStatusKind) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.DEVICE_STATUS
    }

    data class OpenSettingScreen(val target: SettingTarget) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_SETTING
    }

    data object ReadNotifications : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.READ_NOTIFICATIONS
    }

    /**
     * Reminder via the Calendar app: NUVA opens a prefilled event — the user
     * taps Save, so the calendar is never silently edited (policy §37).
     * [whenMillis] null = let the user pick the time in the calendar app.
     */
    data class SetReminder(
        val title: String,
        val whenMillis: Long?,
        val humanWhen: String?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SET_REMINDER
    }

    data class CreateNote(val content: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CREATE_NOTE
    }

    data class CreateTodo(val content: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CREATE_TODO
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

/** Media transport commands (LOCAL-ONLY intent payload, v1.2). */
enum class MediaCommand(val wireName: String) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("toggle"),
    NEXT("next"),
    PREVIOUS("previous"),
    ;

    companion object {
        fun fromWire(value: String?): MediaCommand? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/** Volume commands (LOCAL-ONLY intent payload, v1.2). */
enum class VolumeCommand(val wireName: String) {
    UP("up"),
    DOWN("down"),
    MUTE("mute"),
    ;

    companion object {
        fun fromWire(value: String?): VolumeCommand? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/**
 * Camera modes (LOCAL-ONLY intent payload, v1.2). CAPTURE launches the
 * still-capture flow on an EXPLICIT user command only — the shutter stays
 * under the user's control, NUVA never captures secretly.
 */
enum class CaptureMode(val wireName: String) {
    PHOTO("photo"),
    VIDEO("video"),
    CAPTURE("capture"),
    ;

    companion object {
        fun fromWire(value: String?): CaptureMode? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/** Device-status questions answered locally (LOCAL-ONLY intent payload). */
enum class DeviceStatusKind(val wireName: String) {
    BATTERY("battery"),
    TIME("time"),
    DATE("date"),
    /** One clock read answers combined questions without timestamps drifting. */
    DATE_TIME("date_time"),
    NETWORK("network"),
    STORAGE("storage"),
    ;

    companion object {
        fun fromWire(value: String?): DeviceStatusKind? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/**
 * System setting targets. Android restricts most direct changes by third-party
 * apps, so every target except TORCH opens the matching settings screen and
 * the user flips the switch (policy §27).
 */
enum class SettingTarget(val wireName: String) {
    TORCH("torch"),
    BRIGHTNESS("brightness"),
    VOLUME("volume"),
    DND("dnd"),
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    GENERAL_SETTINGS("general_settings"),
    NOTIFICATION_SETTINGS("notification_settings"),
    APP_SETTINGS("app_settings"),
    ACCESSIBILITY_SETTINGS("accessibility_settings"),
    ;

    companion object {
        fun fromWire(value: String?): SettingTarget? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
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
