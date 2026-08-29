package com.nuva.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Voice input (roadmap step 3): Android SpeechRecognizer with Bangla (bn-BD)
 * and English (en-US). Partial results stream to the UI as they arrive —
 * perceived latency drops even before the final transcript.
 *
 * Error contract (§ error handling): every failure maps to a user-sayable
 * sentence, never silence.
 */
class SpeechRecognizerController(private val context: Context) {

    sealed interface VoiceEvent {
        data class Partial(val text: String) : VoiceEvent
        data class Final(val text: String) : VoiceEvent
        data class Error(val speech: String) : VoiceEvent
        data object ListeningStarted : VoiceEvent
    }

    private fun languageTag(languageHint: String): String = when (languageHint) {
        "bn" -> "bn-BD"
        "en" -> "en-US"
        else -> "bn-BD" // Bangla-first product; the model still handles English words
    }

    fun listen(languageHint: String = "auto"): Flow<VoiceEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(VoiceEvent.Error("Ei phone e speech recognition nai."))
            close()
            return@callbackFlow
        }

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrElse {
            trySend(VoiceEvent.Error("Speech recognizer start korte parini. Voice input settings check korun."))
            close()
            return@callbackFlow
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag(languageHint))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.ListeningStarted)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(VoiceEvent.Partial(text))
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(VoiceEvent.Final(text))
                else trySend(VoiceEvent.Error("Kichu shunlam na, abar bolen."))
                close()
            }

            override fun onError(error: Int) {
                trySend(VoiceEvent.Error(errorSpeech(error)))
                close()
            }
        })

        val started = runCatching { recognizer.startListening(intent) }.isSuccess
        if (!started) {
            recognizer.destroy()
            trySend(VoiceEvent.Error("Speech recognizer start korte parini. Voice input settings check korun."))
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { recognizer.cancel() }
            recognizer.destroy()
        }
    }

    companion object {
        fun errorSpeech(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Kichu shunlam na, abar bolen."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Onek somoy dhore kichu bolen nai, abar try korun."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission lagbe."
            SpeechRecognizer.ERROR_AUDIO -> "Audio somossa hocche, abar bolen."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ektu age somoy din, abar bolen."
            SpeechRecognizer.ERROR_NETWORK -> "Internet e pouchate parchi na."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network slow, abar bolen."
            else -> "Shunte somossa hocche, abar bolen."
        }
    }
}
