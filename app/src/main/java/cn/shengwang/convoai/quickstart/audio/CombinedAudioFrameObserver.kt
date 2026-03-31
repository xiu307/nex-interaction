package cn.shengwang.convoai.quickstart.audio

import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.audio.AudioParams
import java.nio.ByteBuffer

class CombinedAudioFrameObserver(
    observers: List<IAudioFrameObserver>
) : IAudioFrameObserver {

    private val observers = observers.toList()

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
        return observers.fold(false) { handled, observer ->
            observer.onRecordAudioFrame(
                channelId,
                type,
                samplesPerChannel,
                bytesPerSample,
                channels,
                samplesPerSec,
                buffer,
                renderTimeMs,
                avsync_type
            ) || handled
        }
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
        return observers.fold(false) { handled, observer ->
            observer.onPlaybackAudioFrame(
                channelId,
                type,
                samplesPerChannel,
                bytesPerSample,
                channels,
                samplesPerSec,
                buffer,
                renderTimeMs,
                avsync_type
            ) || handled
        }
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
        return observers.fold(false) { handled, observer ->
            observer.onMixedAudioFrame(
                channelId,
                type,
                samplesPerChannel,
                bytesPerSample,
                channels,
                samplesPerSec,
                buffer,
                renderTimeMs,
                avsync_type
            ) || handled
        }
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
        return observers.fold(false) { handled, observer ->
            observer.onEarMonitoringAudioFrame(
                type,
                samplesPerChannel,
                bytesPerSample,
                channels,
                samplesPerSec,
                buffer,
                renderTimeMs,
                avsync_type
            ) || handled
        }
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
        return observers.fold(false) { handled, observer ->
            observer.onPlaybackAudioFrameBeforeMixing(
                channelId,
                uid,
                type,
                samplesPerChannel,
                bytesPerSample,
                channels,
                samplesPerSec,
                buffer,
                renderTimeMs,
                avsync_type,
                rtpTimestamp,
                presentationMs
            ) || handled
        }
    }

    override fun getObservedAudioFramePosition(): Int {
        return observers.fold(0) { position, observer ->
            position or observer.getObservedAudioFramePosition()
        }
    }

    override fun getRecordAudioParams(): AudioParams? {
        return observers.firstNotNullOfOrNull { it.getRecordAudioParams() }
    }

    override fun getPlaybackAudioParams(): AudioParams? {
        return observers.firstNotNullOfOrNull { it.getPlaybackAudioParams() }
    }

    override fun getMixedAudioParams(): AudioParams? {
        return observers.firstNotNullOfOrNull { it.getMixedAudioParams() }
    }

    override fun getEarMonitoringAudioParams(): AudioParams? {
        return observers.firstNotNullOfOrNull { it.getEarMonitoringAudioParams() }
    }
}
