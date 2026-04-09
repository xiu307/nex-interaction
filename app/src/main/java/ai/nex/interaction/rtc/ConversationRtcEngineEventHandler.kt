package ai.nex.interaction.rtc

import android.util.Log
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 将 RTC 引擎事件从 SDK 线程转发到 [scope]（通常为 ViewModelScope），业务由 [sink] 实现。
 */
interface ConversationRtcEventSink {
    suspend fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int)
    suspend fun onLeaveChannel(stats: RtcStats?)
    suspend fun onUserJoined(uid: Int, elapsed: Int)
    suspend fun onUserOffline(uid: Int, reason: Int)
    suspend fun onRtcEngineError(err: Int)
}

class ConversationRtcEngineEventHandler(
    private val scope: CoroutineScope,
    private val logTag: String,
    private val channelNameProvider: () -> String,
    private val sink: ConversationRtcEventSink,
) : IRtcEngineEventHandler() {

    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
        scope.launch {
            sink.onJoinChannelSuccess(channel, uid, elapsed)
        }
    }

    override fun onLeaveChannel(stats: RtcStats?) {
        super.onLeaveChannel(stats)
        scope.launch {
            sink.onLeaveChannel(stats)
        }
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
        scope.launch {
            sink.onUserJoined(uid, elapsed)
        }
    }

    override fun onUserOffline(uid: Int, reason: Int) {
        scope.launch {
            sink.onUserOffline(uid, reason)
        }
    }

    override fun onError(err: Int) {
        scope.launch {
            sink.onRtcEngineError(err)
        }
    }

    override fun onTokenPrivilegeWillExpire(token: String?) {
        Log.d(logTag, "RTC onTokenPrivilegeWillExpire ${channelNameProvider()}")
    }
}
