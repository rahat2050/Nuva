package com.nuva.assistant.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuva.assistant.core.constants.AppConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nuva_settings")

/**
 * User preferences (DataStore). Language / confirmation mode / voice mirror
 * the `settings` table constraints — notably there is NO way to disable
 * confirmations for risky actions (mode is always | risky_only).
 */
class UserPreferences(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val SUPABASE_URL = stringPreferencesKey("supabase_url")
        val SUPABASE_ANON_KEY = stringPreferencesKey("supabase_anon_key")
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val CONFIRMATION_ALWAYS = booleanPreferencesKey("confirmation_always")
        val DIRECT_CALL = booleanPreferencesKey("direct_call")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val ACCESS_TOKEN = stringPreferencesKey("supabase_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("supabase_refresh_token")
    }

    // --- Flows for the UI ------------------------------------------------------

    val baseUrl: Flow<String> = context.dataStore.data.map { it[Keys.BASE_URL] ?: AppConstants.DEFAULT_BASE_URL }
    val language: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "auto" }
    val voiceEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOICE_ENABLED] ?: true }
    val confirmationAlways: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONFIRMATION_ALWAYS] ?: false }
    val directCall: Flow<Boolean> = context.dataStore.data.map { it[Keys.DIRECT_CALL] ?: false }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_WORD_ENABLED] ?: false }
    val supabaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.SUPABASE_URL] ?: AppConstants.DEFAULT_SUPABASE_URL }
    val supabaseAnonKey: Flow<String> =
        context.dataStore.data.map { it[Keys.SUPABASE_ANON_KEY] ?: AppConstants.DEFAULT_SUPABASE_ANON_KEY }
    val signedIn: Flow<Boolean> = context.dataStore.data.map { !it[Keys.ACCESS_TOKEN].isNullOrBlank() }

    // --- Blocking accessors (call from IO dispatchers only) --------------------

    fun baseUrlBlocking(): String = runBlocking { baseUrl.first() }
    fun supabaseUrlBlocking(): String = runBlocking { supabaseUrl.first() }
    fun supabaseAnonKeyBlocking(): String = runBlocking { supabaseAnonKey.first() }
    fun languageBlocking(): String = runBlocking { language.first() }
    fun confirmationAlwaysBlocking(): Boolean = runBlocking { confirmationAlways.first() }
    fun directCallBlocking(): Boolean = runBlocking { directCall.first() }
    fun voiceEnabledBlocking(): Boolean = runBlocking { voiceEnabled.first() }
    fun wakeWordEnabledBlocking(): Boolean = runBlocking { wakeWordEnabled.first() }

    // --- Writes ----------------------------------------------------------------

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.BASE_URL] = url.trim().ifEmpty { AppConstants.DEFAULT_BASE_URL } }
    }

    suspend fun setSupabase(url: String, anonKey: String) {
        context.dataStore.edit {
            it[Keys.SUPABASE_URL] = url.trim()
            it[Keys.SUPABASE_ANON_KEY] = anonKey.trim()
        }
    }

    suspend fun setLanguage(language: String) {
        val allowed = listOf("auto", "bn", "en", "banglish")
        context.dataStore.edit { it[Keys.LANGUAGE] = if (language in allowed) language else "auto" }
    }

    suspend fun setVoiceEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.VOICE_ENABLED] = enabled }

    /** NOTE: there is no setter that could ever disable risky confirmations. */
    suspend fun setConfirmationAlways(always: Boolean) = context.dataStore.edit { it[Keys.CONFIRMATION_ALWAYS] = always }

    suspend fun setDirectCall(direct: Boolean) = context.dataStore.edit { it[Keys.DIRECT_CALL] = direct }

    suspend fun setWakeWordEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }

    // --- Auth session (Supabase JWT for the backend) ---------------------------

    suspend fun saveSession(accessToken: String, refreshToken: String?) {
        context.dataStore.edit {
            it[Keys.ACCESS_TOKEN] = accessToken
            refreshToken?.let { token -> it[Keys.REFRESH_TOKEN] = token }
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.ACCESS_TOKEN)
            it.remove(Keys.REFRESH_TOKEN)
        }
    }

    suspend fun accessToken(): String? = context.dataStore.data.first()[Keys.ACCESS_TOKEN]
    suspend fun refreshToken(): String? = context.dataStore.data.first()[Keys.REFRESH_TOKEN]
    fun accessTokenBlocking(): String? = runBlocking { accessToken() }
}
