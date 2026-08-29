package com.nuva.assistant.automation

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.nuva.assistant.command.MediaCommand
import com.nuva.assistant.command.NuvaAction
import com.nuva.assistant.service.NuvaNotificationListener

/** Official active-MediaSession transport controls, including bounded seek. */
object MediaPlaybackControl {

    sealed interface Result {
        data class Done(val speech: String) : Result
        data class Failed(val userReason: String) : Result
    }

    fun control(context: Context, action: NuvaAction.MediaControl): Result {
        val token = NuvaNotificationListener.activeMediaSessionToken()
            ?: return Result.Failed(
                "Ekhon kono media chalcche na ba notification access nei — gaan/video cholate tarpor abar bolen.",
            )
        return try {
            val controller = MediaController(context, token)
            val transport = controller.transportControls
            val speech = when (action.command) {
                MediaCommand.PLAY -> { transport.play(); "Media play korchi." }
                MediaCommand.PAUSE -> { transport.pause(); "Media pause korechi." }
                MediaCommand.TOGGLE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        transport.pause(); "Media pause korechi."
                    } else {
                        transport.play(); "Media play korchi."
                    }
                }
                MediaCommand.NEXT -> { transport.skipToNext(); "Porer track-e jacchi." }
                MediaCommand.PREVIOUS -> { transport.skipToPrevious(); "Ager track-e jacchi." }
                MediaCommand.STOP -> { transport.stop(); "Media stop korechi." }
                MediaCommand.FAST_FORWARD -> seek(controller, action.offsetSeconds ?: 10, forward = true)
                MediaCommand.REWIND -> seek(controller, action.offsetSeconds ?: 10, forward = false)
            }
            Result.Done(speech)
        } catch (_: Exception) {
            Result.Failed("Media control korte parini — player ta action support na-o korte pare.")
        }
    }

    private fun seek(controller: MediaController, seconds: Int, forward: Boolean): String {
        val transport = controller.transportControls
        val state = controller.playbackState
        val position = state?.position?.coerceAtLeast(0) ?: 0L
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        transport.seekTo(seekTarget(position, duration, seconds, forward))
        return if (forward) "$seconds second samne giyechi." else "$seconds second pichone giyechi."
    }

    fun seekTarget(position: Long, duration: Long?, seconds: Int, forward: Boolean): Long {
        val safePosition = position.coerceAtLeast(0)
        val delta = seconds.coerceIn(1, 300) * 1_000L
        val target = if (forward) safePosition + delta else (safePosition - delta).coerceAtLeast(0)
        return duration?.takeIf { it > 0 }?.let { target.coerceAtMost(it) } ?: target
    }
}
