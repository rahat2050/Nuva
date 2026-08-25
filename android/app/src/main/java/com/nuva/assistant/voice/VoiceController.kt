package com.nuva.assistant.voice

import com.nuva.assistant.command.CommandDecision
import com.nuva.assistant.command.CommandExecutor
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.service.NuvaForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        data class Done(val speech: String, val screenText: String? = null) : State
        data class Failed(val speech: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizerController? = null
    private var recognitionJob: Job? = null
    private val tts = TTSManager(NuvaContainer.appContext)
    private val mainScope = CoroutineScope(Dispatchers.Main)

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
        val language = NuvaContainer.preferences.languageBlocking()

        recognitionJob = mainScope.launch {
            try {
                recognizer?.listen(language)?.collect { event ->
                    when (event) {
                        is SpeechRecognizerController.VoiceEvent.ListeningStarted -> Unit
                        is SpeechRecognizerController.VoiceEvent.Partial ->
                            _state.value = State.Transcribed(event.text)

                        is SpeechRecognizerController.VoiceEvent.Final -> {
                            _state.value = State.Transcribed(event.text)
                            submit(event.text)
                        }

                        is SpeechRecognizerController.VoiceEvent.Error -> {
                            _state.value = State.Failed(event.speech)
                            speakIfEnabled(event.speech)
                        }
                    }
                }
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
        if (_state.value is State.Listening) _state.value = State.Idle
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
                    speakIfEnabled(step.decision.speech.ifBlank { "Korbo?" })
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
                    _state.value = State.Done(step.speech)
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
                _state.value = State.Done(step.speech)
                speakIfEnabled(step.speech)
            } else {
                _state.value = State.Idle
            }
        }
    }

    fun speakIfEnabled(text: String) {
        if (text.isBlank()) return
        if (!NuvaContainer.preferences.voiceEnabledBlocking()) return
        val language = NuvaContainer.preferences.languageBlocking()
        tts.speak(text, if (language == "auto") "banglish" else language)
    }

    fun destroy() {
        recognitionJob?.cancel()
        NuvaForegroundService.stop(NuvaContainer.appContext)
        tts.shutdown()
    }
}
