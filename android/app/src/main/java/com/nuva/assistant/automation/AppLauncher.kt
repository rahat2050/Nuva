package com.nuva.assistant.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

/**
 * Native Android actions: app open/close, alarms, timers, dialer, browser.
 * These need no AccessibilityService — just platform intents.
 */
object AppLauncher {

    /** Known package hints; the server sends these pre-resolved when it knows. */
    val PACKAGE_HINTS: Map<String, String> = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "facebook" to "com.facebook.katana",
        "facebook lite" to "com.facebook.lite",
        "messenger" to "com.facebook.orca",
        "telegram" to "org.telegram.messenger",
        "signal" to "org.thoughtcrime.securesms",
        "imo" to "com.imo.android.imoim",
        "viber" to "com.viber.voip",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.browser",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "gmail" to "com.google.android.gm",
        "camera" to "com.android.camera",
        "calculator" to "com.android.calculator2",
        "settings" to "com.android.settings",
        "phone" to "com.android.dialer",
    )

    fun resolvePackage(app: String, serverHint: String?): String? =
        serverHint?.takeIf { it.isNotBlank() } ?: PACKAGE_HINTS[app.trim().lowercase()]

    sealed interface LaunchResult {
        data class Success(val packageName: String) : LaunchResult
        data class NotFound(val app: String) : LaunchResult
    }

    fun openApp(context: Context, app: String, serverHint: String?): LaunchResult {
        val pkg = resolvePackage(app, serverHint)
            ?: return LaunchResult.NotFound(app)

        val launchIntent: Intent? = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            LaunchResult.Success(pkg)
        } else {
            LaunchResult.NotFound(app)
        }
    }

    /** "Close" = open the home screen; force-stop is not possible for third-party apps. */
    fun closeApp(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(home)
            true
        } catch (err: Exception) {
            false
        }
    }

    fun openUrl(context: Context, url: String): Boolean {
        val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (err: Exception) {
            false
        }
    }

    fun webSearch(context: Context, query: String): Boolean =
        openUrl(context, "https://www.google.com/search?q=" + Uri.encode(query))

    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String?,
        relativeDay: com.nuva.assistant.command.RelativeDay?,
        days: List<com.nuva.assistant.command.Weekday>?,
    ): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (relativeDay == com.nuva.assistant.command.RelativeDay.TOMORROW && days == null) {
                // EXTRA_DAYS with tomorrow's weekday; caller resolves the calendar day.
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(java.util.Calendar.getInstance().let { cal ->
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    dayConstant(cal.get(java.util.Calendar.DAY_OF_WEEK))
                }))
            }
            days?.takeIf { it.isNotEmpty() }?.let { weekdays ->
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList(weekdays.mapNotNull { dayConstant(it) }))
            }
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (err: Exception) {
            false
        }
    }

    fun setTimer(context: Context, durationSeconds: Long, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.toInt())
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (err: Exception) {
            false
        }
    }

    /** Safe dial (dialer shown). Direct ACTION_CALL only with user opt-in + permission. */
    fun dial(context: Context, phoneNumber: String, directCall: Boolean): Boolean {
        val action = if (directCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            context.startActivity(
                Intent(action, Uri.parse("tel:" + phoneNumber)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (err: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (err: Exception) {
        false
    }

    fun openAppInfo(context: Context, packageName: String): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (err: Exception) {
        false
    }

    private fun dayConstant(calendarDay: Int): Int? = when (calendarDay) {
        java.util.Calendar.MONDAY -> java.util.Calendar.MONDAY
        else -> calendarDay
    }

    private fun dayConstant(day: com.nuva.assistant.command.Weekday): Int? = when (day) {
        com.nuva.assistant.command.Weekday.MON -> java.util.Calendar.MONDAY
        com.nuva.assistant.command.Weekday.TUE -> java.util.Calendar.TUESDAY
        com.nuva.assistant.command.Weekday.WED -> java.util.Calendar.WEDNESDAY
        com.nuva.assistant.command.Weekday.THU -> java.util.Calendar.THURSDAY
        com.nuva.assistant.command.Weekday.FRI -> java.util.Calendar.FRIDAY
        com.nuva.assistant.command.Weekday.SAT -> java.util.Calendar.SATURDAY
        com.nuva.assistant.command.Weekday.SUN -> java.util.Calendar.SUNDAY
    }

    @Suppress("unused")
    private fun componentName(intent: Intent): ComponentName? = intent.component
}
