package ai.nex.interaction.biometric

import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult

/**
 * 采集线与上传线的**唯一交汇点**：facedet 回调只写入本类；[RobotFaceInfoRtmSender] 只调用 [copySnapshot] 读取。
 * 二者不互相引用对方类型，避免「取一帧就立刻上传」式耦合。
 */
class RobotFaceSnapshotHub : RobotFaceSnapshotSource {

    private val stateLock = Any()
    // IMPORTANT: keep full-frame face list semantics.
    // Do NOT switch back to Map keyed by faceId, or multi-person same-frame results can be overwritten.
    private var latestFaces: List<FaceResult> = emptyList()
    private var latestFaceFrameTimestampNs: Long = 0L
    private var latestBodies: List<BodyResult> = emptyList()
    private var latestBodyFrameTimestampNs: Long = 0L

    fun onFaceResult(result: FaceResult) {
        synchronized(stateLock) {
            // Keep all faces from the newest frame timestamp for RTM uplink passthrough consistency.
            when {
                latestFaceFrameTimestampNs == 0L || result.frameTimestampNs > latestFaceFrameTimestampNs -> {
                    latestFaceFrameTimestampNs = result.frameTimestampNs
                    latestFaces = arrayListOf(result)
                }
                result.frameTimestampNs == latestFaceFrameTimestampNs -> {
                    val merged = ArrayList<FaceResult>(latestFaces.size + 1)
                    merged.addAll(latestFaces)
                    merged.add(result)
                    latestFaces = merged
                }
                else -> Unit
            }
        }
    }

    fun onBodyResults(bodies: List<BodyResult>, frameTimestampNs: Long) {
        synchronized(stateLock) {
            latestBodies = ArrayList(bodies)
            latestBodyFrameTimestampNs = frameTimestampNs
        }
    }

    fun onNoFaceDetected(timestampMs: Long) {
        val tsNs = timestampMs * 1_000_000L
        synchronized(stateLock) {
            if (latestFaceFrameTimestampNs < tsNs) {
                latestFaces = emptyList()
                latestFaceFrameTimestampNs = 0L
            }
            if (latestBodyFrameTimestampNs < tsNs) {
                latestBodies = emptyList()
                latestBodyFrameTimestampNs = 0L
            }
        }
    }

    fun clearFaceTrackBuffers() {
        synchronized(stateLock) {
            latestFaces = emptyList()
            latestFaceFrameTimestampNs = 0L
        }
    }

    fun resetAllBuffers() {
        synchronized(stateLock) {
            latestFaces = emptyList()
            latestFaceFrameTimestampNs = 0L
            latestBodies = emptyList()
            latestBodyFrameTimestampNs = 0L
        }
    }

    override fun copySnapshot(): RobotFaceDetectionSnapshot {
        synchronized(stateLock) {
            return RobotFaceDetectionSnapshot(
                faces = latestFaces.toList(),
                bodies = latestBodies.toList(),
                bodyFrameTimestampNs = latestBodyFrameTimestampNs,
            )
        }
    }
}
