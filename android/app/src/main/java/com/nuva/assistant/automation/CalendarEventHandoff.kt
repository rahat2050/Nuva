package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.core.security.SensitiveAppPolicy

/** Rich Calendar insert draft. The visible Calendar app owns final Save. */
object CalendarEventHandoff {
    sealed interface Result {
        data object Opened : Result
        data object SensitiveBlocked : Result
        data class Failed(val reason: String) : Result
    }

    fun open(context: Context, action: NuvaAction.CreateCalendarEvent): Result {
        val userText = listOfNotNull(action.title, action.location, action.description).joinToString(" ")
        if (SensitiveAppPolicy.mentionsCredentials(userText) || SensitiveAppPolicy.refusalForText(userText) != null) {
            return Result.SensitiveBlocked
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.beginAt)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, action.endAt)
            .putExtra(CalendarContract.Events.TITLE, action.title)
            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        action.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        action.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        action.attendeeEmail?.let { intent.putExtra(Intent.EXTRA_EMAIL, it) }
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (_: Exception) {
            Result.Failed("Calendar event screen khulte parini.")
        }
    }
}
