package com.nuva.assistant.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Serializes a locally validated [NuvaAction] for the PendingAction table, and
 * deserializes it BACK THROUGH [CommandValidator] — so a pending action that
 * somehow changed on disk can never skip re-validation.
 */
object ActionJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(action: NuvaAction): String = json.encodeToString(JsonObject.serializer(), toJson(action))

    fun decode(raw: String): NuvaAction? {
        val obj = runCatching { json.decodeFromString(JsonObject.serializer(), raw) }.getOrNull() ?: return null
        return (CommandValidator.validateAction(obj) as? CommandValidator.ValidatedAction.Valid)?.action
    }

    private fun toJson(action: NuvaAction): JsonObject = when (action) {
        is NuvaAction.OpenApp -> buildJsonObject {
            put("type", "OPEN_APP"); put("app", action.app); action.pkg?.let { put("package", it) }
        }

        is NuvaAction.CloseApp -> buildJsonObject {
            put("type", "CLOSE_APP"); put("app", action.app); action.pkg?.let { put("package", it) }
        }

        is NuvaAction.GoHome -> buildJsonObject { put("type", "GO_HOME") }
        is NuvaAction.GoBack -> buildJsonObject { put("type", "GO_BACK") }

        is NuvaAction.Tap -> buildJsonObject {
            put("type", "TAP")
            action.target?.let { put("target", selectorJson(it)) }
            action.point?.let { putJsonObject("point") { put("x", it.x); put("y", it.y) } }
            if (action.longClick) put("long_click", true)
        }

        is NuvaAction.TypeText -> buildJsonObject {
            put("type", "TYPE_TEXT"); put("text", action.text)
            action.target?.let { put("target", selectorJson(it)) }
            if (action.submit) put("submit", true)
        }

        is NuvaAction.Swipe -> buildJsonObject {
            put("type", "SWIPE")
            action.direction?.let { put("direction", it.name.lowercase()) }
            action.distance?.let { put("distance", it.name.lowercase()) }
            action.from?.let { putJsonObject("from") { put("x", it.x); put("y", it.y) } }
            action.to?.let { putJsonObject("to") { put("x", it.x); put("y", it.y) } }
        }

        is NuvaAction.Scroll -> buildJsonObject {
            put("type", "SCROLL"); put("direction", action.direction.name.lowercase())
            if (action.amount > 0) put("amount", action.amount)
            action.target?.let { put("target", selectorJson(it)) }
        }

        is NuvaAction.CallContact -> buildJsonObject {
            put("type", "CALL_CONTACT"); put("contact", action.contact)
            action.phoneNumber?.let { put("phone_number", it) }
        }

        is NuvaAction.SendMessage -> buildJsonObject {
            put("type", "SEND_MESSAGE"); put("app", action.app.wireName); put("contact", action.contact)
            put("message", action.message); action.phoneNumber?.let { put("phone_number", it) }
        }

        is NuvaAction.SetAlarm -> buildJsonObject {
            put("type", "SET_ALARM"); put("hour", action.hour); put("minute", action.minute)
            action.label?.let { put("label", it) }
            action.relativeDay?.let { put("relative_day", it.wireName) }
            action.days?.let { days -> put("days", kotlinx.serialization.json.JsonArray(days.map { JsonPrimitive(it.wireName) })) }
        }

        is NuvaAction.SetTimer -> buildJsonObject {
            put("type", "SET_TIMER"); put("duration_seconds", action.durationSeconds)
            action.label?.let { put("label", it) }
        }

        is NuvaAction.OpenUrl -> buildJsonObject { put("type", "OPEN_URL"); put("url", action.url) }
        is NuvaAction.PlayMedia -> buildJsonObject {
            put("type", "PLAY_MEDIA"); put("query", action.query); action.app?.let { put("app", it.wireName) }
        }

        is NuvaAction.ReadScreen -> buildJsonObject {
            put("type", "READ_SCREEN"); action.scope?.let { put("scope", it.wireName) }
        }

        // LOCAL-ONLY (v1.1)
        is NuvaAction.ShowRecents -> buildJsonObject { put("type", "SHOW_RECENTS") }
        is NuvaAction.SearchWeb -> buildJsonObject { put("type", "SEARCH_WEB"); put("query", action.query) }
        is NuvaAction.DeviceStatusQuery -> buildJsonObject {
            put("type", "DEVICE_STATUS"); put("query", action.query.wireName)
        }
        is NuvaAction.LocalAnswer -> buildJsonObject {
            put("type", "LOCAL_ANSWER"); put("answer", action.answer); put("category", action.category)
        }
        is NuvaAction.ReadSavedItems -> buildJsonObject {
            put("type", "READ_SAVED_ITEMS"); put("kind", action.kind.wireName)
        }
        is NuvaAction.UserFile -> buildJsonObject {
            put("type", "USER_FILE"); put("operation", action.operation.wireName)
        }
        is NuvaAction.ComposeEmail -> buildJsonObject {
            put("type", "COMPOSE_EMAIL")
            action.recipient?.let { put("recipient", it) }
            action.subject?.let { put("subject", it) }
            action.body?.let { put("body", it) }
        }
        is NuvaAction.ReplyNotification -> buildJsonObject {
            put("type", "REPLY_NOTIFICATION"); put("ordinal", action.ordinal); put("message", action.message)
        }
        is NuvaAction.OpenSettingScreen -> buildJsonObject {
            put("type", "OPEN_SETTING"); put("target", action.target.wireName)
        }
        is NuvaAction.ReadNotifications -> buildJsonObject { put("type", "READ_NOTIFICATIONS") }
        is NuvaAction.SetReminder -> buildJsonObject {
            put("type", "SET_REMINDER"); put("title", action.title)
            action.whenMillis?.let { put("when_millis", it) }
            action.humanWhen?.let { put("human_when", it) }
        }
        is NuvaAction.CreateNote -> buildJsonObject { put("type", "CREATE_NOTE"); put("content", action.content) }
        is NuvaAction.CreateTodo -> buildJsonObject { put("type", "CREATE_TODO"); put("content", action.content) }
        is NuvaAction.MediaControl -> buildJsonObject {
            put("type", "MEDIA_CONTROL"); put("command", action.command.wireName)
        }
        is NuvaAction.VolumeControl -> buildJsonObject {
            put("type", "VOLUME_CONTROL"); put("command", action.command.wireName)
        }
        is NuvaAction.CameraOpen -> buildJsonObject {
            put("type", "CAMERA"); put("mode", action.mode.wireName)
        }
        is NuvaAction.OpenChat -> buildJsonObject {
            put("type", "OPEN_CHAT"); put("app", action.app.wireName); put("contact", action.contact)
            action.phoneNumber?.let { put("phone_number", it) }
        }
        is NuvaAction.Press -> buildJsonObject {
            put("type", "PRESS"); action.label?.let { put("label", it) }
        }
        is NuvaAction.ClearText -> buildJsonObject { put("type", "CLEAR_TEXT") }
        is NuvaAction.OpenNotificationShade -> buildJsonObject { put("type", "OPEN_NOTIFICATIONS") }
        is NuvaAction.OpenNotificationApp -> buildJsonObject {
            put("type", "OPEN_NOTIFICATION_APP"); put("ordinal", action.ordinal)
        }
        is NuvaAction.DescribeScreen -> buildJsonObject { put("type", "DESCRIBE_SCREEN") }
    }

    private fun selectorJson(selector: UiSelector): JsonObject = buildJsonObject {
        selector.resourceId?.let { put("resource_id", it) }
        selector.contentDescription?.let { put("content_description", it) }
        selector.text?.let { put("text", it) }
        selector.className?.let { put("class_name", it) }
        selector.index?.let { put("index", it) }
    }
}
