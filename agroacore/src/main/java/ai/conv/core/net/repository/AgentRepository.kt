package ai.conv.core.net.repository

import ai.conv.core.net.SecureOkHttpClient
import android.util.Log
import ai.conv.core.config.ConvoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AgentRepository {
    private const val TAG = "AgentRepository"
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
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "startAgentAsync begin channel=$channelName remoteRtcUids=$remoteRtcUids")
            val url = "$API_BASE_URL/${ConvoConfig.APP_ID}/join"
            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = remoteRtcUids,
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

    fun buildStartAgentConfigPreview(
        channelName: String,
        agentRtcUid: String,
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
    ): String {
        val url = "$API_BASE_URL/${ConvoConfig.APP_ID}/join"
        val body = buildJsonPayload(
            name = channelName,
            channel = channelName,
            agentRtcUid = agentRtcUid,
            token = "<agentToken>",
            remoteRtcUids = remoteRtcUids,
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
        val labelUserId = remoteRtcUids.firstOrNull()?.toLongOrNull() ?: 0L
        val labelUserIdStr = labelUserId.toString()
        return JSONObject().apply {
            put("name", name)
            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray().apply {
                    if (remoteRtcUids.isEmpty()) {
                        put("*")
                    } else {
                        remoteRtcUids.forEach { put(it) }
                    }
                })
                put("enable_string_uid", false)
                put("idle_timeout", 120)

                put("advanced_features", JSONObject().apply {
                    put("enable_aivad", false)
                    put("enable_bhvs", true)
                    put("enable_rtm", true)
                    put("enable_sal", true)
                })

                put("asr", buildAsrJson())
                put("llm", buildLlmJson(labelUserId))
                put("tts", buildTtsJson())
                put("sal", JSONObject().apply {
                    put("sal_mode", "locking")
                    put(
                        "sample_urls",
                        buildSalSampleUrlsJson(
                            enablePersonalized = ConvoConfig.SAL_ENABLE_PERSONALIZED,
                            uidStr = labelUserIdStr,
                            runtimeSalSampleUrls = runtimeSalSampleUrls,
                            hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
                        )
                    )
                })
                put("turn_detection", buildTurnDetectionJson())
                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_flexible", true)
                    put("enable_error_message", true)
                    put("enable_dump", true)
                    put("cascading_graph", "v1_soseos_multi_user")
                    put("main", JSONObject().apply {
                        put("interrupt_check", JSONObject().apply {
                            put("enabled", true)
                            put("timeout_seconds", 5)
                            put("url", "http://42.121.218.208:8080/v1/audio/interrupt_check")
                            put("api_key", ConvoConfig.LLM_API_KEY)
                            put("labels", JSONObject().put("userName", labelUserIdStr))
                        })
                    })
                    put("audio3a_downstream", JSONObject().apply {
                        put("enable_ans", false)
                        put("passthrough", true)
                    })
                    put("bvc", JSONObject().apply {
                        put("params", JSONObject().apply {
                            put("vpBVC", JSONObject().apply {
                                put("threshold_calc_low_lower_limit", 0.35)
                                put("threshold_calc_low_upper_limit", 0.35)
                            })
                        })
                    })
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
        val biometricJson = try {
            if (ConvoConfig.SAL_BIOMETRIC_SAMPLE_URLS.isNotEmpty()) {
                JSONObject(ConvoConfig.SAL_BIOMETRIC_SAMPLE_URLS)
            } else {
                JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }

        Log.i(TAG, "SAL: runtime sample_urls size=${runtimeSalSampleUrls.size} keys=${runtimeSalSampleUrls.keys}")
        if (runtimeSalSampleUrls.isEmpty() && hasIncompleteLocalRegistration) {
            Log.w(
                TAG,
                "SAL: 本地有人脸/声纹记录，但 sample_urls 仅在 PCM 为 http(s) 且 face URL 非空时才会带上；" +
                    "若 PCM 仍是 local:// 或未上传 OSS，云端 SAL 无法用你的注册声纹，只会用 env 预注册或实验室默认 PCM。"
            )
        }

        val hasBiometricEntries =
            biometricJson.keys().asSequence().any { key ->
                key.isNotEmpty() && biometricJson.optString(key, "").isNotEmpty()
            } || runtimeSalSampleUrls.isNotEmpty()

        val out = JSONObject()
        if (enablePersonalized) {
            ConvoConfig.SAL_PERSONALIZED_PCM_URL.takeIf { it.isNotEmpty() }?.let { out.put(uidStr, it) }
        }

        val envKeys = biometricJson.keys()
        while (envKeys.hasNext()) {
            val key = envKeys.next()
            val value = biometricJson.optString(key, "")
            if (key.isNotEmpty() && value.isNotEmpty()) {
                out.put(key, value)
            }
        }

        runtimeSalSampleUrls.forEach { (faceId, pcmUrl) ->
            if (faceId.isNotEmpty() && pcmUrl.isNotEmpty()) {
                out.put(faceId, pcmUrl)
            }
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
        val base =
            if (ConvoConfig.LLM_PARRAMS.isNotEmpty()) JSONObject(ConvoConfig.LLM_PARRAMS) else JSONObject()
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
        authToken: String
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
