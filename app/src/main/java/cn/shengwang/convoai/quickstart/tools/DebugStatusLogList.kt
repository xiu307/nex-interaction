package cn.shengwang.convoai.quickstart.tools

/** 与 [cn.shengwang.convoai.quickstart.ui.AgentChatViewModel] 调试日志面板一致的上限。 */
const val DEBUG_STATUS_LOG_MAX_LINES = 20

/**
 * 在列表尾部追加一行；超过 [maxLines] 时从头部丢弃，行为与原先 ViewModel 内联实现一致。
 */
fun List<String>.appendDebugStatusLine(
    message: String,
    maxLines: Int = DEBUG_STATUS_LOG_MAX_LINES,
): List<String> {
    if (message.isEmpty()) return this
    val m = toMutableList()
    m.add(message)
    while (m.size > maxLines) {
        m.removeAt(0)
    }
    return m
}
