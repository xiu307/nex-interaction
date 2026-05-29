package ai.nex.interaction.biometric

import io.agora.rtm.RtmClient

/** 当前会话上下文，供 `VP_DEL_UP` USER 点对点上行（与 FaceInfo 相同 RTM 客户端与 clientId）。 */
object VoicePrintRtmSession {

    @Volatile
    var channelName: String? = null

    @Volatile
    var agentId: String? = null

    @Volatile
    var rtmClient: RtmClient? = null

    @Volatile
    var clientId: String? = null

    fun clear() {
        channelName = null
        agentId = null
        rtmClient = null
        clientId = null
    }
}
