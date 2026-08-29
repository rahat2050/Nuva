package com.nuva.assistant.voice

import com.nuva.assistant.command.CommandDecision
import com.nuva.assistant.command.CommandExecutor
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.core.security.SensitiveAppPolicy
import com.nuva.assistant.service.NuvaForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Voice-first orchestration (§ golden rule): mic → transcript → executor →
 * the reply is BOTH spoken (TTS) and surfaced to the UI.
 *
 * The controller never executes anything itself — it only feeds the
 * CommandExecutor and speaks its outcomes.
 */
class VoiceController(
    private val onDecision: (CommandDecision) -> Unit = {},
) {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Transcribed(val text: String) : State
        data object Processing : State
        data class AwaitingConfirmation(val pendingId: Long, val decision: CommandDecision) : State
        data class AwaitingContactChoice(
            val pendingId: Long,
            val decision: CommandDecision,
            val matches: List<com.nuva.assistant.contacts.ContactResolver.ContactMatch>,
        ) : State

        data class Done(val speech: String, val screenText: String? = null) : State

        /** [fromVoice] false ⇒ recognition failed and the typed fallback is offered. */
        data class Failed(val speech: String, val fromVoice: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: Flow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizerController? = null
    private var recognitionJob: Job? = null
    private val tts = TTSManager(NuvaContainer.appContext)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /** Hard ceiling for one listening session (stuck-recovery). */
        const val LISTEN_TIMEOUT_MS = 15_000L
    }

    fun startListening() {
        if (_state.value is State.Listening) return
        val context = NuvaContainer.appContext
        if (!NuvaPermissions.hasRecordAudio(context)) {
            val speech = "Microphone permission lagbe."
            _state.value = State.Failed(speech)
            speakIfEnabled(speech)
            return
        }

        recognitionJob?.cancel()
        _state.value = State.Listening
        NuvaForegroundService.start(context)
        recognizer = SpeechRecognizerController(context)

        recognitionJob = mainScope.launch {
            val language = NuvaContainer.preferences.language.first()
            try {
                // v1.6 hardening: a recognizer that never reports back (OEM
                // quirks, engine crash) must not leave NUVA "listening"
                // forever — timeout recovers into the typed-fallback state.
                withTimeout(LISTEN_TIMEOUT_MS) {
                recognizer?.listen(language)?.collect { event ->
                    when (event) {
                        is SpeechRecognizerController.VoiceEvent.ListeningStarted -> Unit
                        is SpeechRecognizerController.VoiceEvent.Partial ->
                            _state.value = State.Transcribed(safeTranscript(event.text))

                        is SpeechRecognizerController.VoiceEvent.Final -> {
                            _state.value = State.Transcribed(safeTranscript(event.text))
                            submit(event.text)
                        }

                        is SpeechRecognizerController.VoiceEvent.Error -> {
                            // Recognition failed → typed fallback is offered in the UI.
                            _state.value = State.Failed(event.speech, fromVoice = true)
                            speakIfEnabled(event.speech)
                        }
                    }
                }
                }
            } catch (err: kotlinx.coroutines.TimeoutCancellationException) {
                _state.value = State.Failed("Kichu shunlam na — abar bolen ba likhe din.", fromVoice = true)
                speakIfEnabled("Kichu shunlam na.")
            } finally {
                recognizer = null
                NuvaForegroundService.stop(context)
            }
        }
    }

    fun stopListening() {
        recognitionJob?.cancel()
        recognitionJob = null
        recognizer = null // flow's awaitClose destroys the recognizer
        NuvaForegroundService.stop(NuvaContainer.appContext)
        if (_state.value is State.Listening || _state.value is State.Transcribed) {
            _state.value = State.Idle
        }
    }

    /** Typed/text input uses the same pipeline as voice. */
    fun submit(text: String) {
        _state.value = State.Processing
        mainScope.launch {
            when (val step = NuvaContainer.commandExecutor.process(text)) {
                is CommandExecutor.Step.Decision -> {
                    onDecision(step.decision)
                    _state.value = State.Idle
                }

                is CommandExecutor.Step.AwaitingConfirmation -> {
                    onDecision(step.decision)
                    _state.value = State.AwaitingConfirmation(step.pendingId, step.decision)
                    speakIfEnabled(step.decision.speech.ifBlank { "Korbo? Nishchit korun." })
                }

                is CommandExecutor.Step.AwaitingContactChoice -> {
                    _state.value = State.AwaitingContactChoice(step.pendingId, step.decision, step.matches)
                    val name = (step.decision.action as? com.nuva.assistant.command.NuvaAction.SendMessage)?.contact
                        ?: (step.decision.action as? com.nuva.assistant.command.NuvaAction.CallContact)?.contact
                        ?: (step.decision.action as? com.nuva.assistant.command.NuvaAction.OpenChat)?.contact
                        ?: ""
                    val namePart = if (name.isNullOrBlank()) "" else " $name নামের"
                    speakIfEnabled("আমি$namePart একাধিক contact পেয়েছি। কোনজন?")
                }

                is CommandExecutor.Step.Executing -> _state.value = State.Processing

                is CommandExecutor.Step.Done -> {
                    _state.value = State.Done(step.speech, step.screenText)
                    speakIfEnabled(step.speech)
                }

                is CommandExecutor.Step.Failed -> {
                    _state.value = State.Failed(step.speech)
                    speakIfEnabled(step.speech)
                }
            }
        }
    }

    fun confirm(pendingId: Long) {
        mainScope.launch {
            when (val step = NuvaContainer.commandExecutor.confirm(pendingId)) {
                is CommandExecutor.Step.Done -> {
                    _state.value = State.Done(step.speech, step.screenText)
                    speakIfEnabled(step.speech)
                }

                is CommandExecutor.Step.Failed -> {
                    _state.value = State.Failed(step.speech)
                    speakIfEnabled(step.speech)
                }

                else -> _state.value = State.Idle
            }
        }
    }

    /** The user picked one contact out of several matches → confirm again with it. */
    fun chooseContact(pendingId: Long, match: com.nuva.assistant.contacts.ContactResolver.ContactMatch) {
        mainScope.launch {
            when (val step = NuvaContainer.commandExecutor.chooseContact(pendingId, match)) {
                is CommandExecutor.Step.AwaitingConfirmation -> {
                    _state.value = State.AwaitingConfirmation(step.pendingId, step.decision)
                    speakIfEnabled("${match.displayName} — nishchit korun?")
                }

                is CommandExecutor.Step.Done -> {
                    _state.value = State.Done(step.speech, step.screenText)
                    speakIfEnabled(step.speech)
                }

                is CommandExecutor.Step.Failed -> {
                    _state.value = State.Failed(step.speech)
                    speakIfEnabled(step.speech)
                }

                else -> _state.value = State.Idle
            }
        }
    }

    fun reject(pendingId: Long) {
        mainScope.launch {
            val step = NuvaContainer.commandExecutor.reject(pendingId)
            if (step is CommandExecutor.Step.Done) {
                _state.value = State.Done(step.speech, step.screenText)
                speakIfEnabled(step.speech)
            } else {
                _state.value = State.Idle
            }
        }
    }

    fun speakIfEnabled(text: String) {
        if (text.isBlank()) return
        mainScope.launch {
            if (!NuvaContainer.preferences.voiceEnabled.first()) return@launch
            val language = NuvaContainer.preferences.language.first()
            tts.speak(text, if (language == "auto") "banglish" else language)
        }
    }

    private fun safeTranscript(text: String): String =
        if (SensitiveAppPolicy.mentionsCredentials(text)) {
            "Sensitive content hidden"
        } else {
            SensitiveAppPolicy.redactCodes(text)
        }

    fun destroy() {
        recognitionJob?.cancel()
        recognitionJob = null
        NuvaForegroundService.stop(NuvaContainer.appContext)
        tts.shutdown()
        mainScope.cancel()
    }
}
