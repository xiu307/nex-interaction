package ai.nex.interaction.biometric

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.robotchat.facedet.FaceDetector
import com.robotchat.facedet.FaceDetectorConfig
import com.robotchat.facedet.callback.FrameAnalysisCallback
import com.robotchat.facedet.model.FaceResult

/**
 * facedet（[app/libs/facedet-release.aar]）对接层，语义对齐 Android `ConvoFacedetDock` / face-detc-java Demo。
 *
 * ## 与 face-detc-java 新架构的对接要点（功能 + 不崩）
 *
 * 1. **AAR + 宿主依赖**：`DetectorPipeline.initialize` 会建 [MultiPersonRecognitionManager]，其依赖
 *    InsightFace **`models/w600k_mbf.onnx`**（ONNX Runtime，宿主 `build.gradle` 已显式依赖 `onnxruntime-android`）
 *    与 MediaPipe（`models` 下 `.task`）。若 AAR 未打入这些 assets，初始化失败，表现为「识别模块未就绪」，
 *    见 [FaceDetector.getLastRecognitionInitFailureMessage]。
 * 2. **打 AAR**：在 `face-detc-java` 根目录执行 `./gradlew :facedet:assembleRelease`。
 *    `downloadFacedetModels` 会拉取 `.task` 与 ONNX；**w600k_mbf.onnx** 任选其一：
 *    - 放到 `facedet/vendor/w600k_mbf.onnx`，或
 *    - `gradle.properties` 中 `FACEDET_RECOGNITION_ONNX_URL` / `FACEDET_RECOGNITION_ONNX_FILE`。
 * 3. **注册页**：[FaceDetectorConfig.skipLiveMediaPipeFacePipeline] = true，不创建 LIVE_STREAM MediaPipe，
 *    避免部分机型 native 崩溃；**相册/视频注册**仍走 [FaceLandmarkerPhoto]（VIDEO）+ InsightFace ONNX + Room，与实时会话配置分离。
 * 4. 将生成的 `facedet/build/outputs/aar/facedet-release.aar` 覆盖本模块 `app/libs/facedet-release.aar` 后重编宿主。
 */
object ConvoFacedetDock {

    private const val TAG = "ConvoFacedetDock"

    fun stableDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun applyMainActivityDemoPipelineFields(c: FaceDetectorConfig) {
        c.recordId = ""
        c.posePipelineEnabled = true
        c.faceRecognitionEnabled = true
        c.autoRegisterEnabled = true
        c.autoRegisterMinFrames = 30
        c.autoRegisterMinFaceSize = 40
        c.useOpenCvHeadPose = false
    }

    @Suppress("UNUSED_PARAMETER")
    fun configForBiometricRegistration(context: Context): FaceDetectorConfig {
        return FaceDetectorConfig().apply {
            deviceId = "sdk_demo"
            applyMainActivityDemoPipelineFields(this)
            // 注册页仅需人脸：关 Pose；不创建 LIVE_STREAM 人脸图（避免 native SIGSEGV），视频注册走 VIDEO 模式 FaceLandmarkerPhoto
            posePipelineEnabled = false
            mediapipeForceCpuDelegate = true
            skipLiveMediaPipeFacePipeline = true
            enrollmentSimilarityThreshold = 0.78f
        }
    }

    /**
     * 动态实时注册需要 CameraX 分析链路（DetectorPipeline.getAnalyzer），因此不能开启
     * skipLiveMediaPipeFacePipeline（该模式只用于离线视频/图片注册）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun configForBiometricLiveEnrollment(context: Context): FaceDetectorConfig {
        return FaceDetectorConfig().apply {
            deviceId = "sdk_demo"
            applyMainActivityDemoPipelineFields(this)
            posePipelineEnabled = false
            mediapipeForceCpuDelegate = true
            skipLiveMediaPipeFacePipeline = false
            enrollmentSimilarityThreshold = 0.78f
        }
    }

    /**
     * @param deviceIdForReport 与 RTC 本端 UID / LLM `lables.userName` 一致（勿再用 [stableDeviceId]），便于与 RTM 顶层 clientId 对齐。
     */
    fun configForLiveSession(context: Context, deviceIdForReport: String): FaceDetectorConfig {
        return FaceDetectorConfig().apply {
            deviceId = deviceIdForReport
            applyMainActivityDemoPipelineFields(this)
            poseEmaAlpha = 0.7f
            poseWindowSize = 3
        }
    }

    /**
     * 单条删除时同步清理：
     * 1. 人脸库 **embedding**（[IdentityManager.deleteEmbedding]，与旧「清除注册」一致）；
     * 2. pipeline **跟踪状态**（[FaceDetector.clearFace]）。
     *
     * 先处理 [FaceRtmStreamPublisher]（内部 [RobotFaceDetectionCollector]）中正在运行的实例，再对注册用临时 [FaceDetector] 执行（无实时预览时也能删库）。
     */
    fun clearFacePipelineState(context: Context, faceId: String) {
        if (faceId.isEmpty()) return
        FaceRtmStreamPublisher.deleteEmbeddingIfRunning(faceId)
        FaceRtmStreamPublisher.clearFaceIfRunning(faceId)
        runWithEphemeralRegistrationDetector(context) { fd ->
            runCatching {
                fd.multiPersonRecognitionManager?.getIdentityManager()?.deleteEmbedding(faceId)
            }.onFailure { Log.w(TAG, "ephemeral deleteEmbedding: ${it.message}") }
            runCatching { fd.clearFace(faceId) }
        }
    }

    /**
     * 全量清理：删除人脸库中全部 embedding + pipeline 软重置（与旧「清除注册」删库 + [clearAllFaces] 一致）。
     */
    fun clearAllFacesPipelineState(context: Context) {
        FaceRtmStreamPublisher.deleteAllEmbeddingsIfRunning()
        FaceRtmStreamPublisher.clearAllFacesIfRunning()
        runWithEphemeralRegistrationDetector(context) { fd ->
            runCatching {
                val im = fd.multiPersonRecognitionManager?.getIdentityManager()
                if (im != null) {
                    for (uid in im.getAllUserIds().toList()) {
                        im.deleteEmbedding(uid)
                    }
                }
            }.onFailure { Log.w(TAG, "ephemeral deleteAllEmbeddings: ${it.message}") }
            runCatching { fd.clearAllFaces() }
        }
    }

    private inline fun runWithEphemeralRegistrationDetector(
        context: Context,
        block: (FaceDetector) -> Unit,
    ) {
        val app = context.applicationContext
        var fd: FaceDetector? = null
        try {
            fd = FaceDetector(configForBiometricRegistration(context)).apply {
                setCallback(
                    object : FrameAnalysisCallback {
                        override fun onFaceResult(result: FaceResult) {}
                    },
                )
                start()
                prepareEngine(app)
                block(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ephemeral facedet: ${e.message}", e)
        } finally {
            runCatching {
                fd?.unbindCamera()
                fd?.release()
            }
        }
    }
}
