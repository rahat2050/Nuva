package com.nuva.assistant.ai

import com.nuva.assistant.command.CommandDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The single gateway to the NUVA backend (docs/commands.md contract).
 *
 * NO SECRET LIVES HERE OR ANYWHERE IN THE APP: the app talks to Vercel, Vercel
 * talks to Groq. Optional Supabase JWTs are attached when the user is signed
 * in (see SupabaseRepository).
 */
class AIRepository(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: suspend () -> String?,
    private val deviceIdProvider: () -> String?,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("X-Nuva-Client", "android/$APP_VERSION")
            deviceIdProvider()?.let { builder.header("X-Nuva-Device-Id", it) }
            chain.proceed(builder.build())
        }
        .addInterceptor { chain ->
            val token = runCatching { kotlinx.coroutines.runBlocking { tokenProvider() } }.getOrNull()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            }
            chain.proceed(request)
        }
        .build()

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrlProvider().normalizeBaseUrl())
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    // Cached per URL so Settings can change the backend without a restart.
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedApi: NuvaApi? = null

    val api: NuvaApi
        get() {
            val url = baseUrlProvider().normalizeBaseUrl()
            val current = cachedApi
            if (current !== null && cachedUrl == url) return current
            val built = retrofit().create(NuvaApi::class.java)
            cachedUrl = url
            cachedApi = built
            return built
        }

    // --- Plain interpretation -------------------------------------------------

    suspend fun interpret(text: String, language: String = "auto"): CommandDecision {
        val request = PromptManager.buildRequest(
            PromptManager.CommandInput(
                text = text,
                languageHint = language,
                deviceId = deviceIdProvider(),
            ),
        )
        val response = api.interpret(request)
        if (!response.ok) {
            throw ApiCallException(
                code = response.error?.code ?: "AI_INVALID_OUTPUT",
                speech = response.error?.speech ?: "Bujhte parini.",
            )
        }
        return ActionParser.parse(response)
    }

    // --- SSE streaming (POST /api/ai/command/stream) ---------------------------

    sealed interface StreamEvent {
        data class Stage(val stage: String, val source: String? = null) : StreamEvent
        data class Result(val decision: CommandDecision) : StreamEvent
        data class Failure(val code: String, val speech: String) : StreamEvent
    }

    /**
     * Streams /api/ai/command/stream: instant `accepted`/`interpreting` stage
     * events followed by the final result — identical payload to interpret().
     */
    fun interpretStream(text: String, language: String = "auto"): Flow<StreamEvent> = callbackFlow {
        val requestJson = json.encodeToString(
            CommandRequestDto.serializer(),
            PromptManager.buildRequest(
                PromptManager.CommandInput(
                    text = text,
                    languageHint = language,
                    deviceId = deviceIdProvider(),
                    clientRequestId = UUID.randomUUID().toString(),
                ),
            ),
        )

        val request = Request.Builder()
            .url(baseUrlProvider().normalizeBaseUrl() + "api/ai/command/stream")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .build()

        val call = httpClient.newCall(request)
        val worker = Thread {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val errorSpeech = runCatching { json.decodeFromString(ApiErrorEnvelope.serializer(), body) }
                        .getOrNull()?.error?.speech
                    trySend(StreamEvent.Failure("HTTP_${response.code}", errorSpeech ?: "Server e pouchate parini."))
                    close()
                    return@Thread
                }
                // Parse the SSE body line by line (event:/data: pairs).
                val bodyText = response.body?.string().orEmpty()
                var currentEvent = "message"
                for (line in bodyText.lineSequence()) {
                    when {
                        line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trim()
                            when (currentEvent) {
                                "stage" -> runCatching {
                                    json.decodeFromString(StageEventDto.serializer(), payload)
                                }.getOrNull()?.let { stage ->
                                    trySend(StreamEvent.Stage(stage.stage, stage.source))
                                }

                                "result" -> {
                                    val dto = runCatching {
                                        json.decodeFromString(CommandResponseDto.serializer(), payload)
                                    }.getOrNull()
                                    if (dto != null) {
                                        trySend(StreamEvent.Result(ActionParser.parse(dto)))
                                    } else {
                                        trySend(StreamEvent.Failure("AI_INVALID_OUTPUT", "Bujhte parini."))
                                    }
                                }

                                "error" -> runCatching {
                                    json.decodeFromString(ApiErrorEnvelope.serializer(), payload)
                                }.getOrNull()?.let { envelope ->
                                    trySend(
                                        StreamEvent.Failure(
                                            envelope.error?.code ?: "INTERNAL",
                                            envelope.error?.speech ?: "Somossa hoyeche.",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                close()
            } catch (err: Exception) {
                trySend(StreamEvent.Failure("NETWORK", "Internet e pouchate parchi na."))
                close(err)
            }
        }
        worker.isDaemon = true
        worker.start()

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    // --- Errors ----------------------------------------------------------------

    class ApiCallException(val code: String, val speech: String) : Exception("NUVA API error $code")

    @kotlinx.serialization.Serializable
    private data class ApiErrorEnvelope(val ok: Boolean = false, val error: ApiErrorDto? = null)

    @kotlinx.serialization.Serializable
    private data class StageEventDto(val stage: String = "", val source: String? = null)

    companion object {
        const val APP_VERSION = "2.8.0"
        const val DEFAULT_BASE_URL = "https://nuva-backend.vercel.app/"

        /** Accepts missing scheme and missing trailing slash. */
        fun String.normalizeBaseUrl(): String {
            val withScheme = if (contains("://")) this else "https://$this"
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }
}
