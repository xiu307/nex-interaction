package cn.shengwang.convoai.quickstart.session

/**
 * 单次会话的连接态：频道名、RTC/RTM 是否已就绪、用户侧统一 Token（RTC+RTM）。
 * 从 [cn.shengwang.convoai.quickstart.ui.AgentChatViewModel] 抽出，便于与 Agent/REST 编排分层。
 */
class ConnectionSessionState {
    var unifiedToken: String? = null

    var channelName: String = ""

    var rtcJoined: Boolean = false

    var rtmLoggedIn: Boolean = false

    /** RTC 已进房且 RTM 已登录，可继续订阅消息并启动 Agent。 */
    val rtcAndRtmReady: Boolean
        get() = rtcJoined && rtmLoggedIn

    /** 发起新一轮进房：写入频道名并清除 RTC/RTM 就绪标志。 */
    fun beginJoinAttempt(channelName: String) {
        this.channelName = channelName
        rtcJoined = false
        rtmLoggedIn = false
    }

    /** 已离开 RTC 频道（与 [rtcJoined] 语义一致）。 */
    fun markRtcLeft() {
        rtcJoined = false
    }
}
