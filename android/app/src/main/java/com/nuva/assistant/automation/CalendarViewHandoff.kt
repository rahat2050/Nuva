package com.nuva.assistant.automation

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/** Opens the visible Calendar app focused on a requested day; reads no calendar data. */
object CalendarViewHandoff {
    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    fun open(context: Context, focusAt: Long): Result {
        val uri = ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), focusAt)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.Opened
        } catch (_: Exception) {
            when (AppLauncher.openApp(context, "calendar", null)) {
                is AppLauncher.LaunchResult.Success -> Result.Opened
                is AppLauncher.LaunchResult.NotFound -> Result.Failed("Calendar app khulte parini.")
            }
        }
    }
}
