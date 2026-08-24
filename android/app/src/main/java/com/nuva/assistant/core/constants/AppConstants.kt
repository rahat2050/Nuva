package com.nuva.assistant.core.constants

/**
 * Central constants — mirrors of the frozen backend contract, kept in ONE
 * place so PHRASE 2 code never hard-codes magic values.
 */
object AppConstants {

    /** Wake word — recognised (stripped) client-side before sending text. */
    const val WAKE_WORD_EN = "hey nuva"
    const val WAKE_WORD_BN = "নুভা"
    const val WAKE_WORD_BANGLISH = "nuva"

    /** Default production backend; overridable in Settings (emulator: 10.0.2.2). */
    const val DEFAULT_BASE_URL = "https://nuva-backend.vercel.app/"

    /** Supabase — URL + anon key are the only Supabase values the app may hold. */
    const val DEFAULT_SUPABASE_URL = "https://YOUR-PROJECT.supabase.co"
    const val DEFAULT_SUPABASE_ANON_KEY = ""

    /** Command pipeline limits (must match docs/commands.md). */
    const val MAX_TRANSCRIPT_CHARS = 1000
    const val MAX_SCREEN_CONTEXT_CHARS = 4000

    /** Latency budget targets (§ performance phase). */
    const val TARGET_SPEECH_TO_ACTION_MS = 2_500
    const val TARGET_ACTION_TO_EXECUTION_MS = 1_000

    /** Memory keys used by the sync layer (conventional keys from docs). */
    const val KEY_PREFERRED_LANGUAGE = "preferred_language"
    const val KEY_ASSISTANT_NAME = "assistant_name"
    const val KEY_FAVOURITE_APPS = "favourite_apps"
}
