package cn.shengwang.convoai.quickstart.biometric

import android.content.Context
import android.provider.Settings
import com.robotchat.facedet.FaceDetectorConfig

/**
 * facedet（[app/libs/facedet-release.aar]）对接层，语义对齐 Android `ConvoFacedetDock` / face-detc-java Demo。
 *
 * ## 与 face-detc-java 新架构的对接要点（功能 + 不崩）
 *
 * 1. **AAR 必须带 assets**：`DetectorPipeline.initialize` 会先建 [MultiPersonRecognitionManager]，
 *    其依赖 **ArcFace**（`models/arcface.tflite`）与 MediaPipe（`models` 目录下 `.task` 模型）。若 AAR 未打入这些文件，
 *    初始化会失败，宿主表现为「识别模块未就绪」且 [FaceDetector.getLastRecognitionInitFailureMessage] 有具体原因。
 * 2. **打 AAR**：在 `face-detc-java` 根目录执行 `./gradlew :facedet:assembleRelease`。
 *    构建会跑 `downloadFacedetModels`（已挂到 `preBuild`）并合并到 AAR；**ArcFace** 需任选其一：
 *    - 将团队提供的 `arcface.tflite` 放到 `facedet/vendor/arcface.tflite`，或
 *    - 在 `face-detc-java/gradle.properties` 设置 `FACEDET_ARCFACE_TFLITE_URL` / `FACEDET_ARCFACE_TFLITE_FILE`。
 * 3. **注册页**：[FaceDetectorConfig.skipLiveMediaPipeFacePipeline] = true，不创建 LIVE_STREAM MediaPipe，
 *    避免部分机型 native 崩溃；**相册/视频注册**仍走 [FaceLandmarkerPhoto]（VIDEO）+ ArcFace + Room，与实时会话配置分离。
 * 4. 将生成的 `facedet/build/outputs/aar/facedet-release.aar` 覆盖本模块 `app/libs/facedet-release.aar` 后重编宿主。
 */
object ConvoFacedetDock {

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

    fun configForLiveSession(context: Context): FaceDetectorConfig {
        return FaceDetectorConfig().apply {
            deviceId = stableDeviceId(context)
            applyMainActivityDemoPipelineFields(this)
            poseEmaAlpha = 0.7f
            poseWindowSize = 3
        }
    }
}
