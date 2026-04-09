package cn.shengwang.convoai.quickstart.rtm

/**
 * RTM 链路状态 [io.agora.rtm.RtmEventListener.onLinkStateEvent] 的业务回调（CONNECTED / FAILED）。
 */
interface ConversationRtmEventSink {
    fun onRtmLinkConnected()
    fun onRtmLinkFailed()
}
