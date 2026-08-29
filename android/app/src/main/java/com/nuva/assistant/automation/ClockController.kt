package com.nuva.assistant.automation

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.nuva.assistant.command.ClockOperation

/** Official AlarmClock intents; no Accessibility tapping inside the clock app. */
object ClockController {
    sealed interface Result {
        data class Requested(val speech: String) : Result
        data class ClockOpened(val speech: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun execute(context: Context, operation: ClockOperation): Result {
        val action = when (operation) {
            ClockOperation.SHOW_ALARMS -> AlarmClock.ACTION_SHOW_ALARMS
            ClockOperation.SHOW_TIMERS -> "android.intent.action.SHOW_TIMERS"
            ClockOperation.SNOOZE_ALARM -> "android.intent.action.SNOOZE_ALARM"
            ClockOperation.DISMISS_ALARM -> "android.intent.action.DISMISS_ALARM"
            ClockOperation.DISMISS_TIMER -> "android.intent.action.DISMISS_TIMER"
        }
        val opened = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (opened) {
            val speech = when (operation) {
                ClockOperation.SHOW_ALARMS -> "Alarm list khulechi."
                ClockOperation.SHOW_TIMERS -> "Timer list khulechi."
                ClockOperation.SNOOZE_ALARM -> "Active alarm snooze request pathiyechi."
                ClockOperation.DISMISS_ALARM -> "Active alarm dismiss request pathiyechi."
                ClockOperation.DISMISS_TIMER -> "Active timer dismiss request pathiyechi."
            }
            return Result.Requested(speech)
        }
        return when (val fallback = AppLauncher.openApp(context, "clock", null)) {
            is AppLauncher.LaunchResult.Success -> Result.ClockOpened(
                "Clock app khulechi; ei device direct ${operation.wireName} intent support koreni, final action apni korun.",
            )
            is AppLauncher.LaunchResult.NotFound -> Result.Failed("Clock app ba official action paini.")
        }
    }
}
