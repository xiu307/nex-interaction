package ai.conv.core

import android.content.Context
import ai.conv.core.media.audio.CustomAudioInputManager
import ai.conv.core.convoai.ConversationalAIAPIConfig
import ai.conv.core.convoai.ConversationalAIAPIImpl
import ai.conv.core.convoai.IConversationalAIAPI
import ai.conv.core.convoai.IConversationalAIAPIEventListener
import ai.conv.core.rtc.ConversationRtcEventListener
import ai.conv.core.rtc.ConversationRtcEventSink
import ai.conv.core.rtm.ConversationRtmEventListener
import ai.conv.core.rtm.ConversationRtmEventSink
import ai.conv.core.rtm.RtmLoginState
import ai.conv.core.rtm.createConversationRtmConfig
import ai.conv.core.media.video.ExternalVideoCaptureManager
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtm.RtmClient
import kotlinx.coroutines.CoroutineScope

/**
 * 对话管理器配置
 */
data class ConvoManagerConfig(
    /** 是否启用 ConvoAI 日志 */
    val enableConvoAiLog: Boolean = true,

    /** 音频场景 */
    val audioScenario: Int = Constants.AUDIO_SCENARIO_AI_CLIENT,

    /** 音频输入中断回调 */
    val onAudioInputInterrupted: (() -> Unit)? = null
)

/**
 * RTC/RTM 连接层事件监听（对外 API）。
 *
 * 说明：
 * - 这是对 `internal` 包里 RTC/RTM 事件的“更稳定、更 Android 风格”的封装。
 * - 回调线程：RTC 回调会被转发到 `AgroaManager` 构造参数传入的 `scope`；RTM 回调由 SDK 线程触发（当前实现只做轻量状态通知）。
 */
interface AgroaConnectionListener {
    fun onRtcJoinSuccess(channel: String?, uid: Int, elapsed: Int) {}
    fun onRtcLeave() {}
    fun onRtcUserJoined(uid: Int, elapsed: Int) {}
    fun onRtcUserOffline(uid: Int, reason: Int) {}
    fun onRtcError(code: Int) {}
    fun onRtmConnected() {}
    fun onRtmFailed() {}
}

/**
 * 对话管理器：封装 RTC/RTM/ConvoAI 的初始化、配置和生命周期管理。
 *
 * 使用示例：
 * ```
 * val manager = ConvoManager(
 *     context = context,
 *     appId = ConvoConfig.APP_ID,
 *     userId = userId.toString(),
 *     scope = viewModelScope,
 *     config = ConvoManagerConfig(
 *         autoStartAudioInput = true,
 *         onAudioInputInterrupted = { /* handle */ }
 *     )
 * )
 * manager.setEventHandlers(
 *     rtcEventSink = myRtcSink,
 *     rtmEventSink = myRtmSink,
 *     convoAiEventHandler = myConvoAiHandler
 * )
 * ```
 */
class AgroaManager(
    context: Context,
    appId: String,
    userId: String,
    private val scope: CoroutineScope,
    private val config: ConvoManagerConfig = ConvoManagerConfig(),
    private val connectionListener: AgroaConnectionListener? = null,
    private val convoAiEventHandler: IConversationalAIAPIEventListener? = null,
    logTag: String = "AgroaManager",
) {
    private companion object {
        /** 与对话示例一致的 AI-QoS 扩展（回声消除 / 降噪）。 */
        val CONVERSATION_RTC_AI_EXTENSION_IDS: List<String> = listOf(
            "ai_echo_cancellation_extension",
            "ai_noise_suppression_extension",
        )
    }

    @Volatile
    private var currentChannelName: String = ""

    val rtcEngine: RtcEngineEx
    val rtmClient: RtmClient
    val conversationalAIAPI: IConversationalAIAPI
    val audioInputManager: CustomAudioInputManager
    val videoInputManager: ExternalVideoCaptureManager
    val rtmLoginState = RtmLoginState()

    private val rtcEventSink: ConversationRtcEventSink =
        object : ConversationRtcEventSink {
            override suspend fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                connectionListener?.onRtcJoinSuccess(channel, uid, elapsed)
            }

            override suspend fun onLeaveChannel(stats: RtcStats?) {
                connectionListener?.onRtcLeave()
            }

            override suspend fun onUserJoined(uid: Int, elapsed: Int) {
                connectionListener?.onRtcUserJoined(uid, elapsed)
            }

            override suspend fun onUserOffline(uid: Int, reason: Int) {
                connectionListener?.onRtcUserOffline(uid, reason)
            }

            override suspend fun onRtcEngineError(err: Int) {
                connectionListener?.onRtcError(err)
            }
        }

    private val rtmEventSink: ConversationRtmEventSink =
        object : ConversationRtmEventSink {
            override fun onRtmLinkConnected() {
                connectionListener?.onRtmConnected()
            }

            override fun onRtmLinkFailed() {
                connectionListener?.onRtmFailed()
            }
        }

    // 初始化 RTC（使用真正的 event sink）
    private val rtcEventHandler: ConversationRtcEventListener =
        ConversationRtcEventListener(
            scope = scope,
            logTag = logTag,
            channelNameProvider = { currentChannelName },
            sink = rtcEventSink
        )
    private val rtmEventListener: ConversationRtmEventListener

    init {
        rtcEngine = initRtcEngine(context, appId, rtcEventHandler)

        // 初始化音视频管理器
        audioInputManager = CustomAudioInputManager(
            rtcEngine = rtcEngine,
            onAudioInputInterrupted = config.onAudioInputInterrupted ?: {}
        )
        videoInputManager = ExternalVideoCaptureManager(rtcEngine)

        // 初始化 RTM
        rtmClient = initRtmClient(appId, userId)
        rtmEventListener = ConversationRtmEventListener(logTag, rtmEventSink)
        rtmClient.addEventListener(rtmEventListener)

        // 初始化 ConvoAI
        conversationalAIAPI = ConversationalAIAPIImpl(
            ConversationalAIAPIConfig(
                rtcEngine = rtcEngine,
                rtmClient = rtmClient,
                enableLog = config.enableConvoAiLog
            )
        )
        conversationalAIAPI.loadAudioSettings(config.audioScenario)
        convoAiEventHandler?.let { conversationalAIAPI.addHandler(it) }
    }

    /**
     * 在发起 `joinChannel` 之前调用，用于：
     * - RTC token 将过期日志打印时输出正确的 channelName
     * - 让外部代码更明确地管理“动态会话状态”，而不是在构造函数里传 lambda
     */
    fun setChannelName(channelName: String) {
        currentChannelName = channelName
    }

    /**
     * 释放所有资源
     */
    fun destroy() {
        // 移除事件监听
        rtmClient.removeEventListener(rtmEventListener)

        // 释放 ConvoAI
        convoAiEventHandler?.let { conversationalAIAPI.removeHandler(it) }
        conversationalAIAPI.destroy()

        // 释放音视频管理器
        audioInputManager.release()
        videoInputManager.release()

        // 注意：RtcEngine 和 RtmClient 的销毁由调用方决定
        // 因为它们可能是全局单例
    }

    private fun initRtcEngine(
        context: Context,
        appId: String,
        eventHandler: ConversationRtcEventListener
    ): RtcEngineEx {
        val rtcConfig = RtcEngineConfig().apply {
            mContext = context
            mAppId = appId
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            mAudioScenario = Constants.AUDIO_SCENARIO_DEFAULT
            mEventHandler = eventHandler
        }

        return (RtcEngine.create(rtcConfig) as RtcEngineEx).apply {
            enableVideo()
            loadConversationRtcAiExtensions()
        }
    }

    private fun RtcEngineEx.loadConversationRtcAiExtensions() {
        for (id in CONVERSATION_RTC_AI_EXTENSION_IDS) {
            loadExtensionProvider(id)
        }
    }

    private fun initRtmClient(appId: String, userId: String): RtmClient {
        val rtmConfig = createConversationRtmConfig(appId, userId)
        return RtmClient.create(rtmConfig)
    }
}
