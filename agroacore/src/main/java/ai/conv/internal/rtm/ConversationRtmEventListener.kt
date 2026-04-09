package ai.conv.internal.rtm

import android.util.Log
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener

/**
 * 将 RTM 链路事件交给 [sink]，与原先 [ai.nex.interaction.ui.AgentChatViewModel] 内联 listener 行为一致。
 */
class ConversationRtmEventListener(
    private val logTag: String,
    private val sink: ConversationRtmEventSink,
) : RtmEventListener {

    override fun onLinkStateEvent(event: LinkStateEvent?) {
        super.onLinkStateEvent(event)
        event ?: return

        Log.d(logTag, "Rtm link state changed: ${event.currentState}")

        when (event.currentState) {
            RtmConstants.RtmLinkState.CONNECTED -> sink.onRtmLinkConnected()
            RtmConstants.RtmLinkState.FAILED -> sink.onRtmLinkFailed()
            else -> {
                // nothing
            }
        }
    }

    override fun onTokenPrivilegeWillExpire(channelName: String) {
        Log.d(logTag, "RTM onTokenPrivilegeWillExpire $channelName")
    }

    override fun onPresenceEvent(event: PresenceEvent) {
        super.onPresenceEvent(event)
    }
}
