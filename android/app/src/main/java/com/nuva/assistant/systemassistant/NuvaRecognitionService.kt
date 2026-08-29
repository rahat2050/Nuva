package com.nuva.assistant.systemassistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * RecognitionService required by Android's VoiceInteractionService contract.
 *
 * NUVA is not a speech-model vendor. This service therefore delegates one
 * user-started recognition session to an installed external recognizer instead
 * of pretending to implement STT or recursively selecting itself. Raw audio is
 * never exposed to NUVA's command backend.
 */
class NuvaRecognitionService : RecognitionService() {

    private var recognizer: SpeechRecognizer? = null
    private var activeCallback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        destroyRecognizer()

        val provider = externalRecognitionProvider()
        if (provider == null) {
            runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        val created = runCatching {
            SpeechRecognizer.createSpeechRecognizer(
                recognitionContext(callback),
                provider,
            )
        }.getOrElse {
            runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        activeCallback = callback
        recognizer = created
        created.setRecognitionListener(ForwardingRecognitionListener(callback))
        val request = recognizerIntent?.let(::Intent)
            ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        request.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        runCatching { created.startListening(request) }
            .onFailure {
                runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }
                destroyRecognizer()
            }
    }

    override fun onStopListening(listener: Callback?) {
        if (listener != null && listener !== activeCallback) return
        runCatching { recognizer?.stopListening() }
    }

    override fun onCancel(listener: Callback?) {
        if (listener != null && listener !== activeCallback) return
        destroyRecognizer()
    }

    override fun onDestroy() {
        destroyRecognizer()
        super.onDestroy()
    }

    private fun recognitionContext(callback: Callback): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
        return runCatching {
            createContext(
                ContextParams.Builder()
                    .setNextAttributionSource(callback.callingAttributionSource)
                    .build(),
            )
        }.getOrDefault(this)
    }

    private fun externalRecognitionProvider(): ComponentName? {
        val preferred = Settings.Secure.getString(
            contentResolver,
            "voice_recognition_service",
        )?.let(ComponentName::unflattenFromString)
        val available = packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0,
        ).mapNotNull { info ->
            info.serviceInfo?.let { RecognitionProviderId(it.packageName, it.name) }
        }
        val preferredId = preferred?.let { RecognitionProviderId(it.packageName, it.className) }
        return chooseExternalRecognitionProvider(available, packageName, preferredId)
            ?.let { ComponentName(it.packageName, it.className) }
    }

    private fun destroyRecognizer(cancelFirst: Boolean = true) {
        val current = recognizer
        recognizer = null
        activeCallback = null
        if (cancelFirst) runCatching { current?.cancel() }
        runCatching { current?.destroy() }
    }

    private inner class ForwardingRecognitionListener(
        private val callback: Callback,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (activeCallback === callback) {
                runCatching { callback.readyForSpeech(params ?: Bundle.EMPTY) }
            }
        }

        override fun onBeginningOfSpeech() {
            if (activeCallback === callback) runCatching { callback.beginningOfSpeech() }
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (activeCallback === callback) runCatching { callback.rmsChanged(rmsdB) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (activeCallback === callback) {
                runCatching { callback.bufferReceived(buffer ?: ByteArray(0)) }
            }
        }

        override fun onEndOfSpeech() {
            if (activeCallback === callback) runCatching { callback.endOfSpeech() }
        }

        override fun onError(error: Int) {
            if (activeCallback !== callback) return
            runCatching { callback.error(error) }
            destroyRecognizer(cancelFirst = false)
        }

        override fun onResults(results: Bundle?) {
            if (activeCallback !== callback) return
            runCatching { callback.results(results ?: Bundle.EMPTY) }
            destroyRecognizer(cancelFirst = false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (activeCallback === callback) {
                runCatching { callback.partialResults(partialResults ?: Bundle.EMPTY) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** Android-free provider identity so selection remains deterministic in JVM tests. */
internal data class RecognitionProviderId(
    val packageName: String,
    val className: String,
)

/** Pure selection rule kept separate for deterministic JVM testing. */
internal fun chooseExternalRecognitionProvider(
    available: List<RecognitionProviderId>,
    ownPackage: String,
    preferred: RecognitionProviderId? = null,
): RecognitionProviderId? {
    val external = available
        .filterNot { it.packageName == ownPackage }
        .distinct()
    return preferred
        ?.takeIf { it.packageName != ownPackage && external.contains(it) }
        ?: external.sortedWith(compareBy(RecognitionProviderId::packageName, RecognitionProviderId::className))
            .firstOrNull()
}
