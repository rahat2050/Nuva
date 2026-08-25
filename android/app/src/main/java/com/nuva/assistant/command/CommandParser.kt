package com.nuva.assistant.command

import com.nuva.assistant.core.security.SensitiveAppPolicy

/**
 * OFFLINE / ON-DEVICE COMMAND PARSER v2 — Bangla, Banglish & English.
 *
 * The deterministic local brain (roadmap: "offline/simple command parser
 * আরও ভালো করো"). It runs BEFORE the network AI for cheap, reliable patterns
 * and AFTER it as a rescue path when the server marks a request unsupported
 * or the network is down. It only ever emits typed [NuvaAction]s that the
 * [CommandValidator] schema-validated — plus safe refusals for money/banking
 * requests (policy §32–§36) that must NEVER wait for a server.
 *
 * Bangla-first: every user-visible sentence is Banglish/Bangla, matching the
 * rest of the product voice.
 */
object CommandParser {

    private val WAKE_WORDS = Regex("""^\s*(nuva|নুভা|hey nuva|নুভা শোনো)\s*[,.!]?[\s]*""", RegexOption.IGNORE_CASE)

    // Language-agnostic verb/helper word lists ---------------------------------

    private val OPEN_VERBS = listOf(
        "open koro", "open korun", "open", "khule dao", "khulo", "khulun", "chalu koro",
        "চালু করো", "চালু করুন", "খোলো", "খুলে দাও", "খুলুন", "launch koro", "launch",
        "start koro", "চালাও",
    )
    private val CLOSE_VERBS = listOf(
        "close koro", "close korun", "close", "band koro", "bondho koro", "bondho korun",
        "বন্ধ করো", "বন্ধ করুন", "bandho koro", "লুকাও",
    )
    private val TAIL_VERBS = listOf(
        "koro", "korun", "korbo", "koren", "dao", "din", "diben", "diagcen", "diachen",
        "করো", "করুন", "দাও", "দিন", "দিবেন", "চালাও", "বাজাও", "পাঠাও", "পাঠান",
    )

    /** Well-known app names in the three scripts (normalized keys). */
    private val APP_ALIASES: Map<String, String> = buildMap {
        fun put(vararg keys: String, canonical: String) = keys.forEach { put(it, canonical) }
        put("youtube", "youtube", "ইউটিউব", "ইউটুব", canonical = "youtube")
        put("whatsapp", "whats app", "হোয়াটসঅ্যাপ", "হোয়াটসাপ", "hatsapp", canonical = "whatsapp")
        put("facebook", "fb", "ফেসবুক", canonical = "facebook")
        put("messenger", "মেসেঞ্জার", "fb messenger", canonical = "messenger")
        put("telegram", "টেলিগ্রাম", canonical = "telegram")
        put("chrome", "গুগল ক্রোম", canonical = "chrome")
        put("browser", "ব্রাউজার", "opera", "firefox", canonical = "browser")
        put("camera", "ক্যামেরা", "kamera", canonical = "camera")
        put("calculator", "ক্যালকুলেটর", "hishab", "হিসাব", canonical = "calculator")
        put("calendar", "ক্যালেন্ডার", canonical = "calendar")
        put("gmail", "mail", "ইমেইল", "email", canonical = "gmail")
        put("maps", "google maps", "ম্যাপ", canonical = "google maps")
        put("play store", "playstore", "প্লে স্টোর", canonical = "play store")
        put("phone", "dialer", "ফোন", canonical = "phone")
        put("contacts", "যোগাযোগ", "contact list", canonical = "contacts")
        put("gallery", "photos", "গ্যালারি", "ছবি", canonical = "gallery")
        put("spotify", "স্পটিফাই", canonical = "spotify")
        put("settings", "setting", "সেটিংস", canonical = "settings")
        put("files", "file manager", "ফাইল ম্যানেজার", "my files", canonical = "files")
        put("recorder", "voice recorder", "রেকর্ডার", canonical = "recorder")
        put("translate", "অনুবাদ", canonical = "translate")
        put("music", "গান", "gaan", canonical = "music")
    }

    private val PHONE_NUMBER = Regex("""(\+?88)?01[3-9]\d{8}|\+\d{8,15}""")

    // --- Public API -------------------------------------------------------------

    data class OfflineResult(val decision: CommandDecision)

    /**
     * Parses an utterance. Returns null when nothing locally-understandable
     * matches — the caller then falls back to the AI path.
     */
    fun parse(rawText: String): CommandDecision? {
        val normalized = NuvaDateTimeParser.normalize(rawText)
        val text = stripWakeWord(normalized)
        if (text.isBlank()) return null

        // 0) SECURITY FIRST — money/banking/credential requests are refused
        //    locally, never sent to any server (policy §32–§36).
        SensitiveAppPolicy.refusalForText(text)?.let { return refused() }
        if (SensitiveAppPolicy.mentionsCredentials(text)) {
            return unsupported("OTP, PIN ba password NUVA kochu kore na — egulo nije likhun.")
        }

        // Ordered rule table — the first hit wins.
        return parseNavigation(text)
            ?: parseScreenReading(text)
            ?: parseDeviceStatus(text)
            ?: parseSettings(text)
            ?: parseAlarm(text)
            ?: parseTimer(text)
            ?: parseReminder(text)
            ?: parseNoteTodo(text)
            ?: parseCall(text)
            ?: parseSendMessage(text)
            ?: parsePlayMedia(text)
            ?: parseWeb(text)
            ?: parseScrollSwipe(text)
            ?: parseCloseApp(text)
            ?: parseOpenApp(text)
    }

    private fun stripWakeWord(text: String): String =
        WAKE_WORDS.replace(text, "").replace(Regex("^[,.!\\s]+"), "").trim()

    /**
     * Removes a filler word/phrase. `\b` is ASCII-only in JVM regex, so Bangla
     * words are replaced plainly while ASCII words keep word boundaries.
     */
    private fun swapWord(text: String, word: String): String =
        if (word.all { it.code in 32..127 }) {
            text.replace(Regex("""\b${Regex.escape(word)}\b"""), " ")
        } else {
            text.replace(word, " ")
        }

    // --- 1. Navigation ------------------------------------------------------------

    private fun parseNavigation(t: String): CommandDecision? {
        if (listOf("home e jao", "go home", "home e cholo", "home e firi jao", "হোমে যাও", "হোম স্ক্রিনে যাও")
                .any { t.contains(it) }
        ) return ok(NuvaAction.GoHome, "Home e jacchi.")

        if (listOf("back jao", "go back", "back koro", "pichone jao", "পিছনে যাও", "পিছনে চলো", "একটু পিছনে")
                .any { t.contains(it) }
        ) return ok(NuvaAction.GoBack, "Pichone jacchi.")

        if (listOf("recent app", "recent apps", "recents", "recent dekhao", "রিসেন্ট", "রিসেন্ট অ্যাপ")
                .any { t.contains(it) }
        ) return ok(NuvaAction.ShowRecents, "Recent app guloi dekhacchi.")

        return null
    }

    // --- 2. Screen & notifications ---------------------------------------------------

    private fun parseScreenReading(t: String): CommandDecision? {
        val readScreen = listOf(
            "screen poro", "screen ta poro", "poro screen", "ki lekha ache", "kki lekha ache",
            "screen e ki ache", "এই স্ক্রিনটা পড়ো", "স্ক্রিন পড়ো", "কী লেখা আছে", "স্ক্রিনে কী আছে",
        ).any { t.contains(it) }
        if (readScreen) return ok(NuvaAction.ReadScreen(null), "Pore dicchi.")

        val readNotifications = listOf(
            "notification poro", "notification gulo poro", "notification dekhao", "notification ki eseche",
            "notification summary", "কী নোটিফিকেশন এসেছে", "নোটিফিকেশন পড়ো", "নোটিফিকেশন দেখাও",
        ).any { t.contains(it) }
        if (readNotifications) return ok(NuvaAction.ReadNotifications, "Notification porchi.")

        return null
    }

    // --- 3. Device status ---------------------------------------------------------------

    private fun parseDeviceStatus(t: String): CommandDecision? {
        val battery = listOf("battery", "battary", "charge koto", "কত চার্জ", "ব্যাটারি", "চার্জ কত")
            .any { t.contains(it) }
        if (battery) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.BATTERY), "Battery dekhe nicchi.")

        val time = listOf("কটা বাজে", "কয়টা বাজে", "somoy koto", "time koto", "koto bajche", "সময় কত", "এখন কটা")
            .any { t.contains(it) }
        if (time) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.TIME), "Somoy dekhe nicchi.")

        val date = listOf("aj kibar", "আজ কি বার", "আজ কী বার", "আজ কত তারিখ", "tarikh koto", "date koto", "আজকের তারিখ")
            .any { t.contains(it) }
        if (date) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.DATE), "Tarikh dekhe nicchi.")

        val network = listOf("internet ache", "internet on ache", "network kothay", "net ache",
            "wifi e connected", "নেটওয়ার্ক", "ইন্টারনেট আছে", "নেট আছে", "network status")
            .any { t.contains(it) }
        if (network) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.NETWORK), "Network dekhe nicchi.")

        val storage = listOf("storage", "koto jayga", "কত জায়গা", "স্টোরেজ", "memory koto", "space koto")
            .any { t.contains(it) }
        if (storage) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.STORAGE), "Storage dekhe nicchi.")

        return null
    }

    // --- 4. Settings & torch --------------------------------------------------------------

    private fun parseSettings(t: String): CommandDecision? {
        // Torch — a direct, safe, reversible toggle.
        if (listOf("torch", "flashlight", "টর্চ", "ফ্ল্যাশলাইট", "হাতলণ্ঠন").any { t.contains(it) }) {
            return ok(NuvaAction.OpenSettingScreen(SettingTarget.TORCH), "Torch toggle korchi.")
        }
        if (listOf("brightness", "উজ্জ্বলতা", "ব্রাইটনেস").any { t.contains(it) }) {
            return ok(
                NuvaAction.OpenSettingScreen(SettingTarget.BRIGHTNESS),
                "Android brightness NUVA sorasori badhate pare na — setting screen khulchi.",
            )
        }
        if (listOf("volume", "sound setting", "শব্দ কম", "শব্দ বাড়াও", "ভলিউম", "সাউন্ড").any { t.contains(it) }) {
            return ok(
                NuvaAction.OpenSettingScreen(SettingTarget.VOLUME),
                "Volume setting khulchi — apni nije adjust korun.",
            )
        }
        if (listOf("do not disturb", "disturb", "dnd", "ডিস্টার্ব").any { t.contains(it) }) {
            return ok(NuvaAction.OpenSettingScreen(SettingTarget.DND), "Do Not Disturb setting khulchi.")
        }
        if (listOf("wifi", "wi fi", "ওয়াইফাই").any { t.contains(it) }) {
            return ok(
                NuvaAction.OpenSettingScreen(SettingTarget.WIFI),
                "Wifi setting khulchi — on/off apni korun.",
            )
        }
        if (listOf("bluetooth", "ব্লুটুথ").any { t.contains(it) }) {
            return ok(NuvaAction.OpenSettingScreen(SettingTarget.BLUETOOTH), "Bluetooth setting khulchi.")
        }
        if (listOf("phone er setting", "phone settings", "system setting", "সেটিংস খোলো", "settings khulo")
                .any { t.contains(it) }
        ) {
            return ok(NuvaAction.OpenSettingScreen(SettingTarget.GENERAL_SETTINGS), "Settings khulchi.")
        }
        return null
    }

    // --- 5. Alarm / timer ------------------------------------------------------------------

    private fun parseAlarm(t: String): CommandDecision? {
        val isAlarm = listOf("alarm", "আলার্ম", "অ্যালার্ম", "ghum theke").any { t.contains(it) }
        if (!isAlarm) return null

        val time = NuvaDateTimeParser.parseTime(t) ?: return unsupported("Koto tay alarm dibo? Somoy ta bole din.")

        val daily = listOf("protidin", "pratidin", "roj", "প্রতিদিন", "রোজ", "everyday", "daily")
            .any { NuvaDateTimeParser.hasWord(t, it) }
        val days: List<Weekday>? = when {
            daily -> Weekday.entries.toList()
            else -> NuvaDateTimeParser.weekday(t)?.let { listOf(it) }
        }
        val relative = when {
            NuvaDateTimeParser.relativeDay(t) == RelativeDay.TODAY -> RelativeDay.TODAY
            NuvaDateTimeParser.relativeDay(t) == RelativeDay.TOMORROW -> RelativeDay.TOMORROW
            else -> null
        }
        val label = extractLabel(t)
        return ok(
            NuvaAction.SetAlarm(time.hour, time.minute, label, relative, days),
            "Alarm ${time.format24h()} — nishchit korun.",
            risk = NuvaRisk.LOW,
        )
    }

    private fun parseTimer(t: String): CommandDecision? {
        val isTimer = listOf("timer", "টাইমার").any { t.contains(it) }
        if (!isTimer) return null
        val seconds = NuvaDateTimeParser.parseDuration(t)
            ?: return unsupported("Koto somoyer timer? Bole din — jemon 10 minute.")
        if (seconds !in 1..86_400) return unsupported("Eto boro timer rakha jay na — 24 ghontar moddhe din.")
        return ok(NuvaAction.SetTimer(seconds, extractLabel(t)), "Timer set korar jonno ready.")
    }

    // --- 6. Reminder / calendar -----------------------------------------------------------

    private fun parseReminder(t: String): CommandDecision? {
        val isReminder = listOf(
            "reminder", "রিমাইন্ডার", "মনে করিয়ে", "mone koriye", "calendar e", "ক্যালেন্ডারে",
            "meeting rakho", "meeting boshao", "event rakho",
        ).any { t.contains(it) }
        if (!isReminder) return null

        val time = NuvaDateTimeParser.parseTime(t)
        val title = reminderTitle(t) ?: "Reminder"
        val whenMillis: Long? = time?.let {
            val now = java.util.Calendar.getInstance()
            val occ = NuvaDateTimeParser.nextOccurrence(it, now)
            if (NuvaDateTimeParser.relativeDay(t) == RelativeDay.TOMORROW &&
                occ.timeInMillis <= now.timeInMillis
            ) {
                occ.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            occ.timeInMillis
        }
        val humanWhen = listOf("kal", "কাল", "aj", "আজ", "parso", "পরশু")
            .firstOrNull { NuvaDateTimeParser.hasWord(t, it) } ?: time?.format24h()
        return ok(
            NuvaAction.SetReminder(title, whenMillis, humanWhen),
            "Reminder: $title${time?.let { " — " + it.format24h() } ?: ""}. Calendar khule dicchi.",
        )
    }

    private fun reminderTitle(t: String): String? {
        var title = t
        listOf(
            "reminder", "রিমাইন্ডার", "মনে করিয়ে দাও", "mone koriye dao", "calendar e", "ক্যালেন্ডারে",
            "rakho", "রাখো", "boshao", "meeting", "মিটিং", "event", "আমাকে", "amake",
        ).forEach { title = title.replace(it, " ") }
        // Strip time-ish tokens — the when lives in when_millis, not the title.
        title = title.replace(Regex("""\d{1,2}[:.]\d{2}"""), " ")
        title = title.replace(Regex("""\d{1,2}\s*(টায়|টার|টাতে|টা|taye|tay|ta|bajche|বাজে)(?![a-z])"""), " ")
        NuvaDateTimeParser.parseDuration(title)?.let {
            title = title.replace(Regex("""\d+\s*(din|day|ঘণ্টা|ঘন্টা|মিনিট|minute|second|সেকেন্ড)\w*"""), " ")
        }
        listOf("kal", "কাল", "aj", "আজ", "shokal", "সকাল", "raat", "রাত", "dupur", "দুপুর", "bikal", "বিকাল", "parso", "পরশু")
            .forEach { title = swapWord(title, it) }
        TAIL_VERBS.forEach { title = swapWord(title, it) }
        val cleaned = title.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
        return cleaned.takeIf { it.length in 2..200 }
    }

    // --- 7. Notes & to-dos ----------------------------------------------------------------

    private fun parseNoteTodo(t: String): CommandDecision? {
        // To-do: "todo te add koro X" / "kaj list e X"
        val todoMarker = listOf(
            "todo te", "to do te", "todo list e", "kaj er list e", "kaj list e", "টুডু",
        ).firstOrNull { t.contains(it) }
        if (todoMarker != null) {
            val content = contentAfter(t, todoMarker)
            if (content.isNullOrBlank()) return unsupported("Ki kaj add korbo? Bole din.")
            return ok(NuvaAction.CreateTodo(content), "To-do list e add korchi.")
        }
        if (listOf("kaj moto", "kaj list").any { t.contains(it) }) {
            val content = contentAfter(t, "list") ?: contentAfter(t, "moto") ?: return null
            return ok(NuvaAction.CreateTodo(content), "To-do list e add korchi.")
        }

        // Note: "note koro X" / "note theko X" / "নোট নাও X"
        val noteMarker = listOf(
            "note koro", "note korun", "note nao", "note te likho", "notun note",
            "নোট নাও", "নোট করো", "নোটে লেখো", "লিখে রাখো",
        ).firstOrNull { t.contains(it) }
        if (noteMarker != null) {
            val content = contentAfter(t, noteMarker)
            if (content.isNullOrBlank()) return unsupported("Note e ki likhbo? Bole din.")
            return ok(NuvaAction.CreateNote(content), "Note kore nilam.")
        }
        return null
    }

    // --- 8. Calls ---------------------------------------------------------------------------

    private fun parseCall(t: String): CommandDecision? {
        val isCall = listOf(
            "call koro", "call korun", "call dao", "call diya jao", "phone koro", "phone korun",
            "ফোন করো", "ফোন করুন", "কল করো", "কল দাও", "dial koro", "যোগাযোগ করো",
        ).any { t.contains(it) }
        if (!isCall) return null

        // Raw number beats everything: "call koro 01712345678"
        PHONE_NUMBER.find(t)?.let { m ->
            val action = NuvaAction.CallContact(m.value, m.value)
            return ok(action, "Call: ${m.value} — nishchit korun.", risk = NuvaRisk.MEDIUM)
        }

        val name = contactName(t, callMode = true) ?: return unsupported("Kake call korbo? Nam bole din.")
        return ok(NuvaAction.CallContact(name, null), "$name ke call korbo — nishchit korun.", risk = NuvaRisk.MEDIUM)
    }

    // --- 9. Messages ------------------------------------------------------------------------

    private val WHATSAPP_WORDS = listOf("whatsapp e", "whatsapp", "হোয়াটসঅ্যাপে", "হোয়াটসঅ্যাপ", "hatsapp e", "whats app")
    private val SMS_WORDS = listOf("sms", "es em es", "message e", "এসএমএস", "এস এম এস", "মেসেজে", "text koro")

    private fun parseSendMessage(t: String): CommandDecision? {
        val appWords = WHATSAPP_WORDS.firstOrNull { t.contains(it) }
        val smsWords = SMS_WORDS.firstOrNull { t.contains(it) }
        if (appWords == null && smsWords == null) return null
        val app = if (appWords != null) MessagingApp.WHATSAPP else MessagingApp.SMS

        val sendVerb = listOf("pathao", "pathan", "pathiye dao", "পাঠাও", "পাঠান", "send koro", "send korun")
            .any { t.contains(it) } || t.contains("message") || t.contains("মেসেজ")

        // Just opening the app: "whatsapp khulo" is handled by parseOpenApp.

        val number = PHONE_NUMBER.find(t)?.value
        val name = contactName(t, callMode = false)
        if (name.isNullOrBlank() && number.isNullOrBlank()) {
            return if (sendVerb) unsupported("Kake pathabo? Contact er nam bole din.") else null
        }

        val message = extractMessage(t)
        if (message.isNullOrBlank()) {
            return unsupported(
                "Ki message pathabo bolen — tarpor abar bolen. " +
                    "Jemon: ${if (name != null) "$name ke" else number!!} whatsapp e bole dao kal 9 tay class.",
            )
        }
        val target = name ?: number!!
        return ok(
            NuvaAction.SendMessage(app, target, message, number),
            "$target ke ${app.wireName} e message pathabo — nishchit korun.",
            risk = NuvaRisk.MEDIUM,
        )
    }

    private fun extractMessage(t: String): String? {
        // Quoted message first.
        Regex("""["'“”](.+?)["'“”]""").find(t)?.let { return it.groupValues[1].trim() }

        val markers = listOf(
            "bole dao", "bole din", "bolun", "bolena", "bolen", "bole diya", "message:", "msg:",
            "message e", "বলে দাও", "বলে দিন", "বলুন", "বলো", "মেসেজ:", "মেসেজে",
            "pathao", "pathan", "pathiye dao", "পাঠাও", "পাঠান", "send koro", "send korun",
        )
        for (m in markers) {
            val after = contentAfter(t, m) ?: continue
            if (after.length in 1..2000) return after
        }
        return null
    }

    // --- 10. Media ---------------------------------------------------------------------------

    private fun parsePlayMedia(t: String): CommandDecision? {
        val playVerb = listOf(
            "chalao", "chalun", "bajao", "bajao dao", "chira dao", "chirao", "shonao",
            "চালাও", "চালান", "বাজাও", "শোনাও", "play koro",
        ).any { t.contains(it) } || t.contains("play")
        if (!playVerb) return null

        val isMedia = listOf("gaan", "গান", "giti", "গীত", "song", "video", "ভিডিও", "movie", "সিনেমা", "chobi")
            .any { t.contains(it) } || t.contains("youtube")
        if (!isMedia) return null

        val spotify = t.contains("spotify")
        var query = t
        listOf(
            "gaan", "গান", "giti", "গীত", "song", "video", "ভিডিও", "movie", "সিনেমা", "chobi",
            "youtube", "ইউটিউব", "ইউটুব", "spotify", "স্পটিফাই", "e", "theke", "থেকে",
        ).forEach { query = swapWord(query, it) }
        TAIL_VERBS.forEach { query = swapWord(query, it) }
        listOf("chalao", "chalun", "bajao", "chira dao", "chirao", "shonao", "চালাও", "চালান", "বাজাও", "শোনাও", "play")
            .forEach { query = query.replace(it, " ") }
        val cleaned = query.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
        val finalQuery = cleaned.ifBlank { "bangla gaan" }
        return ok(
            NuvaAction.PlayMedia(finalQuery, if (spotify) MediaApp.SPOTIFY else MediaApp.YOUTUBE),
            "YouTube e \"$finalQuery\" khujchi.",
        )
    }

    // --- 11. Web -----------------------------------------------------------------------------

    private fun parseWeb(t: String): CommandDecision? {
        // Web search — "google e X khujho" / "X khujho" / "search X"
        val searchMarkers = listOf("khujho", "khujen", "khunji", "search koro", "search korun", "google e", "google a",
            "খোঁজো", "খুঁজে দাও", "সার্চ করো", "গুগলে")
        if (searchMarkers.any { t.contains(it) }) {
            var query = t
            searchMarkers.forEach { query = query.replace(it, " ") }
            TAIL_VERBS.forEach { query = swapWord(query, it) }
            val cleaned = query.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
            if (cleaned.length in 2..300) return ok(NuvaAction.SearchWeb(cleaned), "\"$cleaned\" khujchi.")
            return ok(NuvaAction.SearchWeb(t.take(120)), "Khujchi.")
        }

        // URL open — "nuvabeaches.com khulo"
        val domain = Regex("""\b([a-z0-9][a-z0-9-]{1,61}\.(com|net|org|io|co|bd|info|xyz|dev|app|gov|edu|me|tv|shop|site)(/[^\s]*)?)\b""")
        domain.find(t)?.let { m ->
            val url = m.groupValues[1]
            return ok(NuvaAction.OpenUrl("https://$url"), "$url khulchi.")
        }
        return null
    }

    // --- 12. Gestures ------------------------------------------------------------------------

    private fun parseScrollSwipe(t: String): CommandDecision? {
        if (t.contains("scroll") || t.contains("স্ক্রল")) {
            val direction = when {
                t.contains("upore") || t.contains("up") || t.contains("উপরে") -> SwipeDirection.UP
                t.contains("bame") || t.contains("left") -> SwipeDirection.LEFT
                t.contains("dane") || t.contains("right") -> SwipeDirection.RIGHT
                else -> SwipeDirection.DOWN
            }
            val amount = Regex("""(\d{1,2})\s*(bar|page|বার|পেজ)""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1..20) ?: 1
            return ok(NuvaAction.Scroll(direction, amount, null), "Scroll korchi.")
        }
        if (t.contains("swipe")) {
            val direction = when {
                t.contains("up") -> SwipeDirection.UP
                t.contains("left") || t.contains("bame") -> SwipeDirection.LEFT
                t.contains("right") || t.contains("dane") -> SwipeDirection.RIGHT
                else -> SwipeDirection.DOWN
            }
            return ok(NuvaAction.Swipe(direction, SwipeDistance.MEDIUM, null, null), "Swipe korchi.")
        }
        return null
    }

    // --- 13. Apps -----------------------------------------------------------------------------

    private fun parseCloseApp(t: String): CommandDecision? {
        val verb = CLOSE_VERBS.firstOrNull { t.contains(it) } ?: return null
        val app = appNameFrom(t, verb) ?: return null
        if (SensitiveAppPolicy.isSensitiveAppName(app)) return refused()
        return ok(NuvaAction.CloseApp(app, null), "$app bondho kore home e jacchi.")
    }

    private fun parseOpenApp(t: String): CommandDecision? {
        val verb = OPEN_VERBS.firstOrNull { t.contains(it) } ?: return null
        val app = appNameFrom(t, verb) ?: return null
        // Denylist check on the raw name too — banking apps never open by voice.
        if (SensitiveAppPolicy.isSensitiveAppName(app)) return refused()
        val canonical = APP_ALIASES[app]
        return ok(
            NuvaAction.OpenApp(canonical ?: app, null),
            "${(canonical ?: app).replaceFirstChar { it.uppercase() }} khulchi.",
        )
    }

    /** Extracts the app name by removing the matched verb + filler words. */
    private fun appNameFrom(t: String, verb: String): String? {
        var name = t.replace(verb, " ")
        listOf("app", "ta", "টা", "app ta", "please", "eko", "hoye", "amar", "আমার").forEach {
            name = swapWord(name, it)
        }
        TAIL_VERBS.forEach { name = swapWord(name, it) }
        val cleaned = name.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
        if (cleaned.isEmpty() || cleaned.length > 40 || cleaned.split(" ").size > 3) return null
        return cleaned
    }

    // --- Shared helpers ----------------------------------------------------------------------

    /** Contact name from a call/message command (Bangla & Banglish word order). */
    private fun contactName(t: String, callMode: Boolean): String? {
        var s = t
        listOf("nuva", "নুভা").forEach { s = s.replace(it, " ") }

        // "X ke call koro" / "X ke phone koro" / "X ke whatsapp e message pathao"
        Regex("""^(.*?)(\s+ke|\s+কে|\s+keর)\s+""").find(s)?.let { m ->
            val candidate = m.groupValues[1].trim()
            if (candidate.length in 2..60 && !candidate.contains("call") && !candidate.contains("phone")) {
                return cleanName(candidate)
            }
        }
        // "call koro X" / "phone din X"
        for (lead in listOf("call koro", "call korun", "call dao", "phone koro", "phone korun", "dial koro",
                 "কল করো", "ফোন করো", "ফোন করুন", "কল দাও", "যোগাযোগ করো")) {
            if (s.contains(lead)) {
                val rest = contentAfter(s, lead)
                if (rest != null && rest.length in 2..60) return cleanName(rest)
            }
        }
        if (!callMode) return null
        // Last resort for calls: leading words before the verb.
        for (verb in listOf("ke call", "কে কল", "ke phone", "কে ফোন")) {
            val idx = s.indexOf(verb)
            if (idx > 2) return cleanName(s.substring(0, idx))
        }
        return null
    }

    private fun cleanName(raw: String): String? {
        var name = raw
        WHATSAPP_WORDS.forEach { name = name.replace(it, " ") }
        SMS_WORDS.forEach { name = name.replace(it, " ") }
        listOf("message", "msg", "মেসেজ", "e", "diye", "দিয়ে", "amar", "আমার", "er", "এর").forEach {
            name = swapWord(name, it)
        }
        TAIL_VERBS.forEach { name = swapWord(name, it) }
        val cleaned = name.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
        return cleaned.takeIf { it.length in 2..60 }?.takeIf { it.split(" ").size <= 5 }
    }

    private fun contentAfter(t: String, marker: String): String? {
        val idx = t.indexOf(marker)
        if (idx < 0) return null
        var rest = t.substring(idx + marker.length)
        TAIL_VERBS.forEach { rest = swapWord(rest, it) }
        val cleaned = rest.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?')
        return cleaned.ifBlank { null }
    }

    private fun extractLabel(t: String): String? {
        val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted.take(120)
        return null
    }

    // --- Decision builders ---------------------------------------------------------------------

    private fun ok(action: NuvaAction, speech: String, risk: NuvaRisk = NuvaRisk.LOW): CommandDecision {
        // Local risk floor = registry baseline; explicit risk may only raise it.
        val base = baselineRisk(action.intent)
        val finalRisk = if (risk.ordinal > base.ordinal) risk else base
        return CommandDecision(
            intent = action.intent,
            action = action,
            unsupported = false,
            risk = finalRisk,
            requiresConfirmation = finalRisk != NuvaRisk.LOW,
            speech = speech,
            reasons = listOf("parsed on-device"),
            commandId = null,
            source = "offline",
        )
    }

    private fun unsupported(speech: String): CommandDecision = CommandDecision(
        intent = null,
        action = null,
        unsupported = true,
        risk = NuvaRisk.LOW,
        requiresConfirmation = false,
        speech = speech,
        reasons = listOf("offline parser needs more info"),
        commandId = null,
        source = "offline",
    )

    private fun refused(): CommandDecision = CommandDecision(
        intent = null,
        action = null,
        unsupported = true,
        risk = NuvaRisk.HIGH,
        requiresConfirmation = false,
        speech = SensitiveAppPolicy.REFUSAL_SPEECH,
        reasons = listOf(SensitiveAppPolicy.REFUSAL_REASON),
        commandId = null,
        source = "offline-security",
    )
}
