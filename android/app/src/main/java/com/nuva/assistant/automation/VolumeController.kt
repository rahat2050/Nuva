package com.nuva.assistant.automation

import android.content.Context
import android.media.AudioManager
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.command.VolumeCommand
import kotlin.math.roundToInt

/** Direct bounded media-volume control through AudioManager. */
object VolumeController {

    sealed interface Result {
        data class Done(val speech: String) : Result
        data class Failed(val userReason: String) : Result
    }

    fun control(context: Context, action: NuvaAction.VolumeControl): Result = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = AudioManager.FLAG_SHOW_UI
        when (action.command) {
            VolumeCommand.UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
            VolumeCommand.DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
            VolumeCommand.MUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flags)
            VolumeCommand.UNMUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, flags)
            VolumeCommand.SET -> {
                val level = action.levelPercent ?: return Result.Failed("Volume level missing.")
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, indexForPercent(max, level), flags)
            }
        }
        val percent = streamVolumePercent(audio)
        Result.Done(
            when (action.command) {
                VolumeCommand.MUTE -> "Sound mute korechi."
                VolumeCommand.UNMUTE -> "Sound unmute korechi; volume $percent percent."
                else -> "Volume ekhon $percent percent."
            },
        )
    } catch (_: Exception) {
        Result.Failed("Volume change korte parini.")
    }

    fun indexForPercent(maxIndex: Int, percent: Int): Int {
        val max = maxIndex.coerceAtLeast(1)
        return (max * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(0, max)
    }

    private fun streamVolumePercent(audio: AudioManager): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100 / max).coerceIn(0, 100)
    }
}
