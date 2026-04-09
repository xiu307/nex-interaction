package ai.conv.internal.video

import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.video.AgoraVideoFrame

class ExternalVideoCaptureManager(private val rtcEngine: RtcEngineEx) {

    companion object {
        private const val INVALID_TRACK_ID = -1
    }

    private var customVideoTrackId = INVALID_TRACK_ID
    @Volatile
    private var framePushEnabled = false

    /**
     * 确保当前 RTC 引擎已经创建自定义视频轨。
     *
     * 如果轨道已存在则直接复用；否则会先开启视频模块，再创建一条新的
     * 自定义视频轨，供外部视频帧推送使用。
     *
     * @return 自定义视频轨 ID；创建失败时返回负数
     */
    fun ensureCustomVideoTrack(): Int {
        if (customVideoTrackId != INVALID_TRACK_ID) {
            return customVideoTrackId
        }
        rtcEngine.enableVideo()
        customVideoTrackId = rtcEngine.createCustomVideoTrack()
        return customVideoTrackId
    }

    /**
     * 设置是否允许向 RTC 推送外部视频帧。
     *
     * 关闭后即使业务侧继续调用推帧方法，当前帧也会被直接丢弃。
     *
     * @param enabled `true` 表示允许推帧，`false` 表示暂停推帧
     */
    fun setFramePushEnabled(enabled: Boolean) {
        framePushEnabled = enabled
    }

    /**
     * 获取当前自定义视频轨 ID。
     *
     * @return 当前轨道 ID；若尚未创建则返回 `-1`
     */
    fun getCustomVideoTrackId(): Int {
        return customVideoTrackId
    }

    /**
     * 推送一帧已经组装好的外部视频帧到 RTC。
     *
     * 调用前需要先创建自定义视频轨，并确保 [setFramePushEnabled] 已开启；
     * 否则该帧会被直接丢弃。
     *
     * @param frame 业务侧已经构造完成的 Agora 视频帧
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalVideoFrame(frame: AgoraVideoFrame): Int {
        if (!framePushEnabled || customVideoTrackId == INVALID_TRACK_ID) {
            return -1
        }
        return rtcEngine.pushExternalVideoFrameById(frame, customVideoTrackId)
    }

    /**
     * 以 NV21 原始数据构造一帧外部视频并推送到 RTC。
     *
     * 适用于业务侧直接拿到 NV21 摄像头数据的场景，内部会先组装
     * [AgoraVideoFrame]，再复用 [pushExternalVideoFrame] 完成推送。
     *
     * @param data NV21 格式的原始视频帧数据
     * @param width 帧宽，单位为像素
     * @param height 帧高，单位为像素
     * @param rotation 画面顺时针旋转角度，默认 `0`
     * @param timestampMs 帧时间戳，单位为毫秒，默认使用当前系统时间
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalNv21Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int = 0,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        val frame = AgoraVideoFrame().apply {
            format = AgoraVideoFrame.FORMAT_NV21
            buf = data.copyOf()
            stride = width
            this.height = height
            this.rotation = rotation
            timeStamp = timestampMs
        }
        return pushExternalVideoFrame(frame)
    }

    /**
     * 释放当前自定义视频轨资源。
     *
     * 释放后会销毁已创建的自定义视频轨，并将内部轨道 ID 复位，
     * 适合在页面销毁或 RTC 生命周期结束时调用。
     */
    fun release() {
        if (customVideoTrackId != INVALID_TRACK_ID) {
            rtcEngine.destroyCustomVideoTrack(customVideoTrackId)
            customVideoTrackId = INVALID_TRACK_ID
        }
    }
}
