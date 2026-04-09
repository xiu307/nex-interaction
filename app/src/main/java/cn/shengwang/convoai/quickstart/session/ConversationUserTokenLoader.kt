package cn.shengwang.convoai.quickstart.session

import cn.shengwang.convoai.quickstart.api.TokenGenerator

/**
 * 用户侧 RTC + RTM 共用的统一 Token（演示服务约定：`channelName` 为空字符串）。
 * 成功时写入 [ConnectionSessionState.unifiedToken]。
 *
 * **生产环境**：勿在客户端使用演示 Token 服务；应由后端签发 RTC/RTM 用 Token。见仓库根目录 `AGENTS.md`。
 */
object ConversationUserTokenLoader {

    suspend fun fetchAndStoreUnifiedUserToken(
        connection: ConnectionSessionState,
        userId: String,
    ): Result<String> {
        return TokenGenerator.generateTokensAsync(
            channelName = "",
            uid = userId,
        ).map { token ->
            connection.unifiedToken = token
            token
        }
    }
}
