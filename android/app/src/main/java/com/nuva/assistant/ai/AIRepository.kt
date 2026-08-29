package com.nuva.assistant.ai

import com.nuva.assistant.command.CommandDecision
import com.nuva.assistant.core.constants.AppConstants
import com.nuva.assistant.core.security.SecureEndpointPolicy
import com.nuva.assistant.core.security.SensitiveAppPolicy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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
    private val baseUrlProvider: suspend () -> String,
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
            val response = chain.proceed(builder.build())
            val declaredLength = response.body?.contentLength() ?: -1L
            if (declaredLength > MAX_HTTP_RESPONSE_BYTES) {
                response.close()
                throw IOException("HTTP response exceeds limit")
            }
            response
        }
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    // One coherent volatile entry avoids URL/API mismatches under concurrent calls.
    @Volatile private var cachedApi: CachedApi? = null

    private suspend fun api(): NuvaApi {
        val url = baseUrlProvider().normalizeBaseUrl()
        cachedApi?.takeIf { it.url == url }?.let { return it.api }
        return synchronized(this) {
            cachedApi?.takeIf { it.url == url }?.api
                ?: retrofit(url).create(NuvaApi::class.java).also { built ->
                    cachedApi = CachedApi(url, built)
                }
        }
    }

    private suspend fun authorizationHeader(): String? {
        val token = try {
            tokenProvider()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        return token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    suspend fun healthOk(): Boolean = api().health().ok

    private data class CachedApi(val url: String, val api: NuvaApi)

    // --- Plain interpretation -------------------------------------------------

    suspend fun interpret(text: String, language: String = "auto"): CommandDecision {
        val request = PromptManager.buildRequest(
            PromptManager.CommandInput(
                text = text,
                languageHint = language,
                deviceId = deviceIdProvider(),
            ),
        )
        val response = api().interpret(request, authorizationHeader())
        if (!response.ok) {
            throw ApiCallException(
                code = safeErrorCode(response.error?.code),
                speech = safeServerSpeech(response.error?.speech, "Bujhte parini."),
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

        val requestBuilder = Request.Builder()
            .url(baseUrlProvider().normalizeBaseUrl() + "api/ai/command/stream")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
        authorizationHeader()?.let { requestBuilder.header("Authorization", it) }
        val call = httpClient.newCall(requestBuilder.build())

        val worker = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // Never buffer or surface arbitrary custom-endpoint error bodies.
                        trySend(StreamEvent.Failure("HTTP_${response.code}", "Server e pouchate parini."))
                        close()
                        return@launch
                    }

                    val source = response.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Failure("EMPTY_RESPONSE", "Server theke kono response paini."))
                        close()
                        return@launch
                    }

                    // Consume the socket incrementally. ResponseBody.string()
                    // buffers until EOF and used to delay every "instant" stage
                    // event until the entire SSE response had already finished.
                    var currentEvent = "message"
                    val dataLines = mutableListOf<String>()
                    var dataChars = 0

                    fun emitCurrentEvent() {
                        if (dataLines.isEmpty()) return
                        val payload = dataLines.joinToString("\n")
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
                                        safeErrorCode(envelope.error?.code),
                                        safeServerSpeech(envelope.error?.speech, "Somossa hoyeche."),
                                    ),
                                )
                            }
                        }
                        currentEvent = "message"
                        dataLines.clear()
                        dataChars = 0
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                        when {
                            line.isEmpty() -> emitCurrentEvent()
                            line.startsWith(":") -> Unit // SSE keep-alive/comment
                            line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                val data = line.removePrefix("data:").trimStart()
                                dataChars += data.length
                                if (dataChars > MAX_SSE_EVENT_CHARS || dataLines.size >= MAX_SSE_DATA_LINES) {
                                    throw IOException("SSE event exceeds limit")
                                }
                                dataLines += data
                            }
                        }
                    }
                    emitCurrentEvent()
                    close()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (io: IOException) {
                if (!call.isCanceled()) {
                    trySend(StreamEvent.Failure("NETWORK", "Internet e pouchate parchi na."))
                }
                close()
            } catch (_: Exception) {
                trySend(StreamEvent.Failure("NETWORK", "Internet e pouchate parchi na."))
                close()
            }
        }

        awaitClose {
            call.cancel()
            worker.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun safeErrorCode(raw: String?): String =
        raw?.takeIf { it.matches(Regex("^[A-Z0-9_]{1,64}$")) } ?: "INTERNAL"

    private fun safeServerSpeech(raw: String?, fallback: String): String =
        raw?.takeIf { speech ->
            speech.length in 1..500 &&
                !SensitiveAppPolicy.mentionsCredentials(speech) &&
                SensitiveAppPolicy.refusalForText(speech) == null
        } ?: fallback

    // --- Errors ----------------------------------------------------------------

    class ApiCallException(val code: String, val speech: String) : Exception("NUVA API error $code")

    @kotlinx.serialization.Serializable
    private data class ApiErrorEnvelope(val ok: Boolean = false, val error: ApiErrorDto? = null)

    @kotlinx.serialization.Serializable
    private data class StageEventDto(val stage: String = "", val source: String? = null)

    companion object {
        const val APP_VERSION = "4.4.2"
        const val DEFAULT_BASE_URL = AppConstants.DEFAULT_BASE_URL
        private const val MAX_HTTP_RESPONSE_BYTES = 2L * 1024L * 1024L
        private const val MAX_SSE_LINE_BYTES = 64L * 1024L
        private const val MAX_SSE_EVENT_CHARS = 64 * 1024
        private const val MAX_SSE_DATA_LINES = 64

        /** Missing schemes become HTTPS; insecure/malformed stored values fail closed to production. */
        fun String.normalizeBaseUrl(): String =
            SecureEndpointPolicy.normalizeRequired(this, defaultWhenBlank = DEFAULT_BASE_URL)
                ?: DEFAULT_BASE_URL
    }
}
