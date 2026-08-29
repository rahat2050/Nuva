package com.nuva.assistant.command

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Additional offline formulas used in study, household, travel, health and budgeting. */
object AdvancedDailyCalculator {

    fun parse(rawText: String): DailyUtilityParser.Result? {
        val t = NuvaDateTimeParser.normalize(rawText)
        if (t.isBlank() || t.length > 500) return null
        return statistics(t, rawText)
            ?: ratio(t, rawText)
            ?: interest(t, rawText)
            ?: profitLoss(t, rawText)
            ?: unitPrice(t, rawText)
            ?: savings(t, rawText)
            ?: travelEta(t, rawText)
            ?: tripFuelCost(t, rawText)
            ?: geometry(t, rawText)
            ?: bmr(t, rawText)
            ?: water(t, rawText)
            ?: downloadTime(t, rawText)
            ?: grade(t, rawText)
    }

    private fun statistics(t: String, raw: String): DailyUtilityParser.Result? {
        val average = listOf("average", "mean", "gor koto", "গড়", "গড়").any { t.contains(it) }
        val median = listOf("median", "মিডিয়ান", "মধ্যক").any { t.contains(it) }
        if (!average && !median) return null
        val values = numbers(t)
        if (values.size !in 2..100) return null
        val value = if (median) {
            val sorted = values.sorted()
            if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            values.average()
        }
        return answer(raw, number(value), if (median) "median" else "average")
    }

    private fun ratio(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("ratio", "onupat", "অনুপাত").any { t.contains(it) }) return null
        val values = integers(t)
        if (values.size < 2) return null
        val a = abs(values[0])
        val b = abs(values[1])
        if (a == 0L && b == 0L) return null
        val divisor = gcd(a, b).coerceAtLeast(1)
        return answer(raw, "${values[0] / divisor}:${values[1] / divisor}", "ratio")
    }

    private fun interest(t: String, raw: String): DailyUtilityParser.Result? {
        val compound = listOf("compound interest", "chokrobiddhi", "চক্রবৃদ্ধি").any { t.contains(it) }
        val simple = listOf("simple interest", "sud hisab", "interest koto", "সরল সুদ", "সুদ হিসাব").any { t.contains(it) }
        if (!compound && !simple) return null
        val principal = NUMBER.find(t)?.value?.toDoubleOrNull() ?: return null
        val rate = Regex("""(\d+(?:\.\d+)?)\s*(%|percent|পারসেন্ট|শতাংশ)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val years = Regex("""(\d+(?:\.\d+)?)\s*(year|years|bochor|বছর)""")
            .find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (principal <= 0 || rate < 0 || years <= 0 || years > 200) return null
        val total = if (compound) principal * (1.0 + rate / 100.0).pow(years) else principal * (1.0 + rate * years / 100.0)
        val interest = total - principal
        return detailed(raw, "Interest ${number(interest)}; total ${number(total)}", "সুদ ${number(interest)}; মোট ${number(total)}", if (compound) "compound_interest" else "simple_interest")
    }

    private fun profitLoss(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("profit", "loss", "buying price", "selling price", "lav", "lokshan", "লাভ", "লোকসান", "ক্রয়মূল্য", "বিক্রয়মূল্য")
                .any { t.contains(it) }
        ) return null
        val values = numbers(t)
        if (values.size < 2) return null
        val cost = values[0]
        val selling = values[1]
        if (cost <= 0 || selling < 0) return null
        val difference = selling - cost
        val percent = difference / cost * 100.0
        val en = if (difference >= 0) "Profit ${number(difference)} (${number(percent)}%)" else "Loss ${number(abs(difference))} (${number(abs(percent))}%)"
        val bn = if (difference >= 0) "লাভ ${number(difference)} (${number(percent)}%)" else "লোকসান ${number(abs(difference))} (${number(abs(percent))}%)"
        return detailed(raw, en, bn, "profit_loss")
    }

    private fun unitPrice(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("unit price", "per item", "each price", "proti piece", "প্রতি পিস", "একটির দাম").any { t.contains(it) }) return null
        val values = numbers(t)
        if (values.size < 2 || values[1] <= 0) return null
        return answer(raw, number(values[0] / values[1]), "unit_price")
    }

    private fun savings(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("savings goal", "monthly save", "save per month", "proti mashe joma", "মাসে কত জমাব", "সঞ্চয় লক্ষ্য").any { t.contains(it) }) return null
        val target = NUMBER.find(t)?.value?.toDoubleOrNull() ?: return null
        val months = Regex("""(\d+)\s*(month|months|mash|মাস)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (target <= 0 || months !in 1..1200) return null
        return answer(raw, "${number(target / months)} per month", "savings_goal")
    }

    private fun travelEta(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("travel time", "eta koto", "koto somoy lagbe", "কত সময় লাগবে", "কত সময় লাগবে").any { t.contains(it) }) return null
        val distance = Regex("""(\d+(?:\.\d+)?)\s*(km|kilometer|কিলোমিটার)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val speed = Regex("""(\d+(?:\.\d+)?)\s*(km/h|kmph|kph|kilometer per hour)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (distance < 0 || speed <= 0) return null
        val minutes = (distance / speed * 60.0).roundToLong()
        val human = if (minutes >= 60) "${minutes / 60} hour ${minutes % 60} minute" else "$minutes minute"
        return answer(raw, human, "travel_eta")
    }

    private fun tripFuelCost(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("trip fuel cost", "fuel cost", "tel er khoroch", "জ্বালানি খরচ", "তেলের খরচ").any { t.contains(it) }) return null
        val distance = Regex("""(\d+(?:\.\d+)?)\s*(km|kilometer|কিলোমিটার)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val mileage = Regex("""(\d+(?:\.\d+)?)\s*(km/l|km per liter|kilometer per liter)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val price = Regex("""(\d+(?:\.\d+)?)\s*(?:taka|tk|টাকা)?\s*(?:per liter|proti liter|প্রতি লিটার)""")
            .findAll(t).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (distance < 0 || mileage <= 0 || price < 0) return null
        val liters = distance / mileage
        return detailed(raw, "Fuel ${number(liters)} liter; cost ${number(liters * price)}", "জ্বালানি ${number(liters)} লিটার; খরচ ${number(liters * price)}", "trip_fuel_cost")
    }

    private fun geometry(t: String, raw: String): DailyUtilityParser.Result? {
        val values = numbers(t)
        return when {
            listOf("rectangle area", "ayotokhetro", "আয়তক্ষেত্রের ক্ষেত্রফল", "আয়তক্ষেত্রের ক্ষেত্রফল").any { t.contains(it) } && values.size >= 2 ->
                detailed(raw, "Area ${number(values[0] * values[1])}; perimeter ${number(2 * (values[0] + values[1]))}", "ক্ষেত্রফল ${number(values[0] * values[1])}; পরিসীমা ${number(2 * (values[0] + values[1]))}", "rectangle")
            listOf("circle area", "circle circumference", "britter khetrafol", "বৃত্তের ক্ষেত্রফল", "বৃত্তের পরিধি").any { t.contains(it) } && values.isNotEmpty() ->
                detailed(raw, "Area ${number(PI * values[0].pow(2))}; circumference ${number(2 * PI * values[0])}", "ক্ষেত্রফল ${number(PI * values[0].pow(2))}; পরিধি ${number(2 * PI * values[0])}", "circle")
            listOf("triangle area", "tribhuj", "ত্রিভুজের ক্ষেত্রফল").any { t.contains(it) } && values.size >= 2 ->
                answer(raw, number(values[0] * values[1] / 2.0), "triangle")
            listOf("cuboid volume", "box volume", "ayoton", "আয়তন", "আয়তন").any { t.contains(it) } && values.size >= 3 ->
                answer(raw, number(values[0] * values[1] * values[2]), "cuboid")
            else -> null
        }
    }

    private fun bmr(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("bmr", "basal metabolic", "বিএমআর").any { t.contains(it) }) return null
        val kg = Regex("""(\d+(?:\.\d+)?)\s*(kg|kilogram|কেজি)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val cm = Regex("""(\d+(?:\.\d+)?)\s*(cm|centimeter|সেন্টিমিটার)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val age = Regex("""(\d+)\s*(year|years|bochor|বছর|age)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val male = listOf("male", "man", "purush", "পুরুষ").any { t.contains(it) }
        val female = listOf("female", "woman", "mohila", "নারী", "মহিলা").any { t.contains(it) }
        if (!male && !female || kg !in 1.0..700.0 || cm !in 50.0..300.0 || age !in 10..120) return null
        val value = 10 * kg + 6.25 * cm - 5 * age + if (male) 5 else -161
        return detailed(raw, "Estimated BMR ${number(value)} calories/day; not medical advice", "আনুমানিক BMR ${number(value)} ক্যালরি/দিন; এটি চিকিৎসা পরামর্শ নয়", "bmr")
    }

    private fun water(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("water intake", "daily water", "koto pani khabo", "কত পানি খাব", "পানির চাহিদা").any { t.contains(it) }) return null
        val kg = Regex("""(\d+(?:\.\d+)?)\s*(kg|kilogram|কেজি)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (kg !in 1.0..700.0) return null
        val liters = kg * 0.035
        return detailed(raw, "General estimate ${number(liters)} liter/day; medical needs vary", "সাধারণ অনুমান ${number(liters)} লিটার/দিন; চিকিৎসাগত প্রয়োজন ভিন্ন হতে পারে", "water_intake")
    }

    private fun downloadTime(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("download time", "download korte koto", "ডাউনলোড হতে কত", "ডাউনলোড সময়").any { t.contains(it) }) return null
        val sizeMatch = Regex("""(\d+(?:\.\d+)?)\s*(gb|mb)""").find(t) ?: return null
        val speed = Regex("""(\d+(?:\.\d+)?)\s*(mbps|megabit per second)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val size = sizeMatch.groupValues[1].toDouble()
        val megabytes = if (sizeMatch.groupValues[2] == "gb") size * 1024.0 else size
        if (megabytes < 0 || speed <= 0) return null
        val seconds = (megabytes * 8.0 / speed).roundToLong()
        val human = if (seconds >= 60) "${seconds / 60} minute ${seconds % 60} second" else "$seconds second"
        return answer(raw, human, "download_time")
    }

    private fun grade(t: String, raw: String): DailyUtilityParser.Result? {
        if (!listOf("grade percentage", "marks percentage", "score percentage", "number er percent", "মার্কের শতাংশ", "নম্বরের শতাংশ").any { t.contains(it) }) return null
        val match = Regex("""(\d+(?:\.\d+)?)\s*(?:out of|er moddhe|এর মধ্যে|/)\s*(\d+(?:\.\d+)?)""").find(t) ?: return null
        val scored = match.groupValues[1].toDouble()
        val total = match.groupValues[2].toDouble()
        if (scored < 0 || total <= 0) return null
        return answer(raw, "${number(scored / total * 100.0)}%", "grade_percentage")
    }

    private fun answer(raw: String, value: String, category: String): DailyUtilityParser.Result =
        DailyUtilityParser.Result(if (isBangla(raw)) "উত্তর: $value।" else "Uttor: $value.", category)

    private fun detailed(raw: String, en: String, bn: String, category: String): DailyUtilityParser.Result =
        DailyUtilityParser.Result(if (isBangla(raw)) "$bn।" else "$en.", category)

    private fun isBangla(raw: String): Boolean = raw.any { it.code in 0x0980..0x09FF }

    private fun numbers(t: String): List<Double> = NUMBER.findAll(t).mapNotNull { it.value.toDoubleOrNull() }.toList()
    private fun integers(t: String): List<Long> = Regex("""-?\d+""").findAll(t).mapNotNull { it.value.toLongOrNull() }.toList()

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val next = x % y
            x = y
            y = next
        }
        return abs(x)
    }

    private fun number(value: Double): String {
        val normalized = if (abs(value) < 0.0000000001) 0.0 else value
        val rounded = normalized.roundToLong()
        if (abs(normalized - rounded.toDouble()) < 0.000000001) return rounded.toString()
        return FORMAT.format(normalized)
    }

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val FORMAT = DecimalFormat("0.######", DecimalFormatSymbols(Locale.US))
}
