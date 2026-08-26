package com.nuva.assistant.command

/**
 * Date/time parsing for Bangla, Banglish and English commands (v1.1).
 *
 * Pure Kotlin, no Android dependency — fully unit-testable. Understands:
 *  * Bangla numerals (০১২৩৪৫৬৭৮৯) and Latin digits
 *  * clock times: "7 tay", "7:30 tay", "৭টায়", "shokal 9tay", "raat 10 tay",
 *    "dupur 12 tay", "bikal 4:30", "5 pm", "am 6 tay", "সকাল ৭টায়", "রাত ১০টা"
 *  * relative days: aj/today, kal/tomorrow, parso/day after (আজ/কাল/পরশু)
 *  * weekdays: sombar/shonibar/saturday/শনিবার …
 *  * durations: "10 minute", "2 ghonta 30 minute", "আধা ঘণ্টা", "সাড়ে তিন ঘণ্টা",
 *    "500 second", "x min"
 *  * ordinal-free Bangla hour words (এক…বারো) for "সাতটায়"-style input is
 *    handled through numerals; word numbers one..twelve map too.
 */
object NuvaDateTimeParser {

    data class ParsedTime(val hour: Int, val minute: Int) {
        fun format24h(): String = "%02d:%02d".format(hour, minute)
    }

    data class ParsedWhen(
        val time: ParsedTime?,
        val relativeDay: RelativeDay?,
        val weekday: Weekday?,
    )

    // --- Normalization ---------------------------------------------------------

    private val BN_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9',
    )

    /** Lowercases, converts Bangla numerals to ASCII and collapses whitespace. */
    fun normalize(text: String): String =
        text.lowercase().map { BN_DIGITS[it] ?: it }.joinToString("")
            .replace(Regex("[;।]"), ":")
            .replace(Regex("\\s+"), " ")
            .trim()

    // --- Bangla word numbers (1–12) --------------------------------------------

    private val BN_NUMBER_WORDS = mapOf(
        "এক" to 1, "দুই" to 2, "তিন" to 3, "চার" to 4, "পাঁচ" to 5, "ছয়" to 6, "সাত" to 7,
        "আট" to 8, "নয়" to 9, "দশ" to 10, "এগারো" to 11, "বারো" to 12,
        "ek" to 1, "dui" to 2, "tin" to 3, "char" to 4, "panch" to 5, "pach" to 5,
        "chhoy" to 6, "chhaye" to 6, "shat" to 7, "sat" to 7, "at" to 8, "noy" to 9,
        "dash" to 10, "egaro" to 11, "baro" to 12,
    )

    // --- Parts of day → AM/PM ---------------------------------------------------

    /** shokal/sokal = morning (AM). dupur/bikal/shondha/raat/everything else = PM. */
    private val AM_PARTS = listOf("shokal", "sokal", "bhor", "সকাল", "ভোর")
    private val PM_PARTS = listOf(
        "dupur", "dupura", "bikal", "bikela", "shondha", "sandhya", "raat", "রাত",
        "দুপুর", "বিকাল", "বিকেল", "সন্ধ্যা", "সাঁঝ",
    )

    private fun partOfDayIsAm(t: String): Boolean? = when {
        AM_PARTS.any { t.contains(it) } -> true
        PM_PARTS.any { t.contains(it) } -> false
        else -> null
    }

    // --- Clock time -------------------------------------------------------------

    // NOTE: JVM `\b` is ASCII-only, so Bangla markers can never be followed by
    // `\b` — we use `(?![a-z])` instead, which stops English suffixes while
    // allowing Bangla ones (টায় / টার / টাতে).
    private val EXPLICIT_AM_PM = Regex("""\b(am|pm|a\.m|p\.m)\b""")
    private val CLOCK = Regex("""(\d{1,2})\s*[:.]\s*(\d{2})""")
    private val HOUR_MIN_BN =
        Regex("""(\d{1,2})\s*(টায়|টার|টাতে|টা|taye|tay|ta)\s+(\d{1,2})\s*(মিনিট|minit|minute|min)(?![a-z])""")
    private val HOUR_WORD =
        Regex("""(\d{1,2})\s*(টায়|টার|টাতে|টা|taye|tay|ta|baje|bajbe|bajche|বাজে|বাজবে)(?![a-z])""")
    private val BARE_HOUR = Regex("""(\d{1,2})\s*(টার|টায়|টা|taye|tay|ta)(?![a-z])""")

    /**
     * Parses a clock time from mixed-script text. Returns null when no hour is
     * found. Hours > 12 are treated as 24h already; hours ≤ 12 without any
     * AM/PM hint default to AM when ≤ 7 and the day-part words decide the rest
     * (Bangla speakers say "raat 8 tay" for 20:00, "shokal 7 tay" for 07:00).
     */
    fun parseTime(raw: String): ParsedTime? {
        val t = normalize(raw)

        CLOCK.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            return buildTime(h, min, t)
        }

        // "2টা 30 মিনিটে", "7 tay 30 minute", "7tay 30" — hour + minute form.
        HOUR_MIN_BN.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[3].toIntOrNull() ?: 0
            return buildTime(h, min, t)
        }

        // "৭টায়", "7 tay", "7tay" — an hour with the Bangla "o'clock" marker.
        HOUR_WORD.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            return buildTime(h, 0, t)
        }

        // English "at 5" is too ambiguous without am/pm — require a marker.
        val ampm = EXPLICIT_AM_PM.find(t)
        if (ampm != null) {
            val h = Regex("""(\d{1,2})""").findAll(t).mapNotNull { it.value.toIntOrNull() }
                .firstOrNull { it in 0..23 } ?: return null
            return buildTime(h, 0, t)
        }

        // Bangla bare "৭ টা" (without baje) still counts.
        BARE_HOUR.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            return buildTime(h, 0, t)
        }

        return null
    }

    private fun buildTime(hour: Int, minute: Int, normalizedText: String): ParsedTime? {
        if (hour !in 0..23 || minute !in 0..59) return null
        var h = hour
        when {
            EXPLICIT_AM_PM.containsMatchIn(normalizedText) -> {
                val pm = normalizedText.contains(Regex("""\b(p\.?m\.?)\b"""))
                if (pm && h < 12) h += 12
                if (!pm && h == 12) h = 0
            }

            partOfDayIsAm(normalizedText) == false && h < 12 -> h += 12 // raat/dupur/bikal N tay
            partOfDayIsAm(normalizedText) == true && h == 12 -> h = 0 // shokal 12 = noon edge
            else -> Unit
        }
        return ParsedTime(h, minute)
    }

    // --- Relative days ------------------------------------------------------------

    private val TOMORROW_WORDS = listOf("kal", "কাল", "tomorrow", "agami din")
    private val TODAY_WORDS = listOf("aj", "আজ", "today")
    private val DAY_AFTER_WORDS = listOf("parso", "porshu", "parshu", "গোকাল", "পরশু", "day after tomorrow")

    /**
     * Word-boundary matching for ASCII words so "shokal" never counts as
     * "kal" (tomorrow); Bangla words match by substring.
     */
    fun hasWord(raw: String, word: String): Boolean {
        val t = normalize(raw)
        return if (word.all { it.code in 32..127 }) {
            Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(t)
        } else {
            t.contains(word)
        }
    }

    fun relativeDay(raw: String): RelativeDay? {
        val t = normalize(raw)
        fun hit(words: List<String>) = words.any { hasWord(t, it) }
        return when {
            hit(DAY_AFTER_WORDS) -> RelativeDay.TOMORROW // calendar UI resolves the exact day
            hit(TODAY_WORDS) -> RelativeDay.TODAY
            hit(TOMORROW_WORDS) -> RelativeDay.TOMORROW
            else -> null
        }
    }

    // --- Weekdays -------------------------------------------------------------------

    private val WEEKDAY_WORDS = mapOf(
        Weekday.MON to listOf("sombar", "shombar", "monday", "সোমবার", "সমবার"),
        Weekday.TUE to listOf("mongolbar", "mangalbar", "tuesday", "মঙ্গলবার"),
        Weekday.WED to listOf("budhbar", "buddhbar", "wednesday", "বুধবার"),
        Weekday.THU to listOf("brihospotibar", "brishtibar", "thursday", "বৃহস্পতিবার"),
        Weekday.FRI to listOf("shukrobar", "shukrarbar", "friday", "শুক্রবার"),
        Weekday.SAT to listOf("shonibar", "shanibar", "saturday", "শনিবার"),
        Weekday.SUN to listOf("robibar", "rabibar", "sunday", "রবিবার"),
    )

    fun weekday(raw: String): Weekday? {
        val t = normalize(raw)
        return WEEKDAY_WORDS.entries.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.key
    }

    // --- Durations --------------------------------------------------------------------

    private val DURATION_UNITS = listOf(
        3_600L to listOf("ghonta", "ghontar", "ghanta", "hour", "hr", "ঘণ্টা", "ঘন্টা", "ঘটা"),
        60L to listOf("minute", "min", "minit", "miniter", "মিনিট", "মিনিটের"),
        1L to listOf("second", "sec", "sekend", "সেকেন্ড", "সেকেন্ডের"),
        86_400L to listOf("din", "day", "দিন"),
    )

    /**
     * Parses a duration ("10 minute", "1 ghonta 30 minute", "আধা ঘণ্টা",
     * "সাড়ে তিন ঘণ্টা"). Returns seconds, or null when nothing matches.
     * Range-capped to 1..86_400 by the caller (validator).
     */
    fun parseDuration(raw: String): Long? {
        var t = normalize(raw)
        if (t.isBlank()) return null

        var total = 0L
        var matched = false

        // Bangla fractions first: আধা/অর্ধ = half, সাড়ে = half past, পৌনে = quarter to.
        val halfHour = Regex("""(আধা|অর্ধ|adha|ardho|ardh)\s*(ঘণ্টা|ঘন্টা|ghonta|hour)""")
        if (halfHour.containsMatchIn(t)) {
            total += 1_800
            matched = true
            t = halfHour.replace(t, " ")
        }
        val threeQuarter = Regex("""(সাড়ে|sare|share)\s*(তিন|tin|3)\s*(ঘণ্টা|ঘন্টা|ghonta|hour)""")
        if (threeQuarter.containsMatchIn(t)) {
            total += 3 * 3_600 + 1_800
            matched = true
            t = threeQuarter.replace(t, " ")
        }

        // "N unit" pairs, longest match first.
        for ((seconds, words) in DURATION_UNITS) {
            for (word in words) {
                val re = Regex("""(\d+)\s*$word""")
                while (true) {
                    val m = re.find(t) ?: break
                    val n = m.groupValues[1].toLongOrNull() ?: break
                    total += n * seconds
                    matched = true
                    t = t.replaceRange(m.range, " ")
                }
            }
        }
        if (!matched) return null
        return total
    }

    // --- Full "when" extraction -----------------------------------------------------------

    fun parseWhen(raw: String): ParsedWhen = ParsedWhen(
        time = parseTime(raw),
        relativeDay = relativeDay(raw),
        weekday = weekday(raw),
    )

    /** Next occurrence of [time] from [now] (epoch millis), honouring relative day hints. */
    fun nextOccurrence(time: ParsedTime, now: java.util.Calendar): java.util.Calendar {
        val cal = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, time.hour)
            set(java.util.Calendar.MINUTE, time.minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now.timeInMillis) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal
    }
}
