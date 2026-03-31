package cn.shengwang.convoai.quickstart.api

import cn.shengwang.convoai.quickstart.KeyCenter
import cn.shengwang.convoai.quickstart.api.net.SecureOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent Starter
 *
 * Starts/stops Conversational AI agents via Shengwang REST API.
 * Uses HTTP token auth mode: Authorization header is "agora token=<convoai_token>".
 * This auth mode requires APP_CERTIFICATE to be enabled in the ShengWang console.
 * Pipeline (ASR/LLM/TTS) is configured inline in the request body.
 */
object AgentStarter {
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL = "https://api.agora.io/cn/api/conversational-ai-agent/v2/projects"

    private val okHttpClient: OkHttpClient by lazy {
        SecureOkHttpClient.create()
            .build()
    }
    /**
     * Start an agent with inline ASR/LLM/TTS pipeline configuration.
     *
     * @param channelName Channel name for the agent
     * @param agentRtcUid Agent RTC UID
     * @param agentToken Token for the agent to join the RTC channel
     * @param authToken Agora token for REST API authorization (requires APP_CERTIFICATE enabled)
     * @param remoteRtcUid Current user RTC UID that the agent should subscribe to
     * @return Result containing agentId or exception
     */
    suspend fun startAgentAsync(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        authToken: String,
        remoteRtcUid: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/join/"

            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = listOf(remoteRtcUid)
            )

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .addHeader("Authorization", "agora token=$authToken")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw RuntimeException("Start agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            val body = response.body.string()
            val bodyJson = JSONObject(body)
            val agentId = bodyJson.optString("agent_id", "")

            if (agentId.isBlank()) {
                throw RuntimeException("Failed to parse agentId from response: $body")
            }

            Result.success(agentId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build JSON payload with inline ASR/LLM/TTS pipeline configuration.
     * Matches the Shengwang Conversational AI REST API v2 format.
     */
    private fun buildJsonPayload(
        name: String,
        channel: String,
        agentRtcUid: String,
        token: String,
        remoteRtcUids: List<String>
    ): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray(remoteRtcUids))
                put("enable_string_uid", false)
                put("idle_timeout", 120)

                // Advanced features
                put("advanced_features", JSONObject().apply {
                    put("enable_rtm", true)
                })

                // ASR - Shengwang Fengming
                put("asr", JSONObject().apply {
                    put("vendor", "fengming")
                    put("language", "zh-CN")
                })

                // LLM - Qwen via DashScope OpenAI-compatible endpoint
                put("llm", JSONObject().apply {
                    put("url", KeyCenter.LLM_URL)
                    put("api_key", KeyCenter.LLM_API_KEY)
                    put("vendor", "aliyun")
                    put("system_messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "你是一名有帮助的 AI 助手。")
                        })
                    })
                    put("greeting_message", "你好！我是你的 AI 助手，有什么可以帮你？")
                    put("failure_message", "抱歉，我暂时处理不了你的请求，请稍后再试。")
                    put("params", JSONObject().apply {
                        put("model", KeyCenter.LLM_MODEL)
                    })
                })

                // TTS - Volcengine / ByteDance
                put("tts", JSONObject().apply {
                    put("vendor", "bytedance")
                    put("params", JSONObject().apply {
                        put("token", KeyCenter.TTS_BYTEDANCE_TOKEN)
                        put("app_id", KeyCenter.TTS_BYTEDANCE_APP_ID)
                        put("cluster", "volcano_tts")
                        put("voice_type", "BV700_streaming")
                        put("speed_ratio", 1.0)
                        put("volume_ratio", 1.0)
                        put("pitch_ratio", 1.0)
                    })
                })

                // Parameters
                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_error_message", true)
                })
            })
        }
    }

    /**
     * Stop an agent
     *
     * @param agentId Agent ID to stop
     * @param authToken Agora token for REST API authorization (requires APP_CERTIFICATE enabled)
     * @return Result containing success or exception
     */
    suspend fun stopAgentAsync(
        agentId: String,
        authToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/agents/$agentId/leave"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "agora token=$authToken")
                .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                throw RuntimeException("Stop agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            response.body.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
