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

/** 运行时向 Agent 追加 SAL 说话人（[AgentRepository.addSalSpeakersAsync]）。 */
data class SalSpeakerAddRequest(
    val uid: String,
    val registerUuid: String,
    val speakerId: String,
    val sampleUrl: String,
)

/** 运行时从 Agent 移除 SAL 说话人（[AgentRepository.deleteSalSpeakersAsync]）。 */
data class SalSpeakerDeleteRequest(
    val registerUuid: String,
    val speakerId: String,
)

object AgentRepository {
    private const val TAG = "AgentRepository"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
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
        /** 设备侧唯一标识（用于 labels.userName / 个性化等标签链路），不要用 remoteRtcUids 的 first。 */
        labelUserId: Long,
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
        /** speaker_id -> rtc uid，对应 join 体 `more_sal_config.locking_sessions_from_uids`。 */
        lockingSessionsFromUids: Map<String, String> = emptyMap(),
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "startAgentAsync begin channel=$channelName remoteRtcUids=$remoteRtcUids")
            val url = joinUrl()
            val requestBody = buildJsonPayload(
                name = channelName,
                channel = channelName,
                agentRtcUid = agentRtcUid,
                token = agentToken,
                labelUserId = labelUserId,
                remoteRtcUids = remoteRtcUids,
                runtimeSalSampleUrls = runtimeSalSampleUrls,
                hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
                lockingSessionsFromUids = lockingSessionsFromUids,
            )

            val request = buildAgentPostRequest(url, authToken, requestBody.toString())
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw RuntimeException("Start agent error: httpCode=${response.code}, httpMsg=$errorBody")
            }

            val body = response.body?.string()
                ?: throw RuntimeException("Start agent response body is null")
            val agentId = parseAgentIdFromJoinResponse(body)
            if (agentId.isBlank()) {
                throw RuntimeException(
                    "Join 返回无 agent_id（AppId 未换时请让后台查 join 日志与 body 内 LLM/回调 URL）: $body",
                )
            }
            Log.i(TAG, "startAgentAsync ok agentId=$agentId")
            Result.success(agentId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildStartAgentConfigPreview(
        channelName: String,
        agentRtcUid: String,
        labelUserId: Long,
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String> = emptyMap(),
        hasIncompleteLocalRegistration: Boolean = false,
        lockingSessionsFromUids: Map<String, String> = emptyMap(),
    ): String {
        val url = joinUrl()
        val body = buildJsonPayload(
            name = channelName,
            channel = channelName,
            agentRtcUid = agentRtcUid,
            token = "<agentToken>",
            labelUserId = labelUserId,
            remoteRtcUids = remoteRtcUids,
            runtimeSalSampleUrls = runtimeSalSampleUrls,
            hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
            lockingSessionsFromUids = lockingSessionsFromUids,
        )
        return JSONObject().apply {
            put("url", url)
            put("headers", buildApiHeadersJson(authToken = "<authToken>"))
            put("body", body)
        }.toString(2)
    }

    suspend fun addSalSpeakersAsync(
        agentId: String,
        authToken: String,
        speakers: List<SalSpeakerAddRequest>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        postSalSpeakersMutation(
            url = "${ConvoConfig.agentRestBaseUrl()}/${ConvoConfig.APP_ID}/agents/$agentId/add_sal_speakers",
            authToken = authToken,
            body = JSONObject().apply {
                put(
                    "speakers",
                    JSONArray().apply {
                        speakers.forEach { s ->
                            put(
                                JSONObject().apply {
                                    put("uid", s.uid)
                                    put("register_uuid", s.registerUuid)
                                    put("speaker_id", s.speakerId)
                                    put("sample_url", s.sampleUrl)
                                },
                            )
                        }
                    },
                )
            },
            opName = "add_sal_speakers",
        )
    }

    suspend fun deleteSalSpeakersAsync(
        agentId: String,
        authToken: String,
        speakers: List<SalSpeakerDeleteRequest>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        postSalSpeakersMutation(
            url = "${ConvoConfig.agentRestBaseUrl()}/${ConvoConfig.APP_ID}/agents/$agentId/delete_sal_speakers",
            authToken = authToken,
            body = JSONObject().apply {
                put(
                    "speakers",
                    JSONArray().apply {
                        speakers.forEach { s ->
                            put(
                                JSONObject().apply {
                                    put("register_uuid", s.registerUuid)
                                    put("speaker_id", s.speakerId)
                                },
                            )
                        }
                    },
                )
            },
            opName = "delete_sal_speakers",
        )
    }

    private suspend fun postSalSpeakersMutation(
        url: String,
        authToken: String,
        body: JSONObject,
        opName: String,
    ): Result<Unit> = try {
        val request = buildAgentPostRequest(url, authToken, body.toString())
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw RuntimeException("$opName error: httpCode=${response.code}, httpMsg=$errorBody")
        }
        response.body?.close()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 吉利新网关 join 响应：常见为顶层 `agent_id`，也可能在 `data` 内。
     */
    private fun parseAgentIdFromJoinResponse(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val root = JSONObject(body)
            sequenceOf(
                root.optString("agent_id", ""),
                root.optJSONObject("data")?.optString("agent_id", "").orEmpty(),
                root.optJSONObject("data")?.optString("task_id", "").orEmpty(),
                root.optString("task_id", ""),
                root.optString("id", ""),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun joinUrl(): String =
        "${ConvoConfig.agentRestBaseUrl()}/${ConvoConfig.APP_ID}/join"

    private fun leaveUrl(agentId: String): String =
        "${ConvoConfig.agentRestBaseUrl()}/${ConvoConfig.APP_ID}/agents/$agentId/leave"

    private fun buildAgentPostRequest(url: String, authToken: String, jsonBody: String): Request =
        Request.Builder()
            .url(url)
            .apply { applyAgentApiHeaders(authToken) }
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

    private fun Request.Builder.applyAgentApiHeaders(authToken: String): Request.Builder {
        addHeader("Content-Type", JSON_MEDIA_TYPE)
        addHeader("Authorization", "agora token=$authToken")
        if (ConvoConfig.USE_GEELY_MULTI_API && ConvoConfig.SERVICE_NAMESPACE.isNotEmpty()) {
            addHeader("X-Service-Namespace", ConvoConfig.SERVICE_NAMESPACE)
        }
        return this
    }

    private fun buildApiHeadersJson(authToken: String): JSONObject = JSONObject().apply {
        put("Content-Type", JSON_MEDIA_TYPE)
        put("Authorization", "agora token=$authToken")
        if (ConvoConfig.USE_GEELY_MULTI_API && ConvoConfig.SERVICE_NAMESPACE.isNotEmpty()) {
            put("X-Service-Namespace", ConvoConfig.SERVICE_NAMESPACE)
        }
    }

    private fun buildJsonPayload(
        name: String,
        channel: String,
        agentRtcUid: String,
        token: String,
        labelUserId: Long,
        remoteRtcUids: List<String>,
        runtimeSalSampleUrls: Map<String, String>,
        hasIncompleteLocalRegistration: Boolean,
        lockingSessionsFromUids: Map<String, String>,
    ): JSONObject {
        val labelUserIdStr = labelUserId.toString()
        val useLockingSal = true //runtimeSalSampleUrls.isNotEmpty()
        val primaryRtcUid = lockingSessionsFromUids.values.firstOrNull()
            ?: remoteRtcUids.firstOrNull()
            ?: labelUserIdStr

        return JSONObject().apply {
            put("name", name)
            put("properties", JSONObject().apply {
                put("channel", channel)
                put("token", token)
                put("agent_rtc_uid", agentRtcUid)
                put("remote_rtc_uids", JSONArray().apply { put("*") })
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
                    if (!useLockingSal) {
                        put("sal_mode", "pre_register")
                    } else {
                        put("sal_mode", "locking")
                        put(
                            "sample_urls",
                            buildSalSampleUrlsJson(
                                runtimeSalSampleUrls = runtimeSalSampleUrls,
                                hasIncompleteLocalRegistration = hasIncompleteLocalRegistration,
                            ),
                        )
                    }
                })
                put("turn_detection", buildTurnDetectionJson())
                put("parameters", JSONObject().apply {
                    put("data_channel", "rtm")
                    put("enable_flexible", true)
                    put("enable_error_message", true)
                    put("enable_dump", true)
                    put("main", buildMainParametersJson(labelUserIdStr))
                    if (ConvoConfig.USE_GEELY_MULTI_API && useLockingSal) {
                        put(
                            "more_sal_config",
                            buildMoreSalConfigJson(
                                lockingSessionsFromUids = lockingSessionsFromUids,
                                runtimeSalSampleUrls = runtimeSalSampleUrls,
                                primaryRtcUid = primaryRtcUid,
                            ),
                        )
                    }
                    if (ConvoConfig.USE_GEELY_MULTI_API) {
                        put("turn_detector", JSONObject().apply {
                            put("disable_interrupt", true)
                        })
                    }
                    put("audio3a_downstream", JSONObject().apply {
                        put("enable_ans", false)
                        put("passthrough", true)
                    })
                    put("bvc", JSONObject().apply {
                        if (ConvoConfig.USE_PRIVATE_ENV) put("url", ConvoConfig.PRIVATE_BVC_URL)
                        put("params", JSONObject().apply {
                            put("vobvcDelay", 10)
                            put("vpbvcDelay", 10)
                            put("vpBVC", JSONObject().apply {
                                val threshold = if (ConvoConfig.IS_GLASS_SCENARIO) 0.3 else 0.45
                                put("threshold_calc_low_lower_limit", threshold)
                                put("threshold_calc_low_upper_limit", threshold)
                                put("update_similarity_threshold_low", threshold)
                                put("hop_size", 300)
                            })
                        })
                    })
                    put("stt_uploader", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("enable", true)
                            put("accessKey", ConvoConfig.STT_UPLOADER_KEY)
                            put("secretKey", ConvoConfig.STT_UPLOADER_SECRET)
                            put("region", 0)
                            put("vendor", 2)
                            put("bucket", "ndt-public")
                            put("fileNamePrefix", JSONArray().apply {
                                put("shengwen")
                                put("register")
                            })
                        })
                    })
                    if (ConvoConfig.USE_PRIVATE_ENV) {
                        put("rtc", JSONObject().apply {
                            put("domain_list", JSONArray().apply {
                                put(ConvoConfig.PRIVATE_DOMAIN_LIST)
                            })
                            put("ip_list", JSONArray().apply {
                                put(ConvoConfig.GEELY_PRIVATE_IP)
                            })
                        })
                        put("rtm", JSONObject().apply {
                            put("access_point_hosts", JSONArray().apply {
                                put(ConvoConfig.GEELY_PRIVATE_IP)
                            })
                        })
                    }
                })
            })
        }
    }

    private fun buildMainParametersJson(labelUserIdStr: String): JSONObject = JSONObject().apply {
        put("interrupt_check", JSONObject().apply {
            put("enabled", true)
            put("url", ConvoConfig.INTERRUPT_CHECK_URL)
            put("api_key", ConvoConfig.LLM_API_KEY)
            if (ConvoConfig.USE_GEELY_MULTI_API) {
                put("timeout_ms", ConvoConfig.INTERRUPT_CHECK_TIMEOUT_MS)
            } else {
                put("timeout_seconds", 5)
            }
            put("labels", JSONObject().put("userName", labelUserIdStr).apply {
                if (ConvoConfig.IS_GLASS_SCENARIO) {
                    put("channelCode", "1_jowneyTestDevice")
                }
            })
        })
        if (ConvoConfig.USE_GEELY_MULTI_API) {
            put("register", JSONObject().apply {
                put("enable", true)
                put("callback_url", ConvoConfig.PRE_REG_CALLBACK_URL)
                put("api_key", ConvoConfig.LLM_API_KEY)
                put("callback_timeout_seconds", 5.0)
                put("upload_result_timeout_seconds", 10.0)
                put("callback_max_retries", 5)
                put("gate_timeout_seconds", ConvoConfig.REGISTER_GATE_TIMEOUT_SECONDS)
                put("temp_dir", "/tmp/convoai_register")
            })
        } else {
            put("pre_register", JSONObject().apply {
                put("callback_url", ConvoConfig.PRE_REG_CALLBACK_URL)
                put("api_key", ConvoConfig.LLM_API_KEY)
                put("callback_timeout_seconds", 5.0)
                put("upload_result_timeout_seconds", 10.0)
                put("callback_max_retries", 5)
                put("temp_dir", "/tmp/convoai_pre_register")
            })
        }
    }

    private fun buildMoreSalConfigJson(
        lockingSessionsFromUids: Map<String, String>,
        runtimeSalSampleUrls: Map<String, String>,
        primaryRtcUid: String,
    ): JSONObject {
        val sessions = JSONObject()
        val effectiveBindings = if (lockingSessionsFromUids.isNotEmpty()) {
            lockingSessionsFromUids
        } else {
            runtimeSalSampleUrls.keys.associateWith { primaryRtcUid }
        }
        effectiveBindings.forEach { (speakerId, uid) ->
            if (speakerId.isNotEmpty() && uid.isNotEmpty()) {
                sessions.put(speakerId, uid)
            }
        }
        val registerUids = JSONArray().apply { put(primaryRtcUid) }
        return JSONObject().apply {
            put("locking_sessions_from_uids", sessions)
            put("register_session_count", 1)
            put("register_session_uids", registerUids)
            put("negative_locking_session_count", 1)
            put("negative_locking_session_uids", JSONArray().apply { put(primaryRtcUid) })
            put("max_session_count", ConvoConfig.SAL_MAX_SESSION_COUNT)
        }
    }

    private fun buildSalSampleUrlsJson(
        runtimeSalSampleUrls: Map<String, String>,
        hasIncompleteLocalRegistration: Boolean,
    ): JSONObject {
        Log.i(
            TAG,
            "SAL: runtime sample_urls size=${runtimeSalSampleUrls.size} keys=${runtimeSalSampleUrls.keys}",
        )
        if (runtimeSalSampleUrls.isEmpty() && hasIncompleteLocalRegistration) {
            Log.w(
                TAG,
                "SAL: 本地有人脸/声纹记录，但 sample_urls 仅在 PCM 为 http(s) 且 face URL 非空时才会带上；" +
                    "若 PCM 仍是 local:// 或未上传 OSS，云端 SAL 无法用你的注册声纹，只会用 env 预注册或实验室默认 PCM。",
            )
        }

        val out = JSONObject()
        runtimeSalSampleUrls.forEach { (faceId, pcmUrl) ->
            if (faceId.isNotEmpty() && pcmUrl.isNotEmpty()) {
                out.put(faceId, pcmUrl)
            }
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
        ConvoConfig.effectiveLlmMaxHistory().toIntOrNull()?.let { put("max_history", it) }
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
        base.put("lables", JSONObject().apply {
            put("userName", userNameForLabels)
            if (ConvoConfig.IS_GLASS_SCENARIO) {
                put("channelCode", "1_jowneyTestDevice")
            }
        })
    } catch (_: Exception) {
        JSONObject().apply {
            put("lables", JSONObject().apply {
                put("userName", userNameForLabels)
                if (ConvoConfig.IS_GLASS_SCENARIO) {
                    put("channelCode", "1_jowneyTestDevice")
                }
            })
        }
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
            val request = buildAgentPostRequest(leaveUrl(agentId), authToken, "")
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
