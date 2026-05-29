package ai.nex.interaction.biometric

import io.agora.rtm.RtmClient

/** 当前会话上下文，供 `VP_DEL_UP` Message Channel 上行。 */
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
