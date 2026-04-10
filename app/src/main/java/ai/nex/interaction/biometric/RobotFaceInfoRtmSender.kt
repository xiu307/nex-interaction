package ai.nex.interaction.biometric

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.nex.interaction.session.ConversationRtmPeers
import io.agora.rtm.RtmClient

/**
 * 只负责按周期从 [RobotFaceDetectionFrameProvider] 取 [RobotFaceDetectionSnapshot]（当前缓冲的快照，见 [RobotFaceDetectionSnapshot]），
 * 经 [RobotFaceRtmProtocol] 拼 `ROBOT_FACE_INFO_UP` 并发 RTM。不绑定相机、不接触 [FaceDetector]。
 * 快照里某类列表为空时，协议层将对应字段序列化为 JSON 空字符串 `""`（非 `[]`）。
 */
class RobotFaceInfoRtmSender(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    private var running = false
    private var frameProvider: RobotFaceDetectionFrameProvider? = null
    private var rtmClient: RtmClient? = null
    private var peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID
    private var clientIdStr: String = ""
    private var recordIdStr: String = ""
    private var uploadSeq: Long = 0L
    private var onDebugPayload: ((String) -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            flushAndSend()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(
        frameProvider: RobotFaceDetectionFrameProvider,
        rtmClient: RtmClient,
        clientId: String,
        recordId: String,
        peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
        onDebugPayload: ((String) -> Unit)? = null,
    ) {
        stop()
        this.running = true
        this.frameProvider = frameProvider
        this.rtmClient = rtmClient
        this.peerUserId = peerUserId
        this.clientIdStr = clientId
        this.recordIdStr = recordId
        this.onDebugPayload = onDebugPayload
        this.uploadSeq = 0L
        mainHandler.post(tickRunnable)
        Log.d(TAG, "RTM sender started (snapshot-only)")
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(tickRunnable)
        uploadSeq = 0L
        frameProvider = null
        rtmClient = null
        onDebugPayload = null
        Log.d(TAG, "RTM sender stopped")
    }

    private fun flushAndSend() {
        val client = rtmClient ?: return
        val provider = frameProvider ?: return
        val snap = provider.takeSnapshot()
        uploadSeq += 1
        val wallMs = System.currentTimeMillis()
        val json = RobotFaceRtmProtocol.buildRobotFaceInfoUpFromFacedet(
            clientId = clientIdStr,
            recordId = recordIdStr,
            faceResults = snap.faces,
            bodies = snap.bodies,
            bodyFrameTimestampNs = snap.bodyFrameTimestampNs,
            uploadSeq = uploadSeq,
            clientFlushWallMs = wallMs,
        )
        Log.d(
            TAG,
            "ROBOT_FACE_INFO_UP seq=$uploadSeq wallMs=$wallMs bodyTsNs=${snap.bodyFrameTimestampNs} " +
                "faces=${snap.faces.size} bodies=${snap.bodies.size} json=$json",
        )
        onDebugPayload?.invoke(json)
        RtmPeerPlainTextPublisher.publish(client, peerUserId, json) { e ->
            if (e != null) {
                Log.e(TAG, "RTM face uplink failed: ${e.message}")
            } else {
                Log.d(TAG, "RTM face uplink ok seq=$uploadSeq peer=$peerUserId")
            }
        }
    }

    companion object {
        private const val TAG = "RobotFaceRtmSender"
        private const val INTERVAL_MS = 100L
    }
}
