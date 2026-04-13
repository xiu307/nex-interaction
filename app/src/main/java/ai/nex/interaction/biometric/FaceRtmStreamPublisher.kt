package ai.nex.interaction.biometric

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import ai.nex.interaction.session.ConversationRtmPeers
import ai.nex.interaction.ui.widget.DebugOverlayView
import io.agora.rtm.RtmClient

/**
 * 编排层：[RobotFaceSnapshotHub] 为采集/上传唯一共享缓冲；[RobotFaceDetectionCollector] 只写、[RobotFaceInfoRtmSender] 只读。
 * 须在**未占用前置相机推 RTC 自定义视频**时启动，以免与 CameraX 冲突。
 *
 * [previewView] / [debugOverlay] 与上行共用同一 [RobotFaceDetectionCollector] 与 Hub，悬浮窗 JSON 与
 * [RobotFaceRtmProtocol.buildRobotFaceInfoUpFromFacedet] 为**同一份快照**序列化结果，避免「第二套检测器」与 RTM 不一致。
 */
object FaceRtmStreamPublisher {

    private const val TAG = "FaceRtmPublisher"

    private var hub: RobotFaceSnapshotHub? = null
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
        previewView: PreviewView? = null,
        debugOverlay: DebugOverlayView? = null,
    ) {
        stopAll()
        val h = RobotFaceSnapshotHub()
        val c = RobotFaceDetectionCollector(h)
        c.start(activity, clientId, recordId, previewView, debugOverlay)
        val s = RobotFaceInfoRtmSender()
        s.start(
            snapshotSource = h,
            rtmClient = rtmClient,
            clientId = clientId,
            recordId = recordId,
            peerUserId = peerUserId,
            onDebugPayload = { debugPayloadListener?.invoke(it) },
        )
        hub = h
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
        hub = null
        Log.d(TAG, "Face RTM uplink stopped")
    }
}
