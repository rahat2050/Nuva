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
import com.nuva.assistant.systemassistant.NuvaVoiceInteractionService
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
     * Follow-up session (v1.4), modelled by [WakeSessionState]: after the
     * popup opens from a VERIFIED wake event, a short conversational flow is
     * allowed (≤ [WakeSessionState.DEFAULT_MAX_FOLLOW_UPS] follow-ups), then
     * NUVA re-arms pure wake-word listening. The popup NEVER opens by itself.
     */
    private val wakeSession = WakeSessionState()
    private var recognizer: SpeechRecognizerController? = null

    override fun onCreate() {
        super.onCreate()
        updateRuntimeState(RuntimeState.STARTING, "Starting visible wake listener…")
        overlay = FloatingAssistantOverlay(this)
        tts = TTSManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                updateRuntimeState(RuntimeState.STOPPED, "Stopped by user")
                scope.launch {
                    NuvaContainer.preferences.setWakeWordEnabled(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TRIGGER -> {
                if (!startForegroundVisible()) return START_NOT_STICKY
                val keepWakeMode = NuvaContainer.preferences.wakeWordEnabledBlocking()
                activateAssistant(initialCommand = null, fromWake = false)
                return if (keepWakeMode) START_STICKY else START_NOT_STICKY
            }

            else -> {
                if (!startForegroundVisible()) return START_NOT_STICKY
                if (!NuvaContainer.preferences.wakeWordEnabledBlocking()) {
                    // Persist opt-in before the loop reads it. The old ordering
                    // could race and immediately exit on a first-time enable.
                    scope.launch {
                        NuvaContainer.preferences.setWakeWordEnabled(true)
                        startWakeLoop()
                    }
                } else {
                    startWakeLoop()
                }
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
        if (_runtimeStatus.value.state != RuntimeState.ERROR) {
            updateRuntimeState(RuntimeState.STOPPED, "Wake listener is not running")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWakeLoop() {
        if (wakeJob?.isActive == true || commandJob?.isActive == true) return

        wakeJob = scope.launch {
            if (!checkWakePermissions()) return@launch

            while (isActive && NuvaContainer.preferences.wakeWordEnabledBlocking()) {
                if (!isScreenInteractive()) {
                    updateRuntimeState(RuntimeState.PAUSED_SCREEN_OFF, "Paused while screen is off")
                    delay(SCREEN_OFF_RETRY_MS)
                    continue
                }

                updateRuntimeState(RuntimeState.WAITING_FOR_WAKE, "Listening for “Hey Nuva”")
                val detectedCommand = listenForWakeOnce()
                if (detectedCommand != WakeListenResult.NoWake) {
                    val commandAfterWake = (detectedCommand as WakeListenResult.WakeDetected).commandAfterWake
                    updateRuntimeState(RuntimeState.WAKE_DETECTED, "Hey Nuva detected")
                    activateAssistant(commandAfterWake, fromWake = true)
                    break
                }
                delay(WAKE_RESTART_DELAY_MS)
            }
        }
    }

    private suspend fun checkWakePermissions(): Boolean {
        if (!NuvaPermissions.hasRecordAudio(this)) {
            updateRuntimeState(RuntimeState.ERROR, "Microphone permission missing")
            val speech = "Microphone permission lagbe. NUVA app er Settings theke permission din."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Permission needed", speech, autoDismissMs = 5_000)
            speakIfEnabled(speech)
            stopSelf()
            return false
        }
        if (!NuvaPermissions.canDrawOverlays(this)) {
            updateRuntimeState(RuntimeState.ERROR, "Overlay permission missing; notification fallback only")
            val speech = "Floating popup er jonno overlay permission lagbe."
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Overlay needed", speech, autoDismissMs = 5_000)
            speakIfEnabled(speech)
            // Keep the notification alive as an Android-approved fallback; the
            // user can tap it to speak, but the full hands-free UX needs overlay.
        }
        return true
    }

    private suspend fun listenForWakeOnce(): WakeListenResult =
        withTimeoutOrNull(WAKE_LISTEN_TIMEOUT_MS) {
            recognizer = SpeechRecognizerController(this@WakeWordService)
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
                                updateRuntimeState(RuntimeState.ERROR, "Microphone permission missing")
                                result = WakeListenResult.NoWake
                                stopSelf()
                            }
                        }
                    }
                }
            } catch (_: WakeDetectedCancellation) {
                // Expected fast path: partial result contained the wake word.
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                result = WakeListenResult.NoWake
            } finally {
                recognizer = null
            }
            result
        } ?: WakeListenResult.NoWake

    private fun activateAssistant(initialCommand: String?, fromWake: Boolean) {
        wakeJob?.cancel()
        wakeJob = null
        commandJob?.cancel()
        if (fromWake) wakeSession.onVerifiedWake() else wakeSession.onDismissed()
        if (fromWake) {
            // When NUVA is the user-selected default assistant, promote the
            // verified wake into Android's official assistant session so the
            // full app opens like other assistants. The visible overlay remains
            // the fallback on devices/OEMs where NUVA is not selected.
            NuvaVoiceInteractionService.showFromVerifiedWake(initialCommand)
        }

        commandJob = scope.launch {
            updateRuntimeState(RuntimeState.LISTENING_FOR_COMMAND, "Wake accepted; listening for command")
            overlay.showStatus(
                FloatingAssistantOverlay.PopupState.LISTENING,
                if (fromWake) "Hey Nuva" else "NUVA",
                "Listening…",
                onDismiss = { stopCurrentCommandAndRearm() },
            )
            // Do not speak "Listening" into our own recognizer: that feedback
            // used to race the next microphone session on loudspeaker devices.

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
            // v1.6: stuck-listening recovery — a silent recognizer death ends
            // the session and re-arms wake listening instead of hanging.
            kotlinx.coroutines.withTimeout(15_000L) {
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
            }
        } catch (err: kotlinx.coroutines.TimeoutCancellationException) {
            overlay.showStatus(FloatingAssistantOverlay.PopupState.ERROR, "Timeout", "কিছু শুনতে পাইনি — আবার Hey Nuva বলুন।", autoDismissMs = 4_000) {
                rearmIfEnabled()
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

        updateRuntimeState(RuntimeState.PROCESSING, "Processing verified voice command")
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
            if (wakeSession.onCommandFinished(success)) {
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

    private fun updateRuntimeState(state: RuntimeState, detail: String) {
        _runtimeStatus.value = RuntimeStatus(state, detail)
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

    private fun startForegroundVisible(): Boolean {
        val notification = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse {
            // Most likely RECORD_AUDIO was revoked or Android rejected a
            // background while-in-use start. Never fall back to a hidden mic.
            updateRuntimeState(RuntimeState.ERROR, "Android blocked the microphone foreground service")
            stopSelf()
            false
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

    enum class RuntimeState {
        STOPPED,
        STARTING,
        WAITING_FOR_WAKE,
        PAUSED_SCREEN_OFF,
        WAKE_DETECTED,
        LISTENING_FOR_COMMAND,
        PROCESSING,
        ERROR,
    }

    data class RuntimeStatus(
        val state: RuntimeState,
        val detail: String,
    )

    companion object {
        private const val CHANNEL_ID = "nuva_wake_word"
        private const val NOTIFICATION_ID = 43
        private const val WAKE_RESTART_DELAY_MS = 450L
        private const val SCREEN_OFF_RETRY_MS = 2_000L
        private const val WAKE_LISTEN_TIMEOUT_MS = 12_000L

        private val _runtimeStatus = MutableStateFlow(
            RuntimeStatus(RuntimeState.STOPPED, "Wake listener is not running"),
        )
        val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus.asStateFlow()

        const val ACTION_START = "com.nuva.assistant.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.nuva.assistant.action.STOP_WAKE_WORD"
        const val ACTION_TRIGGER = "com.nuva.assistant.action.TRIGGER_ASSISTANT"

        fun start(context: Context): Boolean {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            return runCatching {
                _runtimeStatus.value = RuntimeStatus(RuntimeState.STARTING, "Starting visible wake listener…")
                context.startForegroundService(intent)
                true
            }.getOrElse {
                _runtimeStatus.value = RuntimeStatus(RuntimeState.ERROR, "Android could not start wake listener")
                false
            }
        }

        fun stop(context: Context): Boolean = runCatching {
            val stopped = context.stopService(Intent(context, WakeWordService::class.java))
            _runtimeStatus.value = RuntimeStatus(RuntimeState.STOPPED, "Wake listener is not running")
            stopped
        }.getOrDefault(false)

        fun trigger(context: Context): Boolean {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_TRIGGER)
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrDefault(false)
        }
    }
}
