package com.nuva.assistant.automation

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.core.security.SensitiveAppPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicit calendar-provider read plus exact event view/edit handoff. */
object CalendarProviderController {
    data class Event(
        val id: Long,
        val title: String,
        val beginAt: Long,
        val endAt: Long,
        val location: String?,
        val calendar: String?,
        val allDay: Boolean,
    )

    sealed interface Result {
        data class Agenda(val speech: String, val screenText: String) : Result
        data class Opened(val title: String, val editing: Boolean) : Result
        data object PermissionMissing : Result
        data class NotFound(val query: String) : Result
        data class Ambiguous(val events: List<Event>) : Result
        data class Failed(val reason: String) : Result
    }

    fun execute(context: Context, action: com.nuva.assistant.command.NuvaAction.CalendarProvider): Result {
        if (!NuvaPermissions.hasReadCalendar(context)) return Result.PermissionMissing
        return try {
            val events = queryEvents(context, action.rangeStart, action.rangeEnd)
            when (action.operation) {
                com.nuva.assistant.command.CalendarProviderOperation.READ_AGENDA -> agenda(events)
                com.nuva.assistant.command.CalendarProviderOperation.OPEN_EVENT,
                com.nuva.assistant.command.CalendarProviderOperation.EDIT_EVENT,
                -> openMatched(context, action, events)
            }
        } catch (error: Exception) {
            Result.Failed(error.message ?: "Calendar query failed")
        }
    }

    fun matchEvents(events: List<Event>, query: String): List<Event> {
        val needle = normalize(query)
        if (needle.isBlank()) return emptyList()
        val safe = events.filterNot { SensitiveAppPolicy.mentionsCredentials(it.title) }
        val exact = safe.filter { normalize(it.title) == needle }
        if (exact.isNotEmpty()) return exact
        val starts = safe.filter { normalize(it.title).startsWith(needle) }
        if (starts.isNotEmpty()) return starts
        return safe.filter { normalize(it.title).contains(needle) }
    }

    private fun queryEvents(context: Context, start: Long, end: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, start)
            ContentUris.appendId(builder, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.ALL_DAY,
        )
        val events = mutableListOf<Event>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                val title = cursor.getString(1).orEmpty().ifBlank { "Untitled event" }
                events += Event(
                    id = cursor.getLong(0),
                    title = title,
                    beginAt = cursor.getLong(2),
                    endAt = cursor.getLong(3),
                    location = cursor.getString(4),
                    calendar = cursor.getString(5),
                    allDay = cursor.getInt(6) != 0,
                )
            }
        }
        return events
    }

    private fun agenda(events: List<Event>): Result {
        val safe = events.filterNot { SensitiveAppPolicy.mentionsCredentials(it.title) }
        if (safe.isEmpty()) return Result.Agenda("Ei range-e kono readable event nei.", "Calendar agenda: empty")
        val formatter = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        val screen = safe.take(20).mapIndexed { index, event ->
            val time = if (event.allDay) "all day" else formatter.format(Date(event.beginAt))
            val title = SensitiveAppPolicy.redactCodes(event.title).take(200)
            "${index + 1}. $time · $title${event.location?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        }.joinToString("\n")
        val spoken = safe.take(8).mapIndexed { index, event ->
            val time = if (event.allDay) "all day" else formatter.format(Date(event.beginAt))
            "${index + 1}, $time, ${SensitiveAppPolicy.redactCodes(event.title).take(100)}"
        }.joinToString("; ")
        val more = if (safe.size > 8) "; aro ${safe.size - 8} ta screen-e ache" else ""
        return Result.Agenda("Calendar agenda: $spoken$more.", screen)
    }

    private fun openMatched(
        context: Context,
        action: com.nuva.assistant.command.NuvaAction.CalendarProvider,
        events: List<Event>,
    ): Result {
        val query = action.eventQuery ?: return Result.NotFound("")
        val matches = matchEvents(events, query)
        if (matches.isEmpty()) return Result.NotFound(query)
        if (matches.size > 1) return Result.Ambiguous(matches.take(6))
        val event = matches.first()
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)
        val editing = action.operation == com.nuva.assistant.command.CalendarProviderOperation.EDIT_EVENT
        val intent = Intent(if (editing) Intent.ACTION_EDIT else Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return Result.Opened(event.title, editing)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("""[^a-z0-9\u0980-\u09FF ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private const val MAX_EVENTS = 100
}
