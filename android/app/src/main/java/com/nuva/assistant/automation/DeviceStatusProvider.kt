package com.nuva.assistant.automation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.nuva.assistant.command.DeviceStatusKind
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Local, real-time answers for device-status questions: battery, clock time,
 * date, network and storage. Values are read at execution time, never supplied
 * by the model and never sent off the device.
 */
class DeviceStatusProvider(private val contextProvider: () -> Context) {

    fun answer(kind: DeviceStatusKind, preferredLanguage: String = "auto"): String {
        val language = resolvedLanguage(preferredLanguage)
        return when (kind) {
            DeviceStatusKind.BATTERY -> battery(language)
            DeviceStatusKind.TIME -> DeviceDateTimeFormatter.time(Calendar.getInstance(), language)
            DeviceStatusKind.DATE -> DeviceDateTimeFormatter.date(Calendar.getInstance(), language)
            DeviceStatusKind.DATE_TIME -> DeviceDateTimeFormatter.dateTime(Calendar.getInstance(), language)
            DeviceStatusKind.NETWORK -> network(language)
            DeviceStatusKind.STORAGE -> storage(language)
            DeviceStatusKind.DEVICE_INFO -> deviceInfo(language)
            DeviceStatusKind.MEMORY -> memory(language)
            DeviceStatusKind.UPTIME -> uptime(language)
            DeviceStatusKind.DISPLAY -> display(language)
            DeviceStatusKind.AUDIO -> audio(language)
            DeviceStatusKind.TIMEZONE -> timezone(language)
            DeviceStatusKind.LOCALE -> locale(language)
            DeviceStatusKind.INSTALLED_APPS -> installedApps(language)
            DeviceStatusKind.SENSORS -> sensors(language)
        }
    }

    /** `auto` follows the phone language; non-Bangla phones use Banglish. */
    private fun resolvedLanguage(preferred: String): String {
        if (preferred in setOf("bn", "en", "banglish")) return preferred
        val deviceLanguage = runCatching {
            contextProvider().resources.configuration.locales[0].language
        }.getOrNull()
        return if (deviceLanguage == "bn") "bn" else "banglish"
    }

    private fun battery(language: String): String {
        val context = contextProvider()
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status: Intent? = context.registerReceiver(null, ifilter)
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else queryCapacity(context)
        if (percent < 0) return localize(
            language,
            bn = "ব্যাটারির অবস্থা পড়তে পারিনি।",
            en = "I couldn't read the battery status.",
            banglish = "Battery status porte parini.",
        )
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val shownPercent = if (language == "bn") DeviceDateTimeFormatter.banglaDigits(percent.toString()) else percent.toString()
        return if (charging) {
            localize(
                language,
                bn = "ব্যাটারি $shownPercent শতাংশ—এখন চার্জ হচ্ছে।",
                en = "The battery is at $shownPercent% and is charging.",
                banglish = "Battery $shownPercent percent—ekhon charge hocche.",
            )
        } else {
            localize(
                language,
                bn = "ব্যাটারি $shownPercent শতাংশ আছে।",
                en = "The battery is at $shownPercent%.",
                banglish = "Battery $shownPercent percent ache.",
            )
        }
    }

    private fun queryCapacity(context: Context): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 } ?: -1
    }.getOrDefault(-1)

    private fun network(language: String): String {
        val context = contextProvider()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return localize(
                language,
                bn = "নেটওয়ার্কের অবস্থা পড়তে পারিনি।",
                en = "I couldn't read the network status.",
                banglish = "Network status porte parini.",
            )
        val active = cm.activeNetwork
            ?: return localize(
                language,
                bn = "এখন কোনো ইন্টারনেট সংযোগ নেই।",
                en = "There is no internet connection right now.",
                banglish = "Ekhon kono internet connection nei.",
            )
        val caps = cm.getNetworkCapabilities(active)
            ?: return localize(
                language,
                bn = "এখন কোনো ইন্টারনেট সংযোগ নেই।",
                en = "There is no internet connection right now.",
                banglish = "Ekhon kono internet connection nei.",
            )
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> localize(
                language,
                bn = "ফোনটি ওয়াই-ফাইতে সংযুক্ত আছে।",
                en = "The phone is connected to Wi-Fi.",
                banglish = "Phone Wi-Fi te connected ache.",
            )
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> localize(
                language,
                bn = "ফোনটি মোবাইল ডাটায় সংযুক্ত আছে।",
                en = "The phone is connected through mobile data.",
                banglish = "Phone mobile data te connected ache.",
            )
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> localize(
                language,
                bn = "ফোনটি ইথারনেটে সংযুক্ত আছে।",
                en = "The phone is connected through Ethernet.",
                banglish = "Phone Ethernet e connected ache.",
            )
            else -> localize(
                language,
                bn = "একটি নেটওয়ার্ক সংযোগ আছে।",
                en = "A network connection is active.",
                banglish = "Ekta network connection active ache.",
            )
        }
    }

    private fun storage(language: String): String = runCatching {
        val dataDir: File = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val totalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
        val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
        val usedPct = if (totalGb > 0) ((totalGb - freeGb) / totalGb * 100).toInt() else 0
        val free = String.format(Locale.ENGLISH, "%.1f", freeGb)
        val percent = usedPct.toString()
        localize(
            language,
            bn = "স্টোরেজে ${DeviceDateTimeFormatter.banglaDigits(free)} জিবি খালি আছে; ${DeviceDateTimeFormatter.banglaDigits(percent)} শতাংশ ব্যবহার হয়েছে।",
            en = "$free GB of storage is free; $percent% is used.",
            banglish = "Storage e $free GB free ache; $percent percent use hoyeche.",
        )
    }.getOrDefault(
        localize(
            language,
            bn = "স্টোরেজের অবস্থা পড়তে পারিনি।",
            en = "I couldn't read the storage status.",
            banglish = "Storage status porte parini.",
        ),
    )

    private fun deviceInfo(language: String): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.ifBlank { "unknown model" }
        return localize(
            language,
            bn = "ফোন: $maker $model। Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}।",
            en = "Phone: $maker $model. Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}.",
            banglish = "Phone: $maker $model. Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}.",
        )
    }

    private fun memory(language: String): String {
        val context = contextProvider()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return localize(language, "RAM তথ্য পড়তে পারিনি।", "I couldn't read memory information.", "RAM info porte parini.")
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem / (1024.0 * 1024 * 1024)
        val available = info.availMem / (1024.0 * 1024 * 1024)
        val totalText = String.format(Locale.ENGLISH, "%.1f", total)
        val availableText = String.format(Locale.ENGLISH, "%.1f", available)
        return localize(
            language,
            bn = "RAM মোট ${DeviceDateTimeFormatter.banglaDigits(totalText)} জিবি; এখন প্রায় ${DeviceDateTimeFormatter.banglaDigits(availableText)} জিবি available।",
            en = "RAM: $totalText GB total, about $availableText GB available.",
            banglish = "RAM $totalText GB total; ekhon pray $availableText GB available.",
        )
    }

    private fun uptime(language: String): String {
        val human = formatDuration(SystemClock.elapsedRealtime())
        return localize(language, "ফোন প্রায় $human ধরে চালু আছে।", "The phone has been on for about $human.", "Phone pray $human dhore on ache.")
    }

    private fun display(language: String): String {
        val metrics = contextProvider().resources.displayMetrics
        val density = String.format(Locale.ENGLISH, "%.2f", metrics.density)
        return localize(
            language,
            bn = "Display ${DeviceDateTimeFormatter.banglaDigits(metrics.widthPixels.toString())} × ${DeviceDateTimeFormatter.banglaDigits(metrics.heightPixels.toString())} pixel; density ${DeviceDateTimeFormatter.banglaDigits(density)}।",
            en = "Display: ${metrics.widthPixels} × ${metrics.heightPixels} pixels; density $density.",
            banglish = "Display ${metrics.widthPixels} by ${metrics.heightPixels} pixel; density $density.",
        )
    }

    private fun audio(language: String): String {
        val audio = contextProvider().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return localize(language, "Audio status পড়তে পারিনি।", "I couldn't read audio status.", "Audio status porte parini.")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
        val mode = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        return localize(
            language,
            bn = "Media volume ${DeviceDateTimeFormatter.banglaDigits(percent.toString())} শতাংশ; ringer mode $mode।",
            en = "Media volume is $percent%; ringer mode is $mode.",
            banglish = "Media volume $percent percent; ringer mode $mode.",
        )
    }

    private fun timezone(language: String): String {
        val zone = TimeZone.getDefault()
        val offsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val absolute = kotlin.math.abs(offsetMinutes)
        val offset = String.format(Locale.ENGLISH, "%s%02d:%02d", sign, absolute / 60, absolute % 60)
        return localize(language, "Timezone ${zone.id}, UTC$offset।", "Time zone: ${zone.id}, UTC$offset.", "Timezone ${zone.id}, UTC$offset.")
    }

    private fun locale(language: String): String {
        val locale = contextProvider().resources.configuration.locales[0]
        val label = locale.getDisplayName(locale).ifBlank { locale.toLanguageTag() }
        return localize(language, "ফোনের ভাষা/locale: $label (${locale.toLanguageTag()})।", "Phone locale: $label (${locale.toLanguageTag()}).", "Phone locale $label (${locale.toLanguageTag()}).")
    }

    private fun installedApps(language: String): String {
        val count = AppLauncher.installedLaunchableApps(contextProvider()).size
        val shown = if (language == "bn") DeviceDateTimeFormatter.banglaDigits(count.toString()) else count.toString()
        return localize(language, "Launch করা যায় এমন $shown টি app পেয়েছি।", "I found $shown launchable apps.", "$shown ta launchable app peyechi.")
    }

    private fun sensors(language: String): String {
        val manager = contextProvider().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return localize(language, "Sensor list পড়তে পারিনি।", "I couldn't read the sensor list.", "Sensor list porte parini.")
        val sensors = manager.getSensorList(Sensor.TYPE_ALL)
        val names = sensors.map { it.name }.distinct().take(5).joinToString(", ")
        val suffix = if (sensors.size > 5) " +${sensors.size - 5} more" else ""
        return localize(
            language,
            bn = "${DeviceDateTimeFormatter.banglaDigits(sensors.size.toString())} টি sensor: $names$suffix।",
            en = "${sensors.size} sensors: $names$suffix.",
            banglish = "${sensors.size} ta sensor: $names$suffix.",
        )
    }

    private fun localize(language: String, bn: String, en: String, banglish: String): String = when (language) {
        "bn" -> bn
        "en" -> en
        else -> banglish
    }

    companion object {
        fun formatDuration(milliseconds: Long): String {
            val totalMinutes = (milliseconds.coerceAtLeast(0) / 60_000)
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return buildList {
                if (days > 0) add("$days day")
                if (hours > 0) add("$hours hour")
                if (minutes > 0 || isEmpty()) add("$minutes minute")
            }.joinToString(" ")
        }
    }
}

/** Locale-independent date/time wording, split out so fixed instants are JVM-testable. */
object DeviceDateTimeFormatter {
    private val banglaWeekdays = mapOf(
        Calendar.SUNDAY to "রবিবার",
        Calendar.MONDAY to "সোমবার",
        Calendar.TUESDAY to "মঙ্গলবার",
        Calendar.WEDNESDAY to "বুধবার",
        Calendar.THURSDAY to "বৃহস্পতিবার",
        Calendar.FRIDAY to "শুক্রবার",
        Calendar.SATURDAY to "শনিবার",
    )
    private val banglishWeekdays = mapOf(
        Calendar.SUNDAY to "Robibar",
        Calendar.MONDAY to "Shombar",
        Calendar.TUESDAY to "Mongolbar",
        Calendar.WEDNESDAY to "Budhbar",
        Calendar.THURSDAY to "Brihospotibar",
        Calendar.FRIDAY to "Shukrobar",
        Calendar.SATURDAY to "Shonibar",
    )
    private val englishWeekdays = mapOf(
        Calendar.SUNDAY to "Sunday",
        Calendar.MONDAY to "Monday",
        Calendar.TUESDAY to "Tuesday",
        Calendar.WEDNESDAY to "Wednesday",
        Calendar.THURSDAY to "Thursday",
        Calendar.FRIDAY to "Friday",
        Calendar.SATURDAY to "Saturday",
    )
    private val banglaMonths = listOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর",
    )
    private val englishMonths = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    fun time(calendar: Calendar, language: String): String {
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour12 = when (val h = calendar.get(Calendar.HOUR)) { 0 -> 12; else -> h }
        val minute = calendar.get(Calendar.MINUTE)
        return when (language) {
            "bn" -> {
                val period = banglaPeriod(hour24)
                val minutePart = if (minute == 0) "" else " ${banglaDigits(minute.toString())} মিনিট"
                "এখন $period ${banglaDigits(hour12.toString())}টা$minutePart।"
            }
            "en" -> {
                val amPm = if (hour24 < 12) "AM" else "PM"
                "It is ${String.format(Locale.ENGLISH, "%d:%02d", hour12, minute)} $amPm."
            }
            else -> {
                val period = banglishPeriod(hour24)
                val minutePart = if (minute == 0) "" else " $minute minute"
                "Ekhon $period $hour12 ta$minutePart."
            }
        }
    }

    fun date(calendar: Calendar, language: String): String {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH).coerceIn(0, 11)
        val year = calendar.get(Calendar.YEAR)
        val weekday = calendar.get(Calendar.DAY_OF_WEEK)
        return when (language) {
            "bn" -> "আজ ${banglaWeekdays[weekday]}, ${banglaDigits(day.toString())} ${banglaMonths[month]} ${banglaDigits(year.toString())}।"
            "en" -> "Today is ${englishWeekdays[weekday]}, $day ${englishMonths[month]} $year."
            else -> "Aj ${banglishWeekdays[weekday]}, $day ${englishMonths[month]} $year."
        }
    }

    /** Uses the same [Calendar] snapshot for both values. */
    fun dateTime(calendar: Calendar, language: String): String =
        "${time(calendar, language)} ${date(calendar, language)}"

    fun banglaDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(if (ch in '0'..'9') "০১২৩৪৫৬৭৮৯"[ch - '0'] else ch)
        }
    }

    private fun banglaPeriod(hour: Int): String = when (hour) {
        in 4..11 -> "সকাল"
        in 12..15 -> "দুপুর"
        in 16..17 -> "বিকাল"
        in 18..19 -> "সন্ধ্যা"
        else -> "রাত"
    }

    private fun banglishPeriod(hour: Int): String = when (hour) {
        in 4..11 -> "shokal"
        in 12..15 -> "dupur"
        in 16..17 -> "bikal"
        in 18..19 -> "shondha"
        else -> "raat"
    }
}
