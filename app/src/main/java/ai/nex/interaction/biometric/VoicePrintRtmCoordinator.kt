package ai.nex.interaction.biometric

import android.util.Log

object VoicePrintRtmCoordinator {

    private const val TAG = "VoicePrintRtm"

    /**
     * Message Channel 上行 `VP_DEL_UP` → speech-rtm → SAL delete_sal_speakers。
     * pending / active 均可删（下行时服务端已完成 add_sal_speakers）。
     */
    fun trySendVpDelUp(speakerId: String, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val record = BiometricSalRegistry.getVpSpeakerForDelUp(speakerId) ?: run {
            callback(false, "no VP speaker record")
            return
        }
        val channel = VoicePrintRtmSession.channelName
        val agentId = VoicePrintRtmSession.agentId
        val client = VoicePrintRtmSession.rtmClient
        val clientId = VoicePrintRtmSession.clientId
        if (channel.isNullOrEmpty() || agentId.isNullOrEmpty() || client == null || clientId.isNullOrEmpty()) {
            callback(false, "session not ready for VP_DEL_UP")
            return
        }
        val json = VoicePrintRtmProtocol.buildVpDelUpJson(
            clientId = clientId,
            agentId = agentId,
            registerUuid = record.registerUuid,
            speakerId = record.speakerId,
        )
        RtmMessageChannelPublisher.publish(client, channel, json) { err ->
            if (err != null) {
                Log.e(TAG, "VP_DEL_UP failed: ${err.message}")
                callback(false, err.message)
            } else {
                Log.i(TAG, "VP_DEL_UP sent speakerId=$speakerId agentId=$agentId")
                callback(true, null)
            }
        }
    }
}
