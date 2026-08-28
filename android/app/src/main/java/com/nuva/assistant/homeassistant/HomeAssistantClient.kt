package com.nuva.assistant.homeassistant

import com.nuva.assistant.command.HomeAssistantDomain
import com.nuva.assistant.command.HomeAssistantOperation
import com.nuva.assistant.command.NuvaAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Minimal allowlisted Home Assistant REST client. No arbitrary service/domain call is possible. */
class HomeAssistantClient(private val configStore: HomeAssistantConfigStore) {
    data class Entity(val entityId: String, val friendlyName: String, val state: String)

    sealed interface Result {
        data class Done(val entity: Entity, val speech: String) : Result
        data object NotConfigured : Result
        data class NotFound(val query: String) : Result
        data class Ambiguous(val matches: List<Entity>) : Result
        data class Failed(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun health(): Result = withContext(Dispatchers.IO) {
        val config = configStore.config() ?: return@withContext Result.NotConfigured
        request(config, "api") { responseText ->
            Result.Done(Entity("homeassistant", "Home Assistant", "online"), responseText.take(120).ifBlank { "Connected" })
        }
    }

    suspend fun control(action: NuvaAction.HomeAssistantControl): Result = withContext(Dispatchers.IO) {
        val config = configStore.config() ?: return@withContext Result.NotConfigured
        val states = fetchEntities(config, action.domain)
        if (states is Result.Failed) return@withContext states
        val entities = (states as? EntityList)?.entities.orEmpty()
        val matches = matchEntities(entities, action.entityQuery)
        if (matches.isEmpty()) return@withContext Result.NotFound(action.entityQuery)
        if (matches.size > 1) return@withContext Result.Ambiguous(matches.take(6))
        val entity = matches.first()
        val body = buildJsonObject {
            put("entity_id", entity.entityId)
            action.value?.let { put("temperature", it) }
        }.toString()
        val path = "api/services/${action.domain.wireName}/${action.operation.serviceName}"
        post(config, path, body) {
            val value = action.value?.let { " ${formatNumber(it)}" }.orEmpty()
            Result.Done(entity, "${entity.friendlyName} ${action.operation.wireName}$value request complete.")
        }
    }

    private fun fetchEntities(config: HomeAssistantConfigStore.Config, domain: HomeAssistantDomain): Any {
        return try {
            val response = execute(config, "api/states", method = "GET", body = null)
            if (!response.first) return Result.Failed(response.second)
            val entities = json.parseToJsonElement(response.second).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["entity_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!id.startsWith("${domain.wireName}.")) return@mapNotNull null
                val attributes = obj["attributes"]?.jsonObject
                Entity(
                    entityId = id,
                    friendlyName = attributes?.get("friendly_name")?.jsonPrimitive?.content ?: id.substringAfter('.'),
                    state = obj["state"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            EntityList(entities)
        } catch (error: Exception) {
            Result.Failed(error.message ?: "Home Assistant states failed")
        }
    }

    private inline fun request(
        config: HomeAssistantConfigStore.Config,
        path: String,
        crossinline success: (String) -> Result,
    ): Result = try {
        val response = execute(config, path, method = "GET", body = null)
        if (response.first) success(response.second) else Result.Failed(response.second)
    } catch (error: Exception) {
        Result.Failed(error.message ?: "Home Assistant request failed")
    }

    private inline fun post(
        config: HomeAssistantConfigStore.Config,
        path: String,
        body: String,
        crossinline success: () -> Result,
    ): Result = try {
        val response = execute(config, path, method = "POST", body = body)
        if (response.first) success() else Result.Failed(response.second)
    } catch (error: Exception) {
        Result.Failed(error.message ?: "Home Assistant request failed")
    }

    private fun execute(
        config: HomeAssistantConfigStore.Config,
        path: String,
        method: String,
        body: String?,
    ): Pair<Boolean, String> {
        val builder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/${path.trimStart('/')}")
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")
        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }
        http.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            return if (response.isSuccessful) true to responseBody
            else false to "Home Assistant HTTP ${response.code}: ${responseBody.take(200)}"
        }
    }

    private data class EntityList(val entities: List<Entity>)

    companion object {
        fun matchEntities(entities: List<Entity>, query: String): List<Entity> {
            val needle = normalize(query)
            if (needle.isBlank()) return emptyList()
            val exact = entities.filter { entity ->
                normalize(entity.friendlyName) == needle || normalize(entity.entityId.substringAfter('.')) == needle
            }
            if (exact.isNotEmpty()) return exact
            val starts = entities.filter { normalize(it.friendlyName).startsWith(needle) }
            if (starts.isNotEmpty()) return starts
            return entities.filter { entity ->
                normalize(entity.friendlyName).contains(needle) || normalize(entity.entityId).contains(needle)
            }
        }

        private fun normalize(value: String): String = value.lowercase()
            .replace(Regex("""[^a-z0-9\u0980-\u09FF ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    }
}
