package com.nuva.assistant.systemassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import java.lang.ref.WeakReference

/**
 * Android's official entry point when the user selects NUVA as the default
 * digital assistant. It stays deliberately lightweight and never captures
 * screen context or screenshots.
 */
class NuvaVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // The manifest declares assist support so NUVA can qualify for the
        // system picker, but the product does not need or retain foreground app
        // structure/screenshots just to open its voice surface.
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or
                VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )
        activeService = WeakReference(this)
    }

    override fun onShutdown() {
        activeService?.get()?.takeIf { it === this }?.let {
            activeService?.clear()
            activeService = null
        }
        super.onShutdown()
    }

    companion object {
        internal const val EXTRA_WAKE_SOURCE = "com.nuva.assistant.extra.WAKE_SOURCE"
        internal const val EXTRA_INLINE_COMMAND = "com.nuva.assistant.extra.INLINE_COMMAND"

        @Volatile
        private var activeService: WeakReference<NuvaVoiceInteractionService>? = null

        /**
         * Promotes a verified software wake event into an official assistant
         * session. Returns false when NUVA is not the active system assistant,
         * in which case WakeWordService keeps using its visible overlay.
         */
        fun showFromVerifiedWake(commandAfterWake: String?): Boolean {
            val service = activeService?.get() ?: return false
            return runCatching {
                val args = Bundle().apply {
                    putBoolean(EXTRA_WAKE_SOURCE, true)
                    commandAfterWake?.takeIf { it.isNotBlank() }?.let {
                        putString(EXTRA_INLINE_COMMAND, it)
                    }
                }
                service.showSession(args, 0)
                true
            }.getOrDefault(false)
        }
    }
}
