package ai.nex.interaction.audio

import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.audio.AudioTrackConfig

/**
 * 通用自定义音频输入管理器。
 *
 * 该类对外表达的是“如何把业务侧已采集的音频送入 RTC 自定义音轨”，
 * 而不是绑定某一种具体采集方式。当前仓库默认注入的是麦克风采集示例
 * [MicrophoneAudioCaptureManager]，但客户也可以替换成自己的采集模块。
 */
class CustomAudioInputManager(
    private val rtcEngine: RtcEngineEx,
    private val onAudioInputInterrupted: () -> Unit = {}
) {

    companion object {
        private const val INVALID_TRACK_ID = -1
    }

    private val microphoneAudioCaptureManager = MicrophoneAudioCaptureManager(
        onAudioFrameCaptured = { data, timestampMs ->
            pushExternalPcmData(data, timestampMs)
        },
        onCaptureStopped = { requested ->
            audioInputEnabled = false
            if (!requested) {
                onAudioInputInterrupted()
            }
        }
    )

    @Volatile
    private var published = false

    @Volatile
    private var audioInputEnabled = false

    private var customAudioTrackId = INVALID_TRACK_ID

    /**
     * 确保当前 RTC 引擎已经创建自定义音频轨。
     *
     * 如果轨道已存在则直接复用；否则会创建一条新的自定义音频轨，供
     * 麦克风示例采集或业务侧直接推入 PCM 数据使用。
     *
     * @return 自定义音频轨 ID；创建失败时返回负数
     */
    fun ensureCustomAudioTrack(): Int {
        if (customAudioTrackId != INVALID_TRACK_ID) {
            return customAudioTrackId
        }
        val config = AudioTrackConfig().apply {
            enableLocalPlayback = false
            enableAudioProcessing = true
        }
        // Empirically, DIRECT avoids re-capturing agent playback much better than MIXABLE
        // in this quickstart's full-duplex voice conversation path.
        customAudioTrackId = rtcEngine.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            config
        )
        return customAudioTrackId
    }

    /**
     * 获取当前自定义音频轨 ID。
     *
     * @return 当前轨道 ID；若尚未创建则返回 `-1`
     */
    fun getCustomAudioTrackId(): Int {
        return customAudioTrackId
    }

    /**
     * 返回当前是否已经开启麦克风示例采集。
     *
     * 该状态只表示本地采集线程是否运行，不代表 RTC 连接或自定义音轨
     * 是否已发布。
     */
    fun isAudioInputEnabled(): Boolean {
        return audioInputEnabled
    }

    /**
     * 设置当前自定义音频轨是否已发布到 RTC 频道。
     *
     * 该状态用于控制 [pushExternalPcmData] 是否真正向 RTC 推帧；未发布时
     * 即使调用推帧方法，当前帧也会被直接丢弃。
     *
     * @param published `true` 表示音轨已发布，`false` 表示未发布
     */
    fun setPublished(published: Boolean) {
        this.published = published
    }

    /**
     * 启动默认的麦克风示例采集。
     *
     * 调用前需要先确保已获得麦克风权限；启动成功后，采集到的 PCM 会
     * 通过当前 manager 自动送入 RTC 自定义音频轨。
     *
     * @return `true` 表示采集已成功启动，`false` 表示启动失败
     */
    fun start(): Boolean {
        if (audioInputEnabled) {
            return true
        }
        if (!microphoneAudioCaptureManager.startMicrophoneCapture()) {
            audioInputEnabled = false
            return false
        }
        audioInputEnabled = true
        return true
    }

    /**
     * 停止默认的麦克风示例采集。
     *
     * 该方法只停止本地采集线程，不会销毁自定义音频轨，也不会修改当前
     * 音轨发布状态。
     */
    fun stop() {
        if (!audioInputEnabled) {
            return
        }
        microphoneAudioCaptureManager.stopCapture()
        audioInputEnabled = false
    }

    /**
     * 停止本地采集并将当前自定义音频轨标记为未发布。
     *
     * 适合在离开频道、RTC 出错或整体音频链路重置时调用。
     */
    fun stopAndUnpublish() {
        published = false
        microphoneAudioCaptureManager.stopCapture()
        audioInputEnabled = false
    }

    /**
     * 释放当前自定义音频输入管理器持有的全部资源。
     *
     * 释放后会停止本地采集、释放麦克风示例采集器，并销毁已创建的
     * RTC 自定义音频轨。
     */
    fun release() {
        stopAndUnpublish()
        microphoneAudioCaptureManager.release()
        if (customAudioTrackId != INVALID_TRACK_ID) {
            rtcEngine.destroyCustomAudioTrack(customAudioTrackId)
            customAudioTrackId = INVALID_TRACK_ID
        }
    }

    /**
     * 推送一帧业务侧原始 PCM 数据到 RTC 自定义音频轨。
     *
     * 调用前需要先确保已创建并发布自定义音频轨；否则该帧会被直接丢弃。
     * 当前固定按 16kHz、单声道、16bit PCM 送入 RTC。
     *
     * @param data 原始 PCM 字节数组
     * @param timestampMs 音频帧时间戳，单位毫秒
     * @return SDK 推帧结果；未发布或未创建轨道时返回 `-1`
     */
    fun pushExternalPcmData(
        data: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        val trackId = customAudioTrackId
        if (!published || trackId == INVALID_TRACK_ID) {
            return -1
        }
        return rtcEngine.pushExternalAudioFrame(
            data,
            timestampMs,
            MicrophoneAudioCaptureManager.SAMPLE_RATE,
            MicrophoneAudioCaptureManager.CHANNEL_COUNT,
            Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
            trackId
        )
    }
}
