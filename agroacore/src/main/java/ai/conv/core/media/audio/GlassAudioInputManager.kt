package ai.conv.core.media.audio

import android.os.Process
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.audio.AudioTrackConfig
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 眼镜音频输入管理器。
 *
 * 从眼镜 SPP 通道的 audioQueue 中持续获取 PCM 音频数据，推送到 RTC 自定义音频轨。
 *
 * 使用方式：
 * ```
 * val manager = GlassAudioInputManager.getInstance()
 * manager.setRtcEngine(rtcEngine)
 * manager.ensureCustomAudioTrack()  // 创建音频轨
 * manager.start(audioQueue)         // 开始推送
 * manager.stop()                    // 停止推送
 * manager.release()                 // 释放资源
 * ```
 */
class GlassAudioInputManager private constructor() {

    companion object {
        @Volatile
        private var instance: GlassAudioInputManager? = null

        fun getInstance(): GlassAudioInputManager {
            return instance ?: synchronized(this) {
                instance ?: GlassAudioInputManager().also { instance = it }
            }
        }

        private const val INVALID_TRACK_ID = -1
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        
        // 静音帧数据（1280字节，全0）
        private val SILENT_FRAME = ByteArray(1280)
        
        // 创建新的线程池
        private fun createExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "GlassAudio-Capture").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
        }
    }

    private var rtcEngine: RtcEngineEx? = null
    private var customAudioTrackId = INVALID_TRACK_ID
    
    // 音频捕获线程池（可变，支持重新创建）
    private var captureExecutor: ExecutorService = createExecutor()
    
    @Volatile
    private var isRunning = false
    
    // 当前活跃的音频队列
    @Volatile
    private var currentQueue: BlockingQueue<ByteArray>? = null

    /**
     * 设置 RTC 引擎实例。
     *
     * 在使用其他功能前必须先调用此方法。
     *
     * @param engine RTC 引擎实例
     */
    fun setRtcEngine(engine: RtcEngineEx) {
        this.rtcEngine = engine
    }
    fun setAudioQueue(queue: BlockingQueue<ByteArray>?) {
        currentQueue = queue
    }
    /**
     * 创建自定义音频轨。
     *
     * 在加入 RTC 频道前调用,创建用于推送眼镜音频的自定义音轨。
     *
     * @return 音频轨 ID；创建失败时返回负数
     */
    fun ensureCustomAudioTrack(): Int {
        val engine = rtcEngine ?: run {
            android.util.Log.e("GlassAudioInputManager", "RTC 引擎未设置，请先调用 setRtcEngine()")
            return INVALID_TRACK_ID
        }
        
        if (customAudioTrackId != INVALID_TRACK_ID) {
            return customAudioTrackId
        }
        val config = AudioTrackConfig().apply {
            enableLocalPlayback = false
            enableAudioProcessing = false
        }
        customAudioTrackId = engine.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            config
        )
        return customAudioTrackId
    }


    /**
     * 启动音频推送。
     *
     * 在 SPP 连接成功、audioQueue 有数据后调用，开始持续从队列获取音频并推送到 RTC。
     * 如果已经在运行，会先停止旧的再启动新的。
     *
     * @param queue 眼镜音频数据队列
     */
    fun start() {
        val engine = rtcEngine ?: run {
            android.util.Log.e("GlassAudioInputManager", "RTC 引擎未设置，请先调用 setRtcEngine()")
            return
        }
        
        if (customAudioTrackId == INVALID_TRACK_ID) {
            android.util.Log.e("GlassAudioInputManager", "音频轨未创建，请先调用 ensureCustomAudioTrack()")
            return
        }
        
        // 检查线程池状态，如果已终止则重新创建
        if (captureExecutor.isShutdown || captureExecutor.isTerminated) {
            android.util.Log.w("GlassAudioInputManager", "线程池已终止，重新创建")
            captureExecutor = createExecutor()
        }

        isRunning = true
        
        captureExecutor.submit {
            try {
                while (isRunning) {
                    val pcmData = currentQueue?.poll(200, TimeUnit.MILLISECONDS)  // 队列存在时阻塞获取
                  //  android.util.Log.i("GlassAudioInputManager", "pcmData: ${pcmData?.size}")
                    // 如果队列不存在或取不到数据，推送静音帧
                    val frameToPush = if (pcmData != null && pcmData.isNotEmpty()) {
                        pcmData
                    } else {
                        SILENT_FRAME
                    }
                    
                    engine.pushExternalAudioFrame(
                        frameToPush,
                        System.currentTimeMillis(),
                        SAMPLE_RATE,
                        CHANNEL_COUNT,
                        Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
                        customAudioTrackId
                    )
                    
                    // 短暂休眠，避免空转占用CPU
                    if (pcmData == null) {
                        Thread.sleep(200)
                    }
                }
            } catch (e: InterruptedException) {
                // 正常退出（take()被中断时）
            } catch (e: Exception) {
                android.util.Log.e("GlassAudioInputManager", "音频推送异常: ${e.message}")
            }
        }

        android.util.Log.i("GlassAudioInputManager", "音频推送已启动")
    }


    /**
     * 释放资源。
     *
     * 停止推送 + 销毁音频轨 + 关闭线程池。
     * 注意：调用后如需再次使用，需重新设置 RTC 引擎。
     */
    fun release() {
        isRunning = false
        currentQueue = null
        captureExecutor.shutdown()
        try {
            if (!captureExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                captureExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            captureExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        val engine = rtcEngine
        if (customAudioTrackId != INVALID_TRACK_ID && engine != null) {
            engine.destroyCustomAudioTrack(customAudioTrackId)
            customAudioTrackId = INVALID_TRACK_ID
        }
        
        rtcEngine = null
    }
}
