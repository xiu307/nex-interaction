package ai.nex.interaction.rtc

import io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
import io.agora.rtc2.ChannelMediaOptions

/**
 * 本示例对话场景的 RTC 发布选项：自定义音频轨、可选自定义视频轨，与原先 [ai.nex.interaction.ui.AgentChatViewModel] 内联逻辑一致。
 */
fun buildConversationChannelMediaOptions(
    customAudioTrackId: Int? = null,
    publishCustomVideoTrack: Boolean,
    customTrackId: Int? = null,
): ChannelMediaOptions {
    return ChannelMediaOptions().apply {
        clientRoleType = CLIENT_ROLE_BROADCASTER
        publishMicrophoneTrack = false
        publishCustomAudioTrack = true
        if (customAudioTrackId != null && customAudioTrackId >= 0) {
            publishCustomAudioTrackId = customAudioTrackId
        }
        publishCameraTrack = false
        this.publishCustomVideoTrack = publishCustomVideoTrack
        if (customTrackId != null && customTrackId >= 0) {
            this.customVideoTrackId = customTrackId
        }
        autoSubscribeAudio = true
        autoSubscribeVideo = true
        startPreview = false
    }
}
