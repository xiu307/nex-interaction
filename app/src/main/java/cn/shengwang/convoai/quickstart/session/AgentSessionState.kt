package cn.shengwang.convoai.quickstart.session

/**
 * 对话 Agent 侧状态：云端返回的 [agentId] 与 REST [authToken]（与 [cn.shengwang.convoai.quickstart.api.AgentStarter] 配套）。
 */
class AgentSessionState {
    var agentId: String? = null

    /** `Authorization: agora token=<authToken>`，与 RTC token 可能相同演示值。 */
    var authToken: String? = null

    /** 挂断或本地会话结束时清空云端 Agent 标识与 REST 凭证。 */
    fun clearAgentRestFields() {
        agentId = null
        authToken = null
    }
}
