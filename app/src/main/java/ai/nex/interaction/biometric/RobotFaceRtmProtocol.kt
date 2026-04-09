package ai.nex.interaction.biometric

import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import org.json.JSONObject

/** RTM 业务 JSON：`ROBOT_FACE_INFO_UP` / `ROBOT_FACE_SPEAKER_BIND`（与 Android convoai 一致）。 */
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
