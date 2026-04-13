package ai.nex.interaction.biometric

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import ai.nex.interaction.ui.widget.DebugOverlayView
import com.robotchat.facedet.FaceDetector
import com.robotchat.facedet.callback.FrameAnalysisCallback
import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import java.util.ArrayList
import java.util.LinkedHashMap

/**
 * **采集线**：facedet + CameraX，结果写入 [RobotFaceSnapshotHub]；与 RTM 上行共用同一缓冲。
 *
 * 可选 [previewView] / [debugOverlay]：与 face-detc-java MainActivity「实时」Tab 一致，且与 [hub] 中数据同源；
 * 悬浮窗里的 JSON 仍由 [RobotFaceInfoRtmSender] 对 [RobotFaceSnapshotSource.copySnapshot] 调用
 * [RobotFaceRtmProtocol.buildRobotFaceInfoUpFromFacedet] 生成，保证**预览画面 + 叠加层 + RTM 正文**一致。
 */
class RobotFaceDetectionCollector(
    private val hub: RobotFaceSnapshotHub,
) {

    private var faceDetector: FaceDetector? = null
    private val overlayLastByFaceId = LinkedHashMap<Int, FaceResult>()

    fun start(
        activity: AppCompatActivity,
        clientId: String,
        recordId: String,
        previewView: PreviewView? = null,
        debugOverlay: DebugOverlayView? = null,
    ) {
        stop()
        overlayLastByFaceId.clear()
        val detector = FaceDetector(ConvoFacedetDock.configForLiveSession(activity, clientId))
        detector.setRecordId(recordId)
        val overlay = debugOverlay
        detector.setCallback(
            object : FrameAnalysisCallback {
                override fun onFaceResult(result: FaceResult) {
                    hub.onFaceResult(result)
                    if (overlay != null) {
                        overlayLastByFaceId[result.faceId] = result
                        overlay.updateFaces(ArrayList(overlayLastByFaceId.values))
                    }
                }

                override fun onTrackingStatus(activeFaceIds: List<Int>, activeBodyIds: List<Int>) {
                    if (overlay != null) {
                        val activeSet = activeFaceIds.toHashSet()
                        overlayLastByFaceId.keys.retainAll(activeSet)
                        overlay.pruneStale(activeSet)
                    }
                }

                override fun onBodyResults(bodies: List<BodyResult>, frameTimestampNs: Long) {
                    hub.onBodyResults(bodies, frameTimestampNs)
                    overlay?.updateBodies(bodies)
                }

                override fun onFaceLandmarks(
                    faceId: Int,
                    landmarks: Array<FloatArray>,
                    frameW: Int,
                    frameH: Int,
                    rotationDegrees: Int,
                ) {
                    overlay?.setFaceLandmarks(faceId, landmarks, frameW, frameH, rotationDegrees)
                }

                override fun onNoFaceDetected(timestampMs: Long) {
                    hub.onNoFaceDetected(timestampMs)
                    if (overlay != null) {
                        overlayLastByFaceId.clear()
                        overlay.clear()
                    }
                }
            },
        )

        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    if (previewView != null) {
                        detector.bindToCameraX(provider, activity, activity, previewView)
                    } else {
                        detector.bindToCameraX(provider, activity, activity)
                    }
                    detector.start()
                    faceDetector = detector
                    Log.d(TAG, "FaceDetector bound (hub + optional preview)")
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
        overlayLastByFaceId.clear()
        hub.resetAllBuffers()
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
        hub.clearFaceTrackBuffers()
    }

    companion object {
        private const val TAG = "RobotFaceCollector"
    }
}
