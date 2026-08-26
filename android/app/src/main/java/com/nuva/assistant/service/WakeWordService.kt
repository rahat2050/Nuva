package com.nuva.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nuva.assistant.command.CommandExecutor
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.ui.floating.FloatingAssistantOverlay
import com.nuva.assistant.voice.SpeechRecognizerController
import com.nuva.assistant.voice.TTSManager
import com.nuva.assistant.voice.WakePhraseDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android-compliant wake-word fallback for "Hey Nuva".
 *
 * This is deliberately opt-in and visible:
 *  - user enables it from Settings after granting microphone + overlay perms;
 *  - a foreground microphone notification remains visible while active;
 *  - wake-loop transcripts are checked locally by [WakePhraseDetector];
 *  - NUVA sends text to Vercel/Groq only after the wake phrase is detected.
 *
 * A true low-power DSP wake-word engine can replace [listenForWakeOnce] later
 * without changing the command pipeline or overlay contract. Until then, the
 * service keeps battery use bounded by listening only while the screen is
 * interactive and by restarting short Android SpeechRecognizer sessions.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: FloatingAssistantOverlay
    private lateinit var tts: TTSManager

    private var wakeJob: Job? = null
    private var commandJob: Job? = null

    /**
     * Follow-up session (v1.4): after the popup opens from a VERIFIED wake
     * event, a short conversational workflow is allowed — up to
     * [MAX_FOLLOW_UPS] additional commands with shared context, then NUVA
     * re-arms pure wake-word listening. The popup NEVER opens by itself.
     */
    private var followUpsLeft: Int = 0
    private var recognizer: SpeechRecognizerController? = null

    override fun onCreate() {
        super.onCreate()
        overlay = FloatingAssistantOverlay(this)
        tts = TTSManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                scope.launch {
                    NuvaContainer.preferences.setWakeWordEnabled(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TRIGGER -> {
                startForegroundVisible()
                activateAssistant(initialCommand = null, fromWake = false)
                return START_STICKY
            }

            else -> {
                startForegroundVisible()
                if (!NuvaContainer.preferences.wakeWordEnabledBlocking()) {
                    scope.launch { NuvaContainer.preferences.setWakeWordEnabled(true) }
                }
                startWakeLoop()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        wakeJob?.cancel()
        commandJob?.cancel()
        recognizer = null
        overlay.dismiss()
        tts.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWakeLoop() {
        if (wakeJob?.isActive == true || commandJob?.isActive == true) return

        wakeJob = scope.launch {
            if (!checkWakePermissions()) return@launch

            while (isActive && NuvaContainer.preferences.wakeWordEnabledBlocking()) {
                if (!isScreenInteractive()) {
                    delay(SCREEN_OFF_RETRY_MS)
                    continue
                }

                val detectedCommand = listenForWakeOnce()
                if (detectedCommand != WakeListenResult.NoWake) {
                    val commandAfterWake = (detectedCommand as WakeListenResult.WakeDetected).commandAfterWake
                    activateAssistant(commandAfterWake, fromWake = true)
                    break
                }
                delay(WAKE_RESTART_DELAY_MS)
            }
        }
    }

    private suspend fun checkWakePermissions(): Boolean {
        if (!NuvaPermissions.hasRecordAudio(this)) {
            val speech = "Microphone permission lagbe. NUVA app er Settings theke permission din."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Permission needed", speech, autoDismissMs = 5_000)
            speakIfEnabled(speech)
            stopSelf()
            return false
        }
        if (!NuvaPermissions.canDrawOverlays(this)) {
            val speech = "Floating popup er jonno overlay permission lagbe."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Overlay needed", speech, autoDismissMs = 5_000)
            speakIfEnabled(speech)
            // Keep the notification alive as an Android-approved fallback; the
            // user can tap it to speak, but the full hands-free UX needs overlay.
        }
        return true
    }

    private suspend fun listenForWakeOnce(): WakeListenResult {
        recognizer = SpeechRecognizerController(this)
        val language = wakeRecognizerLanguage()
        var result: WakeListenResult = WakeListenResult.NoWake

        try {
            recognizer?.listen(language)?.collect { event ->
                when (event) {
                    SpeechRecognizerController.VoiceEvent.ListeningStarted -> Unit
                    is SpeechRecognizerController.VoiceEvent.Partial -> {
                        WakePhraseDetector.detect(event.text)?.let { match ->
                            result = WakeListenResult.WakeDetected(match.commandAfterWake)
                            throw WakeDetectedCancellation()
                        }
                    }

                    is SpeechRecognizerController.VoiceEvent.Final -> {
                        WakePhraseDetector.detect(event.text)?.let { match ->
                            result = WakeListenResult.WakeDetected(match.commandAfterWake)
                        }
                    }

                    is SpeechRecognizerController.VoiceEvent.Error -> {
                        if (event.speech.contains("permission", ignoreCase = true)) {
                            result = WakeListenResult.NoWake
                            stopSelf()
                        }
                    }
                }
            }
        } catch (_: WakeDetectedCancellation) {
            // Expected fast path: partial result already contained the wake word.
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            result = WakeListenResult.NoWake
        } finally {
            recognizer = null
        }
        return result
    }

    private fun activateAssistant(initialCommand: String?, fromWake: Boolean) {
        wakeJob?.cancel()
        wakeJob = null
        commandJob?.cancel()
        followUpsLeft = if (fromWake) MAX_FOLLOW_UPS else 0

        commandJob = scope.launch {
            overlay.showStatus(
                FloatingAssistantOverlay.PopupState.LISTENING,
                if (fromWake) "Hey Nuva" else "NUVA",
                "Listening…",
                onDismiss = { stopCurrentCommandAndRearm() },
            )
            if (fromWake) speakIfEnabled("Listening")

            if (!initialCommand.isNullOrBlank()) {
                processCommand(initialCommand)
            } else {
                listenForCommandOnce()
            }
        }
    }

    private suspend fun listenForCommandOnce() {
        if (!NuvaPermissions.hasRecordAudio(this)) {
            val speech = "Microphone permission lagbe."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Permission needed", speech, autoDismissMs = 4_000) {
                rearmIfEnabled()
            }
            speakIfEnabled(speech)
            return
        }

        recognizer = SpeechRecognizerController(this)
        val language = NuvaContainer.preferences.languageBlocking()
        try {
            recognizer?.listen(language)?.collect { event ->
                when (event) {
                    SpeechRecognizerController.VoiceEvent.ListeningStarted -> Unit
                    is SpeechRecognizerController.VoiceEvent.Partial ->
                        overlay.showStatus(FloatingAssistantOverlay.PopupState.LISTENING, "Listening…", event.text)

                    is SpeechRecognizerController.VoiceEvent.Final ->
                        processCommand(event.text)

                    is SpeechRecognizerController.VoiceEvent.Error -> {
                        overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Could not hear", event.speech, autoDismissMs = 4_000) {
                            rearmIfEnabled()
                        }
                        speakIfEnabled(event.speech)
                    }
                }
            }
        } finally {
            recognizer = null
        }
    }

    private suspend fun processCommand(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            val speech = "Kichu shunlam na, abar bolen."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "No command", speech, autoDismissMs = 4_000) {
                rearmIfEnabled()
            }
            speakIfEnabled(speech)
            return
        }

        overlay.showStatus(FloatingAssistantOverlay.PopupState.PROCESSING, "Processing…", cleanText)
        when (val step = NuvaContainer.commandExecutor.process(cleanText)) {
            is CommandExecutor.Step.AwaitingConfirmation -> showConfirmation(step)
            is CommandExecutor.Step.AwaitingContactChoice -> showTerminal(
                success = false,
                speech = "একাধিক contact পাওয়া গেছে — NUVA app খুলে একজন বেছে নিন।",
            )
            is CommandExecutor.Step.Done -> showTerminal(success = true, speech = step.speech, detail = step.screenText)
            is CommandExecutor.Step.Failed -> showTerminal(success = false, speech = step.speech)
            is CommandExecutor.Step.Decision -> {
                val speech = step.decision.speech.ifBlank { "Kore dicchi." }
                showTerminal(success = true, speech = speech)
            }

            is CommandExecutor.Step.Executing ->
                overlay.showStatus(FloatingAssistantOverlay.PopupState.EXECUTING, "Executing…", step.action.intent.wireName)
        }
    }

    private fun showConfirmation(step: CommandExecutor.Step.AwaitingConfirmation) {
        val decision = step.decision
        val intent = decision.intent?.wireName ?: "ACTION"
        val risk = decision.risk.name.lowercase()
        val body = buildString {
            append(decision.speech.ifBlank { "Ei kaj ta korbo?" })
            append("\nRisk: ").append(risk)
            decision.reasons.firstOrNull()?.let { append("\n").append(it) }
        }

        overlay.showConfirmation(
            title = "Confirm $intent",
            message = body,
            confirmLabel = "Yes, do it",
            rejectLabel = "Cancel",
            onConfirm = {
                scope.launch {
                    overlay.showStatus(FloatingAssistantOverlay.PopupState.EXECUTING, "Executing…", intent)
                    when (val confirmed = NuvaContainer.commandExecutor.confirm(step.pendingId)) {
                        is CommandExecutor.Step.Done -> showTerminal(success = true, speech = confirmed.speech, detail = confirmed.screenText)
                        is CommandExecutor.Step.Failed -> showTerminal(success = false, speech = confirmed.speech)
                        else -> showTerminal(success = false, speech = "Action ta complete korte parini.")
                    }
                }
            },
            onReject = {
                scope.launch {
                    when (val rejected = NuvaContainer.commandExecutor.reject(step.pendingId)) {
                        is CommandExecutor.Step.Done -> showTerminal(success = true, speech = rejected.speech)
                        is CommandExecutor.Step.Failed -> showTerminal(success = false, speech = rejected.speech)
                        else -> showTerminal(success = true, speech = "Thik ache, koreni.")
                    }
                }
            },
        )
        speakIfEnabled(decision.speech.ifBlank { "Confirm korben?" })
    }

    private fun showTerminal(success: Boolean, speech: String, detail: String? = null) {
        overlay.showStatus(
            if (success) FloatingAssistantOverlay.PopupState.SUCCESS else FloatingAssistantOverlay.PopupState.ERROR,
            if (success) "Done" else "Problem",
            detail?.takeIf { it.isNotBlank() } ?: speech,
            autoDismissMs = 4_000,
        ) {
            rearmIfEnabled()
        }
        speakIfEnabled(speech)
        commandJob = null
        scope.launch {
            delay(4_100)
            // Conversational follow-up: keep the session briefly open after a
            // SUCCESS so "Rohim-er chat kholo" → "ওকে বলো …" works without a
            // new wake word. Failures always return to wake listening.
            if (success && followUpsLeft > 0) {
                followUpsLeft--
                overlay.showStatus(FloatingAssistantOverlay.PopupState.LISTENING, "NUVA", "আর কিছু? শুনছি…")
                listenForCommandOnce()
            } else {
                rearmIfEnabled()
            }
        }
    }

    private fun stopCurrentCommandAndRearm() {
        commandJob?.cancel()
        commandJob = null
        recognizer = null
        rearmIfEnabled()
    }

    private fun rearmIfEnabled() {
        if (NuvaContainer.preferences.wakeWordEnabledBlocking()) startWakeLoop() else stopSelf()
    }

    private fun speakIfEnabled(text: String) {
        if (text.isBlank()) return
        if (!NuvaContainer.preferences.voiceEnabledBlocking()) return
        val language = NuvaContainer.preferences.languageBlocking()
        tts.speak(text, if (language == "auto") "banglish" else language)
    }

    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isInteractive
    }

    private fun wakeRecognizerLanguage(): String = when (NuvaContainer.preferences.languageBlocking()) {
        "bn" -> "bn" // support users who say “হে নুভা”
        else -> "en" // target phrase is “Hey Nuva”
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NUVA wake word",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible status while NUVA is waiting for Hey Nuva."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundVisible() {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Most likely RECORD_AUDIO was revoked while the service was being
            // restored. Stop instead of trying to run a hidden microphone task.
            stopSelf()
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NUVA active")
            .setContentText("Say “Hey Nuva” or tap to speak. Mic use is visible and opt-in.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(servicePendingIntent(ACTION_TRIGGER, 10))
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak now", servicePendingIntent(ACTION_TRIGGER, 11))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", servicePendingIntent(ACTION_STOP, 12))
            .build()

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WakeWordService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private sealed interface WakeListenResult {
        data object NoWake : WakeListenResult
        data class WakeDetected(val commandAfterWake: String?) : WakeListenResult
    }

    private class WakeDetectedCancellation : RuntimeException()

    companion object {
        /** Extra commands allowed in one wake session after the first success. */
        private const val MAX_FOLLOW_UPS = 2
        private const val CHANNEL_ID = "nuva_wake_word"
        private const val NOTIFICATION_ID = 43
        private const val WAKE_RESTART_DELAY_MS = 450L
        private const val SCREEN_OFF_RETRY_MS = 2_000L

        const val ACTION_START = "com.nuva.assistant.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.nuva.assistant.action.STOP_WAKE_WORD"
        const val ACTION_TRIGGER = "com.nuva.assistant.action.TRIGGER_ASSISTANT"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }

        fun trigger(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_TRIGGER)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
