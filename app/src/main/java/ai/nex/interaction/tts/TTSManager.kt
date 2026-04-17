package ai.nex.interaction.tts

import android.content.Context
import android.util.Log
import com.geely.ai.client.tts.player.Platform8295
import com.geely.ai.client.tts.player.api.ITTSService
import com.geely.ai.client.tts.player.api.TTSServiceFactory
import com.geely.ai.client.tts.player.api.config.DeviceInfo
import com.geely.ai.client.tts.player.api.config.Environment
import com.geely.ai.client.tts.player.api.config.LogLevel
import com.geely.ai.client.tts.player.api.config.TTSConfig
import com.geely.ai.client.tts.player.api.listener.TTSPlayListener
import com.geely.ai.client.tts.player.api.listener.TTSStateListener
import com.geely.ai.client.tts.player.api.model.StreamSession
import com.geely.ai.client.tts.player.api.model.TTSError
import com.geely.ai.client.tts.player.api.model.TTSRequest
import com.geely.ai.client.tts.player.api.model.TTSResult
import com.geely.ai.client.tts.player.api.model.TTSServiceState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 从 AiGlasses 抽出的本地 TTS 管理器。
 *
 * 用法：
 * `TTSManager.getInstance().speak("你好")`
 */
class TTSManager private constructor() {

    interface TTSCallback {
        fun onPlayStart(taskId: String)
        fun onPlayProgress(progress: Int, total: Int)
        fun onPlayComplete(taskId: String)
        fun onPlayError(errorMsg: String)
        fun onInitialized()
        fun onInitializeFailed(errorMsg: String)
    }

    companion object {
        private const val TAG = "TTSManager"
        private const val DEFAULT_VOICE = "f21_vc"
        private const val DEFAULT_BRAND_CODE = "lynkco"
        private const val DEFAULT_MODEL_CODE = "P177"
        // Keep this aligned with the working AiGlasses implementation.
        private const val DEFAULT_UNIQUE_ID = "VIN_DEMO_V2"
        private const val DEFAULT_CHUNK_SIZE = 30
        private const val DEFAULT_CHUNK_DELAY_MS = 100L

        @Volatile
        private var instance: TTSManager? = null

        @JvmStatic
        fun getInstance(): TTSManager {
            return instance ?: synchronized(this) {
                instance ?: TTSManager().also { instance = it }
            }
        }

        private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private var appContext: Context? = null
    private var ttsService: ITTSService? = null
    private var isInitialized = false
    private var isOfflineMode = false
    private var callback: TTSCallback? = null
    private var currentSessionId: String? = null
    private var scope: CoroutineScope = newScope()

    fun init(context: Context, deviceId: String? = null) {
        if (isInitialized && ttsService != null) {
            Log.d(TAG, "TTS already initialized")
            return
        }

        appContext = context.applicationContext
        scope.cancel()
        scope = newScope()

        val offlineModelPath = File(
            appContext?.getExternalFilesDir(null) ?: appContext?.filesDir,
            "offline_res",
        ).absolutePath + "/"

        val config = TTSConfig(
            environment = Environment.UAT,
            deviceInfo = DeviceInfo(
                uniqueId = DEFAULT_UNIQUE_ID,
                brandCode = DEFAULT_BRAND_CODE,
                modelCode = DEFAULT_MODEL_CODE,
                platformType = Platform8295,
            ),
            defaultVoice = DEFAULT_VOICE,
            enableDynamicCache = false,
            dynamicCacheSize = 100,
            enableCloudDownload = false,
            enableOffline = true,
            offlineModelPath = offlineModelPath,
            logLevel = LogLevel.DEBUG,
        )

        val contextOrThrow = requireNotNull(appContext) { "Application context is required for TTS init." }
        ttsService = TTSServiceFactory.create(contextOrThrow, config)
        setupListeners()
        isInitialized = true
    }

    fun setCallback(callback: TTSCallback?) {
        this.callback = callback
    }

    fun speak(text: String) {
        if (!checkInitialized() || text.isBlank()) return

        scope.launch {
            stop()
            val request = TTSRequest(
                text = text,
                useOffline = isOfflineMode,
            )

            val result = ttsService?.speak(request)
            result?.onSuccess { taskId ->
                Log.d(TAG, "Speak task created: $taskId")
            }?.onFailure { error ->
                Log.e(TAG, "Speak task create failed: ${error.message}")
                callback?.onPlayError(error.message ?: "Unknown TTS error")
            }
        }
    }

    /**
     * Non-interrupting speech: if TTS is already playing, skip this utterance.
     * Useful for low-priority guidance prompts that should not cut off current audio.
     */
    fun speakNonInterrupting(text: String) {
        if (!checkInitialized() || text.isBlank()) return

        scope.launch {
            if (isPlaying()) {
                Log.d(TAG, "Skip non-interrupting TTS because another utterance is playing.")
                return@launch
            }
            val request = TTSRequest(
                text = text,
                useOffline = isOfflineMode,
            )
            val result = ttsService?.speak(request)
            result?.onSuccess { taskId ->
                Log.d(TAG, "Non-interrupting speak task created: $taskId")
            }?.onFailure { error ->
                Log.e(TAG, "Non-interrupting speak failed: ${error.message}")
                callback?.onPlayError(error.message ?: "Unknown TTS error")
            }
        }
    }

    fun speakLongText(longText: String) {
        if (!checkInitialized() || longText.isBlank()) return

        scope.launch {
            val session = StreamSession(
                voiceType = DEFAULT_VOICE,
                useOffline = isOfflineMode,
            )
            val sessionResult = ttsService?.startStream(session)

            sessionResult?.onSuccess { sessionId ->
                currentSessionId = sessionId
                val chunks = longText.chunked(DEFAULT_CHUNK_SIZE)
                chunks.forEach { chunk ->
                    ttsService?.appendStream(sessionId, chunk)
                    delay(DEFAULT_CHUNK_DELAY_MS)
                }
                ttsService?.endStream(sessionId)
                Log.d(TAG, "Long text stream finished: $sessionId, chunks=${chunks.size}")
            }?.onFailure { error ->
                Log.e(TAG, "Start long-text stream failed: ${error.message}")
                callback?.onPlayError(error.message ?: "Unknown TTS error")
            }
        }
    }

    fun speakStreamText(text: String, status: String) {
        if (!checkInitialized()) return

        scope.launch {
            when (status) {
                "start" -> {
                    val session = StreamSession(
                        voiceType = DEFAULT_VOICE,
                        useOffline = isOfflineMode,
                    )
                    val sessionResult = ttsService?.startStream(session)
                    sessionResult?.onSuccess { sessionId ->
                        currentSessionId = sessionId
                        if (text.isNotBlank()) {
                            ttsService?.appendStream(sessionId, text)
                        }
                        Log.d(TAG, "Stream session created: $sessionId")
                    }?.onFailure { error ->
                        Log.e(TAG, "Start stream failed: ${error.message}")
                        callback?.onPlayError(error.message ?: "Unknown TTS error")
                    }
                }

                "append" -> {
                    val sessionId = currentSessionId
                    if (sessionId.isNullOrBlank()) {
                        Log.w(TAG, "append ignored: no current stream session")
                        return@launch
                    }
                    if (text.isNotBlank()) {
                        ttsService?.appendStream(sessionId, text)
                    }
                }

                "end" -> {
                    val sessionId = currentSessionId
                    if (sessionId.isNullOrBlank()) {
                        Log.w(TAG, "end ignored: no current stream session")
                        return@launch
                    }
                    ttsService?.endStream(sessionId)
                    currentSessionId = null
                    Log.d(TAG, "Stream session ended: $sessionId")
                }

                else -> Log.w(TAG, "Unsupported stream status: $status")
            }
        }
    }

    fun stop() {
        if (!checkInitialized()) return
        ttsService?.stop()
        currentSessionId = null
    }

    fun preload(text: String) {
        if (!checkInitialized() || text.isBlank()) return
        scope.launch { ttsService?.preload(text) }
    }

    fun isPlaying(): Boolean = isInitialized && ttsService?.isPlaying() == true

    fun getState(): TTSServiceState? = if (isInitialized) ttsService?.getState() else null

    fun setOfflineMode(offline: Boolean) {
        isOfflineMode = offline
    }

    fun isOfflineMode(): Boolean = isOfflineMode

    fun release() {
        scope.cancel()
        scope = newScope()
        ttsService?.release()
        ttsService = null
        isInitialized = false
        currentSessionId = null
        callback = null
        appContext = null
    }

    private fun setupListeners() {
        ttsService?.setPlayListener(
            object : TTSPlayListener {
                override fun onPlayStart(taskId: String) {
                    callback?.onPlayStart(taskId)
                }

                override fun onPlayProgress(taskId: String, progress: Int, total: Int) {
                    callback?.onPlayProgress(progress, total)
                }

                override fun onPlayComplete(taskId: String) {
                    callback?.onPlayComplete(taskId)
                }

                override fun onPlayError(taskId: String, error: TTSError) {
                    callback?.onPlayError(error.message ?: "Unknown TTS error")
                }
            },
        )

        ttsService?.setStateListener(
            object : TTSStateListener {
                override fun onStateChanged(oldState: TTSServiceState, newState: TTSServiceState) {
                    Log.d(TAG, "TTS state changed: $oldState -> $newState")
                }

                override fun onInitialized() {
                    callback?.onInitialized()
                }

                override fun onInitializeFailed(error: TTSError) {
                    isInitialized = false
                    callback?.onInitializeFailed(error.message ?: "Unknown TTS error")
                }
            },
        )
    }

    private fun checkInitialized(): Boolean {
        val ready = isInitialized && ttsService != null
        if (!ready) {
            Log.e(TAG, "TTS is not initialized yet")
        }
        return ready
    }
}
