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

    /** A bounded answer calculated locally by [DailyUtilityParser]. */
    data class LocalAnswer(val answer: String, val category: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.LOCAL_ANSWER
    }

    data class ReadSavedItems(val kind: SavedItemKind) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.READ_SAVED_ITEMS
    }

    /** Opens a system picker; the user always chooses the concrete file/media. */
    data class UserFile(val operation: UserFileOperation, val newName: String? = null) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.USER_FILE
    }

    /** Opens an email composer; the user reviews and taps Send. */
    data class ComposeEmail(
        val recipient: String?,
        val subject: String?,
        val body: String?,
        val attachmentRequested: Boolean = false,
        val multipleAttachments: Boolean = false,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.COMPOSE_EMAIL
    }

    /** Sends through a notification's official RemoteInput action after confirmation. */
    data class ReplyNotification(val ordinal: Int, val message: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.REPLY_NOTIFICATION
    }

    data class PrepareForm(val kind: FormKind, val details: String?) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.PREPARE_FORM
    }

    data class ScheduleCompose(
        val channel: ComposeChannel,
        val recipient: String?,
        val subject: String?,
        val body: String,
        val triggerAt: Long,
        val recurrence: ComposeRecurrence = ComposeRecurrence.ONCE,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SCHEDULE_COMPOSE
    }

    data object ListScheduledDrafts : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.LIST_SCHEDULED_DRAFTS
    }

    data class CancelScheduledDraft(val ordinal: Int) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CANCEL_SCHEDULED_DRAFT
    }

    data class ShareText(val text: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.SHARE_TEXT
    }

    data class CreateContactDraft(
        val name: String,
        val phone: String?,
        val email: String?,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CREATE_CONTACT_DRAFT
    }

    data class ManageNotification(
        val ordinal: Int,
        val operation: NotificationManageOperation,
    ) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.MANAGE_NOTIFICATION
    }

    data class ContactHandoff(val operation: ContactHandoffOperation) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.CONTACT_HANDOFF
    }

    data class UninstallApp(val app: String) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.UNINSTALL_APP
    }

    data class OpenAppManagement(val app: String, val panel: AppManagementPanel) : NuvaAction {
        override val intent: NuvaIntent get() = NuvaIntent.OPEN_APP_MANAGEMENT
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

enum class AppManagementPanel(val wireName: String) {
    APP_INFO("app_info"),
    NOTIFICATIONS("notifications"),
    PLAY_STORE("play_store"),
    ;

    companion object {
        fun fromWire(value: String?): AppManagementPanel? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class ContactHandoffOperation(val wireName: String) {
    VIEW("view"),
    EDIT("edit"),
    ;

    companion object {
        fun fromWire(value: String?): ContactHandoffOperation? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class NotificationManageOperation(val wireName: String) {
    DISMISS("dismiss"),
    MARK_READ("mark_read"),
    ;

    companion object {
        fun fromWire(value: String?): NotificationManageOperation? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class ComposeChannel(val wireName: String) {
    EMAIL("email"),
    SMS("sms"),
    ;

    companion object {
        fun fromWire(value: String?): ComposeChannel? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class ComposeRecurrence(val wireName: String, val days: Int) {
    ONCE("once", 0),
    DAILY("daily", 1),
    WEEKLY("weekly", 7),
    ;

    companion object {
        fun fromWire(value: String?): ComposeRecurrence? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class FormKind(val wireName: String, val searchLabel: String) {
    PASSPORT("passport", "Bangladesh official passport application"),
    NID("nid", "Bangladesh official NID service"),
    BIRTH_REGISTRATION("birth_registration", "Bangladesh official birth registration"),
    DRIVING_LICENSE("driving_license", "Bangladesh official driving license application"),
    VISA("visa", "official visa application"),
    ADMISSION("admission", "official admission application"),
    JOB("job", "official job application portal"),
    DOCTOR("doctor", "doctor appointment booking"),
    HOTEL("hotel", "hotel booking form"),
    FLIGHT("flight", "flight booking form"),
    COURIER("courier", "courier pickup booking form"),
    ;

    companion object {
        fun fromWire(value: String?): FormKind? = entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

enum class SavedItemKind(val wireName: String) {
    TODO("todo"),
    NOTE("note"),
    SHOPPING("shopping"),
    EXPENSE("expense"),
    ;

    companion object {
        fun fromWire(value: String?): SavedItemKind? =
            entries.firstOrNull { it.wireName == value?.lowercase() }
    }
}

/** User-present Storage Access Framework / media-picker operations. */
enum class UserFileOperation(val wireName: String, val mimeType: String, val usesFolderPicker: Boolean = false) {
    OPEN_FILE("open_file", "*/*"),
    SHARE_FILE("share_file", "*/*"),
    READ_TEXT("read_text", "text/*"),
    OPEN_FOLDER("open_folder", "*/*", usesFolderPicker = true),
    PICK_PHOTO("pick_photo", "image/*"),
    SHARE_PHOTO("share_photo", "image/*"),
    PICK_VIDEO("pick_video", "video/*"),
    SHARE_VIDEO("share_video", "video/*"),
    SHARE_MULTIPLE_FILES("share_multiple_files", "*/*"),
    SHARE_MULTIPLE_PHOTOS("share_multiple_photos", "image/*"),
    SHARE_MULTIPLE_VIDEOS("share_multiple_videos", "video/*"),
    EMAIL_ATTACHMENT("email_attachment", "*/*"),
    EMAIL_ATTACHMENTS("email_attachments", "*/*"),
    RENAME_FILE("rename_file", "*/*"),
    COPY_FILE("copy_file", "*/*"),
    MOVE_FILE("move_file", "*/*"),
    DELETE_FILE("delete_file", "*/*"),
    EDIT_PHOTO("edit_photo", "image/*"),
    ;

    val sharesOutsideDevice: Boolean
        get() = this == SHARE_FILE || this == SHARE_PHOTO || this == SHARE_VIDEO ||
            this == SHARE_MULTIPLE_FILES || this == SHARE_MULTIPLE_PHOTOS || this == SHARE_MULTIPLE_VIDEOS ||
            this == EMAIL_ATTACHMENT || this == EMAIL_ATTACHMENTS

    val usesMultiplePicker: Boolean
        get() = this == SHARE_MULTIPLE_FILES || this == SHARE_MULTIPLE_PHOTOS ||
            this == SHARE_MULTIPLE_VIDEOS || this == EMAIL_ATTACHMENTS

    val changesSelectedContent: Boolean
        get() = this == RENAME_FILE || this == MOVE_FILE || this == DELETE_FILE || this == EDIT_PHOTO

    val needsWriteGrant: Boolean
        get() = this == OPEN_FOLDER || this == RENAME_FILE || this == COPY_FILE || this == MOVE_FILE || this == DELETE_FILE || this == EDIT_PHOTO

    val needsBlockingConfirmation: Boolean
        get() = sharesOutsideDevice || changesSelectedContent || this == COPY_FILE || this == OPEN_FOLDER

    companion object {
        fun fromWire(value: String?): UserFileOperation? =
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
    MOBILE_DATA("mobile_data"),
    AIRPLANE_MODE("airplane_mode"),
    LOCATION("location"),
    HOTSPOT("hotspot"),
    NFC("nfc"),
    VPN("vpn"),
    BATTERY_SAVER("battery_saver"),
    DEFAULT_APPS("default_apps"),
    DATE_TIME("date_time"),
    LANGUAGE("language"),
    STORAGE_SETTINGS("storage_settings"),
    PRIVACY("privacy"),
    SECURITY("security"),
    CAST("cast"),
    PRINT("print"),
    CAPTIONS("captions"),
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
