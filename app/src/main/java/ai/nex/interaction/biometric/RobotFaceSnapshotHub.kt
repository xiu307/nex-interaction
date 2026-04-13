package ai.nex.interaction.biometric

import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult
import java.util.LinkedHashMap

/**
 * 采集线与上传线的**唯一交汇点**：facedet 回调只写入本类；[RobotFaceInfoRtmSender] 只调用 [copySnapshot] 读取。
 * 二者不互相引用对方类型，避免「取一帧就立刻上传」式耦合。
 */
class RobotFaceSnapshotHub : RobotFaceSnapshotSource {

    private val stateLock = Any()
    private val latestByFaceId = LinkedHashMap<Int, FaceResult>()
    private var latestBodies: List<BodyResult> = emptyList()
    private var latestBodyFrameTimestampNs: Long = 0L

    fun onFaceResult(result: FaceResult) {
        synchronized(stateLock) {
            latestByFaceId[result.faceId] = result
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
            latestByFaceId.entries.removeIf { (_, fr) -> fr.frameTimestampNs < tsNs }
            if (latestBodyFrameTimestampNs < tsNs) {
                latestBodies = emptyList()
                latestBodyFrameTimestampNs = 0L
            }
        }
    }

    fun clearFaceTrackBuffers() {
        synchronized(stateLock) {
            latestByFaceId.clear()
        }
    }

    fun resetAllBuffers() {
        synchronized(stateLock) {
            latestByFaceId.clear()
            latestBodies = emptyList()
            latestBodyFrameTimestampNs = 0L
        }
    }

    override fun copySnapshot(): RobotFaceDetectionSnapshot {
        synchronized(stateLock) {
            return RobotFaceDetectionSnapshot(
                faces = latestByFaceId.values.toList(),
                bodies = latestBodies.toList(),
                bodyFrameTimestampNs = latestBodyFrameTimestampNs,
            )
        }
    }
}
