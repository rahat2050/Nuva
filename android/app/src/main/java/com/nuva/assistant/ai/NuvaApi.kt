package com.nuva.assistant.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit surface of the NUVA backend — the frozen PHASE 1 contract in
 * docs/commands.md. SSE streaming lives in [AIRepository] (raw OkHttp), not here.
 */
interface NuvaApi {

    @GET("api/health")
    suspend fun health(): HealthResponse

    @POST("api/ai/command")
    suspend fun interpret(
        @Body request: CommandRequestDto,
        @Header("Authorization") authorization: String? = null,
    ): CommandResponseDto

    @GET("api/commands")
    suspend fun commandHistory(@Query("limit") limit: Int = 50): CommandHistoryResponse

    @POST("api/commands")
    suspend fun reportExecution(@Body report: CommandReportDto): CommandReportResult

    @GET("api/memory")
    suspend fun listMemory(): MemoryListResponse

    @POST("api/memory")
    suspend fun saveMemory(@Body memory: MemoryUpsertDto): MemorySaveResult

    @DELETE("api/memory")
    suspend fun forgetMemory(@Query("key") key: String): MemoryDeleteResult

    @GET("api/devices")
    suspend fun listDevices(): DeviceListResponse

    @POST("api/devices")
    suspend fun registerDevice(@Body device: DeviceRegisterDto): DeviceRegisterResult

    @POST("api/screenshots")
    suspend fun screenshotGrant(): ScreenshotGrantResponse
}

// --- DTOs ---------------------------------------------------------------------

@Serializable
data class CommandRequestDto(
    val text: String,
    val language: String = "auto",
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    val context: CommandContextDto? = null,
)

@Serializable
data class CommandContextDto(
    @SerialName("foreground_app") val foregroundApp: String? = null,
    @SerialName("screen_summary") val screenSummary: String? = null,
)

@Serializable
data class CommandResponseDto(
    val ok: Boolean,
    @SerialName("request_id") val requestId: String? = null,
    val input: CommandInputDto? = null,
    val result: CommandResultDto? = null,
    val meta: CommandMetaDto? = null,
    val error: ApiErrorDto? = null,
)

@Serializable
data class CommandInputDto(
    val text: String? = null,
    @SerialName("normalized_text") val normalizedText: String? = null,
    val language: String? = null,
    @SerialName("wake_word_detected") val wakeWordDetected: Boolean? = null,
)

@Serializable
data class CommandResultDto(
    val intent: String? = null,
    val action: JsonObject? = null,
    val risk: String? = null,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean? = null,
    val confidence: Double? = null,
    val speech: String? = null,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class CommandMetaDto(
    val source: String? = null,
    val model: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("command_id") val commandId: String? = null,
    val persisted: Boolean? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
    val speech: String? = null,
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val version: String? = null,
    val config: JsonObject? = null,
)

@Serializable
data class CommandHistoryResponse(
    val ok: Boolean = false,
    val count: Int = 0,
    val commands: List<CommandHistoryRow> = emptyList(),
)

@Serializable
data class CommandHistoryRow(
    val id: String,
    val command: String = "",
    val intent: String = "",
    val risk: String = "low",
    val status: String = "ready",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CommandReportDto(
    @SerialName("command_id") val commandId: String? = null,
    val status: String,
    val error: String? = null,
    val command: String? = null,
    val intent: String? = null,
    val risk: String? = null,
)

@Serializable
data class CommandReportResult(val ok: Boolean = false, @SerialName("command_id") val commandId: String? = null)

@Serializable
data class MemoryListResponse(val ok: Boolean = false, val count: Int = 0, val memories: List<MemoryRow> = emptyList())

@Serializable
data class MemoryRow(val key: String, val value: String, @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class MemoryUpsertDto(val key: String, val value: String)

@Serializable
data class MemorySaveResult(val ok: Boolean = false, val memory: MemoryRow? = null)

@Serializable
data class MemoryDeleteResult(val ok: Boolean = false, val deleted: Boolean = false)

@Serializable
data class DeviceListResponse(val ok: Boolean = false, val count: Int = 0, val devices: List<DeviceRow> = emptyList())

@Serializable
data class DeviceRow(
    val id: String? = null,
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("android_version") val androidVersion: String? = null,
)

@Serializable
data class DeviceRegisterDto(
    @SerialName("device_name") val deviceName: String,
    @SerialName("android_version") val androidVersion: String? = null,
)

@Serializable
data class DeviceRegisterResult(val ok: Boolean = false, val device: DeviceRow? = null)

@Serializable
data class ScreenshotGrantResponse(val ok: Boolean = false, val upload: ScreenshotUploadGrant? = null)

@Serializable
data class ScreenshotUploadGrant(
    @SerialName("cloud_name") val cloudName: String = "",
    @SerialName("api_key") val apiKey: String = "",
    val timestamp: Long = 0,
    val signature: String = "",
    val folder: String = "",
    @SerialName("upload_url") val uploadUrl: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
)
