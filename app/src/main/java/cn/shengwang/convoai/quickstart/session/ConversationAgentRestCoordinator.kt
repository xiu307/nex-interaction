package cn.shengwang.convoai.quickstart.session

import cn.shengwang.convoai.quickstart.api.AgentStarter
import cn.shengwang.convoai.quickstart.api.TokenGenerator

/**
 * 在 RTC/RTM 已就绪后，为当前频道拉取 Agent 用 Token 并调用 [AgentStarter] 启停云端 Agent。
 * 频道级 Token 同时作为 join 体中的 `token` 与 REST `Authorization: agora token=<…>`（参数一致时只需请求一次）。
 *
 * **生产环境**：演示直连 REST 与演示 Token 服务不可用于上线；敏感信息与 Token 签发应在后端完成，客户端只使用短期 Token。
 * 详见仓库根目录 `AGENTS.md`（Production、Token Generation 等约束）。
 */
object ConversationAgentRestCoordinator {

    data class AgentStartOutcome(
        val agentId: String,
        /** 与 Agent RTC token、REST 鉴权共用同一枚频道级 Token（演示 Token 服务下与历史双次请求等价）。 */
        val channelScopedToken: String,
    )

    suspend fun startRemoteAgent(
        channelName: String,
        agentRtcUid: String,
        remoteRtcUid: String,
    ): Result<AgentStartOutcome> {
        val tokenResult = TokenGenerator.generateTokensAsync(
            channelName = channelName,
            uid = agentRtcUid,
        )
        val token = tokenResult.getOrElse { return Result.failure(it) }
        val startResult = AgentStarter.startAgentAsync(
            channelName = channelName,
            agentRtcUid = agentRtcUid,
            agentToken = token,
            authToken = token,
            remoteRtcUid = remoteRtcUid,
        )
        return startResult.map { agentId ->
            AgentStartOutcome(agentId = agentId, channelScopedToken = token)
        }
    }

    suspend fun stopRemoteAgentIfStarted(agentId: String?, authToken: String?): Result<Unit> {
        val id = agentId ?: return Result.success(Unit)
        return AgentStarter.stopAgentAsync(
            agentId = id,
            authToken = authToken ?: "",
        )
    }
}
