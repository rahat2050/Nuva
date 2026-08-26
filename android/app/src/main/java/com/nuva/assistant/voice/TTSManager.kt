package com.nuva.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voice output (roadmap step 3): TextToSpeech with language matching the
 * reply — Bangla speech for bn/banglish replies when a Bangla voice is
 * installed, English otherwise. Degrades gracefully (no TTS → silent, the text
 * is still on screen).
 */
class TTSManager(context: Context) {

    private var ready = false
    private var tts: TextToSpeech? = null
    private var banglaAvailable = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                banglaAvailable = tryLanguage(Locale("bn", "BD"))
            }
        }
    }

    private fun tryLanguage(locale: Locale): Boolean = runCatching {
        tts?.setLanguage(locale)?.let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED } ?: false
    }.getOrDefault(false)

    /** True when the text itself is Bangla script — speaks it with the bn voice. */
    private fun isBanglaScript(text: String): Boolean =
        text.count { it.code in 0x0980..0x09FF } * 2 > text.length

    /** Speaks in the reply's language. Falls back to English when bn missing. */
    fun speak(text: String, language: String = "banglish") {
        if (!ready || text.isBlank()) return
        val engine = tts ?: return
        val locale = when {
            language == "en" && !isBanglaScript(text) -> Locale.US
            language == "bn" || isBanglaScript(text) -> if (banglaAvailable) Locale("bn", "BD") else Locale.US
            else -> Locale.US // banglish replies are written in Latin script
        }
        runCatching {
            engine.language = locale
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nuva-${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
    }
}
