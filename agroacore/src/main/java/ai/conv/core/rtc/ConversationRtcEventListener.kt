package ai.conv.core.rtc

import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats

/**
 * RTC 引擎事件回调透传到业务 [sink]。
 *
 * 线程说明：此处不做线程切换，回调线程由 RTC SDK 决定；
 * 业务如需切主线程/协程，请在业务层自行处理。
 */
interface ConversationRtcEventSink {
    fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int)
    fun onLeaveChannel(stats: RtcStats?)
    fun onUserJoined(uid: Int, elapsed: Int)
    fun onUserOffline(uid: Int, reason: Int)
    fun onRtcEngineError(err: Int)
    fun onRtcTokenWillExpire(token: String?) {}
}

class ConversationRtcEventListener(
    private val sink: ConversationRtcEventSink,
) : IRtcEngineEventHandler() {

    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
        sink.onJoinChannelSuccess(channel, uid, elapsed)
    }

    override fun onLeaveChannel(stats: RtcStats?) {
        super.onLeaveChannel(stats)
        sink.onLeaveChannel(stats)
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
        sink.onUserJoined(uid, elapsed)
    }

    override fun onUserOffline(uid: Int, reason: Int) {
        sink.onUserOffline(uid, reason)
    }

    override fun onError(err: Int) {
        sink.onRtcEngineError(err)
    }

    override fun onTokenPrivilegeWillExpire(token: String?) {
        sink.onRtcTokenWillExpire(token)
    }
}
