package ai.nex.interaction.rtc

import android.content.Context
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx

/** 与对话示例一致的 AI-QoS 扩展（回声消除 / 降噪）。 */
val CONVERSATION_RTC_AI_EXTENSION_IDS: List<String> = listOf(
    "ai_echo_cancellation_extension",
    "ai_noise_suppression_extension",
)

/**
 * 直播场景 + 默认音频场景 + 事件回调，与原先 [ai.nex.interaction.ui.AgentChatViewModel.initRtcEngine] 配置一致。
 */
fun buildConversationRtcEngineConfig(
    context: Context,
    appId: String,
    eventHandler: IRtcEngineEventHandler,
): RtcEngineConfig = RtcEngineConfig().apply {
    mContext = context
    mAppId = appId
    mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
    mAudioScenario = Constants.AUDIO_SCENARIO_DEFAULT
    mEventHandler = eventHandler
}

/** 在已 [io.agora.rtc2.RtcEngineEx.enableVideo] 之后加载 AI 扩展。 */
fun RtcEngineEx.loadConversationRtcAiExtensions() {
    for (id in CONVERSATION_RTC_AI_EXTENSION_IDS) {
        loadExtensionProvider(id)
    }
}
