package com.nuva.assistant.command

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Deterministic, offline daily-life utility engine.
 *
 * This is deliberately data-driven rather than a list of 1,000 brittle command
 * sentences. Unit pairs × three languages × multiple natural phrase shapes
 * already produce thousands of supported command forms, while calculations,
 * percentages, bills, BMI, EMI, mileage, dates and random choices add more.
 * No result comes from an LLM and no private input leaves the phone.
 */
object DailyUtilityParser {

    data class Result(val answer: String, val category: String)

    private enum class Style { BN, EN, BANGLISH }

    private data class UnitDef(
        val name: String,
        val aliases: List<String>,
        val factor: Double,
        val offset: Double = 0.0,
    ) {
        fun toBase(value: Double): Double = (value + offset) * factor
        fun fromBase(value: Double): Double = value / factor - offset
    }

    private data class Dimension(val name: String, val units: List<UnitDef>)

    private fun unit(name: String, factor: Double, vararg aliases: String, offset: Double = 0.0) =
        UnitDef(name, (listOf(name) + aliases).map { it.lowercase() }.distinct(), factor, offset)

    private val dimensions = listOf(
        Dimension(
            "length",
            listOf(
                unit("millimeter", 0.001, "mm", "millimetre", "মিলিমিটার"),
                unit("centimeter", 0.01, "cm", "centimetre", "সেন্টিমিটার"),
                unit("meter", 1.0, "metre", "meters", "মিটার"),
                unit("kilometer", 1000.0, "km", "kilometre", "kilometers", "কিলোমিটার"),
                unit("inch", 0.0254, "inches", "ইঞ্চি"),
                unit("foot", 0.3048, "feet", "ft", "ফুট"),
                unit("yard", 0.9144, "yards", "গজ"),
                unit("mile", 1609.344, "miles", "মাইল"),
            ),
        ),
        Dimension(
            "weight",
            listOf(
                unit("milligram", 0.000001, "mg", "মিলিগ্রাম"),
                unit("gram", 0.001, "grams", "gm", "গ্রাম"),
                unit("kilogram", 1.0, "kg", "kilo", "কেজি", "কিলোগ্রাম"),
                unit("tonne", 1000.0, "ton", "tons", "টন"),
                unit("ounce", 0.028349523125, "oz", "ounces", "আউন্স"),
                unit("pound", 0.45359237, "lb", "lbs", "pounds", "পাউন্ড"),
                unit("stone", 6.35029318, "stones"),
            ),
        ),
        Dimension(
            "volume",
            listOf(
                unit("milliliter", 0.001, "ml", "millilitre", "মিলিলিটার"),
                unit("liter", 1.0, "litre", "liters", "l", "লিটার"),
                unit("teaspoon", 0.00492892159375, "tsp", "চা চামচ"),
                unit("tablespoon", 0.01478676478125, "tbsp", "টেবিল চামচ"),
                unit("cup", 0.2365882365, "cups", "কাপ"),
                unit("pint", 0.473176473, "pints"),
                unit("gallon", 3.785411784, "gallons", "গ্যালন"),
            ),
        ),
        Dimension(
            "area",
            listOf(
                unit("square meter", 1.0, "sq meter", "sqm", "m2", "বর্গমিটার"),
                unit("square foot", 0.09290304, "sq foot", "sq ft", "sqft", "ft2", "বর্গফুট"),
                unit("decimal", 40.468564224, "shotok", "শতক", "ডেসিমেল"),
                unit("katha", 66.8903, "কাঠা"),
                unit("bigha", 1337.806, "বিঘা"),
                unit("acre", 4046.8564224, "acres", "একর"),
                unit("hectare", 10000.0, "hectares", "হেক্টর"),
            ),
        ),
        Dimension(
            "speed",
            listOf(
                unit("meter/second", 1.0, "m/s", "mps", "meter per second"),
                unit("kilometer/hour", 0.2777777777778, "km/h", "kmph", "kph", "kilometer per hour"),
                unit("mile/hour", 0.44704, "mph", "mile per hour"),
                unit("foot/second", 0.3048, "ft/s", "fps", "foot per second"),
                unit("knot", 0.514444444444, "knots"),
            ),
        ),
        Dimension(
            "time",
            listOf(
                unit("millisecond", 0.001, "ms", "milliseconds"),
                unit("second", 1.0, "sec", "seconds", "সেকেন্ড"),
                unit("minute", 60.0, "min", "minutes", "মিনিট"),
                unit("hour", 3600.0, "hr", "hours", "ghonta", "ঘণ্টা", "ঘন্টা"),
                unit("day", 86400.0, "days", "din", "দিন"),
                unit("week", 604800.0, "weeks", "shoptaho", "সপ্তাহ"),
                unit("month", 2629800.0, "months", "mash", "মাস"),
                unit("year", 31557600.0, "years", "bochor", "বছর"),
            ),
        ),
        Dimension(
            "data",
            listOf(
                unit("byte", 1.0, "bytes"),
                unit("kilobyte", 1024.0, "kb", "kib", "kilobytes"),
                unit("megabyte", 1024.0.pow(2), "mb", "mib", "megabytes"),
                unit("gigabyte", 1024.0.pow(3), "gb", "gib", "gigabytes"),
                unit("terabyte", 1024.0.pow(4), "tb", "tib", "terabytes"),
                unit("petabyte", 1024.0.pow(5), "pb", "pib", "petabytes"),
            ),
        ),
        Dimension(
            "energy",
            listOf(
                unit("joule", 1.0, "j", "joules"),
                unit("kilojoule", 1000.0, "kj", "kilojoules"),
                unit("calorie", 4184.0, "kcal", "calories", "ক্যালরি"),
                unit("watt-hour", 3600.0, "wh", "watt hour"),
            ),
        ),
        Dimension(
            "pressure",
            listOf(
                unit("pascal", 1.0, "pa", "pascals"),
                unit("kilopascal", 1000.0, "kpa", "kilopascals"),
                unit("bar", 100000.0, "bars"),
                unit("psi", 6894.757293168, "pound per square inch"),
                unit("atmosphere", 101325.0, "atm", "atmospheres"),
            ),
        ),
        Dimension(
            "temperature",
            listOf(
                unit("celsius", 1.0, "c", "°c", "centigrade", "সেলসিয়াস"),
                unit("fahrenheit", 5.0 / 9.0, "f", "°f", "ফারেনহাইট", offset = -32.0),
                unit("kelvin", 1.0, "k", "কেলভিন", offset = -273.15),
            ),
        ),
    )

    /** Conservative count of conversion utterance forms represented by the data table. */
    fun supportedCommandForms(): Int {
        val directedPairs = dimensions.sumOf { it.units.size * (it.units.size - 1) }
        return directedPairs * 3 /* languages */ * 3 /* "to", "in", Banglish/Bangla suffix shapes */ + 30
    }

    fun parse(
        rawText: String,
        today: LocalDate = LocalDate.now(),
        randomInt: (Int, Int) -> Int = { from, until -> Random.nextInt(from, until) },
    ): Result? {
        val text = NuvaDateTimeParser.normalize(rawText)
            .replace('×', '*')
            .replace('÷', '/')
            .trim()
        if (text.isBlank() || text.length > 500) return null
        val style = detectStyle(rawText)

        return parseBill(text, style)
            ?: parsePercentage(text, style)
            ?: parseBmi(text, style)
            ?: parseEmi(text, style)
            ?: parseMileage(text, style)
            ?: parseDateUtility(text, style, today)
            ?: parseConversion(text, style)
            ?: parseArithmetic(text, style)
            ?: parseRandom(text, style, randomInt)
    }

    private fun parseBill(t: String, style: Style): Result? {
        val billWord = listOf("bill", "বিল", "hisab", "হিসাব").any { t.contains(it) }
        val splitWord = listOf("split", "vag", " ভাগ", "ভাগ", "share", "per person", "jon e", "জনের", "জন এ")
            .any { t.contains(it) }
        val tipWord = listOf("tip", "টিপ", "service charge").any { t.contains(it) }
        if (!billWord && !splitWord && !tipWord) return null

        val amount = NUMBER.find(t)?.value?.toDoubleOrNull() ?: return null
        val people = Regex("""(\d+(?:\.\d+)?)\s*(jon|people|persons?|জন)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
        val tip = Regex("""(\d+(?:\.\d+)?)\s*(%|percent|পারসেন্ট|শতাংশ)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (amount < 0 || tip < 0 || tip > 1000) return null
        val total = amount * (1.0 + tip / 100.0)

        if (splitWord) {
            if (people == null || people !in 1..10_000) return null
            val each = total / people
            val core = when (style) {
                Style.BN -> "মোট ${number(total)}; $people জনের প্রত্যেকে ${number(each)}।"
                Style.EN -> "Total ${number(total)}; ${number(each)} per person for $people people."
                Style.BANGLISH -> "Mot ${number(total)}; $people jon er prottekke ${number(each)}."
            }
            return Result(core, "bill_split")
        }
        if (tipWord) {
            val core = when (style) {
                Style.BN -> "টিপ ${number(total - amount)}; টিপসহ মোট ${number(total)}।"
                Style.EN -> "Tip ${number(total - amount)}; total with tip ${number(total)}."
                Style.BANGLISH -> "Tip ${number(total - amount)}; tip-shoho mot ${number(total)}."
            }
            return Result(core, "tip")
        }
        return null
    }

    private fun parsePercentage(t: String, style: Style): Result? {
        val pct = "(?:%|percent|percentage|shotangsho|পারসেন্ট|শতাংশ)"
        val ofForm = Regex("""(-?\d+(?:\.\d+)?)\s*$pct\s*(?:of|er|এর|এর মধ্যে)\s*(-?\d+(?:\.\d+)?)""")
            .find(t)
        val baseFirst = Regex("""(-?\d+(?:\.\d+)?)\s*(?:er|এর|of)?\s*(-?\d+(?:\.\d+)?)\s*$pct""")
            .find(t)
        val whatPercent = Regex("""(-?\d+(?:\.\d+)?)\s*(?:is|holo|হলো)?\s*(?:what|koto|কত)\s*$pct\s*(?:of|er|এর)\s*(-?\d+(?:\.\d+)?)""")
            .find(t)

        if (whatPercent != null) {
            val part = whatPercent.groupValues[1].toDouble()
            val base = whatPercent.groupValues[2].toDouble()
            if (base == 0.0) return error(style, "zero diye percentage ber kora jay na", "Cannot divide by zero", "শূন্য দিয়ে শতাংশ বের করা যায় না")
            return answer(style, "${number(part / base * 100.0)}%", "percentage")
        }

        val percent: Double
        val base: Double
        when {
            ofForm != null -> {
                percent = ofForm.groupValues[1].toDouble()
                base = ofForm.groupValues[2].toDouble()
            }
            baseFirst != null -> {
                base = baseFirst.groupValues[1].toDouble()
                percent = baseFirst.groupValues[2].toDouble()
            }
            else -> return null
        }
        if (!percent.isFinite() || !base.isFinite()) return null
        val portion = base * percent / 100.0
        val value = when {
            listOf("discount", "ছাড়", "ছাড়", "komle", "কমলে", "off").any { t.contains(it) } -> base - portion
            listOf("increase", "barle", "barale", "বাড়লে", "বাড়লে", "add", "vat", "tax", "ভ্যাট").any { t.contains(it) } -> base + portion
            else -> portion
        }
        return answer(style, number(value), "percentage")
    }

    private fun parseBmi(t: String, style: Style): Result? {
        if (!listOf("bmi", "body mass", "বিএমআই", "বডি মাস").any { t.contains(it) }) return null
        val kg = Regex("""(\d+(?:\.\d+)?)\s*(kg|kilogram|kilo|কেজি|কিলোগ্রাম)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val meters = Regex("""(\d+(?:\.\d+)?)\s*(meter|metre|মিটার)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val cm = Regex("""(\d+(?:\.\d+)?)\s*(cm|centimeter|centimetre|সেন্টিমিটার)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val feetMatch = Regex("""(\d+(?:\.\d+)?)\s*(foot|feet|ft|ফুট)(?:\s*(\d+(?:\.\d+)?)\s*(inch|inches|ইঞ্চি))?""")
            .find(t)
        val heightM = when {
            cm != null -> cm / 100.0
            meters != null -> meters
            feetMatch != null -> {
                val feet = feetMatch.groupValues[1].toDouble()
                val inches = feetMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                (feet * 12.0 + inches) * 0.0254
            }
            else -> return null
        }
        if (kg !in 1.0..700.0 || heightM !in 0.5..3.0) return null
        val bmi = kg / (heightM * heightM)
        val category = when {
            bmi < 18.5 -> when (style) { Style.BN -> "ওজন কম"; Style.EN -> "underweight"; else -> "weight kom" }
            bmi < 25.0 -> when (style) { Style.BN -> "স্বাভাবিক সীমা"; Style.EN -> "normal range"; else -> "normal range" }
            bmi < 30.0 -> when (style) { Style.BN -> "ওজন বেশি"; Style.EN -> "overweight"; else -> "weight beshi" }
            else -> when (style) { Style.BN -> "স্থূলতার সীমা"; Style.EN -> "obesity range"; else -> "obesity range" }
        }
        val speech = when (style) {
            Style.BN -> "BMI ${number(bmi)}—$category। এটি চিকিৎসকের পরামর্শ নয়।"
            Style.EN -> "BMI ${number(bmi)}—$category. This is not medical advice."
            Style.BANGLISH -> "BMI ${number(bmi)}—$category. Eta medical advice noy."
        }
        return Result(speech, "bmi")
    }

    private fun parseEmi(t: String, style: Style): Result? {
        if (!listOf("emi", "monthly installment", "kisti", "কিস্তি", "ইএমআই").any { t.contains(it) }) return null
        val principal = NUMBER.find(t)?.value?.toDoubleOrNull() ?: return null
        val annualRate = Regex("""(\d+(?:\.\d+)?)\s*(%|percent|পারসেন্ট|শতাংশ)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val years = Regex("""(\d+(?:\.\d+)?)\s*(year|years|bochor|বছর)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val monthsExplicit = Regex("""(\d+)\s*(month|months|mash|মাস)""")
            .find(t)?.groupValues?.get(1)?.toIntOrNull()
        val months = monthsExplicit ?: years?.times(12.0)?.roundToLong()?.toInt() ?: return null
        if (principal <= 0.0 || annualRate < 0.0 || months !in 1..1200) return null
        val monthlyRate = annualRate / 1200.0
        val emi = if (monthlyRate == 0.0) {
            principal / months
        } else {
            val growth = (1.0 + monthlyRate).pow(months)
            principal * monthlyRate * growth / (growth - 1.0)
        }
        val total = emi * months
        val speech = when (style) {
            Style.BN -> "আনুমানিক মাসিক কিস্তি ${number(emi)}; মোট ${number(total)}। ব্যাংকের ফি এতে ধরা নেই।"
            Style.EN -> "Estimated monthly installment ${number(emi)}; total ${number(total)}. Bank fees are not included."
            Style.BANGLISH -> "Anumanik monthly kisti ${number(emi)}; mot ${number(total)}. Bank fee dhora hoyni."
        }
        return Result(speech, "emi")
    }

    private fun parseMileage(t: String, style: Style): Result? {
        if (!listOf("mileage", "average", "km per liter", "km/l", "মাইলেজ", "লিটার প্রতি").any { t.contains(it) }) return null
        val km = Regex("""(\d+(?:\.\d+)?)\s*(km|kilometer|কিলোমিটার)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val liters = Regex("""(\d+(?:\.\d+)?)\s*(liter|litre|liters|l|লিটার)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (km < 0.0 || liters <= 0.0) return null
        return answer(style, "${number(km / liters)} km/liter", "mileage")
    }

    private fun parseDateUtility(t: String, style: Style, today: LocalDate): Result? {
        val dates = extractDates(t)
        val asksAge = listOf("age", "boyos", "বয়স", "বয়স", "jonmo", "জন্ম", "born").any { t.contains(it) }
        if (asksAge) {
            val birth = dates.firstOrNull()
            if (birth != null && birth <= today) {
                var years = today.year - birth.year
                if (today < birth.plusYears(years.toLong())) years--
                return answer(style, "$years ${yearWord(style)}", "age")
            }
            val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(t)?.value?.toIntOrNull()
            if (year != null && year <= today.year) {
                val years = today.year - year
                val prefix = when (style) { Style.BN -> "প্রায় "; Style.EN -> "Approximately "; else -> "Pray " }
                return answer(style, "$prefix$years ${yearWord(style)}", "age")
            }
        }

        val asksDifference = listOf("difference", "between", "parthokko", "পার্থক্য", "মাঝে কত দিন")
            .any { t.contains(it) }
        if (asksDifference && dates.size >= 2) {
            val days = abs(ChronoUnit.DAYS.between(dates[0], dates[1]))
            return answer(style, "$days ${dayWord(style)}", "date_difference")
        }

        val asksRemaining = listOf("days until", "day until", "koto din baki", "কত দিন বাকি", "আর কত দিন", "remaining days")
            .any { t.contains(it) }
        if (asksRemaining && dates.isNotEmpty()) {
            val days = ChronoUnit.DAYS.between(today, dates.first())
            val value = when {
                days > 0 -> "$days ${dayWord(style)}"
                days == 0L -> when (style) { Style.BN -> "আজই"; Style.EN -> "today"; else -> "ajkei" }
                else -> when (style) { Style.BN -> "${abs(days)} দিন আগে"; Style.EN -> "${abs(days)} days ago"; else -> "${abs(days)} din age" }
            }
            return answer(style, value, "days_until")
        }

        val asksWeekday = listOf("what day", "which day", "ki bar", "kibar", "কী বার", "কি বার", "বার ছিল", "বার হবে")
            .any { t.contains(it) }
        if (asksWeekday && dates.isNotEmpty()) {
            return answer(style, weekday(dates.first(), style), "weekday")
        }
        return null
    }

    private fun extractDates(t: String): List<LocalDate> {
        val found = mutableListOf<Pair<Int, LocalDate>>()
        Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b""").findAll(t).forEach { m ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?.let { found += m.range.first to it }
        }
        Regex("""\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b""").findAll(t).forEach { m ->
            safeDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                ?.let { found += m.range.first to it }
        }
        return found.distinctBy { it.second }.sortedBy { it.first }.map { it.second }
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun parseConversion(t: String, style: Style): Result? {
        for (dimension in dimensions) {
            val allAliases = dimension.units.flatMap { unit -> unit.aliases.map { it to unit } }
                .sortedByDescending { it.first.length }
            val aliasPattern = allAliases.joinToString("|") { Regex.escape(it.first) }
            val sourceMatch = Regex("""(-?\d+(?:\.\d+)?)\s*($aliasPattern)(?![\p{L}])""", RegexOption.IGNORE_CASE)
                .find(t) ?: continue
            val sourceAlias = sourceMatch.groupValues[2].lowercase()
            val source = allAliases.firstOrNull { it.first == sourceAlias }?.second ?: continue
            val tail = t.substring(sourceMatch.range.last + 1)
            val targetMatch = allAliases.mapNotNull { (alias, unit) ->
                Regex("""(?<![\p{L}])${Regex.escape(alias)}(?![\p{L}])""", RegexOption.IGNORE_CASE)
                    .find(tail)?.let { Triple(it.range.first, alias.length, unit) }
            }.sortedWith(compareBy<Triple<Int, Int, UnitDef>> { it.first }.thenByDescending { it.second })
                .firstOrNull() ?: continue
            val target = targetMatch.third
            if (source == target) continue
            val value = sourceMatch.groupValues[1].toDoubleOrNull() ?: continue
            val converted = target.fromBase(source.toBase(value))
            if (!converted.isFinite() || abs(converted) > 1e18) return null
            val equation = "${number(value)} ${source.name} = ${number(converted)} ${target.name}"
            return answer(style, equation, "unit_${dimension.name}")
        }
        return null
    }

    private fun parseArithmetic(t: String, style: Style): Result? {
        parseSpecialMath(t)?.let { return answer(style, number(it), "calculation") }

        val direct = parseNaturalBinary(t)
        if (direct != null) return answer(style, number(direct), "calculation")

        var expression = t
        val replacements = listOf(
            "divided by" to "/", "divide by" to "/", "vag" to "/", "ভাগ" to "/",
            "multiplied by" to "*", "times" to "*", "into" to "*", "gun" to "*", "গুণ" to "*",
            "plus" to "+", "jog" to "+", "যোগ" to "+",
            "minus" to "-", "biyog" to "-", "বিয়োগ" to "-", "বিয়োগ" to "-",
        )
        replacements.forEach { (word, symbol) -> expression = expression.replace(word, " $symbol ") }
        listOf(
            "calculate", "calculation", "hisab koro", "hisab", "answer", "result", "koto", "ber koro",
            "ক্যালকুলেট", "হিসাব করো", "হিসাব", "উত্তর", "ফলাফল", "কত", "বের করো", "সমান",
        ).forEach { expression = expression.replace(it, " ") }
        expression = expression.replace(Regex("""(?<=\d)\s*[xX]\s*(?=\d)"""), "*")
            .replace("=", " ")
            .replace(Regex("""\s+"""), "")
        if (expression.length !in 3..120 || !expression.any { it in "+-*/^" }) return null
        if (!expression.matches(Regex("""[0-9.+\-*/^()]+"""))) return null
        val value = runCatching { ExpressionParser(expression).parse() }.getOrNull() ?: return null
        if (!value.isFinite() || abs(value) > 1e18) return null
        return answer(style, number(value), "calculation")
    }

    private fun parseNaturalBinary(t: String): Double? {
        val n = "(-?\\d+(?:\\.\\d+)?)"
        val patterns = listOf(
            Regex("""$n\s*(?:theke|থেকে)\s*$n\s*(?:biyog|বাদ|বিয়োগ|বিয়োগ)""") to { a: Double, b: Double -> a - b },
            Regex("""$n\s*(?:sathe|সাথে|সঙ্গে)\s*$n\s*(?:jog|যোগ|add)""") to { a: Double, b: Double -> a + b },
            Regex("""$n\s*(?:ke|কে)?\s*$n\s*(?:diye|দিয়ে|দিয়ে)\s*(?:vag|ভাগ)""") to { a: Double, b: Double -> a / b },
        )
        for ((regex, operation) in patterns) {
            val m = regex.find(t) ?: continue
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            if (b == 0.0 && t.contains(Regex("vag|ভাগ"))) return null
            return operation(a, b)
        }
        return null
    }

    private fun parseSpecialMath(t: String): Double? {
        Regex("""(?:sqrt|square root|borgomul|বর্গমূল)\s*(?:of|er|এর)?\s*(\d+(?:\.\d+)?)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { if (it >= 0.0) return kotlin.math.sqrt(it) }
        Regex("""(\d+(?:\.\d+)?)\s*(?:squared|square|borgo|বর্গ)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it * it }
        Regex("""(?:factorial|fact)\s*(?:of)?\s*(\d{1,3})|\b(\d{1,3})!""")
            .find(t)?.let { m ->
                val n = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: return@let
                if (n in 0..170) {
                    var value = 1.0
                    for (i in 2..n) value *= i
                    return value
                }
            }
        return null
    }

    private fun parseRandom(t: String, style: Style, randomInt: (Int, Int) -> Int): Result? {
        if (listOf("coin toss", "flip a coin", "coin flip", "কয়েন টস", "কয়েন টস", "মুদ্রা টস").any { t.contains(it) }) {
            val heads = randomInt(0, 2) == 0
            val value = when (style) {
                Style.BN -> if (heads) "হেড" else "টেল"
                Style.EN -> if (heads) "Heads" else "Tails"
                Style.BANGLISH -> if (heads) "Head" else "Tail"
            }
            return answer(style, value, "coin")
        }
        if (listOf("roll dice", "dice roll", "dice", "ডাইস", "ছক্কা চাল").any { t.contains(it) }) {
            val sides = Regex("""d(\d{1,3})""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(2, 1000) ?: 6
            return answer(style, randomInt(1, sides + 1).toString(), "dice")
        }
        val between = Regex("""(?:random|pick|choose|beche nao|বেছে নাও)\D{0,20}(\d+)\D+(\d+)""")
            .find(t)
        if (between != null) {
            val a = between.groupValues[1].toIntOrNull() ?: return null
            val b = between.groupValues[2].toIntOrNull() ?: return null
            val low = minOf(a, b)
            val high = maxOf(a, b)
            if (high - low > 1_000_000 || high == Int.MAX_VALUE) return null
            return answer(style, randomInt(low, high + 1).toString(), "random_number")
        }
        return null
    }

    private fun detectStyle(raw: String): Style {
        if (raw.any { it.code in 0x0980..0x09FF }) return Style.BN
        val lower = raw.lowercase()
        val english = listOf(
            "what", "how", "calculate", "convert", "into", "divided", "per person", "days until",
            "difference", "between", "celsius", "fahrenheit", "square root",
        ).any { lower.contains(it) }
        return if (english) Style.EN else Style.BANGLISH
    }

    private fun answer(style: Style, value: String, category: String): Result {
        val speech = when (style) {
            Style.BN -> "উত্তর: $value।"
            Style.EN -> "Answer: $value."
            Style.BANGLISH -> "Uttor: $value."
        }
        return Result(speech, category)
    }

    private fun error(style: Style, banglish: String, english: String, bangla: String): Result = Result(
        when (style) { Style.BN -> "$bangla।"; Style.EN -> "$english."; Style.BANGLISH -> "$banglish." },
        "calculation_error",
    )

    private fun dayWord(style: Style): String = when (style) { Style.BN -> "দিন"; Style.EN -> "days"; Style.BANGLISH -> "din" }
    private fun yearWord(style: Style): String = when (style) { Style.BN -> "বছর"; Style.EN -> "years"; Style.BANGLISH -> "bochor" }

    private fun weekday(date: LocalDate, style: Style): String {
        val index = date.dayOfWeek.value - 1
        return when (style) {
            Style.BN -> listOf("সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার")[index]
            Style.EN -> date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            Style.BANGLISH -> listOf("Shombar", "Mongolbar", "Budhbar", "Brihospotibar", "Shukrobar", "Shonibar", "Robibar")[index]
        }
    }

    private fun number(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val normalized = if (abs(value) < 0.0000000001) 0.0 else value
        val rounded = normalized.roundToLong()
        if (abs(normalized - rounded.toDouble()) < 0.000000001) return rounded.toString()
        return DECIMAL_FORMAT.format(normalized)
    }

    private class ExpressionParser(private val source: String) {
        private var index = 0

        fun parse(): Double {
            val value = expression()
            if (index != source.length) throw IllegalArgumentException("unexpected token")
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (index < source.length) {
                value = when (source[index]) {
                    '+' -> { index++; value + term() }
                    '-' -> { index++; value - term() }
                    else -> return value
                }
            }
            return value
        }

        private fun term(): Double {
            var value = power()
            while (index < source.length) {
                value = when (source[index]) {
                    '*' -> { index++; value * power() }
                    '/' -> {
                        index++
                        val divisor = power()
                        if (divisor == 0.0) throw ArithmeticException("divide by zero")
                        value / divisor
                    }
                    else -> return value
                }
            }
            return value
        }

        private fun power(): Double {
            var value = factor()
            if (index < source.length && source[index] == '^') {
                index++
                value = value.pow(power())
            }
            return value
        }

        private fun factor(): Double {
            if (index >= source.length) throw IllegalArgumentException("number expected")
            if (source[index] == '+') { index++; return factor() }
            if (source[index] == '-') { index++; return -factor() }
            if (source[index] == '(') {
                index++
                val value = expression()
                if (index >= source.length || source[index] != ')') throw IllegalArgumentException("missing parenthesis")
                index++
                return value
            }
            val start = index
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            if (start == index) throw IllegalArgumentException("number expected")
            return source.substring(start, index).toDouble()
        }
    }

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val DECIMAL_FORMAT = DecimalFormat("0.######", DecimalFormatSymbols(Locale.US))
}
