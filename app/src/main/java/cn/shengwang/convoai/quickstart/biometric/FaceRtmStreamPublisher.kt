package cn.shengwang.convoai.quickstart.biometric

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.robotchat.facedet.FaceDetector
import com.robotchat.facedet.callback.FrameAnalysisCallback
import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import cn.shengwang.convoai.quickstart.session.ConversationRtmPeers
import io.agora.rtm.RtmClient
import java.util.LinkedHashMap

/**
 * 实时 facedet → `ROBOT_FACE_INFO_UP` 经 RTM 发往 [peerUserId]（默认 geely_rtm_server）。
 * 须在**未占用前置相机推 RTC 自定义视频**时启动，以免与 CameraX 冲突。
 */
object FaceRtmStreamPublisher {

    private const val TAG = "FaceRtmPublisher"
    private const val INTERVAL_MS = 100L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityRef: AppCompatActivity? = null
    private var faceDetector: FaceDetector? = null
    private var rtmClient: RtmClient? = null
    private var peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID
    private var clientIdStr: String = ""
    private var recordIdStr: String = ""

    private val latestByFaceId = LinkedHashMap<Int, FaceResult>()
    private val bodiesLock = Any()
    private var latestBodies: List<BodyResult> = emptyList()
    private var latestBodyFrameTimestampNs: Long = 0L
    private var uploadSeq: Long = 0L

    @Volatile
    var debugPayloadListener: ((String) -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (activityRef == null) return
            flushAndSend()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(
        activity: AppCompatActivity,
        rtmClient: RtmClient,
        clientId: String,
        recordId: String,
        peerUserId: String = ConversationRtmPeers.GEELY_RTM_SERVER_USER_ID,
    ) {
        stopAll()
        this.rtmClient = rtmClient
        this.peerUserId = peerUserId
        this.clientIdStr = clientId
        this.recordIdStr = recordId
        activityRef = activity

        val detector = FaceDetector(ConvoFacedetDock.configForLiveSession(activity, clientId))
        detector.setRecordId(recordId)
        detector.setCallback(
            object : FrameAnalysisCallback {
                override fun onFaceResult(result: FaceResult) {
                    synchronized(latestByFaceId) {
                        latestByFaceId[result.faceId] = result
                    }
                }

                override fun onBodyResults(bodies: List<BodyResult>, frameTimestampNs: Long) {
                    synchronized(bodiesLock) {
                        latestBodies = ArrayList(bodies)
                        latestBodyFrameTimestampNs = frameTimestampNs
                    }
                }
            },
        )

        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    detector.bindToCameraX(provider, activity, activity)
                    detector.start()
                    faceDetector = detector
                    Log.d(TAG, "FaceDetector bound for RTM uplink")
                } catch (e: Exception) {
                    Log.e(TAG, "FaceDetector bind failed: ${e.message}")
                    detector.release()
                }
            },
            ContextCompat.getMainExecutor(activity),
        )

        mainHandler.post(tickRunnable)
        Log.d(TAG, "Face RTM uplink (facedet) started")
    }

    /** 从人脸库（ArcFace embedding）删除指定 userId，与注册时使用的 id 一致（如 `person_1`）。 */
    fun deleteEmbeddingIfRunning(faceId: String) {
        if (faceId.isEmpty()) return
        runCatching {
            val fd = faceDetector ?: return
            val im = fd.multiPersonRecognitionManager?.getIdentityManager() ?: return
            im.deleteEmbedding(faceId)
        }.onFailure { Log.w(TAG, "deleteEmbeddingIfRunning: ${it.message}") }
    }

    /** 删除人脸库中全部已登记用户（全量删 embedding）。 */
    fun deleteAllEmbeddingsIfRunning() {
        runCatching {
            val fd = faceDetector ?: return
            val im = fd.multiPersonRecognitionManager?.getIdentityManager() ?: return
            for (uid in im.getAllUserIds().toList()) {
                im.deleteEmbedding(uid)
            }
        }.onFailure { Log.w(TAG, "deleteAllEmbeddingsIfRunning: ${it.message}") }
    }

    /**
     * 实时 pipeline 运行时清除指定 faceId 的跟踪状态（与 [com.robotchat.facedet.FaceDetector.clearFace] 一致）。
     */
    fun clearFaceIfRunning(faceId: String) {
        if (faceId.isEmpty()) return
        runCatching { faceDetector?.clearFace(faceId) }
            .onFailure { Log.w(TAG, "clearFaceIfRunning: ${it.message}") }
    }

    /** 软重置当前实时 pipeline 的所有人脸状态（不释放相机与模型）。 */
    fun clearAllFacesIfRunning() {
        runCatching { faceDetector?.clearAllFaces() }
            .onFailure { Log.w(TAG, "clearAllFacesIfRunning: ${it.message}") }
        synchronized(latestByFaceId) { latestByFaceId.clear() }
    }

    /** 与旧 `stop(activity)` 兼容：释放相机与定时器。 */
    fun stop(activity: AppCompatActivity) {
        stopAll()
        if (activityRef === activity) activityRef = null
    }

    /** 不依赖 Activity 引用，供 ViewModel 挂断时调用。 */
    fun stopAll() {
        mainHandler.removeCallbacks(tickRunnable)
        synchronized(latestByFaceId) { latestByFaceId.clear() }
        synchronized(bodiesLock) {
            latestBodies = emptyList()
            latestBodyFrameTimestampNs = 0L
        }
        uploadSeq = 0L
        try {
            faceDetector?.release()
        } catch (_: Exception) {
        }
        faceDetector = null
        activityRef = null
        rtmClient = null
        Log.d(TAG, "Face RTM uplink stopped")
    }

    private fun flushAndSend() {
        val client = rtmClient ?: return
        val faces = synchronized(latestByFaceId) {
            latestByFaceId.values.toList()
        }
        val (bodies, bodyTs) = synchronized(bodiesLock) {
            latestBodies to latestBodyFrameTimestampNs
        }
        if (faces.isEmpty() && bodies.isEmpty()) return
        uploadSeq += 1
        val wallMs = System.currentTimeMillis()
        val json = RobotFaceRtmProtocol.buildRobotFaceInfoUpFromFacedet(
            clientId = clientIdStr,
            recordId = recordIdStr,
            faceResults = faces,
            bodies = bodies,
            bodyFrameTimestampNs = bodyTs,
            uploadSeq = uploadSeq,
            clientFlushWallMs = wallMs,
        )
        Log.d(
            TAG,
            "ROBOT_FACE_INFO_UP seq=$uploadSeq wallMs=$wallMs bodyTsNs=$bodyTs " +
                "faces=${faces.size} bodies=${bodies.size} json=$json",
        )
        debugPayloadListener?.invoke(json)
        RtmPeerPlainTextPublisher.publish(client, peerUserId, json) { e ->
            if (e != null) {
                Log.e(TAG, "RTM face uplink failed: ${e.message}")
            } else {
                Log.d(TAG, "RTM face uplink ok seq=$uploadSeq peer=$peerUserId")
            }
        }
    }
}
