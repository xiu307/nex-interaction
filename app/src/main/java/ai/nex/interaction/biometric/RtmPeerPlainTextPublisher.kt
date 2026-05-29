package ai.nex.interaction.biometric

import android.util.Log
import io.agora.rtm.ErrorInfo
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConstants

/**
 * 与历史 FaceInfo `ROBOT_FACE_INFO_UP` / `CovRtmManager.publishPlainTextToPeer` 一致：
 * USER 点对点、`customType = PlainText`。
 */
object RtmPeerPlainTextPublisher {

    private const val TAG = "RtmPeerPlainText"

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
        Log.i(
            TAG,
            "publish start peer=$peerUserId channelType=USER customType=PlainText msg=$message",
        )
        client.publish(peerUserId, message, options, object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                Log.i(TAG, "publish ok peer=$peerUserId response=$responseInfo")
                callback(null)
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.e(
                    TAG,
                    "publish failed peer=$peerUserId reason=${errorInfo.errorReason} info=$errorInfo",
                )
                callback(Exception(errorInfo.errorReason ?: errorInfo.toString()))
            }
        })
    }
}
