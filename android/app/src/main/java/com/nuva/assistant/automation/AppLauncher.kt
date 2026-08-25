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
        "contacts" to "com.android.contacts",
        "gallery" to "com.android.gallery3d",
        "files" to "com.android.filemanager",
        "calendar" to "com.android.calendar",
        "translate" to "com.google.android.apps.translate",
        "play store" to "com.android.vending",
        "recorder" to "com.android.soundrecorder",
        "music" to "com.android.music",
        // Financial apps (LEVEL 1: opening allowed; transactions always blocked elsewhere)
        "bkash" to "com.bKash.customerapp",
        "nagad" to "com.konasl.mobileapp",
        "rocket" to "bd.com.dbbl.mobilebanking",
        "upay" to "com.upayouth.apps.upay",
    )

    fun resolvePackage(app: String, serverHint: String?): String? =
        serverHint?.takeIf { it.isNotBlank() } ?: PACKAGE_HINTS[app.trim().lowercase()]

    sealed interface LaunchResult {
        data class Success(val packageName: String) : LaunchResult

        /** App not installed — carries a Play Store search suggestion (v1.1). */
        data class NotFound(val app: String, val playStoreUrl: String) : LaunchResult
    }

    fun playStoreSearchUrl(app: String): String =
        "https://play.google.com/store/search?q=" + Uri.encode(app) + "&c=apps"

    /** One launchable installed app with its user-visible label. */
    data class InstalledApp(val packageName: String, val label: String, val normalizedLabel: String)

    @Volatile
    private var installedCache: List<InstalledApp>? = null
    @Volatile
    private var installedCacheAt: Long = 0

    /**
     * Every launchable app on the phone (cached 60 s). This is what makes
     * "Nuva <any app name> khulo" work — not just a hard-coded package list.
     */
    fun installedLaunchableApps(context: Context, forceRefresh: Boolean = false): List<InstalledApp> {
        val now = System.currentTimeMillis()
        val cache = installedCache
        if (!forceRefresh && cache != null && now - installedCacheAt < 60_000) return cache
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(launcherIntent, 0).map { info ->
                val label = info.loadLabel(pm).toString()
                InstalledApp(
                    packageName = info.activityInfo.packageName,
                    label = label,
                    normalizedLabel = normalizeLabel(label),
                )
            }.distinctBy { it.packageName }
        }.getOrDefault(emptyList())
        installedCache = apps
        installedCacheAt = now
        return apps
    }

    private fun normalizeLabel(label: String): String =
        label.lowercase().replace(Regex("""[^a-z0-9\u0980-\u09FF ]"""), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Finds an installed app by spoken/written name: exact alias hit first,
     * then label exact match, then label starts-with, then contains.
     */
    fun findInstalledApp(context: Context, app: String): InstalledApp? {
        val key = normalizeLabel(app)
        if (key.isBlank()) return null
        val apps = installedLaunchableApps(context)
        apps.firstOrNull { it.normalizedLabel == key }?.let { return it }
        // Alias may differ from the label ("maps" → "Maps"); map through hints.
        resolvePackage(app, null)?.let { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.let { return it }
        }
        apps.firstOrNull { it.normalizedLabel.startsWith(key) }?.let { return it }
        return apps.firstOrNull { it.normalizedLabel.contains(key) }
    }

    fun openApp(context: Context, app: String, serverHint: String?): LaunchResult {
        // Financial-policy LEVEL 1: launching any app — including wallets — is
        // allowed. Transactions are blocked elsewhere (parser + accessibility
        // guard), not by refusing to open the user's own apps.
        val hintPkg = resolvePackage(app, serverHint)
        val launchIntent: Intent? = when {
            hintPkg != null -> context.packageManager.getLaunchIntentForPackage(hintPkg)
            else -> {
                // Dynamic resolution: search installed apps by label (v1.1).
                findInstalledApp(context, app)
                    ?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
            }
        }
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }.fold(
                onSuccess = { LaunchResult.Success(launchIntent.`package` ?: app) },
                onFailure = { LaunchResult.NotFound(app, playStoreSearchUrl(app)) },
            )
        } else {
            LaunchResult.NotFound(app, playStoreSearchUrl(app))
        }
    }

    /** Opens the Play Store on the search page for [app] (missing-app suggestion). */
    fun openPlayStoreSearch(context: Context, app: String): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(app)}&c=apps"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val http = Intent(Intent.ACTION_VIEW, Uri.parse(playStoreSearchUrl(app)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(market) }
            .recoverCatching { context.startActivity(http) }
            .isSuccess
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
