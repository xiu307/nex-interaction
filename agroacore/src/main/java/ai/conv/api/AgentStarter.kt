package ai.conv.api

import android.util.Log
import ai.conv.api.net.SecureOkHttpClient
import ai.conv.internal.config.ConvoConfig
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
    private const val TAG = "AgentStarter"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL = "https://api.agora.io/cn/api/conversational-ai-agent/v2/projects"
    private const val SAL_LAB_SPEAKER1_ID = "shengwang_speaker1_zlm"
    private const val SAL_LAB_SPEAKER2_ID = "shengwang_speaker2_lzc"
    private const val SAL_LAB_PCM_URL_SPEAKER1 = "https://voiceprint-labtest.agoralab.co/lab_qn_m1.pcm"
    private const val SAL_LAB_PCM_URL_SPEAKER2 = "https://voiceprint-labtest.agoralab.co/lab_qn_f1.pcm"
    private const val START_OF_SPEECH_MODE_DISABLED = "disabled"
    private const val START_OF_SPEECH_DISABLED_STRATEGY_IGNORE = "ignore"

    private val okHttpClient: OkHttpClient by lazy {
        SecureOkHttpClient.create().build()
    }

    suspend fun startAgentAsync(
        channelName: String,
        agentRtcUid: String,
        agentToken: String,
        authToken: String,
        remoteRtcUid: String,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "startAgentAsync begin channel=$channelName remoteRtcUid=$remoteRtcUid")
            val url = "$API_BASE_URL/${ConvoConfig.APP_ID}/join"

            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = listOf(remoteRtcUid),
                runtimeSalSampleUrls = runtimeSalSampleUrls,
                hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
            )

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .addHeader("Authorization", "agora token=$authToken")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw RuntimeException("Start agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            val body = response.body?.string() ?: throw RuntimeException("Start agent response body is null")
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
     * 供通话页调试查看 startAgentAsync 的请求配置（URL / Headers / Body）。
     * 敏感 token 使用占位符，避免误泄露。
     */
    fun buildStartAgentConfigPreview(
        channelName: String,
        agentRtcUid: String,
        remoteRtcUid: String,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
    ): String {
        val url = "$API_BASE_URL/${ConvoConfig.APP_ID}/join"
        val body = buildJsonPayload(
            name = channelName,
            channel = channelName,
            agentRtcUid = agentRtcUid,
            token = "<agentToken>",
            remoteRtcUids = listOf(remoteRtcUid),
            runtimeSalSampleUrls = runtimeSalSampleUrls,
            hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
        )
        return JSONObject().apply {
            put("url", url)
            put("headers", JSONObject().apply {
                put("Content-Type", JSON_MEDIA_TYPE)
                put("Authorization", "agora token=<authToken>")
            })
            put("body", body)
        }.toString(2)
    }

    private fun buildJsonPayload(
        name: String,
        channel: String,
        agentRtcUid: String,
        token: String,
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String>,
        hasIncompleteLocalRegistration: Boolean,
    ): JSONObject {
        return JSONObject().apply {
            val deviceId = remoteRtcUids.first().toLongOrNull() ?: 0L
            put("name", name)
            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray(remoteRtcUids))
                put("enable_string_uid", false)
                put("idle_timeout", 120)

                put("advanced_features", JSONObject().apply {
                    put("enable_aivad", false)
                    put("enable_bhvs", true)
                    put("enable_rtm", true)
                    put("enable_sal", true)
                })

                put("asr", buildAsrJson())
                put("llm", buildLlmJson(deviceId))
                put("tts", buildTtsJson())

                put("sal", JSONObject().apply {
                    put("sal_mode", "locking")
                    put(
                        "sample_urls",
                        buildSalSampleUrlsJson(
                            enablePersonalized = ConvoConfig.SAL_ENABLE_PERSONALIZED,
                            uidStr = deviceId.toString(),
                            runtimeSalSampleUrls = runtimeSalSampleUrls,
                            hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
                        ),
                    )
                })

                put("turn_detection", buildTurnDetectionJson())

                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_flexible", true)
                    put("enable_error_message", true)
                    put("enable_dump", true)
                })
            })
        }
    }

    private fun buildSalSampleUrlsJson(
        enablePersonalized: Boolean,
        uidStr: String,
        runtimeSalSampleUrls: Map<String, String>,
        hasIncompleteLocalRegistration: Boolean,
    ): JSONObject {
        val rawBiometric = ConvoConfig.SAL_BIOMETRIC_SAMPLE_URLS
        val biometricJson = try {
            if (rawBiometric.isNotEmpty()) JSONObject(rawBiometric) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val runtimeUrls = runtimeSalSampleUrls
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
        Log.i(TAG, "SAL: runtime provider size=${runtimeUrls.size} keys=${runtimeUrls.keys}")
        if (runtimeUrls.isEmpty() && hasIncompleteLocalRegistration) {
            Log.w(
                TAG,
                "SAL: 本地有人脸/声纹记录，但 sample_urls 仅在 PCM 为 http(s) 且 face URL 非空时才会带上；" +
                    "若 PCM 仍是 local:// 或未上传 OSS，云端 SAL 无法用你的注册声纹，只会用 env 预注册或实验室默认 PCM。",
            )
        }
        val hasBiometricEntries = biometricJson.keys().asSequence().any { key ->
            key.isNotEmpty() && biometricJson.optString(key, "").isNotEmpty()
        } || runtimeUrls.isNotEmpty()
        val out = JSONObject()
        if (enablePersonalized) {
            ConvoConfig.SAL_PERSONALIZED_PCM_URL.takeIf { it.isNotEmpty() }?.let { out.put(uidStr, it) }
        }
        val keyIt = biometricJson.keys()
        while (keyIt.hasNext()) {
            val key = keyIt.next()
            val value = biometricJson.optString(key, "")
            if (key.isNotEmpty() && value.isNotEmpty()) {
                out.put(key, value)
            }
        }
        runtimeUrls.forEach { (faceId, pcmUrl) ->
            out.put(faceId, pcmUrl)
        }
        if (!hasBiometricEntries) {
            out.put(SAL_LAB_SPEAKER1_ID, SAL_LAB_PCM_URL_SPEAKER1)
            out.put(SAL_LAB_SPEAKER2_ID, SAL_LAB_PCM_URL_SPEAKER2)
        }
        return out
    }

    private fun buildAsrJson(): JSONObject = JSONObject().apply {
        ConvoConfig.ASR_LANG.takeIf { it.isNotEmpty() }?.let { put("language", it) }
        ConvoConfig.ASR_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        val raw = ConvoConfig.ASR_PARAMS
        if (raw.isNotEmpty()) {
            try {
                put("params", JSONObject(raw))
            } catch (_: Exception) {
                put("params", raw)
            }
        }
    }

    private fun buildLlmJson(userNameForLabels: Long): JSONObject = JSONObject().apply {
        ConvoConfig.LLM_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        ConvoConfig.LLM_URL.takeIf { it.isNotEmpty() }?.let { put("url", it) }
        ConvoConfig.LLM_API_KEY.takeIf { it.isNotEmpty() }?.let { put("api_key", it) }
        val sysRaw = ConvoConfig.LLM_SYSTEM_MESSAGES
        if (sysRaw.isNotEmpty()) {
            try {
                put("system_messages", JSONArray(sysRaw))
            } catch (_: Exception) {
                put("system_messages", sysRaw)
            }
        }
        put("greeting_message", JSONObject.NULL)
        put("params", buildLlmParamsJson(userNameForLabels))
        put("style", JSONObject.NULL)
        ConvoConfig.LLM_MAX_HISTORY.toIntOrNull()?.let { put("max_history", it) }
            ?: put("max_history", JSONObject.NULL)
        put("ignore_empty", JSONObject.NULL)
        put("input_modalities", JSONArray().apply {
            put("text")
            put("image")
        })
        put("output_modalities", JSONObject.NULL)
        put("failure_message", JSONObject.NULL)
        put("auto_merge", false)
    }

    private fun buildLlmParamsJson(userNameForLabels: Long): JSONObject = try {
        val base = if (ConvoConfig.LLM_PARRAMS.isNotEmpty()) JSONObject(ConvoConfig.LLM_PARRAMS) else JSONObject()
        base.put("lables", JSONObject().put("userName", userNameForLabels))
    } catch (_: Exception) {
        JSONObject().put("lables", JSONObject().put("userName", userNameForLabels))
    }

    private fun buildTtsJson(): JSONObject = JSONObject().apply {
        ConvoConfig.TTS_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        val raw = ConvoConfig.TTS_PARAMS
        if (raw.isNotEmpty()) {
            try {
                put("params", JSONObject(raw))
            } catch (_: Exception) {
                put("params", raw)
            }
        }
    }

    private fun buildTurnDetectionJson(): JSONObject = JSONObject().apply {
        put("config", JSONObject().apply {
            put("start_of_speech", JSONObject().apply {
                put("mode", START_OF_SPEECH_MODE_DISABLED)
                put("disabled_config", JSONObject().apply {
                    put("strategy", START_OF_SPEECH_DISABLED_STRATEGY_IGNORE)
                })
            })
        })
    }

    suspend fun stopAgentAsync(
        agentId: String,
        authToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE_URL/${ConvoConfig.APP_ID}/agents/$agentId/leave"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "agora token=$authToken")
                .post("".toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw RuntimeException("Stop agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            response.body?.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
