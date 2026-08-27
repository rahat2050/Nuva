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
        "open koro", "open korun", "open", "khule dao", "kholo dao", "kholo", "khulo", "kholun", "khulun",
        "chalu koro", "চালু করো", "চালু করুন", "খোলো", "খুলে দাও", "খুলুন",
        "launch koro", "launch", "start koro", "চালাও",
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
        // Financial apps — LEVEL 1: opening them by voice is allowed.
        put("bkash", "বিকাশ", "b kash", "bকাশ", canonical = "bkash")
        put("nagad", "নগদ", canonical = "nagad")
        put("rocket", "রকেট", "dbbl rocket", canonical = "rocket")
        put("upay", "উপায়", "উপাই", canonical = "upay")
    }

    // Bangladeshi/Intl numbers, tolerant of spaces/hyphens inside ("01712-345678").
    private val PHONE_NUMBER = Regex("""(\+?88)?01[3-9](?:[\s-]?\d){8}|\+\d{8,15}""")
    private val EMAIL_ADDRESS = Regex("""[A-Za-z0-9.!#\x24%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

    /** "rohim-ke" → "rohim ke", "whatsapp-e" → "whatsapp e" (keeps URLs intact). */
    private val HYPHEN_SUFFIX = Regex("""-(ke|kei|keu|kar|e|te|er|r)\b""")

    private fun digitsOnly(raw: String): String = raw.filter { it.isDigit() || it == '+' }

    // --- Public API -------------------------------------------------------------

    data class OfflineResult(val decision: CommandDecision)

    /**
     * Parses an utterance. Returns null when nothing locally-understandable
     * matches — the caller then falls back to the AI path.
     */
    fun parse(rawText: String): CommandDecision? {
        val text = prepare(rawText) ?: return null
        return parsePreparedWithGrammar(text)
    }

    private fun stripWakeWord(text: String): String =
        WAKE_WORDS.replace(text, "").replace(Regex("^[,.!\\s]+"), "").trim()

    /** Shared utterance preparation: normalize → wake-strip → hyphen suffixes. */
    private fun prepare(rawText: String): String? {
        val normalized = NuvaDateTimeParser.normalize(rawText)
        val text = stripWakeWord(normalized)
        if (text.isBlank()) return null
        return HYPHEN_SUFFIX.replace(text) { m -> " ${m.groupValues[1]} " }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

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

    // --- 0b. Compound / multi-step commands (v1.3) ---------------------------------
    //
    // "WhatsApp kholo ar Rohim-ke message dau ami agamikal asbona"
    //   → [OPEN_APP(whatsapp), SEND_MESSAGE(whatsapp, Rohim, "ami agamikal asbona")]
    //
    // Connectors: ar / ebong / and / tarpor / then / আর / এবং / তারপর.
    // Splitting is CONSERVATIVE: a split is accepted only when the left side
    // parses to a non-message, non-call action — so message content that
    // contains " ar " ("ami ar ashbo") can never be cut in half.

    private val CONNECTORS = listOf(
        " ar ", " ebong ", " and then ", " and ", " tarpor ", " erpor ", " then ", " also ",
        " আর ", " এবং ", " তারপর ", " এরপর ", " তারপরে ", "; ",
    )

    /**
     * Parses a full utterance into an ordered action plan. Returns a
     * single-element list for simple commands, null when nothing is
     * understood (the AI path then takes over).
     */
    fun parseCompound(rawText: String): List<CommandDecision>? {
        val text = prepare(rawText) ?: return null

        // Security first — check both original and typo/ASR-canonicalized text
        // so "paymnt"/"pasword" cannot bypass the same fixed boundary.
        val rewritten = NaturalCommandGrammar.rewrite(text)
        listOf(text, rewritten).distinct().forEach { candidate ->
            SensitiveAppPolicy.refusalForText(candidate)?.let { return listOf(refused()) }
            if (SensitiveAppPolicy.mentionsCredentials(candidate)) {
                return listOf(unsupported("OTP, PIN ba password NUVA kochu kore na — egulo nije likhun."))
            }
        }

        val whole = parsePreparedWithGrammar(text)
        if (whole != null && !whole.unsupported && looksLikeCleanMessage(whole)) {
            // A complete call/message command whose content may contain
            // connector words ("ami ar ashbo") — never split it.
            return listOf(whole)
        }

        val plan = splitPlan(text, depth = 0)
        if (plan != null && plan.size >= 2) return plan

        return listOfNotNull(whole)
    }

    /**
     * A SendMessage is "clean" (never split) only when the extracted contact
     * does not contain leftover device-action words — otherwise the utterance
     * was probably a compound mis-read as one message ("whatsapp kholo ar
     * rohim ke message dau …") and deserves a proper split.
     */
    private fun looksLikeCleanMessage(decision: CommandDecision): Boolean {
        val send = decision.action as? NuvaAction.SendMessage ?: return false
        val contact = send.contact.lowercase()
        val suspicious = listOf(" ar ", "kholo", "bondho", "band ", "open", "launch").any { contact.contains(it) }
        return !suspicious && send.message.isNotBlank()
    }

    /** parse() for already-prepared text (no re-normalization). */
    private fun parsePrepared(text: String): CommandDecision? {
        SensitiveAppPolicy.refusalForText(text)?.let { return refused() }
        if (SensitiveAppPolicy.mentionsCredentials(text)) {
            return unsupported("OTP, PIN ba password NUVA kochu kore na — egulo nije likhun.")
        }
        return ruleTable(text)
    }

    /** Original first for entity/message fidelity; canonical retry on a miss. */
    private fun parsePreparedWithGrammar(text: String): CommandDecision? {
        val rewritten = NaturalCommandGrammar.rewrite(text)
        if (rewritten != text) {
            SensitiveAppPolicy.refusalForText(rewritten)?.let { return refused() }
            if (SensitiveAppPolicy.mentionsCredentials(rewritten)) {
                return unsupported("OTP, PIN ba password NUVA kochu kore na — egulo nije likhun.")
            }
        }
        // Exact static grammar aliases are intentional and should beat a broad
        // dynamic fallback such as treating "notification app" as an app name.
        NaturalCommandGrammar.canonicalStatic(text)?.let { canonical ->
            parsePrepared(canonical)?.let { return it }
        }
        val direct = parsePrepared(text)
        // A successfully extracted message keeps its exact body. Otherwise a
        // command-word rewrite should beat broad parsers such as dynamic app
        // names or generic web questions.
        if (direct?.action is NuvaAction.SendMessage) return direct
        if (rewritten != text) parsePrepared(rewritten)?.let { return it }
        return direct
    }

    private fun ruleTable(text: String): CommandDecision? = parseNavigation(text)
        ?: parseUserPresentFile(text)
        ?: parseProductivityHandoff(text)
        ?: parseCommunicationCompose(text)
        ?: parseMapNavigation(text)
        ?: parseUniversal(text)
        ?: parseScreenReading(text)
        ?: parseDailyUtility(text)
        ?: parseRealtimeInfo(text)
        ?: parseDeviceStatus(text)
        ?: parseAssistantHelp(text)
        ?: parseMediaControl(text)
        ?: parseVolumeControl(text)
        ?: parseCamera(text)
        ?: parseSettings(text)
        ?: parseAlarm(text)
        ?: parseTimer(text)
        ?: parseReminder(text)
        ?: parseNoteTodo(text)
        ?: parseCall(text)
        ?: parseChatOpen(text)
        ?: parseSendMessage(text)
        ?: parsePlayMedia(text)
        ?: parseMaps(text)
        ?: parseWeb(text)
        ?: parseScrollSwipe(text)
        ?: parseCloseApp(text)
        ?: parseOpenApp(text)
        ?: parseDailySkill(text)
        ?: parseExtendedDailySkill(text)
        ?: parseKnowledgeSearch(text)

    private fun parseDailySkill(text: String): CommandDecision? {
        val match = DailySkillRegistry.resolve(text) ?: return null
        val isBangla = text.any { it.code in 0x0980..0x09FF }
        val speech = if (isBangla) {
            "হালনাগাদ ও sourced তথ্য ওয়েবে খুঁজছি।"
        } else {
            "Updated sourced information web e khujchi."
        }
        return ok(NuvaAction.SearchWeb(match.query), speech)
    }

    private fun parseExtendedDailySkill(text: String): CommandDecision? {
        val match = ExtendedDailySkillRegistry.resolve(text) ?: return null
        val speech = if (text.any { it.code in 0x0980..0x09FF }) {
            "নির্দিষ্ট তথ্যটি sourced web result-এ খুঁজছি।"
        } else {
            "Specific information sourced web result e khujchi."
        }
        return ok(NuvaAction.SearchWeb(match.query), speech)
    }

    private fun parseDailyUtility(text: String): CommandDecision? {
        val result = DailyUtilityParser.parse(text) ?: return null
        return ok(NuvaAction.LocalAnswer(result.answer, result.category), result.answer)
    }

    private fun parseAssistantHelp(text: String): CommandDecision? {
        val asksCapabilities = listOf(
            "ki ki korte paro", "ki kaj paro", "tumi ki paro", "what can you do", "show commands",
            "help me", "feature dekhao", "command dekhao", "কী কী করতে পারো", "কি কি করতে পারো",
            "কী কাজ পারো", "সাহায্য করো", "ফিচার দেখাও",
        ).any { text.contains(it) }
        if (asksCapabilities) {
            val answer = if (text.any { it.code in 0x0980..0x09FF }) {
                "আমি ফোন কন্ট্রোল, কল-মেসেজ, রিমাইন্ডার, নোট-লিস্ট, উন্নত হিসাব, ৬০০টি sourced skill এবং ১২,২৫০টি audited natural command form বুঝতে পারি। Polite, ASR ও Bangla/Banglish/English variant-ও চলে। Features পেজে তালিকা আছে।"
            } else {
                "Ami phone control, call-message, reminder, note-list, advanced calculation, 600 ta sourced skill, ar 12,250 ta audited natural command form bujhte pari. Polite, ASR o Bangla-Banglish-English variant-o chole. Features page e list ache."
            }
            return ok(NuvaAction.LocalAnswer(answer, "assistant_help"), answer)
        }

        val greeting = when {
            listOf("assalamu alaikum", "salam", "আসসালামু আলাইকুম", "সালাম").any { text == it || text.startsWith("$it ") } ->
                if (text.any { it.code in 0x0980..0x09FF }) "ওয়ালাইকুম আসসালাম। কীভাবে সাহায্য করতে পারি?" else "Walaikum assalam. Kivabe help korte pari?"
            listOf("thank you", "thanks", "dhonnobad", "ধন্যবাদ").any { text == it || text.startsWith("$it ") } ->
                if (text.any { it.code in 0x0980..0x09FF }) "স্বাগতম।" else "Welcome."
            listOf("tumi ke", "who are you", "তুমি কে").any { text.contains(it) } ->
                if (text.any { it.code in 0x0980..0x09FF }) "আমি NUVA—আপনার নিরাপদ Android সহকারী।" else "Ami NUVA—apnar safe Android assistant."
            else -> null
        } ?: return null
        return ok(NuvaAction.LocalAnswer(greeting, "small_talk"), greeting)
    }

    private fun splitPlan(text: String, depth: Int): List<CommandDecision>? {
        if (depth > 5 || text.isBlank()) return null
        for (connector in CONNECTORS) {
            var from = 0
            while (true) {
                val idx = text.indexOf(connector, from)
                if (idx < 0) break
                val leftRaw = text.substring(0, idx).trim()
                val rightRaw = text.substring(idx + connector.length).trim()
                from = idx + connector.length

                val left = leftRaw.takeIf { it.isNotBlank() }?.let { parsePreparedWithGrammar(it) } ?: continue
                if (left.unsupported) continue
                // A MESSAGE carries free text and may legally contain the
                // connector itself ("…bole dao ami ar ashbo") — never accept
                // it as the left side of a split. Calls carry no content, so
                // splitting after a call is safe.
                if (left.action is NuvaAction.SendMessage) continue
                if (leftRaw.split(" ").size > 12) continue

                // If the tail still contains connectors, recursively try a
                // longer plan first. Message content remains safe because a
                // SEND_MESSAGE is never accepted as a split's left side.
                val nested = if (CONNECTORS.any { rightRaw.contains(it) }) {
                    splitPlan(rightRaw, depth + 1)
                } else {
                    null
                }
                val wholeTail = parsePreparedWithGrammar(rightRaw)
                val rest: List<CommandDecision> = when {
                    !nested.isNullOrEmpty() -> nested
                    wholeTail != null -> listOf(wholeTail)
                    else -> emptyList()
                }
                if (rest.isEmpty()) continue

                return refinePlan(listOf(left) + rest)
            }
        }
        return null
    }

    /** Context rule: "YouTube kholo ar X search koro" → search inside YouTube. */
    private fun refinePlan(plan: List<CommandDecision>): List<CommandDecision> {
        val mediaApp = plan.firstNotNullOfOrNull { step ->
            (step.action as? NuvaAction.OpenApp)?.takeIf { it.app == "youtube" || it.app == "spotify" }
        } ?: return plan
        return plan.map { step ->
            val search = step.action as? NuvaAction.SearchWeb
            if (search != null) {
                CommandDecision(
                    intent = NuvaIntent.PLAY_MEDIA,
                    action = NuvaAction.PlayMedia(
                        search.query,
                        if (mediaApp.app == "spotify") MediaApp.SPOTIFY else MediaApp.YOUTUBE,
                    ),
                    unsupported = false,
                    risk = NuvaRisk.LOW,
                    requiresConfirmation = false,
                    speech = "YouTube e \"${search.query}\" khujchi.",
                    reasons = listOf("parsed on-device"),
                    commandId = null,
                    source = "offline",
                )
            } else {
                step
            }
        }
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

    // --- 1b. Universal app-agnostic commands (v1.5, Phase 5) ---------------------------
    // Targets come from the CURRENT SCREEN at execution time; ambiguity is
    // reported so the user can be specific — never guessed.

    private fun parseUniversal(t: String): CommandDecision? {
        // Notification shade / source app — before generic open/read rules.
        if (listOf("notification panel", "notification shade", "নোটিফিকেশন প্যানেল", "notification khulo")
                .any { t.contains(it) }
        ) return ok(NuvaAction.OpenNotificationShade, "নোটিফিকেশন প্যানেল খুলছি।")

        if (listOf("notification er app", "notification wala app", "notification ta kholo", "prothom notification")
                .any { t.contains(it) }
        ) {
            val ordinal = Regex("""(\d+)\s*(number|no|tomo|তম)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return ok(NuvaAction.OpenNotificationApp(ordinal.coerceIn(1, 30)), "নোটিফিকেশনের অ্যাপ খুলছি।")
        }

        // Press a button by name, or "এটা press করো" for the only one visible.
        // NOTE: no ASCII \b around Bangla markers (JVM \b is ASCII-only).
        val barePress = listOf(
            "eta press", "eta chapo", "এটা press", "এটা চাপো", "এটা ট্যাপ",
        ).any { t.contains(it) }
        val pressLabel = when {
            barePress -> null
            else -> {
                // "send button press" → label is group 1; "press koro send" → label is group 2.
                val byName = Regex("""^(.{2,60}?)\s*(button|btn|ta|বাটন)\s*(press|chapo|chapun|চাপো|ট্যাপ)(?![a-z])""").find(t)
                byName?.groupValues?.get(1)?.trim()?.ifBlank { null }
                    ?: Regex("""(?<![a-z])(press|chapo|chapun|চাপো)\s+(.{2,60}?)$""").find(t)
                        ?.groupValues?.get(2)?.trim()
            }
        }
        val wantsPress = pressLabel != null || barePress ||
            listOf("press koro", "press korun", "button chapo", "press করো", "বাটন চাপো", "চাপো দাও").any { t.contains(it) }
        if (wantsPress) {
            val cleanedLabel = pressLabel?.let { label ->
                label.replace(Regex("""\b(button|btn|ta|the|eta|koro|korun)\b"""), " ")
                    .replace(Regex("""\s+"""), " ").trim().ifBlank { null }
            }
            return ok(NuvaAction.Press(cleanedLabel), "বাটন চাপছি।")
        }

        // Clear the current input.
        if (listOf("muchhe felo", "muchhe dao", "clear koro", "lekhata muchho", "লেখাটা মুছো", "মুছে ফেলো", "মুছে দাও")
                .any { t.contains(it) }
        ) return ok(NuvaAction.ClearText, "লেখাটা মুছে দিচ্ছি।")

        // UI summary (buttons/inputs), distinct from full text reading.
        if (listOf("button dekhao", "button gulo", "ki button ache", "ui dekhao", "ui summary", "বাটন দেখাও", "কী বাটন আছে")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DescribeScreen, "স্ক্রিনের বাটনগুলো বলছি।")

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

    // --- 2b. User-present files & gallery (v2.2) -----------------------------------------

    private fun parseUserPresentFile(t: String): CommandDecision? {
        fun decision(operation: UserFileOperation, speech: String, newName: String? = null): CommandDecision {
            val risk = if (operation.needsBlockingConfirmation) NuvaRisk.MEDIUM else NuvaRisk.LOW
            return ok(NuvaAction.UserFile(operation, newName), speech, risk)
        }

        val share = listOf("share", "pathao", "send", "শেয়ার", "শেয়ার", "পাঠাও").any { t.contains(it) }
        val select = listOf("select", "choose", "pick", "beche", "বেছে", "নির্বাচন").any { t.contains(it) }
        val open = listOf("open", "kholo", "khulo", "খোলো", "খুলে").any { t.contains(it) }
        val fileWord = listOf("file", "document", "ফাইল", "ডকুমেন্ট").any { t.contains(it) }
        val multiple = listOf("multiple", "several", "onek", "sob", "all selected", "একাধিক", "অনেক", "কয়েকটি", "কয়েকটি")
            .any { t.contains(it) }

        if (fileWord && share && multiple) {
            return decision(UserFileOperation.SHARE_MULTIPLE_FILES, "Multiple file picker-er por Android share sheet khulbo.")
        }
        if (fileWord && listOf("delete", "remove", "muchhe", "মুছে", "ডিলিট").any { t.contains(it) }) {
            return decision(UserFileOperation.DELETE_FILE, "File picker-er por selected target abar dekhie delete confirmation nebo.")
        }
        if (fileWord && listOf("rename", "nam bodlao", "নাম বদল", "রিনেম").any { t.contains(it) }) {
            val nameMarker = listOf("new name", "rename to", "nam dao", "নাম দাও", "নতুন নাম")
                .firstOrNull { t.contains(it) }
            val newName = nameMarker?.let { t.substringAfter(it).trim(' ', ',', '.', ':') }
                ?.takeIf { it.isNotBlank() && it.length <= 120 && '/' !in it && '\\' !in it }
                ?: return unsupported("File-er notun nam bolun — jemon: file rename koro new name report.pdf")
            return decision(UserFileOperation.RENAME_FILE, "File select korar por target-aware rename confirmation nebo.", newName)
        }
        if (fileWord && listOf("copy", "কপি").any { t.contains(it) }) {
            return decision(UserFileOperation.COPY_FILE, "Source file o destination folder apni select korben; tarpor copy confirm korbo.")
        }
        if (fileWord && listOf("move", "sorao", "সরাও", "মুভ").any { t.contains(it) }) {
            return decision(UserFileOperation.MOVE_FILE, "Source file o destination folder apni select korben; tarpor move confirm korbo.")
        }

        if (listOf("folder select", "folder choose", "folder access", "directory select", "ফোল্ডার বেছে", "ফোল্ডার সিলেক্ট")
                .any { t.contains(it) }
        ) {
            return decision(UserFileOperation.OPEN_FOLDER, "Android folder picker khulbo — folder apni select korun.")
        }

        val photo = listOf("photo", "chobi", "image", "ছবি", "ফটো").any { t.contains(it) }
        val video = listOf("video", "ভিডিও").any { t.contains(it) }
        val gallerySource = listOf("gallery theke", "gallery থেকে", "গ্যালারি থেকে", "photo picker", "media picker")
            .any { t.contains(it) }
        if (photo && share && multiple) {
            return decision(UserFileOperation.SHARE_MULTIPLE_PHOTOS, "Multiple photo picker-er por Android share sheet khulbo.")
        }
        if (video && share && multiple) {
            return decision(UserFileOperation.SHARE_MULTIPLE_VIDEOS, "Multiple video picker-er por Android share sheet khulbo.")
        }
        if (photo && listOf("edit", "crop", "rotate", "filter", "এডিট", "ক্রপ", "ঘোরাও").any { t.contains(it) }) {
            return decision(UserFileOperation.EDIT_PHOTO, "Photo select korar por installed editor khulbo; final Save apni korben.")
        }
        if (photo && (gallerySource || share || select)) {
            return if (share) {
                decision(UserFileOperation.SHARE_PHOTO, "Photo picker-er por Android share sheet khulbo.")
            } else {
                decision(UserFileOperation.PICK_PHOTO, "Photo picker khulchi — photo apni select korun.")
            }
        }
        if (video && (gallerySource || share || select)) {
            return if (share) {
                decision(UserFileOperation.SHARE_VIDEO, "Video picker-er por Android share sheet khulbo.")
            } else {
                decision(UserFileOperation.PICK_VIDEO, "Video picker khulchi — video apni select korun.")
            }
        }

        val textFile = listOf("text file", "txt file", "লেখার ফাইল", "টেক্সট ফাইল").any { t.contains(it) }
        val wantsRead = listOf("read", "poro", "পড়ো", "পড়ো", "pore shonao", "পড়ে শোনাও")
            .any { t.contains(it) }
        if (textFile && wantsRead) {
            return decision(UserFileOperation.READ_TEXT, "Text file picker khulchi — file apni select korun.")
        }

        if (fileWord && share) {
            return decision(UserFileOperation.SHARE_FILE, "File picker-er por Android share sheet khulbo.")
        }
        if (fileWord && (select || open)) {
            return decision(UserFileOperation.OPEN_FILE, "System file picker khulchi — file apni select korun.")
        }
        return null
    }

    // --- 2c. Forms/productivity handoff + scheduled compose reminder (v2.4) ------------

    private fun parseProductivityHandoff(t: String): CommandDecision? {
        if (listOf("clipboard poro", "read clipboard", "clipboard e ki ache", "ক্লিপবোর্ড পড়ো", "ক্লিপবোর্ডে কী আছে")
                .any { t.contains(it) }
        ) {
            return ok(NuvaAction.ClipboardAction(ClipboardOperation.READ), "Clipboard porbo — nishchit korun.", NuvaRisk.MEDIUM)
        }
        if (listOf("clipboard clear", "clear clipboard", "clipboard muchhe", "ক্লিপবোর্ড মুছো", "ক্লিপবোর্ড পরিষ্কার")
                .any { t.contains(it) }
        ) {
            return ok(NuvaAction.ClipboardAction(ClipboardOperation.CLEAR), "Clipboard clear korbo — nishchit korun.", NuvaRisk.MEDIUM)
        }
        val clipboardCopyMarker = listOf(
            "clipboard e copy koro", "copy to clipboard", "copy text", "clipboard e rakho", "ক্লিপবোর্ডে কপি", "ক্লিপবোর্ডে রাখো",
        ).firstOrNull { t.contains(it) }
        if (clipboardCopyMarker != null) {
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            var text = quoted ?: contentAfter(t, clipboardCopyMarker)
            text = text?.removePrefix("je ")?.removePrefix("যে ")?.trim()
            if (text.isNullOrBlank()) return unsupported("Clipboard-e kon text copy korbo?")
            return ok(NuvaAction.ClipboardAction(ClipboardOperation.COPY, text.take(5_000)), "Text copy korbo — nishchit korun.", NuvaRisk.MEDIUM)
        }

        val calendarRequested = listOf(
            "calendar event create", "calendar event add", "meeting create", "event draft", "ক্যালেন্ডার ইভেন্ট", "মিটিং তৈরি",
        ).any { t.contains(it) }
        if (calendarRequested) {
            val parsedTime = NuvaDateTimeParser.parseTime(t)
                ?: return unsupported("Calendar event-er time bolun.")
            val begin = com.nuva.assistant.automation.ScheduledComposeScheduler.nextTrigger(t, parsedTime.hour, parsedTime.minute)
            val durationSeconds = NuvaDateTimeParser.parseDuration(t)?.takeIf { it in 60..86_400 } ?: 3_600L
            val titleMarker = listOf(" title ", " name ", " শিরোনাম ", " নাম ").firstOrNull { t.contains(it) }
            val eventTitle = titleMarker?.let { marker ->
                t.substringAfter(marker)
                    .substringBefore(" location ").substringBefore(" description ").substringBefore(" attendee ")
                    .substringBefore(" email ").substringBefore(" স্থান ").substringBefore(" বিবরণ ")
                    .trim(' ', ',', '.', ':')
            }?.takeIf { it.isNotBlank() }?.take(200)
                ?: return unsupported("Calendar event-er title bolun.")
            val location = listOf(" location ", " স্থান ").firstOrNull { t.contains(it) }
                ?.let { marker -> t.substringAfter(marker).substringBefore(" description ").substringBefore(" attendee ").substringBefore(" email ").trim(' ', ',', '.', ':').take(300) }
            val description = listOf(" description ", " বিবরণ ").firstOrNull { t.contains(it) }
                ?.let { marker -> t.substringAfter(marker).substringBefore(" attendee ").substringBefore(" email ").trim(' ', ',', '.', ':').take(2_000) }
            val attendee = EMAIL_ADDRESS.find(t)?.value
            return ok(
                NuvaAction.CreateCalendarEvent(eventTitle, begin, begin + durationSeconds * 1_000, location, description, attendee),
                "Rich calendar event draft khulbo — final Save apni chapben.",
                NuvaRisk.MEDIUM,
            )
        }

        val managementPanel = when {
            listOf("app info", "application info", "app details", "অ্যাপ ইনফো").any { t.contains(it) } ->
                AppManagementPanel.APP_INFO
            listOf("notification setting", "notification settings", "নোটিফিকেশন সেটিং").any { t.contains(it) } &&
                !listOf("nuva er", "nuva-র").any { t.contains(it) } -> AppManagementPanel.NOTIFICATIONS
            listOf("play store page", "store page", "প্লে স্টোর পেজ").any { t.contains(it) } ->
                AppManagementPanel.PLAY_STORE
            else -> null
        }
        if (managementPanel != null) {
            var app = t
            listOf(
                "application info", "app details", "app info", "অ্যাপ ইনফো", "notification settings", "notification setting",
                "নোটিফিকেশন সেটিং", "play store page", "store page", "প্লে স্টোর পেজ", "khulo", "kholo", "open", "দেখাও", "খোলো",
            ).forEach { app = app.replace(it, " ") }
            listOf("app", "ta", "টা", "please", "er", "এর").forEach { app = swapWord(app, it) }
            app = app.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', ':')
            if (app.isNotBlank() && app.length <= 80) {
                return ok(NuvaAction.OpenAppManagement(app, managementPanel), "$app er ${managementPanel.wireName} khulchi.")
            }
        }

        val uninstallMarker = listOf("uninstall koro", "uninstall korun", "remove app", "app uninstall", "আনইনস্টল করো")
            .firstOrNull { t.contains(it) }
        if (uninstallMarker != null) {
            var app = t.replace(uninstallMarker, " ")
            listOf("app", "ta", "টা", "please").forEach { app = swapWord(app, it) }
            TAIL_VERBS.forEach { app = swapWord(app, it) }
            app = app.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', ':')
            if (app.isBlank() || app.length > 80) return unsupported("Kon app uninstall korbo? App-er nam bolun.")
            if (SensitiveAppPolicy.isSensitiveAppName(app)) {
                return unsupported("Financial app uninstall NUVA initiate korbe na — Android Settings theke manually korun.")
            }
            return ok(NuvaAction.UninstallApp(app), "$app uninstall prompt khulbo — nishchit korun.", NuvaRisk.MEDIUM)
        }

        val contactHandoff = when {
            listOf("contact edit", "edit contact", "contact bodlao", "কন্টাক্ট এডিট", "কন্টাক্ট বদলাও").any { t.contains(it) } ->
                ContactHandoffOperation.EDIT
            listOf("contact dekhao", "contact details", "view contact", "কন্টাক্ট দেখাও").any { t.contains(it) } ->
                ContactHandoffOperation.VIEW
            else -> null
        }
        if (contactHandoff != null) {
            return ok(
                NuvaAction.ContactHandoff(contactHandoff),
                "Contact picker khulbo — exact contact apni select korun.",
                if (contactHandoff == ContactHandoffOperation.EDIT) NuvaRisk.MEDIUM else NuvaRisk.LOW,
            )
        }

        val contactDraft = listOf(
            "new contact add", "contact create", "contact draft", "contact save screen", "নতুন কন্টাক্ট", "কন্টাক্ট যোগ",
        ).any { t.contains(it) }
        if (contactDraft) {
            val phone = PHONE_NUMBER.find(t)?.value?.let { digitsOnly(it) }
            val email = EMAIL_ADDRESS.find(t)?.value
            val nameMarker = listOf(" name ", " nam ", " নাম ").firstOrNull { t.contains(it) }
            val name = nameMarker?.let { marker ->
                var rawName = t.substringAfter(marker)
                    .substringBefore(" number ").substringBefore(" phone ").substringBefore(" email ")
                phone?.let { rawName = rawName.substringBefore(it) }
                email?.let { rawName = rawName.substringBefore(it) }
                rawName.trim(' ', ',', '.', ':')
            }
            if (name.isNullOrBlank()) return unsupported("Notun contact-er name bolun.")
            return ok(
                NuvaAction.CreateContactDraft(name.take(120), phone, email),
                "Contact draft khulbo — review kore final Save apni chapben.",
                NuvaRisk.MEDIUM,
            )
        }

        val textShareMarker = listOf(
            "text share koro", "lekha share koro", "share text", "ei lekha share", "লেখা শেয়ার", "টেক্সট শেয়ার",
        ).firstOrNull { t.contains(it) }
        if (textShareMarker != null) {
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            var text = quoted ?: contentAfter(t, textShareMarker)
            text = text?.removePrefix("je ")?.removePrefix("যে ")?.trim()
            if (text.isNullOrBlank()) return unsupported("Kon text share korbo? Lekhata bolun.")
            return ok(NuvaAction.ShareText(text.take(5_000)), "Text share korbo — nishchit korun.", NuvaRisk.MEDIUM)
        }

        val scheduledDraftWords = listOf("scheduled draft", "scheduled email", "scheduled sms", "compose reminder", "শিডিউল ড্রাফট")
        if (scheduledDraftWords.any { t.contains(it) } &&
            listOf("list", "dekhao", "poro", "show", "দেখাও", "পড়ো").any { t.contains(it) }
        ) {
            return ok(NuvaAction.ListScheduledDrafts, "Scheduled draft list porchi.")
        }
        if (scheduledDraftWords.any { t.contains(it) } &&
            listOf("cancel", "batil", "বাতিল", "remove").any { t.contains(it) }
        ) {
            val ordinal = Regex("""(\d+)\s*(number|no|tomo|তম)?""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 1
            return ok(
                NuvaAction.CancelScheduledDraft(ordinal),
                "$ordinal number scheduled draft cancel korbo — nishchit korun.",
                NuvaRisk.MEDIUM,
            )
        }

        val scheduled = listOf(
            "schedule email", "scheduled email", "email reminder", "schedule sms", "scheduled sms",
            "message compose reminder", "ইমেইল রিমাইন্ডার", "এসএমএস রিমাইন্ডার",
        ).any { t.contains(it) }
        if (scheduled) {
            val channel = if (t.contains("sms") || t.contains("এসএমএস")) ComposeChannel.SMS else ComposeChannel.EMAIL
            val parsedTime = NuvaDateTimeParser.parseTime(t)
                ?: return unsupported("Koto tay compose reminder dibo? Somoy ta bolun.")
            val triggerAt = com.nuva.assistant.automation.ScheduledComposeScheduler.nextTrigger(t, parsedTime.hour, parsedTime.minute)
            val recipient = when (channel) {
                ComposeChannel.EMAIL -> EMAIL_ADDRESS.find(t)?.value
                ComposeChannel.SMS -> PHONE_NUMBER.find(t)?.value?.let { digitsOnly(it) }
            }
            val subject = Regex("""(?:subject|বিষয়|বিষয়)\s*[:=-]?\s*(.{1,200}?)(?=\s+(?:body|message|je|যে)\s|$)""")
                .find(t)?.groupValues?.get(1)?.trim(' ', ',', '.', ':')
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            val bodyMarker = listOf(" body ", " message ", " je ", " যে ").firstOrNull { t.contains(it) }
            val body = quoted ?: bodyMarker?.let { t.substringAfter(it).trim(' ', ',', '.', ':') }
            if (body.isNullOrBlank()) return unsupported("Reminder-er compose body/message ta bolun.")
            val recurrence = when {
                listOf("protidin", "pratidin", "daily", "every day", "প্রতিদিন", "রোজ").any { t.contains(it) } ->
                    ComposeRecurrence.DAILY
                listOf("weekly", "every week", "proti shoptaho", "প্রতি সপ্তাহ").any { t.contains(it) } ||
                    NuvaDateTimeParser.weekday(t) != null -> ComposeRecurrence.WEEKLY
                else -> ComposeRecurrence.ONCE
            }
            return ok(
                NuvaAction.ScheduleCompose(channel, recipient, subject, body.take(2_000), triggerAt, recurrence),
                "Compose reminder schedule korbo — notification tap korle draft khulbe; Send apni chapben.",
                NuvaRisk.MEDIUM,
            )
        }

        val formRequested = listOf(
            "form prepare", "application prepare", "application form kholo", "booking prepare", "form draft",
            "ফর্ম প্রস্তুত", "আবেদন ফর্ম", "বুকিং ফর্ম",
        ).any { t.contains(it) }
        if (!formRequested) return null
        val kind = when {
            t.contains("passport") || t.contains("পাসপোর্ট") -> FormKind.PASSPORT
            t.contains("nid") || t.contains("এনআইডি") -> FormKind.NID
            t.contains("birth") || t.contains("জন্ম") -> FormKind.BIRTH_REGISTRATION
            t.contains("driving") || t.contains("ড্রাইভিং") -> FormKind.DRIVING_LICENSE
            t.contains("visa") || t.contains("ভিসা") -> FormKind.VISA
            t.contains("admission") || t.contains("ভর্তি") -> FormKind.ADMISSION
            t.contains("job") || t.contains("চাকরি") -> FormKind.JOB
            t.contains("doctor") || t.contains("ডাক্তার") -> FormKind.DOCTOR
            t.contains("hotel") || t.contains("হোটেল") -> FormKind.HOTEL
            t.contains("flight") || t.contains("ফ্লাইট") -> FormKind.FLIGHT
            t.contains("courier") || t.contains("কুরিয়ার") -> FormKind.COURIER
            else -> return unsupported("Kon form/booking prepare korbo? Type ta bolun.")
        }
        val details = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            ?: listOf(" details ", " তথ্য ").firstOrNull { t.contains(it) }
                ?.let { t.substringAfter(it).trim(' ', ',', '.', ':').take(1_000) }
        return ok(
            NuvaAction.PrepareForm(kind, details),
            "${kind.wireName} form draft locally rakhbo, tarpor official portal search khulbo — final Submit apni korben.",
            NuvaRisk.MEDIUM,
        )
    }

    // --- 2d. User-reviewed email + official notification reply (v2.3) ------------------

    private fun parseCommunicationCompose(t: String): CommandDecision? {
        if (listOf("voicemail khulo", "open voicemail", "voicemail dialer", "ভয়েসমেইল খোলো", "ভয়েসমেইল খোলো")
                .any { t.contains(it) }
        ) {
            return ok(NuvaAction.OpenVoicemail, "Voicemail dialer khulchi — final call apni korben.")
        }

        val socialPlatform = when {
            t.contains("facebook") -> SocialPlatform.FACEBOOK
            t.contains("instagram") -> SocialPlatform.INSTAGRAM
            t.contains("linkedin") -> SocialPlatform.LINKEDIN
            t.contains("reddit") -> SocialPlatform.REDDIT
            t.contains("threads") -> SocialPlatform.THREADS
            t.contains("tiktok") -> SocialPlatform.TIKTOK
            Regex("""(^|\s)(x|twitter)(\s|$)""").containsMatchIn(t) -> SocialPlatform.X
            else -> null
        }
        val socialCompose = listOf("post draft", "post compose", "compose post", "post likho", "পোস্ট ড্রাফট", "পোস্ট লেখো")
            .any { t.contains(it) }
        if (socialPlatform != null && socialCompose) {
            val marker = listOf(" je ", " যে ", " text ", " লেখা ").firstOrNull { t.contains(it) }
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            val text = quoted ?: marker?.let { t.substringAfter(it).trim(' ', ',', '.', ':') }
            if (text.isNullOrBlank()) return unsupported("Social post draft-er text bolun.")
            return ok(
                NuvaAction.ComposeSocialPost(socialPlatform, text.take(5_000)),
                "${socialPlatform.wireName} compose khulbo — final Post apni korben.",
                NuvaRisk.MEDIUM,
            )
        }

        val mmsRequested = listOf("mms compose", "mms pathao", "multimedia message", "photo message", "এমএমএস")
            .any { t.contains(it) }
        if (mmsRequested) {
            val recipient = PHONE_NUMBER.find(t)?.value?.let { digitsOnly(it) }
            val attachment = listOf("attachment", "attach", "photo", "image", "file", "ছবি", "ফাইল").any { t.contains(it) }
            val marker = listOf(" message ", " body ", " je ", " যে ").firstOrNull { t.contains(it) }
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            var body = quoted ?: marker?.let { t.substringAfter(it).trim(' ', ',', '.', ':') }
            body = body?.removePrefix("je ")?.removePrefix("যে ")?.trim()
            if (body.isNullOrBlank() && !attachment) return unsupported("MMS-er body ba attachment bolun.")
            return ok(
                NuvaAction.ComposeMms(recipient, body?.take(2_000), attachment),
                "MMS composer khulbo${if (attachment) "; age file beche nin" else ""} — final Send apni korben.",
                NuvaRisk.MEDIUM,
            )
        }

        val mentionsNotification = t.contains("notification") || t.contains("নোটিফিকেশন")
        if (mentionsNotification && listOf("dismiss", "clear notification", "notification muchhe", "সরাও", "মুছে দাও")
                .any { t.contains(it) }
        ) {
            val ordinal = Regex("""(\d+)\s*(number|no|tomo|তম)?""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1
            return ok(
                NuvaAction.ManageNotification(ordinal, NotificationManageOperation.DISMISS),
                "$ordinal number notification dismiss korbo — nishchit korun.",
                NuvaRisk.MEDIUM,
            )
        }
        if (mentionsNotification && listOf("mark as read", "mark read", "পঠিত", "পড়া হয়েছে", "পড়া হয়েছে")
                .any { t.contains(it) }
        ) {
            val ordinal = Regex("""(\d+)\s*(number|no|tomo|তম)?""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1
            return ok(
                NuvaAction.ManageNotification(ordinal, NotificationManageOperation.MARK_READ),
                "$ordinal number notification app-er official Mark as read action diye mark korbo — nishchit korun.",
                NuvaRisk.MEDIUM,
            )
        }

        val replyMarker = listOf(
            "notification e reply dao", "notification reply dao", "notification reply koro",
            "reply to notification", "নোটিফিকেশনে রিপ্লাই দাও", "নোটিফিকেশনের উত্তর দাও",
            "রিপ্লাই দাও", "reply dao", "reply koro",
        ).firstOrNull { t.contains(it) }
        if (replyMarker != null && (t.contains("notification") || t.contains("নোটিফিকেশন"))) {
            val ordinal = Regex("""(\d+)\s*(number|no|tomo|তম)""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30) ?: 1
            val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
            var message = quoted ?: contentAfter(t, replyMarker)
            message = message?.removePrefix("je ")?.removePrefix("যে ")?.trim()
            if (message.isNullOrBlank()) return unsupported("Notification e ki reply dibo? Message ta bolun.")
            return ok(
                NuvaAction.ReplyNotification(ordinal, message.take(1_000)),
                "$ordinal number notification e \"${message.take(120)}\" reply pathabo — nishchit korun.",
                NuvaRisk.MEDIUM,
            )
        }

        val emailMarker = listOf(
            "email compose koro", "compose email", "email likho", "email koro", "email pathao",
            "mail compose koro", "mail likho", "mail pathao", "ইমেইল লেখো", "ইমেইল পাঠাও", "মেইল লেখো",
        ).firstOrNull { t.contains(it) } ?: return null
        val recipient = EMAIL_ADDRESS.find(t)?.value
        val subject = Regex("""(?:subject|বিষয়|বিষয়)\s*[:=-]?\s*(.{1,200}?)(?=\s+(?:body|message|je|যে)\s|$)""")
            .find(t)?.groupValues?.get(1)?.trim(' ', ',', '.', ':')
        val quoted = Regex("""["'“”](.+?)["'“”]""").find(t)?.groupValues?.get(1)?.trim()
        val bodyMarker = listOf(" body ", " message ", " je ", " যে ").firstOrNull { t.contains(it) }
        var body = quoted ?: bodyMarker?.let { marker -> t.substringAfter(marker).trim(' ', ',', '.', ':') }
        if (body.isNullOrBlank()) {
            body = contentAfter(t, emailMarker)?.replace(recipient.orEmpty(), " ")
                ?.replace(Regex("""\b(subject|বিষয়|বিষয়)\b.*$"""), " ")
                ?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null }
        }
        val attachmentWords = listOf(
            "attachment", "attach file", "file attach", "document attach", "সংযুক্তি", "ফাইল অ্যাটাচ",
        )
        val attachmentRequested = attachmentWords.any { t.contains(it) }
        val multipleAttachments = attachmentRequested && listOf(
            "multiple", "several", "onek", "একাধিক", "অনেক", "কয়েকটি", "কয়েকটি",
        ).any { t.contains(it) }
        if (attachmentRequested && quoted == null && bodyMarker == null) {
            (attachmentWords + listOf("multiple", "several", "onek", "একাধিক", "অনেক", "কয়েকটি", "কয়েকটি"))
                .forEach { word -> body = body?.replace(word, " ") }
            body = body?.replace(Regex("""\s+"""), " ")?.trim()?.ifBlank { null }
        }
        return ok(
            NuvaAction.ComposeEmail(recipient, subject, body?.take(5_000), attachmentRequested, multipleAttachments),
            "Email composer khulbo${recipient?.let { " — recipient $it" } ?: ""}${if (attachmentRequested) "; age attachment beche nin" else ""}; Send apni chapben.",
            NuvaRisk.MEDIUM,
        )
    }

    // --- 2e. Maps directions/navigation (v3.2) -----------------------------------------

    private fun parseMapNavigation(t: String): CommandDecision? {
        val street = listOf("street view", "রাস্তার ছবি", "স্ট্রিট ভিউ").any { t.contains(it) }
        val nearby = listOf("nearby ", "near me", "kacher ", "কাছের ", "আশেপাশের ").any { t.contains(it) }
        val navigationMarker = listOf(
            "navigate to", "navigation to", "start navigation", "niye jao", "নেভিগেট করো", "নিয়ে যাও", "নিয়ে যাও",
        ).firstOrNull { t.contains(it) }
        val directionsMarker = listOf(
            "directions to", "direction to", "route to", "rasta dekhao", "jawar rasta", "kivabe jabo", "কীভাবে যাব", "কিভাবে যাব", "রাস্তা দেখাও",
        ).firstOrNull { t.contains(it) }
        val requestType = when {
            street -> MapRequestType.STREET_VIEW
            nearby -> MapRequestType.NEARBY
            navigationMarker != null -> MapRequestType.NAVIGATION
            directionsMarker != null || Regex("""\bfrom\s+.+\s+to\s+.+""").containsMatchIn(t) -> MapRequestType.DIRECTIONS
            else -> return null
        }
        val mode = when {
            listOf("walking", "walk", "hete", "হেঁটে", "পায়ে", "পায়ে").any { t.contains(it) } -> TravelMode.WALKING
            listOf("bicycle", "cycling", "cycle", "সাইকেল").any { t.contains(it) } -> TravelMode.BICYCLING
            listOf("transit", "bus e", "train e", "public transport", "বাসে", "ট্রেনে").any { t.contains(it) } -> TravelMode.TRANSIT
            else -> TravelMode.DRIVING
        }

        var origin: String? = null
        var destination: String? = null
        Regex("""\bfrom\s+(.{1,150}?)\s+to\s+(.{1,150})$""").find(t)?.let { match ->
            origin = cleanMapPlace(match.groupValues[1])
            destination = cleanMapPlace(match.groupValues[2])
        }
        if (destination == null) {
            val marker = when (requestType) {
                MapRequestType.NAVIGATION -> navigationMarker
                MapRequestType.DIRECTIONS -> directionsMarker
                MapRequestType.NEARBY -> listOf("nearby ", "kacher ", "কাছের ", "আশেপাশের ").firstOrNull { t.contains(it) }
                MapRequestType.STREET_VIEW -> listOf("street view", "রাস্তার ছবি", "স্ট্রিট ভিউ").firstOrNull { t.contains(it) }
            }
            destination = marker?.let { found ->
                val after = t.substringAfter(found).trim()
                if (after.isNotBlank()) cleanMapPlace(after) else cleanMapPlace(t.substringBefore(found))
            }
        }
        if (destination == null && navigationMarker != null && t.contains(navigationMarker)) {
            destination = cleanMapPlace(t.substringBefore(navigationMarker))
        }
        val finalDestination = destination?.takeIf { it.isNotBlank() }?.take(300)
            ?: return unsupported("Maps-e kothay jaben ba ki khujben, destination bolun.")
        return ok(
            NuvaAction.MapNavigation(requestType, finalDestination, origin?.take(300), mode),
            "Maps-e $finalDestination ${requestType.wireName} khulchi.",
        )
    }

    private fun cleanMapPlace(raw: String): String {
        var value = raw
        listOf(
            "walking", "walk", "driving", "drive", "bicycle", "cycling", "transit", "public transport",
            "hete", "হেঁটে", "পায়ে", "পায়ে", "car e", "গাড়িতে", "গাড়িতে", "dekhao", "show", "please",
        ).forEach { value = value.replace(it, " ") }
        TAIL_VERBS.forEach { value = swapWord(value, it) }
        return value.replace(Regex("""\s+"""), " ").trim(' ', ',', '.', ':', '?', '!')
    }

    // --- 3. Device status ---------------------------------------------------------------

    private fun parseDeviceStatus(t: String): CommandDecision? {
        val battery = listOf(
            "battery", "battary", "bettery", "charge koto", "charge koy", "চার্জ কত",
            "কত চার্জ", "ব্যাটারি", "battery percentage", "battery percent",
        ).any { t.contains(it) }
        if (battery && !listOf("battery saver", "battery setting", "power saving", "ব্যাটারি সেভার").any { t.contains(it) }) {
            return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.BATTERY), "Battery dekhe nicchi.")
        }
        if (listOf("uptime", "phone koto khon on", "device koto khon cholche", "কতক্ষণ ধরে ফোন চলছে", "ফোন আপটাইম")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.UPTIME), "Uptime dekhe nicchi.")

        // Speech recognition writes the same Banglish sentence many ways. In
        // particular, users commonly say/type "akn koyta baje" rather than
        // the canonical "ekhon koto bajche". Keep these reads local so the
        // answer always comes from the phone's clock, not an AI guess.
        val nowWords = listOf(
            "ekhon", "akhon", "akon", "akn", "ekhn", "now", "current",
            "এখন", "বর্তমান",
        )
        val timePhrases = listOf(
            "koyta baje", "koita baje", "koi ta baje", "koy ta baje", "kota baje", "koto baje",
            "koyta bajche", "koita bajche", "koto bajche", "somoy koto", "shomoy koto", "time koto",
            "what time is it", "what's the time", "current time", "time now", "tell me the time",
            "কটা বাজে", "কয়টা বাজে", "কয়টা বাজে", "কতটা বাজে", "সময় কত", "সময় কত",
            "এখন কটা", "এখন কয়টা", "এখন কয়টা", "বর্তমান সময়", "বর্তমান সময়",
        )
        val asksTime = timePhrases.any { t.contains(it) } ||
            (nowWords.any { NuvaDateTimeParser.hasWord(t, it) } &&
                listOf("time", "somoy", "shomoy", "baje", "bajche", "সময়", "সময়", "বাজে")
                    .any { t.contains(it) })

        val todayWords = listOf("aj", "aaj", "ajke", "today", "আজ", "আজকে", "আজকের")
        val datePhrases = listOf(
            "aj koto tarik", "aj koto tarikh", "aaj koto tarik", "ajke koto tarik", "ajke koto tarikh",
            "aj tarikh koto", "aj tarik koto", "ajker tarik", "ajker tarikh", "aj ki tarik", "aj ki tarikh",
            "tarikh koto", "tarik koto", "date koto", "today's date", "todays date", "date today",
            "what is the date", "what's the date", "what day is it", "aj kibar", "aj ki bar", "ajke ki bar",
            "আজ কি বার", "আজ কী বার", "আজকে কি বার", "আজকে কী বার", "আজ কত তারিখ", "আজকে কত তারিখ",
            "আজ কী তারিখ", "আজ কি তারিখ", "আজকের তারিখ", "আজকের দিন", "আজ কী দিন",
        )
        val asksDate = datePhrases.any { t.contains(it) } ||
            (todayWords.any { NuvaDateTimeParser.hasWord(t, it) } &&
                listOf("tarik", "tarikh", "date", "kibar", "ki bar", "তারিখ", "কি বার", "কী বার")
                    .any { t.contains(it) })

        if (asksTime && asksDate) {
            return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.DATE_TIME), "Tarikh o somoy dekhe nicchi.")
        }
        if (asksTime) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.TIME), "Somoy dekhe nicchi.")
        if (asksDate) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.DATE), "Tarikh dekhe nicchi.")

        if (listOf("phone model", "device info", "phone info", "android version", "mobile model", "ফোনের মডেল", "অ্যান্ড্রয়েড ভার্সন")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.DEVICE_INFO), "Device info dekhe nicchi.")

        if (listOf("ram koto", "ram status", "available ram", "free ram", "র‍্যাম কত", "র‍্যাম স্ট্যাটাস")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.MEMORY), "RAM status dekhe nicchi.")

        if (listOf("display resolution", "screen resolution", "display size pixel", "স্ক্রিন রেজোলিউশন", "ডিসপ্লে রেজোলিউশন")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.DISPLAY), "Display info dekhe nicchi.")

        if (listOf("volume koto", "audio status", "ringer mode", "sound status", "ভলিউম কত", "রিঙ্গার মোড")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.AUDIO), "Audio status dekhe nicchi.")

        if (listOf("timezone", "time zone", "utc offset", "টাইমজোন", "সময় অঞ্চল", "সময় অঞ্চল")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.TIMEZONE), "Timezone dekhe nicchi.")

        if (listOf("phone language ki", "current locale", "locale ki", "system language", "ফোনের ভাষা কী", "লোকেল কী")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.LOCALE), "Locale dekhe nicchi.")

        if (listOf("koyta app installed", "installed app count", "app koyta ache", "কয়টা অ্যাপ আছে", "ইনস্টলড অ্যাপ কত")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.INSTALLED_APPS), "Installed app count korchi.")

        if (listOf("sensor list", "phone e ki sensor", "koyta sensor", "সেন্সর লিস্ট", "কী সেন্সর আছে")
                .any { t.contains(it) }
        ) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.SENSORS), "Sensor info dekhe nicchi.")

        val network = listOf(
            "internet ache", "internet ase", "internet on ache", "network kothay", "net ache", "net ase",
            "wifi e connected", "নেটওয়ার্ক", "ইন্টারনেট আছে", "নেট আছে", "network status", "connection ache",
        ).any { t.contains(it) }
        if (network) return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.NETWORK), "Network dekhe nicchi.")

        val storage = listOf(
            "storage", "koto jayga", "koto jaiga", "কত জায়গা", "কত জায়গা", "স্টোরেজ",
            "memory koto", "space koto", "free space", "jayga khali", "jaiga khali",
        ).any { t.contains(it) }
        if (storage && !listOf("storage setting", "স্টোরেজ সেটিং").any { t.contains(it) }) {
            return ok(NuvaAction.DeviceStatusQuery(DeviceStatusKind.STORAGE), "Storage dekhe nicchi.")
        }

        return null
    }

    // --- 3a. Current information from the live web --------------------------------------

    /**
     * Fresh data such as weather/news/scores must never be invented by the
     * language model. Date/time/device state are answered directly above;
     * internet-backed topics are sent to a browser search so the result is
     * genuinely current. This is intentionally read-only and LOW risk.
     */
    private fun parseRealtimeInfo(t: String): CommandDecision? {
        val topic = listOf(
            "weather", "abohawa", "আবহাওয়া", "আবহাওয়া", "temperature", "তাপমাত্রা", "brishti", "বৃষ্টি",
            "latest news", "today news", "news today", "ajker news", "ajker khobor", "খবর", "সংবাদ",
            "live score", "score koto", "current score", "cricket score", "football score", "লাইভ স্কোর", "স্কোর কত",
            "traffic", "jam kemon", "rastar obostha", "ট্রাফিক", "যানজট", "রাস্তার অবস্থা",
            "dollar rate", "exchange rate", "gold price", "sonar dam", "fuel price", "market price", "product price",
            "ডলারের রেট", "সোনার দাম", "বাজার দর", "দাম কত",
            "prayer time", "namazer shomoy", "namajer somoy", "নামাজের সময়", "নামাজের সময়",
            "sunrise", "sunset", "সূর্যোদয়", "সূর্যাস্ত", "air quality", "aqi", "বায়ুর মান", "বায়ুর মান",
            "bus schedule", "train schedule", "flight status", "বাসের সময়", "ট্রেনের সময়", "ফ্লাইট স্ট্যাটাস",
        ).any { t.contains(it) }
        if (!topic) return null

        val asksCurrent = listOf(
            "ekhon", "akhon", "akon", "akn", "ekhn", "current", "latest", "live", "today", "aj", "ajke", "ajker",
            "koto", "kemon", "ki", "hobe", "now", "এখন", "আজ", "আজকে", "আজকের", "বর্তমান", "সর্বশেষ",
            "লাইভ", "কত", "কেমন", "কি", "কী", "হবে",
        ).any { if (it.all { ch -> ch.code in 32..127 }) NuvaDateTimeParser.hasWord(t, it) else t.contains(it) }
        if (!asksCurrent) return null

        val query = t.trim(' ', '.', ',', '?', '!', ':').take(300)
        if (query.isBlank()) return null
        val speech = if (query.any { it.code in 0x0980..0x09FF }) {
            "সর্বশেষ তথ্য ওয়েবে খুঁজছি।"
        } else {
            "Latest information web e khujchi."
        }
        return ok(NuvaAction.SearchWeb(query), speech)
    }

    // --- 3b. Media transport (v1.2) -------------------------------------------------------

    private fun parseMediaControl(t: String): CommandDecision? {
        val hasMediaWord = listOf(
            "gaan", "গান", "music", "song", "video", "ভিডিও", "media", "player", "giti", "গীত",
            "track", "ট্র্যাক",
        ).any { t.contains(it) }

        val pause = listOf("pause koro", "pause korun", "pause", "thamo", "থামাও", "band koro music")
            .any { t.contains(it) } && (hasMediaWord || t.contains("pause"))
        val resume = listOf("resume koro", "resume korun", "resume", "abar chalao", "আবার চালাও")
            .any { t.contains(it) } && (hasMediaWord || t.contains("resume"))
        val next = hasMediaWord && listOf("next", "porer", "পরের", "agamir").any { t.contains(it) }
        val previous = hasMediaWord && listOf("previous", "ager", "আগের", "agerta", "prev").any { t.contains(it) }

        return when {
            pause -> ok(NuvaAction.MediaControl(MediaCommand.PAUSE), "Music pause korlam.")
            resume -> ok(NuvaAction.MediaControl(MediaCommand.PLAY), "Music abar chalacchi.")
            next -> ok(NuvaAction.MediaControl(MediaCommand.NEXT), "Porer ta chalacchi.")
            previous -> ok(NuvaAction.MediaControl(MediaCommand.PREVIOUS), "Ager ta chalacchi.")
            else -> null
        }
    }

    // --- 3c. Volume (v1.2) — direct control, Android permits it ---------------------------

    private fun parseVolumeControl(t: String): CommandDecision? {
        val mentionsVolume = listOf("volume", "ভলিউম", "shobdo", "শব্দ", "sound", "সাউন্ড").any { t.contains(it) }
        if (!mentionsVolume) return null
        return when {
            listOf("mute", "নীরব", "চুপ", "bondho shobdo", "shobdo bondho", "শব্দ বন্ধ").any { t.contains(it) } ->
                ok(NuvaAction.VolumeControl(VolumeCommand.MUTE), "Sound mute korlam.")

            listOf("barao", "baran", "badhao", "beshi", "up", "বাড়াও", "বাড়ান", "বেশি", "চড়াও").any { t.contains(it) } ->
                ok(NuvaAction.VolumeControl(VolumeCommand.UP), "Volume baracchi.")

            listOf("kom koro", "koman", "kom", "namiye", "নামাও", "কম করো", "কমাও").any { t.contains(it) } ->
                ok(NuvaAction.VolumeControl(VolumeCommand.DOWN), "Volume kome dicchi.")

            else -> null // "volume setting" etc. falls through to parseSettings
        }
    }

    // --- 3d. Camera (v1.2) ------------------------------------------------------------------

    private fun parseCamera(t: String): CommandDecision? {
        val mentionsCamera = listOf("camera", "ক্যামেরা", "chobi tolo", "ছবি তোলো").any { t.contains(it) }
        if (!mentionsCamera) return null
        return when {
            listOf("chobi tolo", "photo tolo", "ছবি তোলো", "ছবি তুলে দাও", "take a photo", "picture tolo")
                .any { t.contains(it) } ->
                ok(
                    NuvaAction.CameraOpen(CaptureMode.CAPTURE),
                    "Camera khulchi photo mode e — shutter apni chapun.",
                )

            listOf("video", "ভিডিও").any { t.contains(it) } ->
                ok(NuvaAction.CameraOpen(CaptureMode.VIDEO), "Video camera khulchi.")

            else -> ok(NuvaAction.CameraOpen(CaptureMode.PHOTO), "Camera khulchi.")
        }
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
        if (listOf("sound setting", "volume setting", "volume setting", "সাউন্ড সেটিং", "শব্দের সেটিং").any { t.contains(it) }) {
            return ok(
                NuvaAction.OpenSettingScreen(SettingTarget.VOLUME),
                "Sound o volume setting screen khulchi.",
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
        val extendedTarget = when {
            listOf("mobile data setting", "data usage setting", "মোবাইল ডাটা সেটিং").any { t.contains(it) } -> SettingTarget.MOBILE_DATA
            listOf("airplane mode", "flight mode", "এয়ারপ্লেন মোড", "ফ্লাইট মোড").any { t.contains(it) } -> SettingTarget.AIRPLANE_MODE
            listOf("location setting", "gps setting", "লোকেশন সেটিং", "জিপিএস সেটিং").any { t.contains(it) } -> SettingTarget.LOCATION
            listOf("hotspot setting", "tether setting", "হটস্পট সেটিং").any { t.contains(it) } -> SettingTarget.HOTSPOT
            listOf("nfc setting", "এনএফসি সেটিং").any { t.contains(it) } -> SettingTarget.NFC
            listOf("vpn setting", "ভিপিএন সেটিং").any { t.contains(it) } -> SettingTarget.VPN
            listOf("battery saver", "power saving", "ব্যাটারি সেভার").any { t.contains(it) } -> SettingTarget.BATTERY_SAVER
            listOf("default app", "default apps", "ডিফল্ট অ্যাপ").any { t.contains(it) } -> SettingTarget.DEFAULT_APPS
            listOf("date time setting", "date and time setting", "তারিখ সময় সেটিং", "তারিখ সময় সেটিং").any { t.contains(it) } -> SettingTarget.DATE_TIME
            listOf("language setting", "ভাষা সেটিং").any { t.contains(it) } -> SettingTarget.LANGUAGE
            listOf("storage setting", "স্টোরেজ সেটিং").any { t.contains(it) } -> SettingTarget.STORAGE_SETTINGS
            listOf("privacy setting", "প্রাইভেসি সেটিং", "গোপনীয়তা সেটিং").any { t.contains(it) } -> SettingTarget.PRIVACY
            listOf("security setting", "সিকিউরিটি সেটিং", "নিরাপত্তা সেটিং").any { t.contains(it) } -> SettingTarget.SECURITY
            listOf("cast setting", "screen cast", "কাস্ট সেটিং").any { t.contains(it) } -> SettingTarget.CAST
            listOf("print setting", "printing setting", "প্রিন্ট সেটিং").any { t.contains(it) } -> SettingTarget.PRINT
            listOf("caption setting", "subtitle setting", "ক্যাপশন সেটিং").any { t.contains(it) } -> SettingTarget.CAPTIONS
            else -> null
        }
        if (extendedTarget != null) {
            return ok(NuvaAction.OpenSettingScreen(extendedTarget), "${extendedTarget.wireName} screen khulchi — final change apni korben.")
        }
        if (listOf("notification setting", "notification settings", "নোটিফিকেশন সেটিং")
                .any { t.contains(it) }
        ) return ok(NuvaAction.OpenSettingScreen(SettingTarget.NOTIFICATION_SETTINGS), "Notification settings khulchi.")

        if (listOf("app setting", "app settings", "nuva er setting", "NUVA-র app settings")
                .any { t.contains(it) }
        ) return ok(NuvaAction.OpenSettingScreen(SettingTarget.APP_SETTINGS), "App settings khulchi.")

        if (listOf("accessibility setting", "accessibility settings", "অ্যাক্সেসিবিলিটি সেটিং")
                .any { t.contains(it) }
        ) return ok(NuvaAction.OpenSettingScreen(SettingTarget.ACCESSIBILITY_SETTINGS), "Accessibility settings khulchi.")

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
        val wantsRead = listOf("dekhao", "dekhan", "poro", "read", "show", "দেখাও", "দেখান", "পড়ো", "পড়ো")
            .any { t.contains(it) }
        if (wantsRead) {
            val savedKind = when {
                listOf("shopping list", "grocery list", "bazar list", "বাজারের তালিকা", "বাজারের লিস্ট", "শপিং লিস্ট")
                    .any { t.contains(it) } -> SavedItemKind.SHOPPING
                listOf("expense", "khoroch", "খরচ").any { t.contains(it) } -> SavedItemKind.EXPENSE
                listOf("todo", "to do", "kaj list", "কাজের তালিকা", "টুডু").any { t.contains(it) } -> SavedItemKind.TODO
                listOf("note", "নোট").any { t.contains(it) } -> SavedItemKind.NOTE
                else -> null
            }
            if (savedKind != null) {
                return ok(NuvaAction.ReadSavedItems(savedKind), "${savedKind.wireName} list porchi.")
            }
        }

        // Shopping/grocery list reuses the local to-do store, with a visible
        // prefix so it remains useful without adding another database/table.
        val shoppingMarker = listOf(
            "shopping list e", "shopping list", "grocery list e", "grocery list", "bazar list e", "bazarer list e",
            "বাজারের তালিকায়", "বাজারের তালিকায়", "বাজারের লিস্টে", "শপিং লিস্টে",
        ).firstOrNull { t.contains(it) }
        if (shoppingMarker != null) {
            val raw = contentAfter(t, shoppingMarker)
            val content = raw?.let { cleanListContent(it) }
            if (content.isNullOrBlank()) return unsupported("Shopping list e ki add korbo?")
            return ok(NuvaAction.CreateTodo("Shopping: $content"), "Shopping list e add korlam.")
        }

        // Expense logging is a local note only; it never opens or automates a
        // financial app and therefore does not cross the transaction boundary.
        val expenseMarker = listOf(
            "expense note", "expense log", "khoroch likhe rakho", "khoroch note koro", "খরচ লিখে রাখো", "খরচ নোট করো",
        ).firstOrNull { t.contains(it) }
        if (expenseMarker != null) {
            val content = contentAfter(t, expenseMarker)?.let { cleanListContent(it) }
                ?: t.replace(expenseMarker, " ").let { cleanListContent(it) }
            if (content.isBlank()) return unsupported("Khoroch er poriman o karon bolun.")
            return ok(NuvaAction.CreateNote("Expense: $content"), "Expense note kore nilam.")
        }

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
        // Pronoun follow-up: "oke call koro" → resolved from context by the executor.
        val pronounCall = ContextMemory.PRONOUN_CONTACTS.firstOrNull { p ->
            t.startsWith("$p ") || t.startsWith("$p,")
        } != null && listOf("call", "phone", "ফোন", "কল").any { t.contains(it) }
        if (pronounCall) {
            val pronoun = ContextMemory.PRONOUN_CONTACTS.first { p -> t.startsWith("$p ") || t.startsWith("$p,") }
            return ok(NuvaAction.CallContact(pronoun, null), "Last contact ke call korbo — nishchit korun.", risk = NuvaRisk.MEDIUM)
        }

        val isCall = listOf(
            "call koro", "call korun", "call dao", "call diya jao", "phone koro", "phone korun",
            "ফোন করো", "ফোন করুন", "কল করো", "কল দাও", "dial koro", "যোগাযোগ করো",
        ).any { t.contains(it) }
        if (!isCall) return null

        // Raw number beats everything: "call koro 01712345678" / "01712-345678"
        PHONE_NUMBER.find(t)?.let { m ->
            val number = digitsOnly(m.value)
            val action = NuvaAction.CallContact(number, number)
            return ok(action, "Call: $number — nishchit korun.", risk = NuvaRisk.MEDIUM)
        }

        val name = contactName(t, callMode = true) ?: return unsupported("Kake call korbo? Nam bole din.")
        return ok(NuvaAction.CallContact(name, null), "$name ke call korbo — nishchit korun.", risk = NuvaRisk.MEDIUM)
    }

    // --- 8b. Chat open (v1.4) — "Rohim-er chat kholo" ---------------------------------------

    private val CHAT_MARKERS = listOf(" er chat", " chat", " er chat e", "ের চ্যাট", "এর চ্যাট", " চ্যাট")

    private fun parseChatOpen(t: String): CommandDecision? {
        val hasOpenVerb = OPEN_VERBS.any { t.contains(it) }
        if (!hasOpenVerb) return null
        val marker = CHAT_MARKERS.firstOrNull { t.contains(it) } ?: return null

        val app = when {
            WHATSAPP_WORDS.any { t.contains(it) } -> MessagingApp.WHATSAPP
            SMS_WORDS.any { t.contains(it) } -> MessagingApp.SMS
            t.contains("telegram") -> MessagingApp.TELEGRAM
            t.contains("messenger") -> MessagingApp.MESSENGER
            t.contains("signal") -> MessagingApp.SIGNAL
            t.contains("viber") -> MessagingApp.VIBER
            t.contains("imo") -> MessagingApp.IMO
            else -> MessagingApp.WHATSAPP // executor overrides from context when present
        }

        val idx = t.indexOf(marker)
        var rawName = if (idx > 0) t.substring(0, idx).trim() else ""
        // Bangla possessive suffix: "রহিমের" → "রহিম".
        if (rawName.length > 3 && (rawName.endsWith("ের") || rawName.endsWith("এর"))) {
            rawName = rawName.dropLast(2)
        }
        // Pronouns pass through — the executor resolves them from context.
        val contact = ContextMemory.PRONOUN_CONTACTS.firstOrNull { p -> rawName.endsWith(p) || rawName == p }
            ?: cleanName(rawName)

        if (contact.isNullOrBlank()) {
            return unsupported("Kake চ্যাট খুলব — contact er nam bole din.")
        }
        val display = if (ContextMemory.isContactPronoun(contact)) contact else contact
        return ok(
            NuvaAction.OpenChat(app, display, null),
            "${display.replaceFirstChar { it.uppercase() }}-er chat khulchi.",
        )
    }

    // --- 9. Messages ------------------------------------------------------------------------

    private val WHATSAPP_WORDS = listOf("whatsapp e", "whatsapp", "হোয়াটসঅ্যাপে", "হোয়াটসঅ্যাপ", "hatsapp e", "whats app")
    private val SMS_WORDS = listOf("sms", "es em es", "message e", "এসএমএস", "এস এম এস", "মেসেজে", "text koro")

    /** Markers that imply "say this to someone" even without an app name. */
    private val SAY_MARKERS = listOf(
        "message dau", "message dao", "msg dau", "msg dao",
        "bolo", "bole dao", "bole din", "bolun", "bolen", "bolena",
        "বলো", "বলুন", "বলে দাও", "বলে দিন", "মেসেজ দাও",
    )

    private fun parseSendMessage(t: String): CommandDecision? {
        val appWords = WHATSAPP_WORDS.firstOrNull { t.contains(it) }
        val smsWords = SMS_WORDS.firstOrNull { t.contains(it) }
        val sayMarker = SAY_MARKERS.firstOrNull { t.contains(it) }
        val pronoun = ContextMemory.PRONOUN_CONTACTS.firstOrNull { p ->
            t.startsWith("$p ") || t.startsWith("$p,") || t.contains(" $p ")
        }
        if (appWords == null && smsWords == null && sayMarker == null && pronoun == null) return null
        // No app named → default WhatsApp (BD's most used); the confirmation
        // dialog always shows the app before anything is sent.
        val app = if (appWords != null) MessagingApp.WHATSAPP
        else if (smsWords != null) MessagingApp.SMS
        else MessagingApp.WHATSAPP

        val sendVerb = listOf("pathao", "pathan", "pathiye dao", "পাঠাও", "পাঠান", "send koro", "send korun", "dau")
            .any { t.contains(it) } || t.contains("message") || t.contains("মেসেজ") || sayMarker != null

        // Just opening the app: "whatsapp khulo" is handled by parseOpenApp.

        val number = PHONE_NUMBER.find(t)?.let { digitsOnly(it.value) }
        val name = contactName(t, callMode = false) ?: pronoun
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
            "message dau", "message dao", "msg dau", "msg dao", "message:", "msg:",
            "message pathao", "bole dao", "bole din", "bolun", "bolena", "bolen", "bole diya",
            "bolo", "message e", "বলে দাও", "বলে দিন", "বলুন", "বলো", "মেসেজ দাও", "মেসেজ:", "মেসেজে",
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

    // --- 10b. Maps / LOCATION (v1.4) ----------------------------------------------------------

    private fun parseMaps(t: String): CommandDecision? {
        val hasMapWord = t.contains("map") || t.contains("ম্যাপ") ||
            t.contains("location") || t.contains("কোথায়") || t.contains("kothay")
        if (!hasMapWord) return null

        val query = when {
            t.contains("map e") -> contentAfter(t, "map e")
            t.contains("maps e") -> contentAfter(t, "maps e")
            t.contains("er location") -> t.substringBefore(" er location").trim().takeIf { it.isNotBlank() }
            t.contains("er map") -> t.substringBefore(" er map").trim().takeIf { it.isNotBlank() }
            t.contains("কোথায়") || t.contains("kothay") ->
                t.replace("kothay", " ").replace("কোথায়", " ").replace("ache", " ").replace("আছে", " ")
                    .replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?').ifBlank { null }
            else -> null
        } ?: return null

        // Place phrases may carry filler words; strip the common ones.
        val cleaned = query.split(" ")
            .filterNot { it in listOf("khujho", "dekhao", "dekhan", "bolo", "jao", "koro", "korun") }
            .joinToString(" ").trim()
        if (cleaned.length !in 2..120) return null

        val url = com.nuva.assistant.resolver.EntityNormalizers.mapsSearchUrl(cleaned)
        return ok(NuvaAction.OpenUrl(url), "\"$cleaned\" map e khujchi.")
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

    /**
     * Safe catch-all for factual/how-to daily questions. Instead of returning
     * UNSUPPORTED or letting an LLM invent an answer, NUVA opens a web search
     * containing the user's full query. Action/phone commands have already had
     * first refusal above, so this cannot steal executable intents.
     */
    private fun parseKnowledgeSearch(t: String): CommandDecision? {
        val question = listOf(
            "what ", "how ", "why ", "who ", "where ", "when ", "which ", "meaning of", "define ",
            "ki ", "kivabe", "keno", "kothay", "kokhon", "kar ", "mane ki", "konti", "kon ",
            "কী ", "কি ", "কিভাবে", "কীভাবে", "কেন", "কোথায়", "কোথায়", "কখন", "কে ", "মানে কী",
        ).any { t.startsWith(it) || t.contains(" $it") } ||
            listOf(
                " ki", " keno", " kothay", " kokhon", " koto", " kemon",
                " কী", " কি", " কেন", " কোথায়", " কোথায়", " কখন", " কত", " কেমন",
            ).any { t.endsWith(it) }
        val usefulTopic = listOf(
            "recipe", "রেসিপি", "রান্না", "meaning", "মানে", "dictionary", "অভিধান", "translate", "translation",
            "অনুবাদ", "near me", "nearby", "কাছাকাছি", "schedule", "সময়সূচি", "সময়সূচি", "routine", "রুটিন",
            "price", "dam koto", "দাম কত", "bus", "train", "flight", "বাস", "ট্রেন", "ফ্লাইট", "doctor",
            "hospital", "medicine", "ডাক্তার", "হাসপাতাল", "ওষুধ", "school", "college", "job", "স্কুল", "কলেজ", "চাকরি",
            "how to", "upay ki", "উপায়", "উপায়",
        ).any { t.contains(it) }
        if (!question && !usefulTopic) return null
        if (t.length !in 3..300) return null

        val speech = if (t.any { it.code in 0x0980..0x09FF }) {
            "নির্ভরযোগ্য ও হালনাগাদ তথ্য ওয়েবে খুঁজছি।"
        } else {
            "Reliable updated information web e khujchi."
        }
        return ok(NuvaAction.SearchWeb(t), speech)
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
        // LEVEL 1: closing/going home is normal navigation, always allowed.
        return ok(NuvaAction.CloseApp(app, null), "$app bondho kore home e jacchi.")
    }

    private fun parseOpenApp(t: String): CommandDecision? {
        val verb = OPEN_VERBS.firstOrNull { t.contains(it) } ?: return null
        val app = appNameFrom(t, verb) ?: return null
        // LEVEL 1 (financial policy): launching a wallet/bank app by voice is
        // ALLOWED. Transaction commands were already refused before parsing;
        // in-app tap/type automation is blocked at the accessibility guard.
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
            val suspicious = listOf("call", "phone", "kholo", "bondho", "open", "launch").any { candidate.contains(it) } ||
                candidate.contains(" ar ")
            if (candidate.length in 2..60 && !suspicious) {
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

    private fun cleanListContent(raw: String): String {
        var content = raw
        listOf(
            "add", "add koro", "add korun", "jog koro", "likhe rakho", "note koro",
            "যোগ করো", "অ্যাড করো", "লিখে রাখো", "নোট করো", "দাও", "দিন",
        ).forEach { content = content.replace(it, " ") }
        TAIL_VERBS.forEach { content = swapWord(content, it) }
        return content.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', ',', '!', '?', ':')
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
        speech = SensitiveAppPolicy.TRANSACTION_REFUSAL,
        reasons = listOf(SensitiveAppPolicy.TRANSACTION_REFUSAL_REASON),
        commandId = null,
        source = "offline-security",
    )
}
