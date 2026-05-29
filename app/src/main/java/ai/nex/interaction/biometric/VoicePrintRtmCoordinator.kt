package ai.nex.interaction.biometric

import android.util.Log
import ai.nex.interaction.session.ConversationRtmPeers
import io.agora.rtm.RtmClient

object VoicePrintRtmCoordinator {

    const val LOG_TAG = "VoicePrintRtm"
    private const val TAG = LOG_TAG

    /**
     * USER 点对点上行 `VP_DEL_UP` → `geely_rtm_server`。
     * 传输层与历史 [RobotFaceInfoRtmSender] / `ROBOT_FACE_INFO_UP` 完全一致：
     * 同一 [RtmPeerPlainTextPublisher]、同一 peer、同一 `clientId`/`recordId` 规则。
     */
    fun trySendVpDelUp(speakerId: String, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val record = BiometricSalRegistry.getVpSpeakerForDelUp(speakerId) ?: run {
            callback(false, "no VP speaker record")
            return
        }
        val session = readSession() ?: run {
            callback(false, "session not ready for VP_DEL_UP")
            return
        }
        val json = VoicePrintRtmProtocol.buildVpDelUpJson(
            clientId = session.clientId,
            recordId = session.recordId,
            agentId = session.agentId,
            registerUuid = record.registerUuid,
            speakerId = record.speakerId,
        )
        publishVpDelUp(session, json, record.speakerId, callback)
    }

    /** 模拟删除：固定样例 payload，传输层与 FaceInfo 一致。 */
    fun trySendMockVpDelUp(callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val session = readSession() ?: run {
            callback(false, "session not ready for VP_DEL_UP")
            return
        }
        val json = VoicePrintRtmProtocol.buildVpDelUpJson(
            clientId = session.clientId,
            recordId = session.recordId,
            agentId = session.agentId,
            registerUuid = VoicePrintRtmProtocol.MOCK_REGISTER_UUID,
            speakerId = VoicePrintRtmProtocol.MOCK_SPEAKER_ID,
        )
        publishVpDelUp(session, json, VoicePrintRtmProtocol.MOCK_SPEAKER_ID, callback, mock = true)
    }

    private data class VpDelUpSession(
        val client: RtmClient,
        /** 与 FaceInfo 相同：顶层 recordId = 当前 RTM Message 频道名。 */
        val recordId: String,
        val clientId: String,
        val agentId: String,
        val peerUserId: String,
    )

    private fun readSession(): VpDelUpSession? {
        val recordId = VoicePrintRtmSession.channelName?.takeIf { it.isNotEmpty() } ?: return null
        val agentId = VoicePrintRtmSession.agentId?.takeIf { it.isNotEmpty() } ?: return null
        val client = VoicePrintRtmSession.rtmClient ?: return null
        val clientId = VoicePrintRtmSession.clientId?.takeIf { it.isNotEmpty() } ?: return null
        return VpDelUpSession(
            client = client,
            recordId = recordId,
            clientId = clientId,
            agentId = agentId,
            peerUserId = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
        )
    }

    private fun publishVpDelUp(
        session: VpDelUpSession,
        json: String,
        speakerId: String,
        callback: (Boolean, String?) -> Unit,
        mock: Boolean = false,
    ) {
        val label = if (mock) "VP_DEL_UP mock" else "VP_DEL_UP"
        Log.i(
            TAG,
            "$label publish peer=${session.peerUserId} channelType=USER customType=PlainText " +
                "clientId=${session.clientId} recordId=${session.recordId} json=$json",
        )
        RtmPeerPlainTextPublisher.publish(session.client, session.peerUserId, json) { err ->
            if (err != null) {
                Log.e(
                    TAG,
                    "$label failed peer=${session.peerUserId} json=$json err=${err.message}",
                )
                callback(false, err.message)
            } else {
                Log.i(
                    TAG,
                    "$label ok peer=${session.peerUserId} speakerId=$speakerId agentId=${session.agentId} json=$json",
                )
                callback(true, null)
            }
        }
    }
}
