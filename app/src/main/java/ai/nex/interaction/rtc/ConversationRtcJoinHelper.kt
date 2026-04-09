package ai.nex.interaction.rtc

import io.agora.rtc2.RtcEngineEx

/**
 * 使用 [buildConversationChannelMediaOptions] 加入 RTC 频道（与 ViewModel 进房路径一致）。
 * @return SDK `RtcEngineEx.joinChannel` 返回值，`ERR_OK` 表示异步进房已发起。
 */
fun joinConversationChannelWithOptions(
    rtcEngine: RtcEngineEx?,
    rtcToken: String,
    channelName: String,
    uid: Int,
    customAudioTrackId: Int,
    publishCustomVideoTrack: Boolean = false,
    customVideoTrackId: Int? = null,
): Int {
    val options = buildConversationChannelMediaOptions(
        customAudioTrackId = customAudioTrackId,
        publishCustomVideoTrack = publishCustomVideoTrack,
        customTrackId = customVideoTrackId,
    )
    return rtcEngine?.joinChannel(rtcToken, channelName, uid, options) ?: -1
}
