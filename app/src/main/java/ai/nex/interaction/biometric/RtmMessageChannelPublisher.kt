package ai.nex.interaction.biometric

import io.agora.rtm.ErrorInfo
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConstants

/** RTM Message Channel（与 [ai.conv.core.convoai.ConversationalAIAPIImpl.subscribeMessage] 同频道名）。 */
object RtmMessageChannelPublisher {

    fun publish(
        client: RtmClient,
        channelName: String,
        message: String,
        callback: (Exception?) -> Unit,
    ) {
        val options = PublishOptions().apply {
            setChannelType(RtmConstants.RtmChannelType.MESSAGE)
            customType = "PlainText"
        }
        client.publish(channelName, message, options, object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                callback(null)
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                callback(Exception(errorInfo.errorReason ?: errorInfo.toString()))
            }
        })
    }
}
