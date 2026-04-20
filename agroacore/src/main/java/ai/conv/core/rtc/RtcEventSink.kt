package ai.conv.core.rtc

import io.agora.rtc2.IRtcEngineEventHandler.RtcStats

/**
 * RTC 引擎事件直接交给 [sink]，线程切换由 app 侧自行决定。
 */
interface RtcEventSink {
    fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int)
    fun onLeaveChannel(stats: RtcStats?)
    fun onUserJoined(uid: Int, elapsed: Int)
    fun onUserOffline(uid: Int, reason: Int)
    fun onRtcEngineError(err: Int)
}
