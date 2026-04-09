package ai.conv.internal.rtm

/**
 * RTM 登录流程与 [io.agora.rtm.RtmEventListener.onLinkStateEvent] 共用的连接标志位。
 * 由 [ConversationRtmLogin] 与 UI 层 listener 协同更新。
 */
class RtmLoginState {
    @Volatile
    var isLoggingIn: Boolean = false

    @Volatile
    var isRtmLogin: Boolean = false
}
