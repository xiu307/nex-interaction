package ai.nex.interaction.biometric

import io.agora.rtm.ErrorInfo
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConstants

/**
 * USER 点对点 RTM 文本消息（与历史 FaceInfo `ROBOT_FACE_INFO_UP` 一致）。
 */
object RtmPeerPlainTextPublisher {

    fun publish(
        client: RtmClient,
        peerUserId: String,
        message: String,
        callback: (Exception?) -> Unit,
    ) {
        val options = PublishOptions().apply {
            setChannelType(RtmConstants.RtmChannelType.USER)
            customType = "PlainText"
        }
        client.publish(peerUserId, message, options, object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                callback(null)
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                callback(Exception(errorInfo.errorReason ?: errorInfo.toString()))
            }
        })
    }
}
