package com.nuva.assistant.systemassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.nuva.assistant.MainActivity

/** Creates the user-visible session for Android's assistant gesture/shortcut. */
class NuvaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        NuvaVoiceInteractionSession(this)
}

/**
 * Delegates the official assistant surface to NUVA's existing Activity. A wake
 * event already being handled by WakeWordService opens the Activity for visual
 * parity while that service owns the current recognizer/confirmation session.
 * A normal Android assistant gesture opens the Activity and starts one in-app
 * listening session.
 */
private class NuvaVoiceInteractionSession(
    private val sessionContext: Context,
) : VoiceInteractionSession(sessionContext) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val fromWakeService = args?.getBoolean(
            NuvaVoiceInteractionService.EXTRA_WAKE_SOURCE,
            false,
        ) == true
        val intent = Intent(sessionContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SYSTEM_ASSISTANT
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_LISTEN_IN_APP, !fromWakeService)
            args?.getString(NuvaVoiceInteractionService.EXTRA_INLINE_COMMAND)
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(MainActivity.EXTRA_INLINE_COMMAND, it) }
        }

        runCatching { startAssistantActivity(intent) }
            .recoverCatching { sessionContext.startActivity(intent) }
    }
}
