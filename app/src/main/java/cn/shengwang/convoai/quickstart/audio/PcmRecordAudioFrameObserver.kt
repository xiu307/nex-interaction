package cn.shengwang.convoai.quickstart.audio

import io.agora.rtc2.Constants
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.audio.AudioParams
import java.nio.ByteBuffer

class PcmRecordAudioFrameObserver(
    private val onRecordPcmData: (ByteArray) -> Unit
) : IAudioFrameObserver {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val MODE = 0
        const val SAMPLES_PER_CALL = 3200
    }

    @Volatile
    private var onPcmDataListener: ((ByteArray) -> Unit)? = null

    fun setOnPcmDataListener(listener: ((ByteArray) -> Unit)?) {
        onPcmDataListener = listener
    }

    override fun onRecordAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int
    ): Boolean {
        if (buffer == null || buffer.remaining() == 0) {
            return false
        }
        val audioData = ByteArray(buffer.remaining())
        buffer.get(audioData)
        buffer.flip()
        onRecordPcmData(audioData)
        onPcmDataListener?.invoke(audioData)
        return false
    }

    override fun onPlaybackAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int
    ): Boolean {
        return false
    }

    override fun onMixedAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int
    ): Boolean {
        return false
    }

    override fun onEarMonitoringAudioFrame(
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int
    ): Boolean {
        return false
    }

    override fun onPlaybackAudioFrameBeforeMixing(
        channelId: String?,
        uid: Int,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
        rtpTimestamp: Int,
        presentationMs: Long
    ): Boolean {
        return false
    }

    override fun getObservedAudioFramePosition(): Int {
        return Constants.POSITION_RECORD
    }

    override fun getRecordAudioParams(): AudioParams? {
        return null
    }

    override fun getPlaybackAudioParams(): AudioParams? {
        return null
    }

    override fun getMixedAudioParams(): AudioParams? {
        return null
    }

    override fun getEarMonitoringAudioParams(): AudioParams? {
        return null
    }
}
