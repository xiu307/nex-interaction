package ai.nex.interaction.biometric

import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import org.json.JSONObject

/**
 * RTM 文本正文：`ROBOT_FACE_INFO_UP` / `ROBOT_FACE_SPEAKER_BIND`。
 *
 * `payload.faces` / `payload.bodies`：**直接透传** facedet 的 [FaceResult.toJson] / [BodyResult.toJson] 解析为 JSON 数组元素，
 * 不在宿主侧按业务再排序、去重或改字段（与 Demo IDE 是否一致由 facedet 输出本身决定）。
 */
object RobotFaceRtmProtocol {

    const val TYPE_ROBOT_FACE_INFO_UP = "ROBOT_FACE_INFO_UP"
    const val TYPE_ROBOT_FACE_SPEAKER_BIND = "ROBOT_FACE_SPEAKER_BIND"

    fun buildSpeakerBindJson(
        clientId: String,
        recordId: String,
        faceId: String,
        speakerId: String,
    ): String {
        val root = JSONObject()
        root.put("clientId", clientId)
        root.put("recordId", recordId)
        root.put("type", TYPE_ROBOT_FACE_SPEAKER_BIND)
        root.put("timestamp", System.currentTimeMillis().toString())
        val payload = JSONObject()
        payload.put("faceId", faceId)
        payload.put("speakerId", speakerId)
        root.put("payload", payload)
        return root.toString()
    }

    fun buildRobotFaceInfoUpFromFacedet(
        clientId: String,
        recordId: String,
        faceResults: List<FaceResult>,
        bodies: List<BodyResult>,
        bodyFrameTimestampNs: Long = 0L,
        uploadSeq: Long,
        clientFlushWallMs: Long,
    ): String {
        // 性能关键路径：不再逐条 JSONObject(parse) 重建 facedet 结果，直接拼接 toJson() 片段。
        val facesJoined = faceResults.joinToString(",") { facedetJsonFragmentOrEmpty(it.toJson()) }
        val bodiesJoined = bodies.joinToString(",") { facedetJsonFragmentOrEmpty(it.toJson()) }
        val ts = clientFlushWallMs.toString()
        val bodyTsPart = if (bodyFrameTimestampNs != 0L) {
            ",\"bodyFrameTimestampNs\":$bodyFrameTimestampNs"
        } else {
            ""
        }
        val payloadJson =
            "{\"faces\":[$facesJoined],\"bodies\":[$bodiesJoined]$bodyTsPart,\"uploadSeq\":$uploadSeq,\"clientFlushWallMs\":$clientFlushWallMs}"
        return "{\"clientId\":${JSONObject.quote(clientId)},\"recordId\":${JSONObject.quote(recordId)},\"type\":${JSONObject.quote(TYPE_ROBOT_FACE_INFO_UP)},\"timestamp\":${JSONObject.quote(ts)},\"payload\":$payloadJson}"
    }

    private fun facedetJsonFragmentOrEmpty(raw: String): String =
        if (raw.isEmpty()) "\"\"" else raw
}
