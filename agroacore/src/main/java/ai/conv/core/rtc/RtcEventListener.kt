package ai.conv.core.rtc

import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats

/**
 * 为了保证对外接口一致性，统一进行二次封装
 */
interface RtcEventSink {
    fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int)
    fun onLeaveChannel(stats: RtcStats?)
    fun onUserJoined(uid: Int, elapsed: Int)
    fun onUserOffline(uid: Int, reason: Int)
    fun onRtcEngineError(err: Int)
    fun onRtcTokenWillExpire(token: String?) {}
}

class RtcEventListener(
    private val sink: RtcEventSink,
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
