package cn.shengwang.convoai.quickstart.biometric

import android.content.Context
import android.provider.Settings
import com.robotchat.facedet.FaceDetectorConfig

/**
 * facedet（[app/libs/facedet-release.aar]）对接层，语义对齐 Android `ConvoFacedetDock` / face-detc-java Demo。
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
            // 注册页仅需人脸：关 Pose，且强制 MediaPipe CPU（华为等机型 GPU 下 FaceLandmarker 可能 SIGSEGV）
            posePipelineEnabled = false
            mediapipeForceCpuDelegate = true
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
