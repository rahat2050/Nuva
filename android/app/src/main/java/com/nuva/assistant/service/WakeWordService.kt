package com.nuva.assistant.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * WAKE WORD — "Hey Nuva" (roadmap step 20, deliberately LAST).
 *
 * SCAFFOLD ONLY, NOT WIRED IN: the class exists and is documented, but v1
 * does not start it. Wake-word detection will be opt-in, battery-aware
 * (charging/docked or screen-on only) and will NEVER record silently — the
 * same microphone foreground notification governs it.
 *
 * Planned implementation: on-device keyword spotting (Picovoice/Porcupine or
 * Android's SpeechRecognizer hotword support), gated behind a Settings toggle
 * that ships OFF.
 */
class WakeWordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Intentionally inert until the wake-word phase ships. Do not record.
        stopSelf()
        return START_NOT_STICKY
    }
}
