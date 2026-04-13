package ai.nex.interaction.biometric

import com.robotchat.facedet.model.BodyResult
import com.robotchat.facedet.model.FaceResult

/** 发送侧只读快照：某一时刻算法缓冲的「当前最后一帧」聚合结果（人脸按 track 去重、人体为上一帧列表）。 */
data class RobotFaceDetectionSnapshot(
    val faces: List<FaceResult>,
    val bodies: List<BodyResult>,
    val bodyFrameTimestampNs: Long,
)

/** 上传线只依赖此接口，不依赖 [RobotFaceDetectionCollector]。 */
fun interface RobotFaceSnapshotSource {
    fun copySnapshot(): RobotFaceDetectionSnapshot
}
