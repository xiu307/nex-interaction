package ai.nex.interaction.biometric

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.robotchat.facedet.FaceDetector
import com.robotchat.facedet.callback.FrameAnalysisCallback
import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import java.util.LinkedHashMap

/**
 * 只负责 facedet + CameraX：写入算法回调结果，供发送侧通过 [takeSnapshot] 取最后一帧聚合数据。
 * 不引用 RTM、不拼 JSON。
 */
class RobotFaceDetectionCollector : RobotFaceDetectionFrameProvider {

    private val stateLock = Any()
    private val latestByFaceId = LinkedHashMap<Int, FaceResult>()
    private var latestBodies: List<BodyResult> = emptyList()
    private var latestBodyFrameTimestampNs: Long = 0L

    private var faceDetector: FaceDetector? = null

    override fun takeSnapshot(): RobotFaceDetectionSnapshot {
        synchronized(stateLock) {
            return RobotFaceDetectionSnapshot(
                faces = latestByFaceId.values.toList(),
                bodies = latestBodies.toList(),
                bodyFrameTimestampNs = latestBodyFrameTimestampNs,
            )
        }
    }

    fun start(activity: AppCompatActivity, clientId: String, recordId: String) {
        stop()
        val detector = FaceDetector(ConvoFacedetDock.configForLiveSession(activity, clientId))
        detector.setRecordId(recordId)
        detector.setCallback(
            object : FrameAnalysisCallback {
                override fun onFaceResult(result: FaceResult) {
                    synchronized(stateLock) {
                        latestByFaceId[result.faceId] = result
                    }
                }

                override fun onBodyResults(bodies: List<BodyResult>, frameTimestampNs: Long) {
                    synchronized(stateLock) {
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
                    Log.d(TAG, "FaceDetector bound (collector only)")
                } catch (e: Exception) {
                    Log.e(TAG, "FaceDetector bind failed: ${e.message}")
                    detector.release()
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    fun stop() {
        try {
            faceDetector?.release()
        } catch (_: Exception) {
        }
        faceDetector = null
        synchronized(stateLock) {
            latestByFaceId.clear()
            latestBodies = emptyList()
            latestBodyFrameTimestampNs = 0L
        }
        Log.d(TAG, "Collector stopped")
    }

    fun deleteEmbeddingIfRunning(faceId: String) {
        if (faceId.isEmpty()) return
        runCatching {
            val fd = faceDetector ?: return
            val im = fd.multiPersonRecognitionManager?.getIdentityManager() ?: return
            im.deleteEmbedding(faceId)
        }.onFailure { Log.w(TAG, "deleteEmbeddingIfRunning: ${it.message}") }
    }

    fun deleteAllEmbeddingsIfRunning() {
        runCatching {
            val fd = faceDetector ?: return
            val im = fd.multiPersonRecognitionManager?.getIdentityManager() ?: return
            for (uid in im.getAllUserIds().toList()) {
                im.deleteEmbedding(uid)
            }
        }.onFailure { Log.w(TAG, "deleteAllEmbeddingsIfRunning: ${it.message}") }
    }

    fun clearFaceIfRunning(faceId: String) {
        if (faceId.isEmpty()) return
        runCatching { faceDetector?.clearFace(faceId) }
            .onFailure { Log.w(TAG, "clearFaceIfRunning: ${it.message}") }
    }

    fun clearAllFacesIfRunning() {
        runCatching { faceDetector?.clearAllFaces() }
            .onFailure { Log.w(TAG, "clearAllFacesIfRunning: ${it.message}") }
        synchronized(stateLock) { latestByFaceId.clear() }
    }

    companion object {
        private const val TAG = "RobotFaceCollector"
    }
}
