package ai.conv.core.rtm

import android.util.Log
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
/**
 * 为了保证对外接口一致性，统一进行二次封装
 */
interface RtmEventSink {
    fun onRtmLinkConnected()
    fun onRtmLinkFailed()
}

class RtmEventListener(
    private val sink: RtmEventSink,
) : RtmEventListener {
    private companion object {
        private const val TAG = "ConversationRtm"
    }

    override fun onLinkStateEvent(event: LinkStateEvent?) {
        super.onLinkStateEvent(event)
        event ?: return

        Log.d(TAG, "Rtm link state changed: ${event.currentState}")

        when (event.currentState) {
            RtmConstants.RtmLinkState.CONNECTED -> sink.onRtmLinkConnected()
            RtmConstants.RtmLinkState.FAILED -> sink.onRtmLinkFailed()
            else -> {
                // nothing
            }
        }
    }

    override fun onTokenPrivilegeWillExpire(channelName: String) {
        Log.d(TAG, "RTM onTokenPrivilegeWillExpire $channelName")
    }

    override fun onPresenceEvent(event: PresenceEvent) {
        super.onPresenceEvent(event)
    }
}
