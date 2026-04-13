package ai.conv

import android.content.Context
import ai.conv.internal.audio.CustomAudioInputManager
import ai.conv.internal.convoai.ConversationalAIAPIConfig
import ai.conv.internal.convoai.ConversationalAIAPIImpl
import ai.conv.internal.convoai.IConversationalAIAPI
import ai.conv.internal.rtc.ConversationRtcEngineEventHandler
import ai.conv.internal.rtc.ConversationRtcEventSink
import ai.conv.internal.rtm.ConversationRtmEventListener
import ai.conv.internal.rtm.ConversationRtmEventSink
import ai.conv.internal.rtm.RtmLoginState
import ai.conv.internal.rtm.createConversationRtmConfig
import ai.conv.internal.video.ExternalVideoCaptureManager
import io.agora.rtc2.Constants
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
class ConvoManager(
    context: Context,
    appId: String,
    userId: String,
    private val scope: CoroutineScope,
    private val config: ConvoManagerConfig = ConvoManagerConfig(),
    rtcEventSink: ConversationRtcEventSink,
    rtmEventSink: ConversationRtmEventSink,
    convoAiEventHandler: ai.conv.internal.convoai.IConversationalAIAPIEventHandler,
    logTag: String = "ConvoManager",
    channelNameProvider: () -> String
) {
    private companion object {
        /** 与对话示例一致的 AI-QoS 扩展（回声消除 / 降噪）。 */
        val CONVERSATION_RTC_AI_EXTENSION_IDS: List<String> = listOf(
            "ai_echo_cancellation_extension",
            "ai_noise_suppression_extension",
        )
    }

    val rtcEngine: RtcEngineEx
    val rtmClient: RtmClient
    val conversationalAIAPI: IConversationalAIAPI
    val audioInputManager: CustomAudioInputManager
    val videoInputManager: ExternalVideoCaptureManager
    val rtmLoginState = RtmLoginState()

    private val rtcEventHandler: ConversationRtcEngineEventHandler
    private val rtmEventListener: ConversationRtmEventListener

    init {
        // 初始化 RTC（使用真正的 event sink）
        rtcEventHandler = ConversationRtcEngineEventHandler(
            scope = scope,
            logTag = logTag,
            channelNameProvider = channelNameProvider,
            sink = rtcEventSink
        )
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
        conversationalAIAPI.addHandler(convoAiEventHandler)
    }


    /**
     * 释放所有资源
     */
    fun destroy() {
        // 移除事件监听
        rtmClient.removeEventListener(rtmEventListener)

        // 释放 ConvoAI
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
        eventHandler: ConversationRtcEngineEventHandler
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
