package com.nuva.assistant.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuva.assistant.core.constants.AppConstants
import com.nuva.assistant.core.security.AndroidTokenCipher
import com.nuva.assistant.core.security.SecureEndpointPolicy
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

    private val sessionCipher by lazy { AndroidTokenCipher(SESSION_KEY_ALIAS) }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val SUPABASE_URL = stringPreferencesKey("supabase_url")
        val SUPABASE_ANON_KEY = stringPreferencesKey("supabase_anon_key")
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val CONFIRMATION_ALWAYS = booleanPreferencesKey("confirmation_always")
        val DIRECT_CALL = booleanPreferencesKey("direct_call")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACCESS_TOKEN = stringPreferencesKey("supabase_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("supabase_refresh_token")
    }

    // --- Flows for the UI ------------------------------------------------------

    val baseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        SecureEndpointPolicy.normalizeRequired(
            prefs[Keys.BASE_URL].orEmpty(),
            defaultWhenBlank = AppConstants.DEFAULT_BASE_URL,
        ) ?: AppConstants.DEFAULT_BASE_URL
    }
    val language: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "auto" }
    val voiceEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOICE_ENABLED] ?: true }
    val confirmationAlways: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONFIRMATION_ALWAYS] ?: false }
    val directCall: Flow<Boolean> = context.dataStore.data.map { it[Keys.DIRECT_CALL] ?: false }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_WORD_ENABLED] ?: false }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val supabaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        SecureEndpointPolicy.normalizeOptional(
            prefs[Keys.SUPABASE_URL] ?: AppConstants.DEFAULT_SUPABASE_URL,
        ) ?: ""
    }
    val supabaseAnonKey: Flow<String> =
        context.dataStore.data.map { it[Keys.SUPABASE_ANON_KEY] ?: AppConstants.DEFAULT_SUPABASE_ANON_KEY }
    val signedIn: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ACCESS_TOKEN]?.startsWith(SESSION_PREFIX) == true
    }

    // --- Blocking accessors (call from IO dispatchers only) --------------------

    fun baseUrlBlocking(): String = runBlocking { baseUrl.first() }
    fun supabaseUrlBlocking(): String = runBlocking { supabaseUrl.first() }
    fun supabaseAnonKeyBlocking(): String = runBlocking { supabaseAnonKey.first() }
    fun languageBlocking(): String = runBlocking { language.first() }
    fun confirmationAlwaysBlocking(): Boolean = runBlocking { confirmationAlways.first() }
    fun directCallBlocking(): Boolean = runBlocking { directCall.first() }
    fun voiceEnabledBlocking(): Boolean = runBlocking { voiceEnabled.first() }
    fun wakeWordEnabledBlocking(): Boolean = runBlocking { wakeWordEnabled.first() }
    fun onboardingDoneBlocking(): Boolean = runBlocking { onboardingDone.first() }

    // --- Writes ----------------------------------------------------------------

    suspend fun setBaseUrl(url: String): Boolean {
        val normalized = SecureEndpointPolicy.normalizeRequired(
            url,
            defaultWhenBlank = AppConstants.DEFAULT_BASE_URL,
        ) ?: return false
        val endpointChanged = baseUrl.first() != normalized
        context.dataStore.edit {
            it[Keys.BASE_URL] = normalized
            if (endpointChanged) {
                it.remove(Keys.ACCESS_TOKEN)
                it.remove(Keys.REFRESH_TOKEN)
            }
        }
        return true
    }

    suspend fun setSupabase(url: String, anonKey: String): Boolean {
        val normalized = SecureEndpointPolicy.normalizeOptional(url) ?: return false
        val cleanKey = anonKey.trim()
        val endpointChanged = supabaseUrl.first() != normalized || supabaseAnonKey.first() != cleanKey
        context.dataStore.edit {
            it[Keys.SUPABASE_URL] = normalized
            it[Keys.SUPABASE_ANON_KEY] = cleanKey
            if (endpointChanged) {
                it.remove(Keys.ACCESS_TOKEN)
                it.remove(Keys.REFRESH_TOKEN)
            }
        }
        return true
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

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }

    // --- Auth session (Supabase JWT for the backend) ---------------------------

    suspend fun saveSession(accessToken: String, refreshToken: String?) {
        require(accessToken.isNotBlank()) { "access token cannot be blank" }
        val encryptedAccess = SESSION_PREFIX + sessionCipher.encrypt(accessToken)
        val encryptedRefresh = refreshToken?.takeIf { it.isNotBlank() }
            ?.let { SESSION_PREFIX + sessionCipher.encrypt(it) }
        context.dataStore.edit {
            it[Keys.ACCESS_TOKEN] = encryptedAccess
            if (encryptedRefresh == null) it.remove(Keys.REFRESH_TOKEN)
            else it[Keys.REFRESH_TOKEN] = encryptedRefresh
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.ACCESS_TOKEN)
            it.remove(Keys.REFRESH_TOKEN)
        }
    }

    suspend fun accessToken(): String? = readSessionToken(Keys.ACCESS_TOKEN)

    suspend fun refreshToken(): String? = readSessionToken(Keys.REFRESH_TOKEN)

    private suspend fun readSessionToken(key: Preferences.Key<String>): String? {
        val stored = context.dataStore.data.first()[key] ?: return null
        if (!stored.startsWith(SESSION_PREFIX)) {
            // Refuse legacy/plaintext token storage. The user signs in again and
            // receives a Keystore-encrypted session instead.
            clearSession()
            return null
        }
        return runCatching { sessionCipher.decrypt(stored.removePrefix(SESSION_PREFIX)) }
            .getOrElse {
                clearSession()
                null
            }
    }

    fun accessTokenBlocking(): String? = runBlocking { accessToken() }

    companion object {
        private const val SESSION_PREFIX = "enc-v1:"
        private const val SESSION_KEY_ALIAS = "nuva-supabase-session-v1"
    }
}
