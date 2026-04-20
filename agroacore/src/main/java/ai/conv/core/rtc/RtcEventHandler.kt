package ai.conv.core.rtc

import android.util.Log
import io.agora.rtc2.IRtcEngineEventHandler

class RtcEventHandler(
    private val logTag: String,
    private val channelNameProvider: () -> String,
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
        Log.d(logTag, "RTC onTokenPrivilegeWillExpire ${channelNameProvider()}")
    }
}
