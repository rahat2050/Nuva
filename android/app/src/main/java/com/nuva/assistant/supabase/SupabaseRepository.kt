package com.nuva.assistant.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Supabase access — REST ONLY, via OkHttp.
 *
 * The app holds exactly two non-secret Supabase values (URL + anon key) and the
 * user's own JWT after sign-in. The service-role key NEVER exists here (§12),
 * and RLS is the real gate for everything user-scoped.
 *
 * Auth: GoTrue password grant (/auth/v1/token). The returned JWT is attached
 * to every backend request by AIRepository's interceptor.
 */
class SupabaseRepository(
    private val baseUrlProvider: () -> String,
    private val supabaseUrlProvider: () -> String,
    private val anonKeyProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class Session(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String = "",
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long? = null,
        val user: SupabaseUser? = null,
    )

    @Serializable
    data class SupabaseUser(
        val id: String? = null,
        val email: String? = null,
    )

    @Serializable
    data class GoTrueError(
        val error: String? = null,
        @kotlinx.serialization.SerialName("error_description") val errorDescription: String? = null,
    )

    sealed interface SignInResult {
        data class Success(val session: Session) : SignInResult
        data class Failure(val reason: String) : SignInResult
    }

    suspend fun signIn(email: String, password: String): SignInResult = withContext(Dispatchers.IO) {
        val supabaseUrl = supabaseUrlProvider().trimEnd('/')
        val anonKey = anonKeyProvider()
        if (anonKey.isBlank()) return@withContext SignInResult.Failure("Supabase anon key set kora hoy nai (Settings).")

        val body = FormBody.Builder()
            .add("grant_type", "password")
            .add("email", email)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url("$supabaseUrl/auth/v1/token")
            .header("apikey", anonKey)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    SignInResult.Success(json.decodeFromString(Session.serializer(), text))
                } else {
                    val parsed = runCatching { json.decodeFromString(GoTrueError.serializer(), text) }.getOrNull()
                    SignInResult.Failure(
                        parsed?.errorDescription ?: parsed?.error ?: "Sign in korte parini (${response.code}).",
                    )
                }
            }
        }.getOrElse { SignInResult.Failure("Network somossa: ${it.message ?: "unknown"}") }
    }

    /** Wired by NuvaContainer so auth tokens come from UserPreferences. */
    var tokenProvider: (suspend () -> String?)? = null

    suspend fun accessToken(): String? = tokenProvider?.invoke()

    // --- Backend endpoints that need the JWT -----------------------------------

    private fun url(path: String): String {
        val base = baseUrlProvider().let { if (it.endsWith("/")) it else "$it/" }
        return base + path
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        val token = runCatching { kotlinx.coroutines.runBlocking { accessToken() } }.getOrNull()
        return if (token.isNullOrBlank()) this else header("Authorization", "Bearer $token")
    }

    private fun postJson(path: String, payload: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url(path))
            .header("Content-Type", "application/json")
            .withAuth()
            .post(payload.toRequestBody())
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    suspend fun reportExecution(commandId: String, status: String, error: String?): Boolean = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            ReportPayload.serializer(),
            ReportPayload(commandId = commandId, status = status, error = error),
        )
        postJson("api/commands", payload)
    }

    suspend fun saveMemory(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            MemoryPayload.serializer(),
            MemoryPayload(key = key, value = value),
        )
        postJson("api/memory", payload)
    }

    suspend fun forgetMemory(key: String): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(key, "UTF-8")
        runCatching {
            val request = Request.Builder()
                .url(url("api/memory?key=$encoded"))
                .withAuth()
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    @Serializable
    data class MemoryRowRemote(val key: String, val value: String)

    suspend fun listMemory(): List<MemoryRowRemote> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("api/memory")).withAuth().get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val text = response.body?.string().orEmpty()
                json.decodeFromString(MemoryEnvelope.serializer(), text).memories
            }
        }.getOrDefault(emptyList())
    }

    suspend fun registerDevice(deviceName: String, androidVersion: String?): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("device_name", deviceName)
            androidVersion?.let { put("android_version", it) }
        }.toString()
        postJson("api/devices", payload)
    }

    @Serializable
    private data class ReportPayload(
        @kotlinx.serialization.SerialName("command_id") val commandId: String,
        val status: String,
        val error: String? = null,
    )

    @Serializable
    private data class MemoryPayload(val key: String, val value: String)

    @Serializable
    private data class MemoryEnvelope(
        val ok: Boolean = false,
        val memories: List<MemoryRowRemote> = emptyList(),
    )
}
