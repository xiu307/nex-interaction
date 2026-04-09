package ai.nex.interaction.video

import ai.nex.interaction.biometric.FaceRtmStreamPublisher
import ai.nex.interaction.audio.CustomAudioInputManager
import ai.nex.interaction.rtc.buildConversationChannelMediaOptions
import ai.nex.interaction.session.ConnectionSessionState
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.video.AgoraVideoFrame

/**
 * RTC 自定义视频轨的发布开关与推帧，集中管理 [isCustomVideoPublishing] 与 [ExternalVideoCaptureManager] 的帧开关。
 */
class ConversationExternalVideoPublishController(
    private val rtcEngine: () -> RtcEngineEx?,
    private val audioInputManager: () -> CustomAudioInputManager?,
    private val externalVideoCapture: () -> ExternalVideoCaptureManager?,
    private val connection: () -> ConnectionSessionState,
    private val onStatusLog: (String) -> Unit,
) {

    private var customVideoTrackPublished: Boolean = false

    /** 是否已向频道发布自定义视频（与原先 ViewModel 字段语义一致）。 */
    val isCustomVideoPublishing: Boolean get() = customVideoTrackPublished

    /** 即将发起新的 RTC 进房：关闭自定义视频发布状态与推帧。 */
    fun prepareForNewRtcJoin() {
        customVideoTrackPublished = false
        externalVideoCapture()?.setFramePushEnabled(false)
    }

    /** RTC `onJoinChannelSuccess`：按当前发布意图恢复推帧开关。 */
    fun onRtcJoinChannelSuccess() {
        externalVideoCapture()?.setFramePushEnabled(customVideoTrackPublished)
    }

    /** 离开频道或引擎错误：复位发布标志并停推帧。 */
    fun resetVideoPipelineOnLeaveOrError() {
        customVideoTrackPublished = false
        externalVideoCapture()?.setFramePushEnabled(false)
    }

    /**
     * 开启或关闭 RTC 自定义视频发布（`updateChannelMediaOptions`）。
     * @return 是否切换成功
     */
    fun setPublishingEnabled(enabled: Boolean): Boolean {
        val manager = externalVideoCapture() ?: return false
        val conn = connection()

        if (!enabled) {
            manager.setFramePushEnabled(false)
            if (!conn.rtcJoined) {
                customVideoTrackPublished = false
                return true
            }
        } else if (customVideoTrackPublished) {
            manager.setFramePushEnabled(true)
            return true
        } else if (!conn.rtcJoined) {
            onStatusLog("External video publishing requires an active RTC connection")
            return false
        }

        val customTrackId = if (enabled) {
            manager.ensureCustomVideoTrack()
        } else {
            manager.getCustomVideoTrackId()
        }

        if (enabled && customTrackId < 0) {
            onStatusLog("Create custom video track failed ret: $customTrackId")
            return false
        }

        val result = rtcEngine()?.updateChannelMediaOptions(
            buildConversationChannelMediaOptions(
                customAudioTrackId = audioInputManager()?.getCustomAudioTrackId()
                    ?.takeIf { it >= 0 },
                publishCustomVideoTrack = enabled,
                customTrackId = customTrackId.takeIf { it >= 0 }
            )
        ) ?: -1

        return if (result == ERR_OK) {
            customVideoTrackPublished = enabled
            manager.setFramePushEnabled(enabled)
            if (enabled) {
                FaceRtmStreamPublisher.stopAll()
            }
            onStatusLog(
                if (enabled) {
                    "External video publishing enabled"
                } else {
                    "External video publishing disabled"
                }
            )
            true
        } else {
            onStatusLog(
                if (enabled) {
                    "Enable external video publishing failed ret: $result"
                } else {
                    "Disable external video publishing failed ret: $result"
                }
            )
            false
        }
    }

    fun pushExternalVideoFrame(frame: AgoraVideoFrame): Int =
        externalVideoCapture()?.pushExternalVideoFrame(frame) ?: -1

    fun pushExternalNv21Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int = 0,
        timestampMs: Long = System.currentTimeMillis(),
    ): Int = externalVideoCapture()?.pushExternalNv21Frame(
        data = data,
        width = width,
        height = height,
        rotation = rotation,
        timestampMs = timestampMs
    ) ?: -1
}
