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
    /** 实验室预置声纹（SAL sample_urls 键与 PCM URL） */
    private const val SAL_LAB_SPEAKER1_ID = "shengwang_speaker1_zlm"
    private const val SAL_LAB_SPEAKER2_ID = "shengwang_speaker2_lzc"
    private const val SAL_LAB_PCM_URL_SPEAKER1 = "https://voiceprint-labtest.agoralab.co/lab_qn_m1.pcm"
    private const val SAL_LAB_PCM_URL_SPEAKER2 = "https://voiceprint-labtest.agoralab.co/lab_qn_f1.pcm"
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
            val url = "$API_BASE_URL/$projectId/join"

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

                put("sal", JSONObject().apply {
                    put("sal_mode", "recognition")
                    put(
                        "sample_urls",
                        buildSalSampleUrlsJson(KeyCenter.SAL_ENABLE_PERSONALIZED, deviceId.toString())
                    )
                })

                put("turn_detection", JSONObject().apply {
                    put("interrupt_mode", "adaptive")
                })

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
        val hasBiometricEntries = biometricJson.keys().asSequence().any { k ->
            k.isNotEmpty() && biometricJson.optString(k, "").isNotEmpty()
        }
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
