package ai.nex.interaction.biometric

import org.json.JSONObject

/** 吉利声纹 RTM（端口修改.md）：`VP_REGISTER_DOWN` / `VP_DEL_UP`。 */
object VoicePrintRtmProtocol {

    const val TYPE_VP_REGISTER_DOWN = "VP_REGISTER_DOWN"
    const val TYPE_VP_DEL_UP = "VP_DEL_UP"

    /** 单点测试用固定样例（来自历史 VP_REGISTER_DOWN / VP_DEL_UP 日志）。 */
    const val MOCK_REGISTER_UUID = "8e40ea93-fa43-4a7f-bdb1-8c3ae8a6756c"
    const val MOCK_SPEAKER_ID = "318679079723208704"

    data class VpRegisterDownSpeaker(
        val registerUuid: String,
        val rtcUid: String,
        val sampleUrl: String,
        val speakerId: String,
    )

    fun parseVpRegisterDownSpeakers(message: Map<String, Any>): List<VpRegisterDownSpeaker> {
        val payload = message["payload"] as? Map<*, *> ?: return emptyList()
        val speakers = payload["speakers"] as? List<*> ?: return emptyList()
        return speakers.mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            val registerUuid = row["register_uuid"]?.toString()?.trim().orEmpty()
            val rtcUid = row["rtc_uid"]?.toString()?.trim().orEmpty()
            val sampleUrl = row["sample_url"]?.toString()?.trim().orEmpty()
            val speakerId = row["speaker_id"]?.toString()?.trim().orEmpty()
            if (registerUuid.isEmpty() || rtcUid.isEmpty() || sampleUrl.isEmpty() || speakerId.isEmpty()) {
                null
            } else {
                VpRegisterDownSpeaker(registerUuid, rtcUid, sampleUrl, speakerId)
            }
        }
    }

    fun buildVpDelUpJson(
        clientId: String,
        recordId: String,
        agentId: String,
        registerUuid: String,
        speakerId: String,
    ): String {
        val root = JSONObject()
        root.put("clientId", clientId)
        root.put("recordId", recordId)
        root.put("type", TYPE_VP_DEL_UP)
        root.put("timestamp", System.currentTimeMillis().toString())
        val payload = JSONObject()
        payload.put("agent_id", agentId)
        payload.put("register_uuid", registerUuid)
        payload.put("speaker_id", speakerId)
        root.put("payload", payload)
        return root.toString()
    }
}
