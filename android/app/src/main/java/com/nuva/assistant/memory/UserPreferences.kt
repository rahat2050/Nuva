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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
    val signedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        val stored = prefs[Keys.ACCESS_TOKEN]
        if (stored == null || !stored.startsWith(SESSION_PREFIX)) {
            false
        } else {
            runCatching {
                sessionCipher.decrypt(stored.removePrefix(SESSION_PREFIX)).isNotBlank()
            }.getOrDefault(false)
        }
    }.flowOn(Dispatchers.IO)

    // --- Suspend snapshots -----------------------------------------------------

    /** Read the current backend endpoint without ever blocking the caller thread. */
    suspend fun currentBaseUrl(): String = baseUrl.first()

    /**
     * URL and anon key are read from one DataStore snapshot so a concurrent
     * Settings save cannot produce a mixed old/new Supabase configuration.
     */
    suspend fun currentSupabaseConnection(): SupabaseConnection =
        supabaseConnection(context.dataStore.data.first())

    // --- Writes ----------------------------------------------------------------

    suspend fun setBaseUrl(url: String): Boolean {
        val normalized = SecureEndpointPolicy.normalizeRequired(
            url,
            defaultWhenBlank = AppConstants.DEFAULT_BASE_URL,
        ) ?: return false
        context.dataStore.edit { prefs ->
            val previous = SecureEndpointPolicy.normalizeRequired(
                prefs[Keys.BASE_URL].orEmpty(),
                defaultWhenBlank = AppConstants.DEFAULT_BASE_URL,
            ) ?: AppConstants.DEFAULT_BASE_URL
            prefs[Keys.BASE_URL] = normalized
            if (previous != normalized) {
                prefs.remove(Keys.ACCESS_TOKEN)
                prefs.remove(Keys.REFRESH_TOKEN)
            }
        }
        return true
    }

    suspend fun setSupabase(url: String, anonKey: String): Boolean {
        val normalized = SecureEndpointPolicy.normalizeOptional(url) ?: return false
        val cleanKey = anonKey.trim()
        context.dataStore.edit { prefs ->
            val previous = supabaseConnection(prefs)
            val next = SupabaseConnection(normalized, cleanKey)
            prefs[Keys.SUPABASE_URL] = normalized
            prefs[Keys.SUPABASE_ANON_KEY] = cleanKey
            if (previous != next) {
                prefs.remove(Keys.ACCESS_TOKEN)
                prefs.remove(Keys.REFRESH_TOKEN)
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

    /**
     * Saves a session only if it still belongs to the Supabase configuration
     * that initiated sign-in. This closes the race where Settings changed the
     * endpoint while an old password-grant request was still in flight.
     */
    suspend fun saveSession(
        accessToken: String,
        refreshToken: String?,
        expectedConnection: SupabaseConnection,
    ): Boolean = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "access token cannot be blank" }
        val encryptedAccess = SESSION_PREFIX + sessionCipher.encrypt(accessToken)
        val encryptedRefresh = refreshToken?.takeIf { it.isNotBlank() }
            ?.let { SESSION_PREFIX + sessionCipher.encrypt(it) }
        var saved = false
        context.dataStore.edit { prefs ->
            if (supabaseConnection(prefs) != expectedConnection) return@edit
            prefs[Keys.ACCESS_TOKEN] = encryptedAccess
            if (encryptedRefresh == null) prefs.remove(Keys.REFRESH_TOKEN)
            else prefs[Keys.REFRESH_TOKEN] = encryptedRefresh
            saved = true
        }
        saved
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.ACCESS_TOKEN)
            it.remove(Keys.REFRESH_TOKEN)
        }
    }

    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        readSessionToken(Keys.ACCESS_TOKEN)
    }

    suspend fun refreshToken(): String? = withContext(Dispatchers.IO) {
        readSessionToken(Keys.REFRESH_TOKEN)
    }

    private suspend fun readSessionToken(key: Preferences.Key<String>): String? {
        val stored = context.dataStore.data.first()[key] ?: return null
        if (!stored.startsWith(SESSION_PREFIX)) {
            // Refuse legacy/plaintext token storage. The compare-before-clear
            // prevents this stale read from deleting a newly saved session.
            clearSessionIfUnchanged(key, stored)
            return null
        }
        val decrypted = try {
            sessionCipher.decrypt(stored.removePrefix(SESSION_PREFIX))
        } catch (_: Exception) {
            clearSessionIfUnchanged(key, stored)
            return null
        }
        if (decrypted.isBlank()) {
            clearSessionIfUnchanged(key, stored)
            return null
        }
        return decrypted
    }

    private suspend fun clearSessionIfUnchanged(
        key: Preferences.Key<String>,
        expectedStoredValue: String,
    ) {
        context.dataStore.edit { prefs ->
            if (prefs[key] != expectedStoredValue) return@edit
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
        }
    }

    data class SupabaseConnection(
        val baseUrl: String,
        val anonKey: String,
    )

    private fun supabaseConnection(prefs: Preferences): SupabaseConnection =
        SupabaseConnection(
            baseUrl = SecureEndpointPolicy.normalizeOptional(
                prefs[Keys.SUPABASE_URL] ?: AppConstants.DEFAULT_SUPABASE_URL,
            ) ?: "",
            anonKey = prefs[Keys.SUPABASE_ANON_KEY] ?: AppConstants.DEFAULT_SUPABASE_ANON_KEY,
        )

    companion object {
        private const val SESSION_PREFIX = "enc-v1:"
        private const val SESSION_KEY_ALIAS = "nuva-supabase-session-v1"
    }
}
