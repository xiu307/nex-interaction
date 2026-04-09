package ai.conv.internal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 麦克风采集参考实现。
 *
 * 该类只负责：
 * 1. 用 `AudioRecord` 从本地麦克风采集 PCM；
 * 2. 把采集到的 PCM 通过回调交给上层。
 *
 * 如果客户的音频来源不是麦克风，而是外部设备、SDK 或自定义采集模块，
 * 可以保留 [CustomAudioInputManager] 这一层，替换掉当前类的实现。
 */
class MicrophoneAudioCaptureManager(
    private val onAudioFrameCaptured: (ByteArray, Long) -> Unit,
    private val onCaptureStopped: (requested: Boolean) -> Unit = {}
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_DURATION_MS = 20
        private const val FRAME_SIZE_BYTES =
            SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * FRAME_DURATION_MS / 1000
    }

    @Volatile
    private var captureRunning = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var captureTask: Future<*>? = null

    @Volatile
    private var stopRequested = false

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startMicrophoneCapture(): Boolean {
        if (captureRunning) {
            return true
        }
        stopRequested = false

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, FRAME_SIZE_BYTES * 4)
            )
        } catch (exception: IllegalArgumentException) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (exception: SecurityException) {
            record.release()
            return false
        } catch (exception: IllegalStateException) {
            record.release()
            return false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }

        captureRunning = true
        audioRecord = record
        captureTask = captureExecutor.submit {
            captureLoop(record)
        }
        return true
    }

    private fun captureLoop(record: AudioRecord) {
        val readBuffer = ByteArray(FRAME_SIZE_BYTES)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (captureRunning) {
                val readSize = record.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING
                )
                if (!captureRunning) {
                    break
                }
                if (readSize <= 0) {
                    continue
                }
                onAudioFrameCaptured(readBuffer.copyOf(readSize), System.currentTimeMillis())
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        } finally {
            val requested = stopRequested
            safelyStopAudioRecord(record)
            record.release()
            audioRecord = null
            captureTask = null
            captureRunning = false
            stopRequested = false
            onCaptureStopped(requested)
        }
    }

    @Synchronized
    fun stopCapture() {
        if (!captureRunning) {
            return
        }
        stopRequested = true
        captureRunning = false
        audioRecord?.let { safelyStopAudioRecord(it) }
        val task = captureTask
        if (task != null) {
            try {
                task.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun release() {
        stopCapture()
        captureExecutor.shutdown()
    }

    private fun safelyStopAudioRecord(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: IllegalStateException) {
        }
    }
}
