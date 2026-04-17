package ai.nex.interaction.biometric

import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.nex.interaction.session.ConversationRtmPeers
import io.agora.rtm.RtmClient
import org.json.JSONObject

/**
 * **上传线**：按周期从 [RobotFaceSnapshotSource] 拷贝 [RobotFaceDetectionSnapshot]，经 [RobotFaceRtmProtocol] 发 RTM。
 * 不绑定相机、不接触 [FaceDetector]、不依赖采集器类名。
 */
class RobotFaceInfoRtmSender(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    private var running = false
    private var snapshotSource: RobotFaceSnapshotSource? = null
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
        snapshotSource: RobotFaceSnapshotSource,
        rtmClient: RtmClient,
        clientId: String,
        recordId: String,
        peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
        onDebugPayload: ((String) -> Unit)? = null,
    ) {
        stop()
        this.running = true
        this.snapshotSource = snapshotSource
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
        snapshotSource = null
        rtmClient = null
        onDebugPayload = null
        Log.d(TAG, "RTM sender stopped")
    }

    private fun flushAndSend() {
        val client = rtmClient ?: return
        val source = snapshotSource ?: return
        val snap = source.copySnapshot()
        uploadSeq += 1
        val wallMs = System.currentTimeMillis()
        val rawJson = RobotFaceRtmProtocol.buildRobotFaceInfoUpFromFacedet(
            clientId = clientIdStr,
            recordId = recordIdStr,
            faceResults = snap.faces,
            bodies = snap.bodies,
            bodyFrameTimestampNs = snap.bodyFrameTimestampNs,
            uploadSeq = uploadSeq,
            clientFlushWallMs = wallMs,
        )
        val json = rewriteFaceIdsToUserIds(rawJson)
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

    private fun rewriteFaceIdsToUserIds(rawJson: String): String {
        return runCatching {
            val root = JSONObject(rawJson)
            val payload = root.optJSONObject("payload") ?: return rawJson
            val faces = payload.optJSONArray("faces") ?: return rawJson
            for (i in 0 until faces.length()) {
                val item = faces.optJSONObject(i) ?: continue
                val rawFaceId = item.opt("faceId")?.toString().orEmpty()
                if (rawFaceId.isEmpty()) continue
                val userId = BiometricSalRegistry.resolveUserIdByFaceId(rawFaceId) ?: continue
                item.put("faceId", userId)
            }
            root.toString()
        }.getOrElse { rawJson }
    }
}
