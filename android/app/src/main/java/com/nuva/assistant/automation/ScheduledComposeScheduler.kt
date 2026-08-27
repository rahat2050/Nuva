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
import com.nuva.assistant.command.ComposeRecurrence
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.NuvaDateTimeParser
import com.nuva.assistant.command.RelativeDay
import com.nuva.assistant.command.Weekday
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.core.security.SensitiveAppPolicy
import com.nuva.assistant.database.entities.ScheduledDraftEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Persistent local draft reminders. A reminder opens a composer; it never sends. */
object ScheduledComposeScheduler {

    sealed interface Result {
        data class Scheduled(val id: Long, val triggerAt: Long) : Result
        data object NotificationPermissionMissing : Result
        data class Failed(val reason: String) : Result
    }

    sealed interface CancelResult {
        data class Cancelled(val id: Long) : CancelResult
        data object Missing : CancelResult
        data class Failed(val reason: String) : CancelResult
    }

    data class RestoreReport(val scheduled: Int, val delivered: Int, val failed: Int)

    suspend fun schedule(context: Context, action: NuvaAction.ScheduleCompose): Result {
        if (!NuvaPermissions.hasNotifications(context)) return Result.NotificationPermissionMissing
        validateAction(action)?.let { return Result.Failed(it) }
        val dao = NuvaContainer.database.scheduledDraftDao()
        val row = ScheduledDraftEntity(
            channel = action.channel.wireName,
            recipient = action.recipient,
            subject = action.subject,
            body = action.body.take(2_000),
            triggerAt = action.triggerAt,
            recurrence = action.recurrence.wireName,
        )
        val id = dao.insert(row)
        return try {
            scheduleAlarm(context, row.copy(id = id))
            Result.Scheduled(id, action.triggerAt)
        } catch (error: Exception) {
            dao.updateStatus(id, "failed")
            Result.Failed(error.message ?: "alarm scheduling failed")
        }
    }

    suspend fun pendingSpeech(): Pair<String, String?> {
        val rows = NuvaContainer.database.scheduledDraftDao().pendingOnce()
        if (rows.isEmpty()) return "Scheduled draft list ekhon khali." to "Scheduled drafts: empty"
        val formatter = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        val screen = rows.take(50).mapIndexed { index, row ->
            "${index + 1}. ${row.channel.uppercase()} · ${formatter.format(Date(row.triggerAt))} · ${row.recurrence} · ${row.recipient ?: "no recipient"} · ${row.body.take(100)}"
        }.joinToString("\n")
        val spoken = rows.take(8).mapIndexed { index, row ->
            "${index + 1}, ${row.channel}, ${formatter.format(Date(row.triggerAt))}, ${row.recurrence}"
        }.joinToString("; ")
        val more = if (rows.size > 8) "; aro ${rows.size - 8} ta screen e ache" else ""
        return "Scheduled drafts: $spoken$more." to screen
    }

    suspend fun cancelByOrdinal(context: Context, ordinal: Int): CancelResult {
        val dao = NuvaContainer.database.scheduledDraftDao()
        val row = dao.pendingOnce().getOrNull(ordinal.coerceIn(1, 100) - 1) ?: return CancelResult.Missing
        return try {
            cancelAlarm(context, row.id)
            dao.updateStatus(row.id, "cancelled")
            CancelResult.Cancelled(row.id)
        } catch (error: Exception) {
            CancelResult.Failed(error.message ?: "cancel failed")
        }
    }

    suspend fun restorePending(context: Context): RestoreReport {
        if (!NuvaPermissions.hasNotifications(context)) return RestoreReport(0, 0, 0)
        var scheduled = 0
        var delivered = 0
        var failed = 0
        val now = System.currentTimeMillis()
        for (row in NuvaContainer.database.scheduledDraftDao().pendingOnce()) {
            try {
                if (row.triggerAt <= now) {
                    handleAlarm(context, row.id)
                    delivered++
                } else {
                    scheduleAlarm(context, row)
                    scheduled++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return RestoreReport(scheduled, delivered, failed)
    }

    suspend fun handleAlarm(context: Context, id: Long) {
        if (!NuvaPermissions.hasNotifications(context)) return
        val dao = NuvaContainer.database.scheduledDraftDao()
        val row = dao.get(id) ?: return
        if (row.status != "pending") return
        if (SensitiveAppPolicy.mentionsCredentials(row.body) || SensitiveAppPolicy.refusalForText(row.body) != null) {
            dao.updateStatus(id, "blocked")
            return
        }
        postDraftNotification(context, row)
        val recurrence = ComposeRecurrence.fromWire(row.recurrence) ?: ComposeRecurrence.ONCE
        if (recurrence == ComposeRecurrence.ONCE) {
            dao.updateStatus(id, "fired")
            return
        }
        val next = nextRecurringTrigger(row.triggerAt, recurrence, System.currentTimeMillis())
        dao.reschedule(id, next)
        scheduleAlarm(context, row.copy(triggerAt = next))
    }

    fun nextRecurringTrigger(previous: Long, recurrence: ComposeRecurrence, now: Long): Long {
        require(recurrence != ComposeRecurrence.ONCE) { "ONCE has no next trigger" }
        var next = previous
        val step = recurrence.days * 86_400_000L
        do next += step while (next <= now)
        return next
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

    private fun validateAction(action: NuvaAction.ScheduleCompose): String? = when {
        action.triggerAt <= System.currentTimeMillis() -> "Reminder time future-e hote hobe."
        SensitiveAppPolicy.mentionsCredentials(action.body) || SensitiveAppPolicy.refusalForText(action.body) != null ->
            "Sensitive ba financial draft schedule kora jabe na."
        else -> null
    }

    private fun scheduleAlarm(context: Context, row: ScheduledDraftEntity) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            row.triggerAt,
            alarmPendingIntent(context, row.id, PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun cancelAlarm(context: Context, id: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(alarmPendingIntent(context, id, PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun alarmPendingIntent(context: Context, id: Long, modeFlag: Int): PendingIntent {
        val intent = Intent(context, ScheduledComposeReceiver::class.java).apply {
            data = Uri.parse("nuva://scheduled-draft/$id")
            putExtra(EXTRA_DRAFT_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            modeFlag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun postDraftNotification(context: Context, row: ScheduledDraftEntity) {
        if (!NuvaPermissions.hasNotifications(context)) return
        val channel = ComposeChannel.fromWire(row.channel) ?: return
        val draftIntent = when (channel) {
            ComposeChannel.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(EmailComposer.mailtoUri(row.recipient))).apply {
                row.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                putExtra(Intent.EXTRA_TEXT, row.body)
            }
            ComposeChannel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${row.recipient.orEmpty()}" )).apply {
                putExtra("sms_body", row.body)
            }
        }
        val content = PendingIntent.getActivity(
            context,
            requestCode(row.id),
            draftIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scheduled drafts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "User-created reminders that open an email or SMS draft."
            },
        )
        manager.notify(
            requestCode(row.id),
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(if (channel == ComposeChannel.EMAIL) "Email draft ready" else "SMS draft ready")
                .setContentText(row.body.take(120))
                .setContentIntent(content)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build(),
        )
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

    private fun requestCode(id: Long): Int = (id xor (id ushr 32)).toInt() and Int.MAX_VALUE

    internal const val EXTRA_DRAFT_ID = "nuva.compose.draft_id"
    private const val CHANNEL_ID = "nuva_scheduled_drafts"
}

class ScheduledComposeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(ScheduledComposeScheduler.EXTRA_DRAFT_ID, -1L) ?: -1L
        if (id <= 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledComposeScheduler.handleAlarm(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }
    }
}

class ScheduledComposeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        NuvaContainer.init(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledComposeScheduler.restorePending(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
