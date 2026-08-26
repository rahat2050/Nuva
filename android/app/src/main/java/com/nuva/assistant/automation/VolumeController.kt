package com.nuva.assistant.automation

import android.content.Context
import android.media.AudioManager
import com.nuva.assistant.command.VolumeCommand

/**
 * Direct volume control (v1.2). Android permits third-party apps to change
 * media volume (raising is capped by safe-volume rules on some devices), so
 * NUVA changes it directly instead of detouring to the settings screen.
 */
object VolumeController {

    sealed interface Result {
        data class Done(val speech: String) : Result
        data class Failed(val userReason: String) : Result
    }

    fun control(context: Context, command: VolumeCommand): Result = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = AudioManager.FLAG_SHOW_UI
        when (command) {
            VolumeCommand.UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
            VolumeCommand.DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
            VolumeCommand.MUTE -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, flags)
        }
        val percent = streamVolumePercent(audio)
        Result.Done(
            when (command) {
                VolumeCommand.MUTE -> "Sound mute korlam."
                else -> "Volume ekhon $percent%."
            },
        )
    } catch (err: Exception) {
        Result.Failed("Volume change korte parini.")
    }

    private fun streamVolumePercent(audio: AudioManager): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100 / max).coerceIn(0, 100)
    }
}
