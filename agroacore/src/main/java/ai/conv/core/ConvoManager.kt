package ai.conv.core

import ai.conv.core.config.ConvoConfig
import ai.conv.core.convoai.ConversationalAIAPIConfig
import ai.conv.core.convoai.ConversationalAIAPIImpl
import ai.conv.core.convoai.IConversationalAIAPI
import ai.conv.core.convoai.IConversationalAIAPIEventHandler
import ai.conv.core.media.audio.CustomAudioInputManager
import ai.conv.core.media.video.ExternalVideoCaptureManager
import ai.conv.core.rtc.RtcEventHandler
import ai.conv.core.rtc.RtcEventSink
import ai.conv.core.rtm.RtmEventHandler
import ai.conv.core.rtm.RtmEventSink
import ai.conv.core.rtm.RtmLoginState
import android.content.Context
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.proxy.LocalAccessPointConfiguration
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants.RtmServiceType
import io.agora.rtm.RtmPrivateConfig
import java.io.File
import java.util.EnumSet


/**
 * 对话管理器配置
 */
data class ConvoManagerConfig(
    /** 是否启用 ConvoAI 日志 */
    val enableConvoAiLog: Boolean = true,

    /** 音频场景 */
    val audioScenario: Int = Constants.AUDIO_SCENARIO_AI_CLIENT,

    /** 是否为眼镜场景,会动态修改 ConvoConfig.IS_GLASS_SCENARIO */
    val isGlassScenario: Boolean = false,

    /** 音频输入中断回调 */
    val onAudioInputInterrupted: (() -> Unit)? = null,

    /** 声纹预注册 RTM 成功返回的 PCM http(s) URL,供宿主写入本地供下次 join 使用 */
    val onVoicePrintRegisterPcmHttpUrl: ((String) -> Unit)? = null,
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
    private val config: ConvoManagerConfig = ConvoManagerConfig(),
    rtcEventSink: RtcEventSink,
    rtmEventSink: RtmEventSink,
    convoAiEventHandler: IConversationalAIAPIEventHandler,
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

    // 初始化 RTC（使用真正的 event sink）
    private val rtcEventHandler: RtcEventHandler =
        RtcEventHandler(
            logTag = logTag,
            channelNameProvider = channelNameProvider,
            sink = rtcEventSink
        )
    private val rtmEventHandler: RtmEventHandler =
        RtmEventHandler(logTag, rtmEventSink)

    init {
        // 通过 ConvoManagerConfig 动态修改 ConvoConfig.IS_GLASS_SCENARIO
        ConvoConfig.IS_GLASS_SCENARIO = config.isGlassScenario

        rtcEngine = initRtcEngine(context, appId, rtcEventHandler)
        if (ConvoConfig.USE_PRIVATE_ENV) {
            val localConfig = LocalAccessPointConfiguration()
            val iplist = ArrayList<String?>()
            iplist.add(ConvoConfig.GEELY_PRIVATE_IP)
            localConfig.ipList = iplist
            localConfig.mode = 1
            localConfig.verifyDomainName = ConvoConfig.PRIVATE_DOMAIN_LIST
            localConfig.disableAut = false
            rtcEngine.setLocalAccessPoint(localConfig)
        }
        // 初始化音视频管理器
        audioInputManager = CustomAudioInputManager(
            rtcEngine = rtcEngine,
            onAudioInputInterrupted = config.onAudioInputInterrupted ?: {}
        )
        videoInputManager = ExternalVideoCaptureManager(rtcEngine)

        // 初始化 RTM
        rtmClient = initRtmClient(appId, userId)

        rtmClient.addEventListener(rtmEventHandler)

        // 初始化 ConvoAI
        val voicePrintPcmDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "voice_print_register_pcm",
        ).apply { mkdirs() }
        conversationalAIAPI = ConversationalAIAPIImpl(
            ConversationalAIAPIConfig(
                rtcEngine = rtcEngine,
                rtmClient = rtmClient,
                enableLog = config.enableConvoAiLog,
                voicePrintRegisterPcmOutputDir = voicePrintPcmDir,
                onVoicePrintRegisterPcmHttpUrl = config.onVoicePrintRegisterPcmHttpUrl,
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
        rtmClient.removeEventListener(rtmEventHandler)

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
        eventHandler: RtcEventHandler
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
        val rtmConfig = if (ConvoConfig.USE_PRIVATE_ENV) {
            val hosts = ArrayList<String?>()
            hosts.add(ConvoConfig.GEELY_PRIVATE_IP)
            val privateConfig = RtmPrivateConfig()
            privateConfig.setServiceType(EnumSet.of(RtmServiceType.MESSAGE, RtmServiceType.STREAM))
            privateConfig.accessPointHosts = hosts
            RtmConfig.Builder(appId, userId).privateConfig(privateConfig).build()
        } else {
            RtmConfig.Builder(appId, userId).build()
        }
        return RtmClient.create(rtmConfig)
    }
}
