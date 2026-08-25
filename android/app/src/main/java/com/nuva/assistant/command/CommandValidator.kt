package com.nuva.assistant.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * LOCAL RE-VALIDATION (roadmap step 7) — the server check is not the only gate.
 *
 * The Android app re-validates every action against the same rules the backend
 * enforces (strict whitelist + field checks) BEFORE execution. A decision that
 * fails here is refused even if the server somehow approved it.
 */
object CommandValidator {

    sealed interface ValidatedAction {
        data class Valid(val action: NuvaAction) : ValidatedAction
        data class Invalid(val reasons: List<String>) : ValidatedAction
    }

    private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val PHONE_PATTERN = Regex("^\\+?[0-9][0-9 \\-()]{3,23}$")
    private const val MAX_TEXT = 1000
    private const val MAX_MESSAGE = 2000

    // --- Primitive guards -----------------------------------------------------

    fun safeUrl(raw: String): String? {
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        // Scheme check BEFORE building a URL object: blocks javascript:, file:,
        // intent:, content: … without depending on a URL parser accepting them.
        val scheme = candidate.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = candidate.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return null
        return candidate
    }

    private fun JsonObject.str(field: String): String? =
        (this[field] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun JsonObject.int(field: String): Int? = this[field]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(field: String): Long? =
        (this[field] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(field: String): Boolean? = this[field]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.fraction(field: String): Float? =
        this[field]?.jsonPrimitive?.doubleOrNull?.toFloat()?.takeIf { it in 0.0f..1.0f }

    private fun selector(json: JsonObject?): UiSelector? {
        if (json == null) return null
        val resourceId = json.str("resource_id")
        val contentDescription = json.str("content_description")
        val text = json.str("text")
        val className = json.str("class_name")
        if (resourceId == null && contentDescription == null && text == null && className == null) return null
        val index = json.int("index")?.takeIf { it in 0..64 }
        return UiSelector(resourceId, contentDescription, text, className, index)
    }

    private fun point(json: JsonObject?): ScreenPoint? {
        if (json == null) return null
        val x = json.fraction("x") ?: return null
        val y = json.fraction("y") ?: return null
        return ScreenPoint(x, y)
    }

    // --- Action validation ----------------------------------------------------

    /**
     * Validates the raw action JSON against the mirrored registry rules.
     * Unknown types and malformed payloads are refused.
     *
     * NOTE: this resolves ALL intents (AI + local-only) because pending
     * actions round-trip through here. The AI wire path can still never
     * produce a local-only intent — [NuvaIntent.fromWire] refuses them and
     * ai/ActionParser checks fromWire() before validateAction is reached.
     */
    fun validateAction(actionJson: JsonObject?): ValidatedAction {
        if (actionJson == null) return ValidatedAction.Invalid(listOf("action is missing"))
        val type = actionJson.str("type")
        val intent = NuvaIntent.entries.firstOrNull { it.wireName == type }
            ?: return ValidatedAction.Invalid(listOf("action type $type is not in the NUVA registry"))

        return when (intent) {
            NuvaIntent.OPEN_APP, NuvaIntent.CLOSE_APP -> validateAppAction(intent, actionJson)
            NuvaIntent.GO_HOME -> ValidatedAction.Valid(NuvaAction.GoHome)
            NuvaIntent.GO_BACK -> ValidatedAction.Valid(NuvaAction.GoBack)
            NuvaIntent.TAP -> validateTap(actionJson)
            NuvaIntent.TYPE_TEXT -> validateTypeText(actionJson)
            NuvaIntent.SWIPE -> validateSwipe(actionJson)
            NuvaIntent.SCROLL -> validateScroll(actionJson)
            NuvaIntent.CALL_CONTACT -> validateCall(actionJson)
            NuvaIntent.SEND_MESSAGE -> validateSendMessage(actionJson)
            NuvaIntent.SET_ALARM -> validateAlarm(actionJson)
            NuvaIntent.SET_TIMER -> validateTimer(actionJson)
            NuvaIntent.OPEN_URL -> validateUrl(actionJson)
            NuvaIntent.PLAY_MEDIA -> validatePlayMedia(actionJson)
            NuvaIntent.READ_SCREEN -> validateReadScreen(actionJson)

            // LOCAL-ONLY (v1.1) — validated here so pending actions round-trip
            // safely; unreachable from the AI wire path because fromWire()
            // refuses local-only intents.
            NuvaIntent.SHOW_RECENTS -> ValidatedAction.Valid(NuvaAction.ShowRecents)
            NuvaIntent.SEARCH_WEB -> validateSearchWeb(actionJson)
            NuvaIntent.DEVICE_STATUS -> validateDeviceStatus(actionJson)
            NuvaIntent.OPEN_SETTING -> validateOpenSetting(actionJson)
            NuvaIntent.READ_NOTIFICATIONS -> ValidatedAction.Valid(NuvaAction.ReadNotifications)
            NuvaIntent.SET_REMINDER -> validateReminder(actionJson)
            NuvaIntent.CREATE_NOTE -> validateNote(actionJson)
            NuvaIntent.CREATE_TODO -> validateTodo(actionJson)
            NuvaIntent.MEDIA_CONTROL -> validateMediaControl(actionJson)
            NuvaIntent.VOLUME_CONTROL -> validateVolumeControl(actionJson)
            NuvaIntent.CAMERA -> validateCamera(actionJson)
        }
    }

    private fun validateMediaControl(json: JsonObject): ValidatedAction {
        val command = MediaCommand.fromWire(json.str("command"))
            ?: return ValidatedAction.Invalid(listOf("MEDIA_CONTROL requires a known command"))
        return ValidatedAction.Valid(NuvaAction.MediaControl(command))
    }

    private fun validateVolumeControl(json: JsonObject): ValidatedAction {
        val command = VolumeCommand.fromWire(json.str("command"))
            ?: return ValidatedAction.Invalid(listOf("VOLUME_CONTROL requires a known command"))
        return ValidatedAction.Valid(NuvaAction.VolumeControl(command))
    }

    private fun validateCamera(json: JsonObject): ValidatedAction {
        val mode = CaptureMode.fromWire(json.str("mode"))
            ?: return ValidatedAction.Invalid(listOf("CAMERA requires a known mode"))
        return ValidatedAction.Valid(NuvaAction.CameraOpen(mode))
    }

    private fun validateSearchWeb(json: JsonObject): ValidatedAction {
        val query = json.str("query")?.takeIf { it.isNotEmpty() && it.length <= 300 }
            ?: return ValidatedAction.Invalid(listOf("SEARCH_WEB requires query (1..300 chars)"))
        return ValidatedAction.Valid(NuvaAction.SearchWeb(query))
    }

    private fun validateDeviceStatus(json: JsonObject): ValidatedAction {
        val kind = DeviceStatusKind.fromWire(json.str("query"))
            ?: return ValidatedAction.Invalid(listOf("DEVICE_STATUS requires a known query kind"))
        return ValidatedAction.Valid(NuvaAction.DeviceStatusQuery(kind))
    }

    private fun validateOpenSetting(json: JsonObject): ValidatedAction {
        val target = SettingTarget.fromWire(json.str("target"))
            ?: return ValidatedAction.Invalid(listOf("OPEN_SETTING requires a known target"))
        return ValidatedAction.Valid(NuvaAction.OpenSettingScreen(target))
    }

    private fun validateReminder(json: JsonObject): ValidatedAction {
        val title = json.str("title")?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: return ValidatedAction.Invalid(listOf("SET_REMINDER requires title (1..200 chars)"))
        val whenMillis = json.long("when_millis")?.takeIf { it in 0..4_102_444_800_000L }
        val humanWhen = json.str("human_when")?.takeIf { it.length <= 120 }
        return ValidatedAction.Valid(NuvaAction.SetReminder(title, whenMillis, humanWhen))
    }

    private fun validateNote(json: JsonObject): ValidatedAction {
        val content = json.str("content")?.takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE }
            ?: return ValidatedAction.Invalid(listOf("CREATE_NOTE requires content (1..2000 chars)"))
        return ValidatedAction.Valid(NuvaAction.CreateNote(content))
    }

    private fun validateTodo(json: JsonObject): ValidatedAction {
        val content = json.str("content")?.takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE }
            ?: return ValidatedAction.Invalid(listOf("CREATE_TODO requires content (1..2000 chars)"))
        return ValidatedAction.Valid(NuvaAction.CreateTodo(content))
    }

    private fun validateAppAction(intent: NuvaIntent, json: JsonObject): ValidatedAction {
        val app = json.str("app")?.takeIf { it.isNotEmpty() && it.length <= 60 }
            ?: return ValidatedAction.Invalid(listOf("${intent.wireName} requires app (1..60 chars)"))
        val pkg = json.str("package")?.takeIf { PACKAGE_PATTERN.matches(it) }
        return ValidatedAction.Valid(
            if (intent == NuvaIntent.OPEN_APP) NuvaAction.OpenApp(app, pkg) else NuvaAction.CloseApp(app, pkg),
        )
    }

    private fun validateTap(json: JsonObject): ValidatedAction {
        val target = selector(json["target"] as? JsonObject)
        val longClick = json.bool("long_click") ?: false
        val p = point(json["point"] as? JsonObject)
        if (target == null && p == null) {
            return ValidatedAction.Invalid(listOf("TAP requires a semantic target or a point fallback"))
        }
        return ValidatedAction.Valid(NuvaAction.Tap(target, p, longClick))
    }

    private fun validateTypeText(json: JsonObject): ValidatedAction {
        val text = json.str("text")?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
            ?: return ValidatedAction.Invalid(listOf("TYPE_TEXT requires text (1..1000 chars)"))
        return ValidatedAction.Valid(NuvaAction.TypeText(text, selector(json["target"] as? JsonObject), json.bool("submit") ?: false))
    }

    private fun validateSwipe(json: JsonObject): ValidatedAction {
        val direction = SwipeDirection.fromWire(json.str("direction"))
        val from = point(json["from"] as? JsonObject)
        val to = point(json["to"] as? JsonObject)
        if (direction == null && (from == null || to == null)) {
            return ValidatedAction.Invalid(listOf("SWIPE requires a direction, or both from and to points"))
        }
        return ValidatedAction.Valid(NuvaAction.Swipe(direction, SwipeDistance.fromWire(json.str("distance")), from, to))
    }

    private fun validateScroll(json: JsonObject): ValidatedAction {
        val direction = SwipeDirection.fromWire(json.str("direction"))
            ?: return ValidatedAction.Invalid(listOf("SCROLL requires direction (up|down|left|right)"))
        val amount = json.int("amount")?.takeIf { it in 1..20 }
            ?: return ValidatedAction.Invalid(listOf("SCROLL requires amount (1..20)"))
        return ValidatedAction.Valid(NuvaAction.Scroll(direction, amount, selector(json["target"] as? JsonObject)))
    }

    private fun validateCall(json: JsonObject): ValidatedAction {
        val contact = json.str("contact")?.takeIf { it.isNotEmpty() && it.length <= 120 }
            ?: return ValidatedAction.Invalid(listOf("CALL_CONTACT requires contact"))
        val phone = json.str("phone_number")?.takeIf { PHONE_PATTERN.matches(it) }
        return ValidatedAction.Valid(NuvaAction.CallContact(contact, phone))
    }

    private fun validateSendMessage(json: JsonObject): ValidatedAction {
        val app = MessagingApp.fromWire(json.str("app"))
            ?: return ValidatedAction.Invalid(listOf("SEND_MESSAGE requires a supported app"))
        val contact = json.str("contact")?.takeIf { it.isNotEmpty() && it.length <= 120 }
            ?: return ValidatedAction.Invalid(listOf("SEND_MESSAGE requires contact"))
        val message = json.str("message")?.takeIf { it.isNotEmpty() && it.length <= MAX_MESSAGE }
            ?: return ValidatedAction.Invalid(listOf("SEND_MESSAGE requires message (1..2000 chars)"))
        val phone = json.str("phone_number")?.takeIf { PHONE_PATTERN.matches(it) }
        return ValidatedAction.Valid(NuvaAction.SendMessage(app, contact, message, phone))
    }

    private fun validateAlarm(json: JsonObject): ValidatedAction {
        val hour = json.int("hour")?.takeIf { it in 0..23 }
            ?: return ValidatedAction.Invalid(listOf("SET_ALARM requires hour (0..23)"))
        val minute = json.int("minute")?.takeIf { it in 0..59 }
            ?: return ValidatedAction.Invalid(listOf("SET_ALARM requires minute (0..59)"))
        val label = json.str("label")?.takeIf { it.length <= 120 }
        val relativeDay = RelativeDay.fromWire(json.str("relative_day"))
        val days = json["days"]?.jsonArray?.mapNotNull { el ->
            (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { Weekday.fromWire(it) }
        }?.takeIf { it.isNotEmpty() && it.size <= 7 }
        return ValidatedAction.Valid(NuvaAction.SetAlarm(hour, minute, label, relativeDay, days))
    }

    private fun validateTimer(json: JsonObject): ValidatedAction {
        val duration = json.int("duration_seconds")?.takeIf { it in 1..86_400 }
            ?: return ValidatedAction.Invalid(listOf("SET_TIMER requires duration_seconds (1..86400)"))
        val label = json.str("label")?.takeIf { it.length <= 120 }
        return ValidatedAction.Valid(NuvaAction.SetTimer(duration.toLong(), label))
    }

    private fun validateUrl(json: JsonObject): ValidatedAction {
        val raw = json.str("url")?.takeIf { it.length in 3..2048 }
            ?: return ValidatedAction.Invalid(listOf("OPEN_URL requires url"))
        val url = safeUrl(raw) ?: return ValidatedAction.Invalid(listOf("OPEN_URL allows http/https URLs only"))
        return ValidatedAction.Valid(NuvaAction.OpenUrl(url))
    }

    private fun validatePlayMedia(json: JsonObject): ValidatedAction {
        val query = json.str("query")?.takeIf { it.isNotEmpty() && it.length <= 300 }
            ?: return ValidatedAction.Invalid(listOf("PLAY_MEDIA requires query"))
        return ValidatedAction.Valid(NuvaAction.PlayMedia(query, MediaApp.fromWire(json.str("app"))))
    }

    private fun validateReadScreen(json: JsonObject): ValidatedAction {
        return ValidatedAction.Valid(NuvaAction.ReadScreen(ReadScreenScope.fromWire(json.str("scope"))))
    }

    // --- Risk recomputation ---------------------------------------------------

    private val HIGH_RISK_KEYWORDS = listOf(
        "bkash", "nagad", "rocket", "send money", "payment", "transaction", "bank",
        "password", "otp", "pin", "2fa", "seed phrase", "factory reset", "delete account",
        "বিকাশ", "নগদ", "পাসওয়ার্ড", "পেমেন্ট", "লেনদেন",
    )

    /**
     * Client-side risk floor = max(registry baseline, keyword escalation).
     * The model's risk can only ever raise this, never lower it.
     */
    fun recomputeRisk(action: NuvaAction?, unsupportedReasonText: String, modelRisk: NuvaRisk): NuvaRisk {
        var risk = action?.let { baselineRisk(it.intent) } ?: NuvaRisk.LOW
        val haystack = unsupportedReasonText.lowercase()
        if (HIGH_RISK_KEYWORDS.any { haystack.contains(it.lowercase()) }) risk = NuvaRisk.HIGH
        if (modelRisk.ordinal > risk.ordinal) risk = modelRisk
        return risk
    }

    /** Local confirmation gate — identical policy to the server. */
    fun requiresConfirmation(risk: NuvaRisk, modelAsked: Boolean): Boolean = risk != NuvaRisk.LOW || modelAsked
}
