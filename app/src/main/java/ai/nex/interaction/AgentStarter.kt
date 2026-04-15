package ai.nex.interaction

import ai.conv.internal.config.ConvoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AgentStarter {
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL = "https://api.agora.io/cn/api/conversational-ai-agent/v2/projects"
    private const val LOG_TAG = "OkHttp/Agent"

    private val httpClient = OkHttpDebugLogger.createClient(LOG_TAG)

    suspend fun startAgentAsync(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        authToken: String,
        remoteRtcUid: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$API_BASE_URL/${ConvoConfig.APP_ID}/join")
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .addHeader("Authorization", "agora token=$authToken")
                .post(
                    buildJsonPayload(
                        channelName = channelName,
                        agentRtcUid = agentRtcUid,
                        agentToken = agentToken,
                        remoteRtcUid = remoteRtcUid,
                    ).toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType())
                )
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Start agent failed http=${response.code} body=${response.body?.string().orEmpty()}")
                }
                val responseJson = JSONObject(response.body?.string().orEmpty())
                responseJson.optString("agent_id").takeIf { it.isNotBlank() }
                    ?: error("agent_id missing in response: $responseJson")
            }
        }
    }

    suspend fun stopAgentAsync(agentId: String, authToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$API_BASE_URL/${ConvoConfig.APP_ID}/agents/$agentId/leave")
                    .addHeader("Authorization", "agora token=$authToken")
                    .post("".toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Stop agent failed http=${response.code} body=${response.body?.string().orEmpty()}")
                    }
                }
            }
        }

    private fun buildJsonPayload(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        remoteRtcUid: String,
    ): JSONObject {
        return JSONObject().apply {
            put("name", channelName)
            put("properties", JSONObject().apply {
                put("channel", channelName)
                put("token", agentToken)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray().put(remoteRtcUid))
                put("enable_string_uid", false)
                put("idle_timeout", 120)
                put("advanced_features", JSONObject().apply {
                    put("enable_rtm", true)
                })
                put("asr", buildAsrJson())
                put("llm", buildLlmJson())
                put("tts", buildTtsJson())
                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_error_message", true)
                })
            })
        }
    }

    private fun buildAsrJson(): JSONObject = JSONObject().apply {
        put("vendor", ConvoConfig.ASR_VENDOR)
        put("language", ConvoConfig.ASR_LANG)
        put("params", parseJsonObject(ConvoConfig.ASR_PARAMS))
    }

    private fun buildLlmJson(): JSONObject = JSONObject().apply {
        put("vendor", ConvoConfig.LLM_VENDOR)
        put("url", ConvoConfig.LLM_URL)
        put("api_key", ConvoConfig.LLM_API_KEY)
        put("system_messages", parseJsonArray(ConvoConfig.LLM_SYSTEM_MESSAGES))
        put("greeting_message", "你好，我是语音助手。")
        put("failure_message", "抱歉，我暂时无法回答。")
        put("max_history", ConvoConfig.LLM_MAX_HISTORY.toIntOrNull() ?: 21)
        put("params", parseJsonObject(ConvoConfig.LLM_PARRAMS))
    }

    private fun buildTtsJson(): JSONObject = JSONObject().apply {
        put("vendor", ConvoConfig.TTS_VENDOR)
        put("params", parseJsonObject(ConvoConfig.TTS_PARAMS))
    }

    private fun parseJsonObject(raw: String): JSONObject {
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun parseJsonArray(raw: String): JSONArray {
        return if (raw.isBlank()) JSONArray() else JSONArray(raw)
    }
}
