package com.nuva.assistant.automation

import android.content.Context
import android.media.session.MediaController
import com.nuva.assistant.command.MediaCommand
import com.nuva.assistant.service.NuvaNotificationListener

/**
 * Media playback control (v1.2): pause/resume/next/previous through the
 * ACTIVE MediaSession, discovered from the media notification via
 * NotificationListenerService — the officially supported route for
 * third-party apps. Works for YouTube, Spotify, and any player that posts a
 * media-style notification. If nothing is playing, NUVA says so instead of
 * pretending.
 */
object MediaPlaybackControl {

    sealed interface Result {
        data object Done : Result
        data class Failed(val userReason: String) : Result
    }

    fun control(context: Context, command: MediaCommand): Result {
        val token = NuvaNotificationListener.activeMediaSessionToken()
            ?: return Result.Failed(
                "Ekhon kono media chalcche na ba notification access nei — " +
                    "gaan/video cholate tarpor abar bolen.",
            )
        return try {
            val controller = MediaController(context, token)
            val transport = controller.transportControls
            when (command) {
                MediaCommand.PLAY -> transport.play()
                MediaCommand.PAUSE -> transport.pause()
                MediaCommand.TOGGLE ->
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        transport.pause()
                    } else {
                        transport.play()
                    }

                MediaCommand.NEXT -> transport.skipToNext()
                MediaCommand.PREVIOUS -> transport.skipToPrevious()
            }
            Result.Done
        } catch (err: Exception) {
            Result.Failed("Media control korte parini — player ta lock kore rakhe hote pare.")
        }
    }
}
