package com.nuva.assistant.supabase

import com.nuva.assistant.core.constants.AppConstants
import com.nuva.assistant.core.security.SecureEndpointPolicy
import com.nuva.assistant.memory.UserPreferences
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
 * explicitly to authenticated backend requests.
 */
class SupabaseRepository(
    private val baseUrlProvider: suspend () -> String,
    private val supabaseConnectionProvider: suspend () -> UserPreferences.SupabaseConnection,
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

    sealed interface SignInResult {
        data class Success(
            val session: Session,
            val connection: UserPreferences.SupabaseConnection,
        ) : SignInResult
        data class Failure(val reason: String) : SignInResult
    }

    suspend fun signIn(email: String, password: String): SignInResult = withContext(Dispatchers.IO) {
        val connection = supabaseConnectionProvider()
        val supabaseUrl = SecureEndpointPolicy.normalizeRequired(connection.baseUrl)
            ?.trimEnd('/')
            ?: return@withContext SignInResult.Failure("Supabase URL অবশ্যই valid HTTPS endpoint হতে হবে।")
        val anonKey = connection.anonKey
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

        runCatchingCancellable {
            client.newCall(request).awaitResponse().use { response ->
                if (response.isSuccessful) {
                    val text = response.readBoundedBody()
                    val session = json.decodeFromString(Session.serializer(), text)
                    if (session.accessToken.isBlank()) {
                        SignInResult.Failure("Supabase response-e valid session token chilo na.")
                    } else {
                        SignInResult.Success(session, connection)
                    }
                } else {
                    // Do not surface arbitrary identity-provider response text;
                    // status is enough and cannot inject credentials into UI/TTS.
                    SignInResult.Failure("Sign in korte parini (${response.code}). Email/password check korun.")
                }
            }
        }.getOrElse { SignInResult.Failure("Network somossa; abar try korun.") }
    }

    /** Wired by NuvaContainer so auth tokens come from UserPreferences. */
    var tokenProvider: (suspend () -> String?)? = null

    suspend fun accessToken(): String? = tokenProvider?.invoke()

    // --- Backend endpoints that need the JWT -----------------------------------

    private suspend fun url(path: String): String {
        val base = SecureEndpointPolicy.normalizeRequired(
            baseUrlProvider(),
            defaultWhenBlank = AppConstants.DEFAULT_BASE_URL,
        ) ?: AppConstants.DEFAULT_BASE_URL
        return base + path.trimStart('/')
    }

    private suspend fun Request.Builder.withAuth(): Request.Builder {
        val token = runCatchingCancellable { accessToken() }.getOrNull()
        return if (token.isNullOrBlank()) this else header("Authorization", "Bearer $token")
    }

    private suspend fun postJson(path: String, payload: String): Boolean = runCatchingCancellable {
        val request = Request.Builder()
            .url(url(path))
            .header("Content-Type", "application/json")
            .withAuth()
            .post(payload.toRequestBody())
            .build()
        client.newCall(request).awaitResponse().use { it.isSuccessful }
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
        runCatchingCancellable {
            val request = Request.Builder()
                .url(url("api/memory?key=$encoded"))
                .withAuth()
                .delete()
                .build()
            client.newCall(request).awaitResponse().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    @Serializable
    data class MemoryRowRemote(val key: String, val value: String)

    suspend fun listMemory(): List<MemoryRowRemote> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val request = Request.Builder().url(url("api/memory")).withAuth().get().build()
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val text = response.readBoundedBody()
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

    private fun Response.readBoundedBody(): String {
        val source = body?.source() ?: return ""
        if (source.request(MAX_RESPONSE_BYTES + 1L)) {
            throw IOException("response exceeds limit")
        }
        return source.buffer.readUtf8()
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) continuation.resume(response) else response.close()
                    }
                },
            )
        }

    private inline fun <T> runCatchingCancellable(block: () -> T): kotlin.Result<T> = try {
        kotlin.Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        kotlin.Result.failure(error)
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

    private companion object {
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}
