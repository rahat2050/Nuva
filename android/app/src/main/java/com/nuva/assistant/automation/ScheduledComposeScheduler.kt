package com.nuva.assistant.automation

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nuva.assistant.command.ComposeChannel
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaDateTimeParser
import com.nuva.assistant.command.RelativeDay
import com.nuva.assistant.command.Weekday
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.core.security.SensitiveAppPolicy
import java.util.Calendar

/** Schedules a local reminder notification; tapping it opens a draft, never auto-sends. */
object ScheduledComposeScheduler {

    sealed interface Result {
        data class Scheduled(val triggerAt: Long) : Result
        data object NotificationPermissionMissing : Result
        data class Failed(val reason: String) : Result
    }

    fun schedule(context: Context, action: NuvaAction.ScheduleCompose): Result {
        if (!NuvaPermissions.hasNotifications(context)) return Result.NotificationPermissionMissing
        if (action.triggerAt <= System.currentTimeMillis()) return Result.Failed("Reminder time future-e hote hobe.")
        if (SensitiveAppPolicy.mentionsCredentials(action.body) || SensitiveAppPolicy.refusalForText(action.body) != null) {
            return Result.Failed("Sensitive ba financial draft schedule kora jabe na.")
        }
        return try {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduledComposeReceiver::class.java).apply {
                putExtra(EXTRA_CHANNEL, action.channel.wireName)
                putExtra(EXTRA_RECIPIENT, action.recipient)
                putExtra(EXTRA_SUBJECT, action.subject)
                putExtra(EXTRA_BODY, action.body.take(2_000))
                putExtra(EXTRA_ID, requestCode(action))
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.triggerAt, pending)
            Result.Scheduled(action.triggerAt)
        } catch (error: Exception) {
            Result.Failed(error.message ?: "alarm scheduling failed")
        }
    }

    fun nextTrigger(
        rawText: String,
        hour: Int,
        minute: Int,
        now: Calendar = Calendar.getInstance(),
    ): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (NuvaDateTimeParser.relativeDay(rawText)) {
            RelativeDay.TOMORROW -> target.add(Calendar.DAY_OF_YEAR, 1)
            RelativeDay.TODAY -> if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
            null -> {
                val weekday = NuvaDateTimeParser.weekday(rawText)
                if (weekday != null) {
                    val wanted = weekday.toCalendarDay()
                    var delta = (wanted - target.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (delta == 0 && target.timeInMillis <= now.timeInMillis) delta = 7
                    target.add(Calendar.DAY_OF_YEAR, delta)
                } else if (target.timeInMillis <= now.timeInMillis) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        return target.timeInMillis
    }

    private fun Weekday.toCalendarDay(): Int = when (this) {
        Weekday.MON -> Calendar.MONDAY
        Weekday.TUE -> Calendar.TUESDAY
        Weekday.WED -> Calendar.WEDNESDAY
        Weekday.THU -> Calendar.THURSDAY
        Weekday.FRI -> Calendar.FRIDAY
        Weekday.SAT -> Calendar.SATURDAY
        Weekday.SUN -> Calendar.SUNDAY
    }

    private fun requestCode(action: NuvaAction.ScheduleCompose): Int =
        (action.triggerAt xor action.body.hashCode().toLong()).toInt() and Int.MAX_VALUE

    internal const val EXTRA_CHANNEL = "nuva.compose.channel"
    internal const val EXTRA_RECIPIENT = "nuva.compose.recipient"
    internal const val EXTRA_SUBJECT = "nuva.compose.subject"
    internal const val EXTRA_BODY = "nuva.compose.body"
    internal const val EXTRA_ID = "nuva.compose.id"
}

class ScheduledComposeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!NuvaPermissions.hasNotifications(context)) return
        val source = intent ?: return
        val channel = ComposeChannel.fromWire(source.getStringExtra(ScheduledComposeScheduler.EXTRA_CHANNEL)) ?: return
        val recipient = source.getStringExtra(ScheduledComposeScheduler.EXTRA_RECIPIENT)
        val subject = source.getStringExtra(ScheduledComposeScheduler.EXTRA_SUBJECT)
        val body = source.getStringExtra(ScheduledComposeScheduler.EXTRA_BODY).orEmpty()
        val id = source.getIntExtra(ScheduledComposeScheduler.EXTRA_ID, 7_300)

        val draftIntent = when (channel) {
            ComposeChannel.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(EmailComposer.mailtoUri(recipient))).apply {
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                putExtra(Intent.EXTRA_TEXT, body)
            }
            ComposeChannel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${recipient.orEmpty()}" )).apply {
                putExtra("sms_body", body)
            }
        }
        val content = PendingIntent.getActivity(
            context,
            id,
            draftIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled drafts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "User-created reminders that open an email or SMS draft."
            },
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (channel == ComposeChannel.EMAIL) "Email draft ready" else "SMS draft ready")
            .setContentText(body.take(120))
            .setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "nuva_scheduled_drafts"
    }
}
