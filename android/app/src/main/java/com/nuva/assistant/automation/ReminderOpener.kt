package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.nuva.assistant.command.NuvaAction

/**
 * SET_REMINDER (v1.1) — opens the user's calendar app with a pre-filled
 * event. NUVA never silently edits the calendar: the user reviews the event
 * and taps Save themselves (policy §37: calendar edits always confirm — and
 * here the final save is the user's own tap on top of NUVA's confirmation).
 */
object ReminderOpener {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val userReason: String) : Result
    }

    fun open(context: Context, action: NuvaAction.SetReminder): Result {
        val now = System.currentTimeMillis()
        val begin = action.whenMillis?.takeIf { it > now - 86_400_000L } ?: (now + 3_600_000L)
        val end = begin + 3_600_000L
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.Events.TITLE, action.title)
            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (err: Exception) {
            // No calendar app installed — try opening the calendar app by alias.
            when (AppLauncher.openApp(context, "calendar", "com.android.calendar")) {
                is AppLauncher.LaunchResult.Success -> Result.Opened
                is AppLauncher.LaunchResult.NotFound ->
                    Result.Failed("Calendar app painai — ekta calendar app thakle NUVA reminder rakhte parbo.")
            }
        }
    }
}
