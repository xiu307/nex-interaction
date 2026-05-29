package ai.nex.interaction.biometric

import android.util.Log
import ai.nex.interaction.session.ConversationRtmPeers
import io.agora.rtm.RtmClient

object VoicePrintRtmCoordinator {

    const val LOG_TAG = "VoicePrintRtm"
    private const val TAG = LOG_TAG

    /**
     * USER 点对点上行 `VP_DEL_UP` → `geely_rtm_server`（与历史 FaceInfo 发送方式一致）。
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
            agentId = session.agentId,
            registerUuid = record.registerUuid,
            speakerId = record.speakerId,
        )
        publishVpDelUp(session.client, session.peerUserId, json, record.speakerId, session.agentId, callback)
    }

    /** 模拟删除：固定样例 payload，走与真实删除相同的 RTM 点对点 publish。 */
    fun trySendMockVpDelUp(callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val session = readSession() ?: run {
            callback(false, "session not ready for VP_DEL_UP")
            return
        }
        val json = VoicePrintRtmProtocol.buildVpDelUpJson(
            clientId = session.clientId,
            agentId = session.agentId,
            registerUuid = VoicePrintRtmProtocol.MOCK_REGISTER_UUID,
            speakerId = VoicePrintRtmProtocol.MOCK_SPEAKER_ID,
        )
        publishVpDelUp(
            client = session.client,
            peerUserId = session.peerUserId,
            json = json,
            speakerId = VoicePrintRtmProtocol.MOCK_SPEAKER_ID,
            agentId = session.agentId,
            callback = callback,
            mock = true,
        )
    }

    private data class VpDelUpSession(
        val client: RtmClient,
        val clientId: String,
        val agentId: String,
        val peerUserId: String,
    )

    private fun readSession(): VpDelUpSession? {
        val agentId = VoicePrintRtmSession.agentId?.takeIf { it.isNotEmpty() } ?: return null
        val client = VoicePrintRtmSession.rtmClient ?: return null
        val clientId = VoicePrintRtmSession.clientId?.takeIf { it.isNotEmpty() } ?: return null
        return VpDelUpSession(
            client = client,
            clientId = clientId,
            agentId = agentId,
            peerUserId = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
        )
    }

    private fun publishVpDelUp(
        client: RtmClient,
        peerUserId: String,
        json: String,
        speakerId: String,
        agentId: String,
        callback: (Boolean, String?) -> Unit,
        mock: Boolean = false,
    ) {
        val label = if (mock) "VP_DEL_UP mock" else "VP_DEL_UP"
        Log.i(TAG, "$label publish peer=$peerUserId channelType=USER customType=PlainText json=$json")
        RtmPeerPlainTextPublisher.publish(client, peerUserId, json) { err ->
            if (err != null) {
                Log.e(TAG, "$label failed peer=$peerUserId json=$json err=${err.message}")
                callback(false, err.message)
            } else {
                Log.i(TAG, "$label ok peer=$peerUserId speakerId=$speakerId agentId=$agentId json=$json")
                callback(true, null)
            }
        }
    }
}
