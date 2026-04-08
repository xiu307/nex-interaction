package cn.shengwang.convoai.quickstart.api

import android.util.Log
import cn.shengwang.convoai.quickstart.KeyCenter
import cn.shengwang.convoai.quickstart.biometric.BiometricSalRegistry
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
    private const val TAG = "AgentStarter"
    /** 短标签，便于 `adb logcat -s SAL:I`（部分机型对 AgentStarter 过滤异常） */
    private const val TAG_SAL = "SAL"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val API_BASE_URL = "https://api.agora.io/cn/api/conversational-ai-agent/v2/projects"
    /** 实验室预置声纹（SAL sample_urls 键与 PCM URL） */
    private const val SAL_LAB_SPEAKER1_ID = "shengwang_speaker1_zlm"
    private const val SAL_LAB_SPEAKER2_ID = "shengwang_speaker2_lzc"
    private const val SAL_LAB_PCM_URL_SPEAKER1 = "https://voiceprint-labtest.agoralab.co/lab_qn_m1.pcm"
    private const val SAL_LAB_PCM_URL_SPEAKER2 = "https://voiceprint-labtest.agoralab.co/lab_qn_f1.pcm"
    /** join 请求体 `turn_detection.config.start_of_speech.vad_config.speaking_interrupt_duration_ms` 默认值 */
    private const val DEFAULT_SPEAKING_INTERRUPT_DURATION_MS = 480
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
            Log.i(TAG, "startAgentAsync begin channel=$channelName remoteRtcUid=$remoteRtcUid")
            val projectId = KeyCenter.APP_ID
            val url = "$API_BASE_URL/$projectId/join"

            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                remoteRtcUids = listOf(remoteRtcUid)
            )

            Log.d("requestBody",requestBody.toString())
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
     * Aligns with open-source body shape (see CovLivingViewModel.getConvoaiOpenSourceBodyMap): ASR/LLM/TTS
     * from [KeyCenter], no `avatar` block.
     */
    private fun buildJsonPayload(
        name: String,
        channel: String,
        agentRtcUid: String,
        token: String,
        remoteRtcUids: List<String>
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

                val sampleUrlsJson = buildSalSampleUrlsJson(
                    KeyCenter.SAL_ENABLE_PERSONALIZED,
                    deviceId.toString(),
                )
                val salPretty = sampleUrlsJson.toString(2)
                Log.i(TAG, "SAL sample_urls (${sampleUrlsJson.length()} keys, uidStr=$deviceId):\n$salPretty")
                Log.i(TAG_SAL, salPretty)
                put("sal", JSONObject().apply {
                    put("sal_mode", "recognition")
                    put("sample_urls", sampleUrlsJson)
                })

                put("turn_detection", buildTurnDetectionJson())

                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_flexible", true)
                    put("enable_error_message", true)
                })
            })
        }
    }

    /**
     * 与 [io.agora.scene.convoai.ui.living.CovLivingViewModel.buildSalSampleUrls] 同构：
     * - 个性化声纹：开启且 URL 非空时追加 [uidStr] → PCM URL；
     * - 预注册：env `SAL_BIOMETRIC_SAMPLE_URLS` JSON 对象，faceId → PCM URL（等价 BiometricSalRegistry）；
     * - **仅当无任何预注册条目时**才追加两条实验室 PCM。
     */
    private fun buildSalSampleUrlsJson(enablePersonalized: Boolean, uidStr: String): JSONObject {
        val rawBiometric = KeyCenter.SAL_BIOMETRIC_SAMPLE_URLS
        val biometricJson = try {
            if (rawBiometric.isNotEmpty()) JSONObject(rawBiometric) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val registryComplete = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls()
        Log.i(TAG, "SAL: getCompleteSalFaceIdToPcmUrls size=${registryComplete.size} keys=${registryComplete.keys}")
        if (registryComplete.isEmpty() && BiometricSalRegistry.hasLocalRegistrationButNoHttpSalPair()) {
            Log.w(
                TAG,
                "SAL: 本地有人脸/声纹记录，但人脸图与 PCM 均须为 http(s) OSS URL 才会进入 sample_urls；" +
                    "仅 local:// 或未上传 OSS 时云端 SAL 无法用你的注册声纹，只会用 env 预注册或实验室默认 PCM。",
            )
        }
        val hasBiometricEntries = biometricJson.keys().asSequence().any { k ->
            k.isNotEmpty() && biometricJson.optString(k, "").isNotEmpty()
        } || registryComplete.isNotEmpty()
        val out = JSONObject()
        if (enablePersonalized) {
            KeyCenter.SAL_PERSONALIZED_PCM_URL.takeIf { it.isNotEmpty() }?.let { out.put(uidStr, it) }
        }
        val keyIt = biometricJson.keys()
        while (keyIt.hasNext()) {
            val key = keyIt.next()
            val v = biometricJson.optString(key, "")
            if (key.isNotEmpty() && v.isNotEmpty()) out.put(key, v)
        }
        // 与 CovLivingViewModel：本地注册页完成的 faceId→PCM（须同时有人脸图 OSS 与 PCM OSS）
        for ((faceId, pcmUrl) in registryComplete) {
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
        KeyCenter.ASR_LANG.takeIf { it.isNotEmpty() }?.let { put("language", it) }
        KeyCenter.ASR_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        val raw = KeyCenter.ASR_PARAMS
        if (raw.isNotEmpty()) {
            try {
                put("params", JSONObject(raw))
            } catch (_: Exception) {
                put("params", raw)
            }
        }
    }

    private fun buildLlmJson(userNameForLabels: Long): JSONObject = JSONObject().apply {
        KeyCenter.LLM_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        KeyCenter.LLM_URL.takeIf { it.isNotEmpty() }?.let { put("url", it) }
        KeyCenter.LLM_API_KEY.takeIf { it.isNotEmpty() }?.let { put("api_key", it) }
        val sysRaw = KeyCenter.LLM_SYSTEM_MESSAGES
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
        KeyCenter.LLM_MAX_HISTORY.toIntOrNull()?.let { put("max_history", it) }
            ?: put("max_history", JSONObject.NULL)
        put("ignore_empty", JSONObject.NULL)
        put("input_modalities", JSONArray().apply { put("text"); put("image") })
        put("output_modalities", JSONObject.NULL)
        put("failure_message", JSONObject.NULL)
        put("auto_merge", false)
    }

    private fun buildLlmParamsJson(userNameForLabels: Long): JSONObject = try {
        val base =
            if (KeyCenter.LLM_PARRAMS.isNotEmpty()) JSONObject(KeyCenter.LLM_PARRAMS) else JSONObject()
        base.put(
            "lables",
            JSONObject().put("userName", userNameForLabels)
        )
    } catch (_: Exception) {
        JSONObject().put(
            "lables",
            JSONObject().put("userName", userNameForLabels)
        )
    }

    private fun buildTtsJson(): JSONObject = JSONObject().apply {
        KeyCenter.TTS_VENDOR.takeIf { it.isNotEmpty() }?.let { put("vendor", it) }
        val raw = KeyCenter.TTS_PARAMS
        if (raw.isNotEmpty()) {
            try {
                put("params", JSONObject(raw))
            } catch (_: Exception) {
                put("params", raw)
            }
        }
    }

    /**
     * 构建 `properties.turn_detection`：`config` → `start_of_speech` → `vad_config` → `speaking_interrupt_duration_ms`。
     */
    private fun buildTurnDetectionJson(
        speakingInterruptDurationMs: Int = DEFAULT_SPEAKING_INTERRUPT_DURATION_MS,
    ): JSONObject = JSONObject().apply {
        put("config", JSONObject().apply {
            put("start_of_speech", JSONObject().apply {
                put("vad_config", JSONObject().apply {
                    put("speaking_interrupt_duration_ms", speakingInterruptDurationMs)
                })
            })
        })
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
