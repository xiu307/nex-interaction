package cn.shengwang.convoai.quickstart.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import cn.shengwang.convoai.quickstart.api.net.SecureOkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.min

/**
 * 从 HTTP(S) 拉取 **16kHz / 16bit / 单声道 raw PCM** 并试听（与注册页录音参数一致）。
 *
 * 使用 [AudioTrack.MODE_STREAM] 分块写入，避免 [AudioTrack.MODE_STATIC] 在 PCM 较大（约 >1MB）时初始化/写入失败。
 */
class Pcm16kMonoPreviewPlayer {

    private val client by lazy { SecureOkHttpClient.create().build() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioTrack: AudioTrack? = null
    private var endRunnable: Runnable? = null
    private var playJob: Job? = null

    private fun releaseTrackOnly() {
        endRunnable?.let { mainHandler.removeCallbacks(it) }
        endRunnable = null
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        releaseTrackOnly()
    }

    fun playFromHttpUrl(
        url: String,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onStarted: () -> Unit,
        onEnded: () -> Unit,
    ) {
        stop()
        playJob = scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "ConvoAI-Android/1.0")
                        .build(),
                ).execute()
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) { onError("HTTP ${resp.code}") }
                    return@launch
                }
                var bytes = resp.body?.bytes() ?: run {
                    withContext(Dispatchers.Main) { onError("响应为空") }
                    return@launch
                }
                if (bytes.size % 2 != 0) {
                    bytes = bytes.copyOf(bytes.size - 1)
                }
                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("数据为空") }
                    return@launch
                }
                ensureActive()
                playPcm16Mono16kStream(bytes, onError, onStarted, onEnded)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "网络异常") }
            }
        }
    }

    private suspend fun playPcm16Mono16kStream(
        bytes: ByteArray,
        onError: (String) -> Unit,
        onStarted: () -> Unit,
        onEnded: () -> Unit,
    ) {
        val sampleRate = 16_000
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf == AudioTrack.ERROR) {
            withContext(Dispatchers.Main) { onError("设备不支持该音频格式") }
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            withContext(Dispatchers.Main) { onError("AudioTrack 初始化失败") }
            return
        }
        withContext(Dispatchers.Main) {
            releaseTrackOnly()
            audioTrack = track
            onStarted()
        }
        track.play()
        var offset = 0
        val chunk = 4096
        while (offset < bytes.size) {
            val w = track.write(bytes, offset, min(chunk, bytes.size - offset))
            if (w < 0) {
                withContext(Dispatchers.Main) {
                    onError("播放写入失败 ($w)")
                    releaseTrackOnly()
                }
                return
            }
            offset += w
        }
        val durationMs = (bytes.size / 2) * 1000L / sampleRate
        delay(durationMs + 80L)
        withContext(Dispatchers.Main) {
            onEnded()
            releaseTrackOnly()
        }
    }
}
