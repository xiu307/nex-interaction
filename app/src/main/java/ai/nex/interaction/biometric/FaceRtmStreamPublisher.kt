package ai.nex.interaction.biometric

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import ai.nex.interaction.session.ConversationRtmPeers
import io.agora.rtm.RtmClient

/**
 * 编排层：[RobotFaceDetectionCollector] 只采集，[RobotFaceInfoRtmSender] 只取快照并经 RTM 发送。
 * 须在**未占用前置相机推 RTC 自定义视频**时启动，以免与 CameraX 冲突。
 */
object FaceRtmStreamPublisher {

    private const val TAG = "FaceRtmPublisher"

    private var collector: RobotFaceDetectionCollector? = null
    private var sender: RobotFaceInfoRtmSender? = null

    @Volatile
    var debugPayloadListener: ((String) -> Unit)? = null

    fun start(
        activity: AppCompatActivity,
        rtmClient: RtmClient,
        clientId: String,
        recordId: String,
        peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
    ) {
        stopAll()
        val c = RobotFaceDetectionCollector()
        c.start(activity, clientId, recordId)
        val s = RobotFaceInfoRtmSender()
        s.start(
            frameProvider = c,
            rtmClient = rtmClient,
            clientId = clientId,
            recordId = recordId,
            peerUserId = peerUserId,
            onDebugPayload = { debugPayloadListener?.invoke(it) },
        )
        collector = c
        sender = s
        Log.d(TAG, "Face RTM uplink started (collector + sender)")
    }

    fun deleteEmbeddingIfRunning(faceId: String) {
        collector?.deleteEmbeddingIfRunning(faceId)
    }

    fun deleteAllEmbeddingsIfRunning() {
        collector?.deleteAllEmbeddingsIfRunning()
    }

    fun clearFaceIfRunning(faceId: String) {
        collector?.clearFaceIfRunning(faceId)
    }

    fun clearAllFacesIfRunning() {
        collector?.clearAllFacesIfRunning()
    }

    fun stop(activity: AppCompatActivity) {
        stopAll()
    }

    fun stopAll() {
        sender?.stop()
        sender = null
        collector?.stop()
        collector = null
        Log.d(TAG, "Face RTM uplink stopped")
    }
}
