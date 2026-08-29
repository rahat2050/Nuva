package com.nuva.assistant.homeassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuva.assistant.core.security.SecureEndpointPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local Home Assistant config. Token is AES-GCM encrypted with an Android Keystore key. */
class HomeAssistantConfigStore(private val context: Context) {
    data class Config(val baseUrl: String, val token: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun save(baseUrl: String, newToken: String?): Result<Unit> = runCatching {
        val normalized = normalizeAndValidateUrl(baseUrl)
            ?: error("Home Assistant URL must be a valid HTTPS origin")
        val previousUrl = prefs.getString(KEY_URL, null)?.let(::normalizeAndValidateUrl)
        val suppliedToken = newToken?.trim()?.takeIf { it.isNotBlank() }
        val encryptedToken = when {
            suppliedToken != null -> encrypt(suppliedToken)
            previousUrl != normalized -> error("A new token is required when the Home Assistant URL changes")
            readToken().isNullOrBlank() -> error("Long-lived access token is required")
            else -> null // same endpoint: keep the existing encrypted token
        }
        prefs.edit()
            .putString(KEY_URL, normalized)
            .apply {
                encryptedToken?.let { putString(KEY_TOKEN, it) }
            }
            .apply()
    }

    @Synchronized
    fun config(): Config? {
        val url = prefs.getString(KEY_URL, null)?.let(::normalizeAndValidateUrl) ?: return null
        val token = readToken()?.takeIf { it.isNotBlank() } ?: return null
        return Config(url, token)
    }

    fun isConfigured(): Boolean = config() != null

    @Synchronized
    fun savedBaseUrl(): String = prefs.getString(KEY_URL, "").orEmpty()

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_URL).remove(KEY_TOKEN).apply()
    }

    private fun readToken(): String? = prefs.getString(KEY_TOKEN, null)?.let { encrypted ->
        runCatching { decrypt(encrypted) }.getOrNull()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_BYTES)
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        fun normalizeAndValidateUrl(raw: String): String? {
            val value = raw.trim()
            // Home Assistant setup intentionally requires the scheme to be
            // visible to the user; the shared policy handles every other URI
            // edge case (credentials/query/fragment/port/path traversal).
            if (!value.startsWith("https://", ignoreCase = true)) return null
            return SecureEndpointPolicy.normalizeRequired(value)?.trimEnd('/')
        }

        private const val PREFS = "nuva_home_assistant_secure"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token_ciphertext"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nuva-home-assistant-token-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
