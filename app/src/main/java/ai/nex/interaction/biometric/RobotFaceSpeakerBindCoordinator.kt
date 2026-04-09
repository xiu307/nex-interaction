package ai.nex.interaction.biometric

import android.util.Log
import ai.nex.interaction.session.ConversationRtmPeers
import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.TranscriptType
import io.agora.rtm.RtmClient

/**
 * SAL [vpids_info] 与本地 [BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls] 的 faceId 一致且置信度 > 0.5 时，
 * 经 RTM 发送 [RobotFaceRtmProtocol] 的 `ROBOT_FACE_SPEAKER_BIND`（对齐场景工程对话页逻辑）。
 */
class RobotFaceSpeakerBindCoordinator {

    private val sentKeys = mutableSetOf<String>()

    fun clearDedupeState() {
        sentKeys.clear()
    }

    /**
     * @param connectionConnected 会话已连接、可与 Agent 对话时为 true（与界面层 Connected 一致）。
     */
    fun maybeSendOnUserTranscript(
        transcript: Transcript,
        connectionConnected: Boolean,
        rtmClient: RtmClient?,
        clientId: String,
    ) {
        if (transcript.type != TranscriptType.USER) return
        if (!connectionConnected) {
            Log.d(
                ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
                "skip: connectionState not Connected",
            )
            return
        }
        val vpidsInfo = transcript.vpidsInfo ?: return
        val localFaceIds = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls().keys
        val confSummary = vpidsInfo.vpidsConfidence.joinToString { "${it.speaker}=${it.confidence}" }
        Log.d(
            ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
            "check turn=${transcript.turnId} localFaceIds=$localFaceIds conf=[$confSummary]",
        )
        if (localFaceIds.isEmpty()) {
            Log.d(
                ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
                "skip: no local complete SAL face (need face OSS + PCM both)",
            )
            return
        }
        val recordId = transcript.turnId.toString()
        val rc = rtmClient ?: return
        var sent = false
        for (sc in vpidsInfo.vpidsConfidence) {
            if (sc.confidence <= 0.5) continue
            if (!localFaceIds.contains(sc.speaker)) continue
            val dedupeKey = "${transcript.turnId}_${sc.speaker}"
            if (!sentKeys.add(dedupeKey)) continue
            val json = RobotFaceRtmProtocol.buildSpeakerBindJson(
                clientId = clientId,
                recordId = recordId,
                faceId = sc.speaker,
                speakerId = sc.speaker,
            )
            sent = true
            Log.d(
                ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
                "ROBOT_FACE_SPEAKER_BIND -> peer=${ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID} $json",
            )
            RtmPeerPlainTextPublisher.publish(
                rc,
                ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
                json,
            ) { err ->
                if (err != null) {
                    Log.e(ConversationRtmPeers.LOG_TAG_SPEAKER_BIND, "RTM publish failed: ${err.message}")
                } else {
                    Log.d(
                        ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
                        "ROBOT_FACE_SPEAKER_BIND ok turn=${transcript.turnId} speaker=${sc.speaker}",
                    )
                }
            }
        }
        if (!sent) {
            Log.d(
                ConversationRtmPeers.LOG_TAG_SPEAKER_BIND,
                "skip: no row matched (need conf>0.5 AND speaker in local faceIds)",
            )
        }
    }
}
