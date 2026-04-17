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
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtm.RtmClient

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

class AgroaManager(
    context: Context,
    appId: String,
    userId: String,
    private val config: ConvoManagerConfig = ConvoManagerConfig(),
    private val rtcEventSink: ConversationRtcEventSink,
    private val rtmEventSink: ConversationRtmEventSink,
    private val convoAiEventHandler: IConversationalAIAPIEventListener? = null,
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

    private val rtcEventHandler: ConversationRtcEventListener =
        ConversationRtcEventListener(
            sink = rtcEventSink
        )
    private val rtmEventListener: ConversationRtmEventListener  =
        ConversationRtmEventListener(
            sink = rtmEventSink
        )

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
