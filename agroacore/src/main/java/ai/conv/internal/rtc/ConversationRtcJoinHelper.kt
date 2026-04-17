package ai.conv.internal.rtc

import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngineEx

private const val TAG = "RtcJoinHelper"
private var handlerEx: MutableMap<Int, IRtcEngineEventHandler> = mutableMapOf()

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

fun joinConversationChannelExWithOptions(
    rtcEngine: RtcEngineEx?,
    rtcToken: String,
    channelName: String,
    uid: Int,
    customAudioTrackId: Int,
): Int {
    val options = ChannelMediaOptions().apply {
        clientRoleType = CLIENT_ROLE_BROADCASTER
        publishMicrophoneTrack = false
        publishCustomAudioTrack = true
        if (customAudioTrackId >= 0) {
            publishCustomAudioTrackId = customAudioTrackId
        }
        publishCameraTrack = false
        autoSubscribeAudio = false
        autoSubscribeVideo = false
        enableAudioRecordingOrPlayout = false
        startPreview = false
    }
    var handler = handlerEx[uid]
    if (handler == null) {
        handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                Log.i(TAG, "onJoinChannelSuccess channel=$channel uid=$uid")
            }
        }
        handlerEx[uid] = handler
    }
    return rtcEngine?.joinChannelEx(rtcToken, RtcConnection(channelName, uid), options, handler) ?: -1
}
