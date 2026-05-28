package ai.conv.core.media.audio

import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.audio.AudioTrackConfig

/**
 * 通用自定义音频输入管理器。
 *
 * 该类负责管理 RTC 自定义音频轨道的创建、发布和 PCM 数据推送。
 * 不绑定任何具体采集方式，外部可以自由传入来自不同音频源（麦克风、
 * 蓝牙设备、TTS 等）的 PCM 数据，通过 [pushExternalPcmData] 推入 RTC。
 */
class ExternalAudioInputManager(
    private val rtcEngine: RtcEngineEx,
    private val onAudioInputInterrupted: () -> Unit = {}
) {

    companion object {
        private const val INVALID_TRACK_ID = -1
        
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        private val BYTES_PER_SAMPLE = Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE

        var customAudioTrackEx: MutableMap<Int, Int> = mutableMapOf()
    }



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
     * 返回当前音频输入是否已启用。
     *
     * 该状态表示当前管理器是否处于工作状态，外部可据此判断是否可以
     * 继续推送 PCM 数据。
     */
    fun isAudioInputEnabled(): Boolean {
        return audioInputEnabled
    }

    /**
     * 启用音频输入并标记为已发布。
     *
     * 调用此方法后，[pushExternalPcmData] 将开始接受外部传入的 PCM 数据
     * 并推入 RTC 自定义音频轨道。此方法会自动将音频轨道标记为已发布状态。
     */
    fun enable() {
        audioInputEnabled = true
        published = true
    }

    /**
     * 禁用音频输入并取消发布。
     *
     * 调用此方法后，[pushExternalPcmData] 将停止接受外部 PCM 数据，
     * 传入的数据帧会被直接丢弃。同时会将音频轨道标记为未发布状态。
     */
    fun disable(notifyInterrupted: Boolean = false) {
        audioInputEnabled = false
        published = false
        if (notifyInterrupted) {
            onAudioInputInterrupted()
        }
    }

    /**
     * 释放当前自定义音频输入管理器持有的全部资源。
     *
     * 释放后会停止音频输入状态，并销毁已创建的 RTC 自定义音频轨。
     */
    fun release() {
        audioInputEnabled = false
        published = false
        if (customAudioTrackId != INVALID_TRACK_ID) {
            rtcEngine.destroyCustomAudioTrack(customAudioTrackId)
            customAudioTrackId = INVALID_TRACK_ID
        }
        customAudioTrackEx.values.forEach {
            rtcEngine.destroyCustomAudioTrack(it)
        }
        customAudioTrackEx.clear()
    }

    /**
     * 推送一帧业务侧原始 PCM 数据到 RTC 自定义音频轨。
     *
     * 该方法为公开接口，外部可自由调用以推送来自不同音频源（麦克风、
     * 蓝牙设备、TTS 等）的 PCM 数据。
     *
     * 调用前需要满足以下条件，否则该帧会被直接丢弃：
     * 1. 已通过 [ensureCustomAudioTrack] 或 [ensureCustomAudioTrackEx] 创建自定义音频轨
     * 2. 已通过 [setPublished] 标记音频轨为已发布状态
     * 3. 已通过 [enable] 启用音频输入
     *
     * 当前固定按 16kHz、单声道、16bit PCM 送入 RTC。
     *
     * @param data 原始 PCM 字节数组
     * @param timestampMs 音频帧时间戳，单位毫秒，默认使用当前系统时间
     * @return SDK 推帧结果码；未满足推帧条件时返回 `-1`
     */
    fun pushExternalPcmData(
        data: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        val trackId = customAudioTrackId
        if (!published || trackId == INVALID_TRACK_ID || !audioInputEnabled) {
            return -1
        }
        return rtcEngine.pushExternalAudioFrame(
            data,
            timestampMs,
            SAMPLE_RATE,
            CHANNEL_COUNT,
            BYTES_PER_SAMPLE,
            trackId
        )
    }
}
