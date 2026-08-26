package com.nuva.assistant.command

/**
 * Data-driven command paraphrase grammar.
 *
 * Fifty safe command families × five phrase aliases × seven prefixes × seven
 * suffixes produce 12,250 concrete accepted forms. This is in addition to
 * dynamic app/contact/query slots. Unknown input is tried unchanged first;
 * only then does [rewrite] canonicalize command words and retry the same typed,
 * validated parser. Security policy is run again on the rewritten text.
 */
object NaturalCommandGrammar {

    data class Pattern(val id: String, val canonical: String, val aliases: List<String>)

    private fun pattern(id: String, canonical: String, vararg aliases: String): Pattern =
        Pattern(id, canonical, (listOf(canonical) + aliases).map { NuvaDateTimeParser.normalize(it) }.distinct())

    val patterns: List<Pattern> = listOf(
        pattern("home", "home e jao", "go to home", "home screen e jao", "home cholo", "হোমে যাও"),
        pattern("back", "back jao", "go backward", "previous screen e jao", "pichone firo", "পিছনে যাও"),
        pattern("recents", "recent apps dekhao", "show recent apps", "app switcher dekhao", "recent screen kholo", "রিসেন্ট অ্যাপ দেখাও"),
        pattern("read_screen", "screen poro", "read current screen", "screen e ki lekha", "visible text poro", "স্ক্রিন পড়ো"),
        pattern("describe_screen", "button gulo dekhao", "describe current screen", "ui summary dao", "ki button ache", "বাটনগুলো দেখাও"),
        pattern("read_notifications", "notification poro", "read my notifications", "notification summary dao", "ki notification eseche", "নোটিফিকেশন পড়ো"),
        pattern("notification_panel", "notification panel khulo", "open notification shade", "notification bar namao", "quick notification dekhao", "নোটিফিকেশন প্যানেল খোলো"),
        pattern("notification_app", "notification er app khulo", "open notification app", "latest notification app kholo", "prothom notification kholo", "নোটিফিকেশনের অ্যাপ খোলো"),
        pattern("clear_text", "lekhata muchhe dao", "clear current text", "input khali koro", "typed text delete koro", "লেখাটা মুছে দাও"),
        pattern("media_pause", "music pause koro", "pause current media", "gaan thamao", "audio pause dao", "গান থামাও"),
        pattern("media_resume", "music resume koro", "resume current media", "gaan abar chalao", "audio continue koro", "গান আবার চালাও"),
        pattern("media_next", "next track", "play next song", "porer gaan chalao", "media next koro", "পরের গান চালাও"),
        pattern("media_previous", "previous track", "play previous song", "ager gaan chalao", "media back track", "আগের গান চালাও"),
        pattern("volume_up", "volume barao", "increase sound", "sound up koro", "awaj beshi koro", "ভলিউম বাড়াও"),
        pattern("volume_down", "volume kom koro", "decrease sound", "sound down koro", "awaj komao", "ভলিউম কমাও"),
        pattern("volume_mute", "sound mute koro", "mute current audio", "awaj bondho koro", "silent sound koro", "সাউন্ড মিউট করো"),
        pattern("camera_photo", "camera khulo", "open photo camera", "camera app chalu koro", "photo mode kholo", "ক্যামেরা খোলো"),
        pattern("camera_video", "video camera khulo", "open video mode", "video record screen kholo", "camera video chalu", "ভিডিও ক্যামেরা খোলো"),
        pattern("camera_capture", "chobi tolo", "take a photo", "photo capture kholo", "picture tolar screen dao", "ছবি তোলো"),
        pattern("torch", "torch jalo", "turn flashlight on", "flash light toggle koro", "torch chalu koro", "টর্চ জ্বালাও"),
        pattern("wifi", "wifi on koro", "open wifi settings", "wifi control kholo", "wireless setting dao", "ওয়াইফাই সেটিং খোলো"),
        pattern("bluetooth", "bluetooth on koro", "open bluetooth settings", "bluetooth control kholo", "bt setting dao", "ব্লুটুথ সেটিং খোলো"),
        pattern("brightness", "brightness kom koro", "open brightness settings", "screen light control", "display brightness kholo", "ব্রাইটনেস সেটিং খোলো"),
        pattern("sound_settings", "volume setting khulo", "open sound settings", "audio settings dao", "ringtone volume kholo", "সাউন্ড সেটিং খোলো"),
        pattern("dnd", "dnd on koro", "open do not disturb", "silent mode settings", "dnd setting kholo", "ডু নট ডিস্টার্ব খোলো"),
        pattern("general_settings", "settings khulo", "open phone settings", "system setting chalu koro", "mobile settings dao", "সেটিংস খোলো"),
        pattern("notification_settings", "notification setting khulo", "open notification settings", "app notification control", "notification permission setting", "নোটিফিকেশন সেটিং খোলো"),
        pattern("app_settings", "app setting khulo", "open application settings", "current app info kholo", "manage apps setting", "অ্যাপ সেটিং খোলো"),
        pattern("accessibility_settings", "accessibility setting khulo", "open accessibility settings", "accessibility control dao", "special access setting", "অ্যাক্সেসিবিলিটি সেটিং খোলো"),
        pattern("battery", "battery koto ache", "battery percentage bolo", "charge level koto", "phone charge check", "ব্যাটারি কত আছে"),
        pattern("clock", "ekhon koyta baje", "tell current time", "time now koto", "ghori koto baje", "এখন কয়টা বাজে"),
        pattern("date", "aj koto tarik", "tell todays date", "current date bolo", "ajke ki tarikh", "আজ কত তারিখ"),
        pattern("network", "internet ache", "check network connection", "net connected kina", "internet status bolo", "ইন্টারনেট আছে কিনা"),
        pattern("storage", "storage koto", "free space koto", "phone memory check", "koto jayga khali", "স্টোরেজ কত"),
        pattern("read_notes", "note gulo dekhao", "read saved notes", "amar note poro", "show note list", "নোটগুলো পড়ো"),
        pattern("read_todos", "todo list dekhao", "read task list", "amar kaj gulo poro", "show saved todos", "টুডু লিস্ট দেখাও"),
        pattern("read_shopping", "shopping list dekhao", "read grocery list", "bazar list poro", "show shopping items", "বাজারের তালিকা পড়ো"),
        pattern("read_expenses", "khoroch gulo poro", "read expense notes", "expense list dekhao", "show spending notes", "খরচগুলো পড়ো"),
        pattern("help", "ki ki korte paro", "show supported commands", "help features bolo", "tomar kaj gulo ki", "কী কী করতে পারো"),
        pattern("greeting", "assalamu alaikum", "hello nuva", "hi assistant", "salam nuva", "আসসালামু আলাইকুম"),
        pattern("thanks", "dhonnobad", "thank you nuva", "thanks assistant", "onek thanks", "ধন্যবাদ"),
        pattern("identity", "tumi ke", "who are you", "tomar porichoy dao", "nuva ki", "তুমি কে"),
        pattern("coin", "coin toss koro", "flip a coin", "head tail choose", "coin chure dao", "কয়েন টস করো"),
        pattern("dice", "roll dice", "throw a dice", "dice result dao", "chokka chalo", "ডাইস রোল করো"),
        pattern("weather", "ajker weather kemon", "current weather bolo", "abohawa update dao", "brishti hobe kina", "আজকের আবহাওয়া কেমন"),
        pattern("news", "latest news ki", "today news dekhao", "ajker khobor dao", "breaking news kholo", "আজকের খবর কী"),
        pattern("live_score", "live score koto", "current match score", "khelar score bolo", "cricket update dao", "লাইভ স্কোর কত"),
        pattern("traffic", "traffic kemon", "current road traffic", "rastar jam bolo", "live traffic update", "রাস্তার যানজট কেমন"),
        pattern("prayer", "namazer shomoy koto", "current prayer time", "salat time bolo", "ajker namaj time", "নামাজের সময় কত"),
        pattern("air_quality", "air quality kemon", "current aqi bolo", "batasher man koto", "pollution level check", "বায়ুর মান কেমন"),
    )

    val rawPrefixes: List<String> = listOf("", "nuva ", "hey nuva ", "please ", "nuva please ", "doya kore ", "ektu ")
    val suffixes: List<String> = listOf("", " please", " ekhon", " ekhoni", " kore dao", " bolen", " taratari")

    val supportedStaticFormCount: Int = patterns.sumOf { it.aliases.size } * rawPrefixes.size * suffixes.size

    init {
        check(patterns.size == 50) { "NaturalCommandGrammar requires 50 command families" }
        check(patterns.all { it.aliases.size == 5 }) { "Every command family must have exactly five aliases" }
        check(supportedStaticFormCount >= 10_000) { "Grammar must represent at least 10,000 forms" }
        check(patterns.flatMap { it.aliases }.toSet().size == patterns.sumOf { it.aliases.size }) {
            "Command aliases must be unique"
        }
    }

    /** Generates the concrete raw forms used by the count/audit test. */
    fun generatedStaticForms(): Sequence<String> = sequence {
        patterns.forEach { pattern ->
            pattern.aliases.forEach { alias ->
                rawPrefixes.forEach { prefix ->
                    suffixes.forEach { suffix -> yield("$prefix$alias$suffix".trim()) }
                }
            }
        }
    }

    fun canonicalStatic(rawText: String): String? {
        val text = normalizedCore(rawText)
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.takeIf { candidate -> candidate.aliases.any { it == text } }?.canonical
        }
    }

    fun rewrite(rawText: String): String {
        var text = normalizedCore(rawText)
        canonicalStatic(text)?.let { return it }

        // Conservative dynamic-command vocabulary. This runs only after the
        // original parser misses, so contact names/message bodies that already
        // parsed successfully remain untouched.
        DYNAMIC_REWRITES.forEach { (alias, canonical) ->
            text = text.replace(alias, canonical)
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    private fun normalizedCore(rawText: String): String {
        var text = NuvaDateTimeParser.normalize(rawText).trim(' ', '.', ',', '!', '?', ':')
        text = WAKE_PREFIX.replace(text, "").trim()
        return stripWrappers(text)
    }

    private fun stripWrappers(raw: String): String {
        var text = raw
        var changed: Boolean
        do {
            changed = false
            RUNTIME_PREFIXES.firstOrNull { it.isNotEmpty() && text.startsWith(it) }?.let {
                text = text.removePrefix(it).trim()
                changed = true
            }
            suffixes.asSequence().filter { it.isNotEmpty() }.map { it.trim() }
                .firstOrNull { text.endsWith(" $it") }?.let {
                    text = text.removeSuffix(" $it").trim()
                    changed = true
                }
        } while (changed)
        return text
    }

    private val RUNTIME_PREFIXES = listOf("nuva please ", "hey nuva ", "doya kore ", "please ", "nuva ", "ektu ", "plz ")
    private val WAKE_PREFIX = Regex("""^\s*(hey\s+nuva|nuva|নুভা)\s*[,.!]?\s*""", RegexOption.IGNORE_CASE)

    private val DYNAMIC_REWRITES = listOf(
        "open kore dao" to "open koro",
        "open kore din" to "open koro",
        "launch kore dao" to "open koro",
        "khuilla dao" to "kholo",
        "khule den" to "kholo",
        "খুলে দিন" to "খোলো",
        "bondho kore dao" to "bondho koro",
        "close kore dao" to "close koro",
        "বন্ধ করে দিন" to "বন্ধ করো",
        "search kore dao" to "search koro",
        "khuje ber koro" to "khujho",
        "সার্চ করে দাও" to "সার্চ করো",
        "call kore dao" to "call koro",
        "phone lagiye dao" to "call koro",
        "ফোন করে দাও" to "ফোন করো",
        "message kore dao" to "message pathao",
        "msg kore dao" to "message pathao",
        "মেসেজ করে দাও" to "মেসেজ পাঠাও",
        "remind kore dio" to "reminder dao",
        "mone korai dio" to "mone koriye dao",
        "মনে করিয়ে দিও" to "মনে করিয়ে দাও",
        "paymnt" to "payment",
        "paymentt" to "payment",
        "send mony" to "send money",
        "pasword" to "password",
        "passwrd" to "password",
    )
}
